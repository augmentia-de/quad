package de.augmentia.quad.examples.capmcp;

import de.augmentia.quad.core.session.AgentSessionState;
import de.augmentia.quad.core.session.CurrentSession;
import de.augmentia.quad.core.capability.CapabilitySearchAgent;
import de.augmentia.quad.core.tool.CapabilitySearchTool;
import de.augmentia.quad.core.capability.skill.SkillRegistry;
import de.augmentia.quad.core.tool.QuadToolRegistry;
import de.augmentia.quad.core.tool.ToolArgsMapper;
import de.augmentia.quad.core.tool.ToolMethod;
import de.augmentia.quad.core.tool.ToolResult;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.ChatModel;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.util.function.Function;

/**
 * Example demonstrating how to use capability search with MCP tools and skills.
 * 
 * This example shows:
 * 1. Setting up a tool registry with tools
 * 2. Loading skills from the skills directory
 * 3. Using CapabilitySearchTool for intelligent tool selection
 * 4. How to use the selected tools for task execution
 */
public class CapabilitySearchExample {

    public static void main(String[] args) throws Exception {
        System.out.println("=== Capability Search with MCP Tools and Skills ===\n");
        
        if (args.length < 1) {
            printUsage();
            return;
        }
        
        String task = args[0];
        String workspaceBase = System.getProperty("quad.workspace", 
            System.getenv().getOrDefault("QUAD_WORKSPACE", "/work/quad"));
        
        Path workspace = Path.of(workspaceBase, "cap-mcp-demo");
        setupWorkspace(workspace);
        
        System.out.println("Workspace: " + workspace);
        System.out.println("Available skills: code-review, data-analysis, documentation, ki-research\n");
        
        // Initialize session state
        AgentSessionState state = AgentSessionState.create("cap-mcp-demo-" + System.currentTimeMillis());
        CurrentSession.setCurrent(state);
        
        // Create tool registry
        ToolArgsMapper argsMapper = new ToolArgsMapper(new ObjectMapper());
        QuadToolRegistry registry = new QuadToolRegistry(argsMapper);
        
        // Register demo tools for capability search
        registerDemoTools(registry);
        
        // Load skills
        SkillRegistry skillRegistry = loadSkills();
        
        // Create capability search tool with optional ChatModel
        ChatModel chatModel = System.getenv("OPENAI_API_KEY") != null 
            ? createOpenAiModel() 
            : null;
        
        CapabilitySearchAgent searchAgent = chatModel != null 
            ? new CapabilitySearchAgent(chatModel) 
            : null;
        
        CapabilitySearchTool capSearchTool = new CapabilitySearchTool(
            null, // capabilitySearch - would use vector index in production
            searchAgent,
            skillRegistry,
            20,  // defaultTopK
            3    // maxTools
        );
        
        registry.register("capability_search", capSearchTool);
        
        // Execute the capability search
        System.out.println("Task: " + task + "\n");
        
        String jsonInput = "{\"task\": \"" + task + "\"}";
        var result = capSearchTool.execute(jsonInput, state);
        
        System.out.println("=== Capability Search Result ===\n");
        System.out.println(result.text());
        
        System.out.println("\n=== Next Steps ===");
        explainNextSteps();
    }
    
    private static void printUsage() {
        System.out.println("Usage: java CapabilitySearchExample <task>");
        System.out.println("\nExamples:");
        System.out.println("  java CapabilitySearchExample \"Find files with TODO comments\"");
        System.out.println("  java CapabilitySearchExample \"Read and analyze a Java file\"");
        System.out.println("  java CapabilitySearchExample \"Search for configuration patterns\"");
        System.out.println("\nSet OPENAI_API_KEY for LLM-powered tool selection");
    }
    
    private static void setupWorkspace(Path workspace) throws Exception {
        if (!java.nio.file.Files.exists(workspace)) {
            java.nio.file.Files.createDirectories(workspace);
            System.out.println("Created workspace: " + workspace);
        }
    }
    
    private static void registerDemoTools(QuadToolRegistry registry) {
        // Create simple tool methods using the functional interface pattern
        
        ToolMethod readFile = createTool("read_file", "Read file contents", 
            (json) -> {
                try {
                    var node = new ObjectMapper().readTree(json);
                    String path = node.get("path").asText();
                    return ToolResult.success("Content of " + path);
                } catch (Exception e) {
                    return ToolResult.error("Failed to parse: " + e.getMessage());
                }
            });
        
        ToolMethod grepSearch = createTool("grep_search", "Search file contents for pattern",
            (json) -> {
                return ToolResult.success("Search results...");
            });
        
        ToolMethod findFiles = createTool("find_files", "Find files matching pattern",
            (json) -> {
                return ToolResult.success("Found files: ...");
            });
        
        ToolMethod listDir = createTool("list_directory", "List directory contents",
            (json) -> {
                return ToolResult.success("Directory listing: ...");
            });
        
        registry.register("read_file", readFile);
        registry.register("grep_search", grepSearch);
        registry.register("find_files", findFiles);
        registry.register("list_directory", listDir);
        
        System.out.println("Registered tools:");
        System.out.println("  - read_file: Read file contents");
        System.out.println("  - grep_search: Search file contents");
        System.out.println("  - find_files: Find files by pattern");
        System.out.println("  - list_directory: List directory contents\n");
    }
    
    private static ToolMethod createTool(String name, String description, 
                                         Function<String, ToolResult> executor) {
        return new ToolMethod() {
            private final ToolSpecification spec = ToolSpecification.builder()
                .name(name)
                .description(description)
                .build();
            
            @Override public ToolSpecification spec() { return spec; }
            @Override public ToolResult execute(String jsonArguments, AgentSessionState state) {
                return executor.apply(jsonArguments);
            }
        };
    }
    
    private static SkillRegistry loadSkills() {
        try {
            System.out.println("Loading skills from: /skills directory");
            System.out.println("Available skills: code-review, data-analysis, documentation, ki-research\n");
            return new SkillRegistry(null, null);
        } catch (Exception e) {
            System.out.println("Warning: Could not load skills: " + e.getMessage() + "\n");
            return null;
        }
    }
    
    private static ChatModel createOpenAiModel() {
        String apiKey = System.getenv("OPENAI_API_KEY");
        if (apiKey == null) {
            System.out.println("OPENAI_API_KEY not set - using fallback mode\n");
            return null;
        }
        
        return dev.langchain4j.model.openai.OpenAiChatModel.builder()
            .apiKey(apiKey)
            .modelName("gpt-4o-mini")
            .temperature(0.7)
            .build();
    }
    
    private static void explainNextSteps() {
        System.out.println("1. The capability_search tool analyzed your task");
        System.out.println("2. It recommended up to 3 tools based on:");
        System.out.println("   - LLM analysis of skills and tools (when OPENAI_API_KEY is set)");
        System.out.println("   - Skill-declared capabilities");
        System.out.println("3. Use the recommended tools to execute the task:");
        System.out.println("   registry.get(\"read_file\").execute(args)");
        System.out.println("   registry.get(\"grep_search\").execute(args)");
        System.out.println("   etc.");
        System.out.println("\n4. MCP tools can be added dynamically:");
        System.out.println("   - Filesystem MCP server for file operations");
        System.out.println("   - Git MCP server for version control");
        System.out.println("   - Custom MCP servers for specific domains");
    }
}