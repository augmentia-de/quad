package de.augmentia.quad.quarkus.mcp;

import de.augmentia.quad.core.tool.McpToolClient;
import de.augmentia.quad.core.tool.McpToolMethod;
import de.augmentia.quad.core.tool.ToolMethod;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.mcp.client.McpClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import io.quarkus.runtime.StartupEvent;
import org.eclipse.microprofile.config.ConfigProvider;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Manages MCP servers at runtime: setup on startup and reinit
 * (reconnect + tool reload) on demand — without restarting the app.
 *
 * Configuration (application.properties):
 *   quad.mcp.enabled=true
 *   quad.mcp.servers=filesystem,issues
 *   quad.mcp.servers.filesystem.command=npx -y @modelcontextprotocol/server-filesystem
 *   quad.mcp.servers.issues.url=https://issues.example.com/mcp
 */
@ApplicationScoped
public class McpManagerService {

    private static final Logger log = Logger.getLogger(McpManagerService.class);

    public record McpServerStatus(String name, String transport, int toolCount, String error) {}

    public record McpStatus(boolean enabled, int toolCount, String lastSetupAt, List<McpServerStatus> servers) {}

    private final List<McpToolClient> clients = new CopyOnWriteArrayList<>();
    private final List<ToolMethod> tools = new CopyOnWriteArrayList<>();
    private final Map<String, String> serverErrors = new ConcurrentHashMap<>();
    private volatile String lastSetupAt;
    private volatile boolean initialized = false;

    void onStartup(@Observes StartupEvent ev) {
        log.info("McpManagerService startup...");
        // Enable MCP when config exists (most common case)
        // Use absolute path since Quarkus working directory varies
        String[] configPaths = {
            "config/mcp-config.json",
            "../config/mcp-config.json",
            "../../config/mcp-config.json",
            System.getProperty("user.dir") + "/config/mcp-config.json"
        };
        
        String foundConfig = null;
        for (String p : configPaths) {
            try {
                if (java.nio.file.Files.exists(java.nio.file.Paths.get(p))) {
                    foundConfig = p;
                    break;
                }
            } catch (Exception e) {
                // ignore
            }
        }
        
        if (foundConfig != null) {
            log.infof("MCP config found: %s, enabling MCP", foundConfig);
            setup();
        } else {
            log.info("MCP disabled (no config file found)");
        }
    }

    public boolean isEnabled() {
        // Quarkus exposes properties as system properties
        // quad.mcp.enabled=true in application.properties
        String value = System.getProperty("quad.mcp.enabled", "true");
        return Boolean.parseBoolean(value);
    }

    /**
     * Reads servers from config/mcp-config.json (or configurable path)
     * AND from application.properties. Both sources are combined.
     */
    public synchronized McpStatus setup() {
        teardown();
        if (!isEnabled()) {
            initialized = false;
            lastSetupAt = null;
            return status();
        }

        // 1. Load JSON config - search multiple paths
        String[] configPaths = {
            "config/mcp-config.json",
            "../config/mcp-config.json",
            "../../config/mcp-config.json",
            System.getProperty("user.dir") + "/config/mcp-config.json"
        };
        
        for (String configPath : configPaths) {
            try {
                if (java.nio.file.Files.exists(java.nio.file.Paths.get(configPath))) {
                    var jsonClients = McpToolClient.fromConfigFile(configPath);
                    for (var client : jsonClients) {
                        registerClient(client);
                    }
                    log.infof("MCP config loaded from: %s (%d clients)", configPath, clients.size());
                    break;
                }
            } catch (Exception e) {
                log.warnf(e, "Failed to load MCP clients from %s", configPath);
            }
        }

        // 2. System properties as additional source
        try {
            var propClients = McpToolClient.fromSystemProperties();
            Set<String> knownNames = new LinkedHashSet<>();
            for (var c : clients) knownNames.add(c.serverName());
            for (var client : propClients) {
                if (!knownNames.contains(client.serverName())) {
                    registerClient(client);
                }
            }
        } catch (Exception e) {
            log.debugf(e, "Failed to load MCP clients from system properties");
        }

        lastSetupAt = Instant.now().toString();
        initialized = !clients.isEmpty();
        log.infof("MCP setup completed: %d clients, %d tools, initialized=%s",
            clients.size(), tools.size(), initialized);
        return status();
    }

