package de.augmentia.quad.core.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for the three silent defects in the MCP configuration.
 *
 * <p>None of the three was covered by a test before, and all three are silent: the
 * server starts but does not report what the config promises.
 */
class McpToolClientStdioTest {

    @TempDir
    Path tempDir;

    // --- Defekt 1: die Regex ---

    @Test
    void splitCommand_trennt_an_leerzeichen() {
        var argv = McpToolClient.splitCommand("npx -y @modelcontextprotocol/server-filesystem /tmp");

        assertEquals(4, argv.size(),
                "four literal backslashes yielded ONE element — no startable executable");
        assertEquals("npx", argv.get(0));
        assertEquals("-y", argv.get(1));
        assertEquals("@modelcontextprotocol/server-filesystem", argv.get(2));
        assertEquals("/tmp", argv.get(3));
    }

    @Test
    void buildArgv_haengt_args_an_den_befehl() {
        var argv = McpToolClient.buildArgv("npx",
                List.of("-y", "@modelcontextprotocol/server-filesystem", "/tmp"));

        assertEquals(List.of("npx", "-y", "@modelcontextprotocol/server-filesystem", "/tmp"), argv);
    }

    @Test
    void buildArgv_leitet_sich_vom_split_ab_wenn_args_fehlen() {
        assertEquals(McpToolClient.splitCommand("python -m mcp_server_git"),
                McpToolClient.buildArgv("python -m mcp_server_git", null));
    }

