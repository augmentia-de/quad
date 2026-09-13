package de.augmentia.quad.core.tool;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.transport.http.HttpMcpTransport;
import dev.langchain4j.mcp.client.transport.stdio.StdioMcpTransport;
import de.augmentia.quad.core.security.TokenExchangeClient;
import de.augmentia.quad.core.security.TokenExchangeConfig;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public class McpToolClient {

    private static final Logger log = Logger.getLogger(McpToolClient.class);

    private final String serverName;
    private final McpClient client;

    public McpToolClient(String serverName, McpClient client) {
        this.serverName = serverName;
        this.client = client;
    }

    public String serverName() {
        return serverName;
    }

    public McpClient client() {
        return client;
    }

    public List<ToolSpecification> listTools() throws Exception {
        return client.listTools();
    }

    public static McpToolClient stdio(String serverName, String command, Map<String, String> env) {
        List<String> cmdParts = splitCommand(command);
        StdioMcpTransport transport = new StdioMcpTransport.Builder()
            .command(cmdParts)
            .environment(env != null ? env : Collections.emptyMap())
            .logEvents(false)
            .build();
        McpClient mcpClient = DefaultMcpClient.builder()
            .transport(transport)
            .clientName("quad-mcp")
            .clientVersion("1.0")
            .protocolVersion("2024-11-05")
            .build();
        return new McpToolClient(serverName, mcpClient);
    }

    public static McpToolClient sse(String serverName, String sseUrl, Map<String, String> headers) {
        HttpMcpTransport transport = HttpMcpTransport.builder()
            .sseUrl(sseUrl)
            .customHeaders(headers)
            .logRequests(false)
            .logResponses(false)
            .build();
        McpClient mcpClient = DefaultMcpClient.builder()
            .transport(transport)
            .clientName("quad-mcp")
            .clientVersion("1.0")
            .protocolVersion("2024-11-05")
            .build();
        return new McpToolClient(serverName, mcpClient);
    }

    /**
     * Creates an SSE MCP client with Token Exchange (RFC 8693).
     * Exchanges the given user token for a new token scoped to the target audience.
     *
     * @param serverName name of the MCP server
     * @param sseUrl SSE endpoint URL
     * @param userToken the incoming user token to exchange
     * @param targetAudience the audience for the exchanged token
     * @param config token exchange configuration (issuer, client credentials, etc.)
     * @return connected MCP client with exchanged auth header
     */
    public static McpToolClient sseWithTokenExchange(String serverName, String sseUrl,
                                                      String userToken, String targetAudience,
                                                      TokenExchangeConfig config) {
        Map<String, String> headers = new java.util.HashMap<>();
        if (config != null && userToken != null && !userToken.isBlank()) {
            TokenExchangeClient exchangeClient = new TokenExchangeClient(config);
            Map<String, String> authHeaders = exchangeClient.buildAuthHeaders(userToken, targetAudience);
            headers.putAll(authHeaders);
        }
        return sse(serverName, sseUrl, headers);
    }

    public static List<String> splitCommand(String command) {
        if (command == null || command.isBlank()) return Collections.emptyList();
        return List.of(command.trim().split("\\\\s+"));
    }

    public static Map<String, String> systemEnv() {
        return System.getenv().entrySet().stream()
            .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                (a, b) -> b, TreeMap::new));
    }

    public static List<McpToolClient> fromSystemProperties() {
        List<McpToolClient> clients = new ArrayList<>();
        String servers = System.getProperty("quad.mcp.servers", "");
        if (servers == null) servers = "";
        for (String name : servers.split(",")) {
            String trimmed = name.trim();
            if (trimmed.isEmpty()) continue;
            String cmd = System.getProperty("quad.mcp.servers." + trimmed + ".command");
            String url = System.getProperty("quad.mcp.servers." + trimmed + ".url");
            Map<String, String> env = new TreeMap<>();
            String prefix = "quad.mcp.servers." + trimmed + ".env.";
            for (var prop : System.getProperties().stringPropertyNames()) {
                if (prop.startsWith(prefix)) {
                    int keyStart = prefix.length();
                    String key = prop.substring(keyStart);
                    env.put(key, System.getProperty(prop));
                }
            }
            if (cmd != null && !cmd.isBlank()) {
                clients.add(stdio(trimmed, cmd, env));
            } else if (url != null && !url.isBlank()) {
                clients.add(sse(trimmed, url, env));
            }
        }
        return clients;
    }

    /**
     * Parsed configuration of an MCP server (without connection).
     */
    public record McpServerConfig(String name, String command, String url, Map<String, String> env,
                                   boolean tokenExchange, String audience, String[] scopes) {}

    /**
     * Parses MCP server configuration from a JSON file (without establishing connections).
     * Expected format:
     * <pre>
     * {
     *   "mcpServers": {
     *     "server-name": {
     *       "command": "npx -y @modelcontextprotocol/server-filesystem /tmp",
     *       "env": { "KEY": "value" }
     *     },
     *     "another-server": {
     *       "url": "https://mcp.example.com/sse",
     *       "env": { "API_KEY": "secret" }
     *     },
     *     "disabled-server": {
     *       "url": "https://mcp.example.com/sse",
     *       "disabled": true
     *     }
     *   }
     * }
     * </pre>
     *
     * @param configPath path to the JSON file (relative or absolute)
     * @return parsed server configurations (without clients)
     */
    public static List<McpServerConfig> parseConfigFile(String configPath) {
        Path path = Path.of(configPath);
        if (!Files.exists(path)) {
            log.warnf("MCP config file not found: %s", path.toAbsolutePath());
            return List.of();
        }
        try {
            String json = Files.readString(path);
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var root = mapper.readTree(json);
            var servers = root.get("mcpServers");
            if (servers == null || !servers.isObject()) {
                log.warnf("MCP config: 'mcpServers' field is missing or not an object in %s", configPath);
                return List.of();
            }
            List<McpServerConfig> configs = new ArrayList<>();
            var fieldNames = servers.fieldNames();
            while (fieldNames.hasNext()) {
                String name = fieldNames.next();
                var serverNode = servers.get(name);
                if (serverNode.has("disabled") && serverNode.get("disabled").asBoolean()) {
                    log.infof("MCP config: server '%s' is disabled, skipping", name);
                    continue;
                }
                String cmd = serverNode.has("command") ? serverNode.get("command").asText() : null;
                String url = serverNode.has("url") ? serverNode.get("url").asText() : null;
                Map<String, String> env = new TreeMap<>();
                var envNode = serverNode.get("env");
                if (envNode != null && envNode.isObject()) {
                    var envFields = envNode.fieldNames();
                    while (envFields.hasNext()) {
                        String key = envFields.next();
                        env.put(key, envNode.get(key).asText());
                    }
                }
                boolean tokenExchange = serverNode.has("tokenExchange") && serverNode.get("tokenExchange").asBoolean();
                String audience = serverNode.has("audience") ? serverNode.get("audience").asText() : null;
                String[] scopes = new String[0];
                if (serverNode.has("scopes") && serverNode.get("scopes").isArray()) {
                    var scopesNode = serverNode.get("scopes");
                    scopes = new String[scopesNode.size()];
                    for (int i = 0; i < scopesNode.size(); i++) {
                        scopes[i] = scopesNode.get(i).asText();
                    }
                }
                if ((cmd != null && !cmd.isBlank()) || (url != null && !url.isBlank())) {
                    configs.add(new McpServerConfig(name, cmd, url, env, tokenExchange, audience, scopes));
                } else {
                    log.warnf("MCP config: server '%s' has neither 'command' nor 'url'", name);
                }
            }
            return configs;
        } catch (IOException e) {
            log.errorf(e, "MCP config file could not be read: %s", configPath);
            return List.of();
        }
    }

    /**
     * Reads MCP server configuration from a JSON file and establishes connections.
     *
     * @param configPath path to the JSON file (relative or absolute)
     * @return list of connected MCP clients
     */
    public static List<McpToolClient> fromConfigFile(String configPath) {
        List<McpServerConfig> configs = parseConfigFile(configPath);
        List<McpToolClient> clients = new ArrayList<>();
        for (McpServerConfig cfg : configs) {
            try {
                if (cfg.command() != null && !cfg.command().isBlank()) {
                    clients.add(stdio(cfg.name(), cfg.command(), cfg.env()));
                    log.infof("MCP config: server '%s' (stdio) connected", cfg.name());
                } else if (cfg.url() != null && !cfg.url().isBlank()) {
                    clients.add(sse(cfg.name(), cfg.url(), cfg.env()));
                    log.infof("MCP config: server '%s' (sse) connected", cfg.name());
                }
            } catch (Exception e) {
                log.errorf(e, "MCP config: server '%s' could not be connected", cfg.name());
            }
        }
        return clients;
    }
}