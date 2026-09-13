package de.augmentia.quad.examples;

import de.augmentia.quad.core.tool.ToolMethod;
import de.augmentia.quad.core.tool.*;
import de.augmentia.quad.core.annotation.Tool;
import de.augmentia.quad.core.tool.builtin.*;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.lang.reflect.Method;
import java.util.List;

/**
 * Lists available tools including MCP-SSE services.
 * Uses the core implementation (McpToolProvider) for MCP server loading.
 */
public class ToolListExample {

    public static void main(String[] args) {
        System.out.println("=== Tool Registry & MCP Test (via Core Impl) ===\n");

        QuadToolRegistry registry = new QuadToolRegistry(new ToolArgsMapper(new ObjectMapper()));

        // Register built-in tools
        registerBuiltInTools(registry);
        System.out.println("✓ Built-in Tools: " + registry.getAll().size());

        // Load MCP tools via Core Implementation
        loadMcpTools(registry);

        System.out.println("\n=== All available tools (" + registry.getAll().size() + ") ===");
        registry.getAll().forEach(t -> System.out.println("  - " + t.spec().name()));
    }

    private static void registerBuiltInTools(QuadToolRegistry registry) {
        Object[] tools = {
            new ReadFileTool(),
            new WebSearchTool(),
            new WriteTool(),
            new GrepTool(),
            new FindTool()
        };
        for (Object bean : tools) {
            for (Method m : bean.getClass().getDeclaredMethods()) {
                if (m.isAnnotationPresent(Tool.class)) {
                    registry.register(m.getName(), new ReflectiveToolMethod(m, bean, new ToolArgsMapper(new ObjectMapper())));
                }
            }
        }
    }

    private static void loadMcpTools(QuadToolRegistry registry) {
        System.out.println("\n=== MCP SSE Tools (via McpToolProvider) ===");
        
        // Use Core Implementation: McpToolProvider loads from config/mcp-config.json
        McpToolProvider provider = new McpToolProvider();
        
        // Check if config exists
        java.io.File configFile = new java.io.File("config/mcp-config.json");
        if (!configFile.exists()) {
            System.out.println("Keine MCP-Config gefunden: " + configFile.getAbsolutePath());
            return;
        }
        
        try {
            List<ToolMethod> mcpTools = provider.getTools();
            for (ToolMethod tool : mcpTools) {
                registry.register(tool.spec().name(), tool);
            }
            System.out.println("✓ MCP Tools geladen: " + mcpTools.size());
        } catch (Exception e) {
            System.out.println("MCP Fehler: " + e.getMessage());
        }
    }
}