    private void registerClient(McpToolClient client) {
        try {
            clients.add(client);
            int count = 0;
            for (ToolSpecification spec : client.listTools()) {
                String prefixed = "mcp_" + client.serverName() + "_" + spec.name();
                ToolSpecification wrapped = ToolSpecification.builder()
                    .name(prefixed)
                    .description(spec.description() + " (from MCP: " + client.serverName() + ")")
                    .parameters(spec.parameters())
                    .build();
                tools.add(new McpToolMethod(wrapped, client.client(), spec.name()));
                count++;
            }
            serverErrors.remove(client.serverName());
            log.infof("MCP server '%s': %d tools loaded", client.serverName(), count);
        } catch (Exception e) {
            serverErrors.put(client.serverName(), e.getMessage());
            log.errorf(e, "MCP server '%s' could not be loaded", client.serverName());
        }
    }

    /** Reconnects all servers and reloads tools — no app restart needed */
    public McpStatus reinit() {
        return setup();
    }

    public synchronized void teardown() {
        for (var client : clients) {
            closeQuietly(client.client());
        }
        clients.clear();
        tools.clear();
        serverErrors.clear();
    }

    private void closeQuietly(McpClient client) {
        try {
            client.close();
        } catch (Exception e) {
            log.debugf(e, "MCP client could not be closed cleanly");
        }
    }

    public List<ToolMethod> tools() {
        return List.copyOf(tools);
    }

    public Set<String> toolNames() {
        var names = new LinkedHashSet<String>();
        for (var t : tools) names.add(t.spec().name());
        return names;
    }

    public McpStatus status() {
        var serverStatuses = new ArrayList<McpServerStatus>();
        for (var client : clients) {
            int count = (int) tools.stream()
                .filter(t -> t.spec().name().startsWith("mcp_" + client.serverName() + "_"))
                .count();
            serverStatuses.add(new McpServerStatus(client.serverName(),
                transportOf(client), count, serverErrors.get(client.serverName())));
        }
        // also list failed servers without a running client
        for (var entry : serverErrors.entrySet()) {
            boolean hasClient = clients.stream().anyMatch(c -> c.serverName().equals(entry.getKey()));
            if (!hasClient) {
                serverStatuses.add(new McpServerStatus(entry.getKey(), "unknown", 0, entry.getValue()));
            }
        }
        return new McpStatus(isEnabled(), tools.size(), lastSetupAt,
            Collections.unmodifiableList(serverStatuses));
    }

    public boolean initialized() { return initialized; }

    // ─── Configuration ─────────────────────────────────────────

    private String transportOf(McpToolClient client) {
        String cmd = configString("quad.mcp.servers." + client.serverName() + ".command").orElse(null);
        if (cmd != null && !cmd.isBlank()) return "stdio";
        String url = configString("quad.mcp.servers." + client.serverName() + ".url").orElse(null);
        if (url != null && !url.isBlank()) return "sse";
        return "json-config";
    }

    private List<String> serverNames() {
        return configString("quad.mcp.servers").stream()
            .flatMap(s -> Arrays.stream(s.split(",")))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .toList();
    }

    private Optional<String> configString(String key) {
        return ConfigProvider.getConfig().getOptionalValue(key, String.class);
    }

    /**
     * The core module reads MCP configuration from system properties
     * (McpToolClient.fromSystemProperties). For consistency we mirror
     * the config there — both remain a shared source.
     */
    private void bridgeToSystemProperties() {
        configString("quad.mcp.enabled").ifPresent(v -> System.setProperty("quad.mcp.enabled", v));
        configString("quad.mcp.servers").ifPresent(v -> System.setProperty("quad.mcp.servers", v));
        for (String name : serverNames()) {
            String prefix = "quad.mcp.servers." + name;
            configString(prefix + ".command").ifPresent(v -> System.setProperty(prefix + ".command", v));
            configString(prefix + ".url").ifPresent(v -> System.setProperty(prefix + ".url", v));
        }
    }
}