    @Test
    void buildArgvWithNeitherCommandNorArgsThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> McpToolClient.buildArgv(null, null));
    }

    // --- Defekt 2: args wurden verworfen ---

    @Test
    void parseConfigFile_liest_das_args_array() throws IOException {
        Path config = tempDir.resolve("mcp.json");
        Files.writeString(config, """
            {
              "mcpServers": {
                "filesystem": {
                  "command": "npx",
                  "args": ["-y", "@modelcontextprotocol/server-filesystem", "/workspace/shared"]
                }
              }
            }
            """);

        var cfg = McpToolClient.parseConfigFile(config.toString()).get(0);

        assertEquals("npx", cfg.command());
        assertEquals(3, cfg.args().size(), "args sind der Grund, warum der Server ueberhaupt startet");
        assertEquals(List.of("npx", "-y", "@modelcontextprotocol/server-filesystem", "/workspace/shared"),
                cfg.argv());
    }

    @Test
    void parseConfigFile_alle_vier_mitgelieferten_server_haben_argumente() throws IOException {
        // Die echte Config aus quad-quarkus/mcp/mcp-config.json, unveraendert.
        Path config = tempDir.resolve("mcp.json");
        Files.writeString(config, """
            {
              "mcpServers": {
                "filesystem": { "command": "npx",
                  "args": ["-y", "@modelcontextprotocol/server-filesystem", "/root/shared"] },
                "git": { "command": "python",
                  "args": ["-m", "mcp_server_git", "--repository", "/root/shared"] },
                "memory": { "command": "npx",
                  "args": ["-y", "@modelcontextprotocol/server-memory"] },
                "fetch": { "command": "python", "args": ["-m", "mcp_server_fetch"] }
              },
              "registry": { "allowed": ["filesystem", "git", "memory", "fetch"] }
            }
            """);

        var configs = McpToolClient.parseConfigFile(config.toString());

        assertEquals(4, configs.size());
        for (var cfg : configs) {
            assertFalse(cfg.args().isEmpty(),
                    cfg.name() + " startet ohne args — der Server bekommt nichts zu tun");
            assertTrue(cfg.argv().size() >= 2, cfg.name() + ": argv zu kurz " + cfg.argv());
        }
    }

    @Test
    void parseConfigFile_befehl_mit_leerzeichen_ohne_args_funktioniert_durch_den_split() throws IOException {
        Path config = tempDir.resolve("mcp.json");
        Files.writeString(config, """
            { "mcpServers": { "legacy": { "command": "npx -y @scope/server /tmp" } } }
            """);

        var cfg = McpToolClient.parseConfigFile(config.toString()).get(0);

        // The split also makes legacy configs work — the args list is only the
        // unambiguous form, not a requirement.
        // die eindeutige Form, kein Muss.
        assertEquals(4, cfg.argv().size(), cfg.argv().toString());
    }

    // --- Defect 3: registry.allowed was not enforced ---

    @Test
    void parseConfigFile_durchsetzt_registry_allowed() throws IOException {
        Path config = tempDir.resolve("mcp.json");
        Files.writeString(config, """
            {
              "mcpServers": {
                "erlaubt": { "url": "http://localhost:3001/sse" },
                "nicht_erlaubt": { "url": "http://localhost:6666/sse" }
              },
              "registry": { "allowed": ["erlaubt"] }
            }
            """);

        var names = McpToolClient.parseConfigFile(config.toString()).stream()
                .map(McpToolClient.McpServerConfig::name).toList();

        assertEquals(List.of("erlaubt"), names,
                "an allowlist that is not enforced is not an allowlist");
    }

    @Test
    void parseConfigFile_ohne_registry_erlaubt_alles() throws IOException {
        Path config = tempDir.resolve("mcp.json");
        Files.writeString(config, """
            { "mcpServers": { "a": { "url": "http://x/sse" }, "b": { "url": "http://y/sse" } } }
            """);

        assertEquals(2, McpToolClient.parseConfigFile(config.toString()).size(),
                "fehlende Allowlist darf kein Bruch sein");
    }

    // --- Defekt 4: ${QUAD_MCP_ROOT} blieb ein literaler String ---

    @Test
    void parseConfigFile_ersetzt_platzhalter() throws IOException {
        Path config = tempDir.resolve("mcp.json");
        Files.writeString(config, """
            {
              "mcpServers": {
                "git": {
                  "command": "python",
                  "args": ["-m", "mcp_server_git", "--repository", "${QUAD_MCP_ROOT_TEST}/shared"],
                  "env": { "MEMORY_FILE_PATH": "${QUAD_MCP_ROOT_TEST}/memory.json" }
                }
              }
            }
            """);
        System.setProperty("QUAD_MCP_ROOT_TEST", "/var/quad");
        try {
            var cfg = McpToolClient.parseConfigFile(config.toString()).get(0);

            assertTrue(cfg.argv().contains("/var/quad/shared"),
                    "ein unaufgeloester Platzhalter geht als Verzeichnisname durch: " + cfg.argv());
            assertEquals("/var/quad/memory.json", cfg.env().get("MEMORY_FILE_PATH"));
        } finally {
            System.clearProperty("QUAD_MCP_ROOT_TEST");
        }
    }

    @Test
    void substituteReplacesEnvironmentVariablesAndLeavesTheRestAlone() {
        System.setProperty("QUAD_PROBE", "/srv");
        try {
            assertEquals("/srv/data", McpToolClient.substitute("${QUAD_PROBE}/data"));
            assertEquals("kein Platzhalter", McpToolClient.substitute("kein Platzhalter"));
            assertEquals("vor /srv nach",
                    McpToolClient.substitute("vor ${QUAD_PROBE} nach"));
            assertNull(McpToolClient.substitute(null));
        } finally {
            System.clearProperty("QUAD_PROBE");
        }
    }

    @Test
    void substitute_unaufgeloester_platzhalter_bleibt_sichtbar_falsch() {
        var r = McpToolClient.substitute("${QUAD_MCP_ROOT_GIBT_ES_NICHT}/shared");

        assertEquals("QUAD_MCP_ROOT_GIBT_ES_NICHT/shared", r,
                "ohne Warnung und ohne Ersatz waere der Wert ein scheinbar gueltiger Pfad");
    }
}