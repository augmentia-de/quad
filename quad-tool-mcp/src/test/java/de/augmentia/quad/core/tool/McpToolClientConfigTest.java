package de.augmentia.quad.core.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class McpToolClientConfigTest {

    @TempDir
    Path tempDir;

    @Test
    void parseConfigFile_reads_stdio_server() throws IOException {
        Path config = tempDir.resolve("mcp.json");
        Files.writeString(config, """
            {
              "mcpServers": {
                "myserver": {
                  "command": "npx -y @modelcontextprotocol/server-filesystem /tmp",
                  "env": { "API_KEY": "test123" }
                }
              }
            }
            """);
        var configs = McpToolClient.parseConfigFile(config.toString());
        assertEquals(1, configs.size());
        assertEquals("myserver", configs.get(0).name());
        assertEquals("npx -y @modelcontextprotocol/server-filesystem /tmp", configs.get(0).command());
        assertNull(configs.get(0).url());
        assertEquals("test123", configs.get(0).env().get("API_KEY"));
    }

    @Test
    void parseConfigFile_reads_sse_server() throws IOException {
        Path config = tempDir.resolve("mcp.json");
        Files.writeString(config, """
            {
              "mcpServers": {
                "remote": {
                  "url": "http://localhost:3001/sse",
                  "env": { "TOKEN": "abc" }
                }
              }
            }
            """);
        var configs = McpToolClient.parseConfigFile(config.toString());
        assertEquals(1, configs.size());
        assertEquals("remote", configs.get(0).name());
        assertNull(configs.get(0).command());
        assertEquals("http://localhost:3001/sse", configs.get(0).url());
    }

    @Test
    void parseConfigFile_reads_multiple_servers() throws IOException {
        Path config = tempDir.resolve("mcp.json");
        Files.writeString(config, """
            {
              "mcpServers": {
                "server-1": { "url": "http://localhost:3001/sse" },
                "server-2": { "url": "http://localhost:3002/sse" },
                "server-3": { "url": "http://localhost:3003/sse" },
                "server-4": { "url": "http://localhost:3004/sse" }
              }
            }
            """);
        var configs = McpToolClient.parseConfigFile(config.toString());
        assertEquals(4, configs.size());
        var names = configs.stream().map(McpToolClient.McpServerConfig::name).toList();
        assertTrue(names.contains("server-1"));
        assertTrue(names.contains("server-2"));
        assertTrue(names.contains("server-3"));
        assertTrue(names.contains("server-4"));
    }

    @Test
    void parseConfigFile_returns_empty_for_missing_file() {
        var configs = McpToolClient.parseConfigFile("/nonexistent/mcp.json");
        assertTrue(configs.isEmpty());
    }

    @Test
    void parseConfigFile_returns_empty_for_invalid_json() throws IOException {
        Path config = tempDir.resolve("mcp.json");
        Files.writeString(config, "not json at all");
        var configs = McpToolClient.parseConfigFile(config.toString());
        assertTrue(configs.isEmpty());
    }

    @Test
    void parseConfigFile_returns_empty_for_missing_mcpServers() throws IOException {
        Path config = tempDir.resolve("mcp.json");
        Files.writeString(config, """
            { "other": {} }
            """);
        var configs = McpToolClient.parseConfigFile(config.toString());
        assertTrue(configs.isEmpty());
    }

    @Test
    void parseConfigFile_skips_server_without_command_or_url() throws IOException {
        Path config = tempDir.resolve("mcp.json");
        Files.writeString(config, """
            {
              "mcpServers": {
                "bad": { "env": { "X": "Y" } },
                "good": { "url": "http://localhost:3001/sse" }
              }
            }
            """);
        var configs = McpToolClient.parseConfigFile(config.toString());
        assertEquals(1, configs.size());
        assertEquals("good", configs.get(0).name());
    }

    @Test
    void parseConfigFile_reads_env_vars() throws IOException {
        Path config = tempDir.resolve("mcp.json");
        Files.writeString(config, """
            {
              "mcpServers": {
                "server": {
                  "url": "http://localhost:3001/sse",
                  "env": {
                    "API_KEY": "secret",
                    "TIMEOUT": "30",
                    "DEBUG": "true"
                  }
                }
              }
            }
            """);
        var configs = McpToolClient.parseConfigFile(config.toString());
        assertEquals(1, configs.size());
        assertEquals(3, configs.get(0).env().size());
        assertEquals("secret", configs.get(0).env().get("API_KEY"));
        assertEquals("30", configs.get(0).env().get("TIMEOUT"));
        assertEquals("true", configs.get(0).env().get("DEBUG"));
    }
}