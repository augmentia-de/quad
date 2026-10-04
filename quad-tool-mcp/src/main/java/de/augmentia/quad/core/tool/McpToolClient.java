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
import java.util.Set;
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
        return stdio(serverName, command, null, env);
    }

    /**
     * stdio server from a separate {@code command} and {@code args}.
     *
     * <p><b>Why the overloaded call exists.</b> The MCP configuration format separates
     * {@code command} and {@code args}:
     *
     * <pre>{@code
     * "filesystem": {
     *   "command": "npx",
     *   "args": ["-y", "@modelcontextprotocol/server-filesystem", "/workspace/shared"]
     * }
     * }</pre>
     *
     * <p>The parser used to ignore {@code args} and read only {@code command}. With
     * {@code "command": "npx"} the server therefore started as a bare {@code npx} without a
     * single argument — for all four servers in {@code mcp-config.json}. The failure was
     * silent: the process started but reported no tools.
     *
     * <p>With {@code args} there is also no longer any need to split a command on
     * whitespace. The split now only exists for compatibility with
     * {@link #splitCommand(String)} and for configs that write the full command into a
     * {@code command} field.
     */
    public static McpToolClient stdio(String serverName, String command, List<String> args,
                                       Map<String, String> env) {
        var argv = buildArgv(command, args);
        log.infof("MCP stdio '%s': argv=%d %s", serverName, argv.size(), argv);
        StdioMcpTransport transport = new StdioMcpTransport.Builder()
            .command(argv)
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

    /**
     * Zerlegt eine Befehlszeile in argv-Elemente.
     *
     * <p><b>Die vier Backslashes waren ein echter Bug.</b>
     * {@code "\\\\s+"} is the string {@code \\s+} in Java; as a regex that means
     * <em>a literal backslash followed by one or more {@code s}</em> — not whitespace.
     * Demonstrated:
     *
     * <pre>
     * "npx -y @scope/server /tmp".split("\\\\s+")  -&gt; 1 Element
     * "npx -y @scope/server /tmp".split("\\s+")   -&gt; 4 Elemente
     * </pre>
     *
     * <p>For a command containing whitespace, the <em>whole</em> command went to the
     * process as one argv element — no startable executable. The bug did not surface
     * because the shipped {@code mcp-config.json} uses {@code "command": "npx"} without
     * whitespace.
     */
    public static List<String> splitCommand(String command) {
        if (command == null || command.isBlank()) return Collections.emptyList();
        return List.of(command.trim().split("\\s+"));
    }

    /**
     * Builds argv from {@code command} and optional {@code args}.
     *
     * <p>The command line is only split when {@code args} is missing. Splitting while an
     * argument list is present would be the original bug in the other direction.
     */
    static List<String> buildArgv(String command, List<String> args) {
        var argv = new ArrayList<String>();
        if (command != null && !command.isBlank()) {
            argv.addAll(splitCommand(command));
        }
        if (args != null) {
            for (var a : args) {
                if (a != null && !a.isBlank()) {
                    argv.add(a);
                }
            }
        }
        if (argv.isEmpty()) {
            throw new IllegalArgumentException(
                    "MCP stdio server needs a command or args — both were empty");
        }
        return List.copyOf(argv);
    }

    /**
     * Replaces {@code ${NAME}} with a system property or environment variable.
     *
     * <p><b>Why this is necessary.</b> {@code mcp-config.json} uses
     * {@code ${QUAD_MCP_ROOT}/shared}. Without substitution the literal string
     * {@code ${QUAD_MCP_ROOT}/shared} went to {@code server-filesystem} — the server
     * would have created or read a directory named {@code ${QUAD_MCP_ROOT}}. A
     * placeholder that is silently not substituted is worse than a missing one: it looks
     * like a valid path.
     *
     * <p>Unresolved placeholders are therefore warned about and replaced with the name
     * without braces, so that the value is at least visibly wrong and does not pass as a
     * normal path.
     */
    static String substitute(String value) {
        if (value == null || !value.contains("${")) return value;
        var sb = new StringBuilder();
        int i = 0;
        while (i < value.length()) {
            int start = value.indexOf("${", i);
            if (start < 0) {
                sb.append(value, i, value.length());
                break;
            }
            int end = value.indexOf('}', start);
            if (end < 0) {
                sb.append(value, i, value.length());
                break;
            }
            String name = value.substring(start + 2, end);
            String resolved = System.getProperty(name);
            if (resolved == null) resolved = System.getenv(name);
            if (resolved == null) {
                log.warnf("MCP config: placeholder ${%s} cannot be resolved — "
                        + "leaving it unresolved; check the environment", name);
                resolved = name;
            }
            sb.append(value, i, start).append(resolved);
            i = end + 1;
        }
        return sb.toString();
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
     *
     * <p>{@code args} is the argument list of the MCP format. It used not to be part of
     * the record, and the parser discarded it — see
     * {@link #stdio(String, String, List, Map)}.
     */
    public record McpServerConfig(String name, String command, List<String> args, String url,
                                   Map<String, String> env, boolean tokenExchange,
                                   String audience, String[] scopes) {

        /** Compatibility constructor for callers without {@code args}. */
        public McpServerConfig(String name, String command, String url, Map<String, String> env,
                               boolean tokenExchange, String audience, String[] scopes) {
            this(name, command, List.of(), url, env, tokenExchange, audience, scopes);
        }

        /** argv, as the server is actually started. */
        public List<String> argv() {
            return buildArgv(command, args);
        }
    }

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

            // registry.allowed is an allowlist. It is enforced, not merely documented:
            // a config that allows one server while the code starts all of them is a
            // config nobody trusts.
            var allowed = readAllowedServers(root);

            var fieldNames = servers.fieldNames();
            while (fieldNames.hasNext()) {
                String name = fieldNames.next();
                var serverNode = servers.get(name);
                if (serverNode.has("disabled") && serverNode.get("disabled").asBoolean()) {
                    log.infof("MCP config: server '%s' is disabled, skipping", name);
                    continue;
                }
                if (!allowed.isEmpty() && !allowed.contains(name)) {
                    log.warnf("MCP config: server '%s' is not in registry.allowed %s — skipping",
                            name, allowed);
                    continue;
                }
                String cmd = serverNode.has("command")
                        ? substitute(serverNode.get("command").asText()) : null;
                String url = serverNode.has("url")
                        ? substitute(serverNode.get("url").asText()) : null;

                // args ist Pflicht fuer stdio. Ohne sie startet "npx" ohne Argumente.
                List<String> args = new ArrayList<>();
                var argsNode = serverNode.get("args");
                if (argsNode != null && argsNode.isArray()) {
                    for (var a : argsNode) {
                        args.add(substitute(a.asText("")));
                    }
                } else if (cmd != null && !cmd.isBlank() && cmd.contains(" ")) {
                    // Not an error — the split does apply. But it is a heuristic: an
                    // argument that itself contains whitespace gets torn apart.
                    log.infof("MCP config: server '%s' has no 'args' array; its command is split "
                            + "on whitespace — an argument containing spaces would be torn apart. "
                            + "Use \"command\" plus \"args\" to be unambiguous.", name);
                }

                Map<String, String> env = new TreeMap<>();
                var envNode = serverNode.get("env");
                if (envNode != null && envNode.isObject()) {
                    var envFields = envNode.fieldNames();
                    while (envFields.hasNext()) {
                        String key = envFields.next();
                        env.put(key, substitute(envNode.get(key).asText()));
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
                    configs.add(new McpServerConfig(name, cmd, List.copyOf(args), url, env,
                            tokenExchange, audience, scopes));
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
     * Liest {@code registry.allowed} als Allowlist.
     *
     * <p>If the field is missing or empty, nothing is restricted — that is the state
     * most configs are in, and it should not be a breaking change.
     */
    private static Set<String> readAllowedServers(
            com.fasterxml.jackson.databind.JsonNode root) {
        Set<String> allowed = new java.util.LinkedHashSet<>();
        var registry = root.get("registry");
        if (registry == null || !registry.isObject()) return allowed;
        var allowedNode = registry.get("allowed");
        if (allowedNode != null && allowedNode.isArray()) {
            allowedNode.forEach(n -> {
                var v = n.asText("");
                if (!v.isBlank()) allowed.add(v.trim());
            });
        }
        return allowed;
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
                    clients.add(stdio(cfg.name(), cfg.command(), cfg.args(), cfg.env()));
                    log.infof("MCP config: server '%s' (stdio) configured with argv %s",
                            cfg.name(), cfg.argv());
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