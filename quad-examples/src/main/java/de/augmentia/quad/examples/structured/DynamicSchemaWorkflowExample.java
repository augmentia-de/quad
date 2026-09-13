package de.augmentia.quad.examples.structured;

import de.augmentia.quad.core.agent.Agent;
import de.augmentia.quad.core.agent.AgentResult;
import de.augmentia.quad.core.config.ModelFactory;
import de.augmentia.quad.core.config.StructuredInputConfig;
import de.augmentia.quad.core.config.StructuredOutputConfig;
import de.augmentia.quad.core.session.AgentSessionState;
import de.augmentia.quad.core.workflow.AgentWorkflowBuilder;
import de.augmentia.quad.core.workflow.AgentWorkflowResult;
import de.augmentia.quad.core.scope.FieldExtractor;
import dev.langchain4j.model.chat.ChatModel;

/**
 * Like {@link StructuredWorkflowExample}, but with dynamic JSON schemas
 * instead of static Java Records.
 *
 * <h3>Difference from StructuredWorkflowExample</h3>
 * <ul>
 *   <li>Static: {@code StructuredOutputConfig.staticModel(TaskAnalysis.class)} — schema generated from Java Record</li>
 *   <li>Dynamic: {@code StructuredOutputConfig.dynamicSchema(jsonSchema)} — schema as raw JSON string</li>
 * </ul>
 *
 * <h3>Advantages of dynamic schemas</h3>
 * <ul>
 *   <li>No Java classes needed — ideal for generic pipelines</li>
 *   <li>Schemas can be configured/loaded at runtime</li>
 *   <li>Output is raw JSON string (no deserialization into Record)</li>
 * </ul>
 *
 * <h3>Execution</h3>
 * <pre>
 * cd quad
 * mvn install -pl quad-core -DskipTests -q
 * mvn compile -pl quad-examples -q
 * export OPENAI_API_KEY=sk-...
 * mvn exec:java -pl quad-examples \
 *     -Dexec.mainClass=de.augmentia.quad.examples.structured.DynamicSchemaWorkflowExample
 * </pre>
 */
public class DynamicSchemaWorkflowExample {

    static class PipelineAgent extends Agent {
        @Override
        protected AgentSessionState newSessionState() {
            return new AgentSessionState();
        }
    }

    // ── Dynamic JSON schemas as strings ──

    private static final String TASK_ANALYSIS_SCHEMA = """
        {
          "type": "object",
          "properties": {
            "complexity": {
              "type": "string",
              "description": "Schwierigkeitsgrad: simple, medium oder complex"
            },
            "recommendedTools": {
              "type": "array",
              "items": { "type": "string" },
              "description": "Recommended tools for the task"
            },
            "estimatedHours": {
              "type": "integer",
              "description": "Estimated effort in hours"
            }
          },
          "required": ["complexity", "recommendedTools", "estimatedHours"]
        }
        """;

    private static final String IMPLEMENTATION_PLAN_SCHEMA = """
        {
          "type": "object",
          "properties": {
            "steps": {
              "type": "array",
              "items": { "type": "string" },
              "description": "Konkrete Umsetzungsschritte in Reihenfolge"
            },
            "tools": {
              "type": "array",
              "items": { "type": "string" },
              "description": "Tools, die je Schritt verwendet werden"
            }
          },
          "required": ["steps", "tools"]
        }
        """;

    // ── Prompt templates (System + User) ──

    private static final StructuredInputConfig ANALYZE_SYSTEM_MSG = StructuredInputConfig.fromTemplate("""
        Du bist ein erfahrener Software-Analyst.
        Analyze the given task and assess complexity, recommended tools, and estimated effort.
        Antworte NUR mit einem JSON-Objekt passend zum Schema.
        """);

    private static final StructuredInputConfig ANALYZE_USER_MSG = StructuredInputConfig.fromTemplate("""
        Analysiere folgenden Task:

        {{initialPrompt}}
        """);

    private static final StructuredInputConfig PLAN_SYSTEM_MSG = StructuredInputConfig.fromTemplate("""
        Du bist ein erfahrener Software-Architekt.
        Erstelle einen detaillierten Implementierungsplan basierend auf der Analyse.
        Antworte NUR mit einem JSON-Objekt passend zum Schema.
        """);

    private static final StructuredInputConfig PLAN_USER_MSG = StructuredInputConfig.fromTemplate("""
        Create an implementation plan for the following task:

        Task: {{initialPrompt}}
        Complexity: {{analysis.complexity}}
        Estimated effort: {{analysis.estimatedHours}} hours
        Available tools: {{tools_for_planner}}
        """);

    public static void main(String[] args) {
        System.out.println("╔══════════════════════════════════════════════════════════╗");
        System.out.println("║   Dynamic Schema (JSON-String) for Agent-Workflows       ║");
        System.out.println("╚══════════════════════════════════════════════════════════╝");
        System.out.println();

        demo1Schemas();

        String apiKey = System.getenv("OPENAI_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            System.out.println("━━━ Demo 2: Volle Pipeline ━━━");
            System.out.println("Skipped: OPENAI_API_KEY not set.");
            System.out.println("  export OPENAI_API_KEY=sk-...");
            return;
        }
        demo2FullPipeline();
        demo3JsonInputMode();
    }

    // ──────────────────────────────────────────────────────────────
    //  Demo 1: Display dynamic schemas
    // ──────────────────────────────────────────────────────────────

    private static void demo1Schemas() {
        System.out.println("━━━ Demo 1: Dynamische JSON-Schemas ━━━");
        System.out.println();

        var analysisConfig = StructuredOutputConfig.dynamicSchema(TASK_ANALYSIS_SCHEMA);
        var planConfig = StructuredOutputConfig.dynamicSchema(IMPLEMENTATION_PLAN_SCHEMA);

        System.out.println("TaskAnalysis Schema (dynamisch):");
        System.out.println(analysisConfig.effectiveSchema());
        System.out.println();

        System.out.println("ImplementationPlan Schema (dynamisch):");
        System.out.println(planConfig.effectiveSchema());
        System.out.println();

        // Compare with static schema
        System.out.println("Vergleich: Statisches Schema (aus Java Record):");
        var staticConfig = StructuredOutputConfig.staticModel(
            de.augmentia.quad.examples.structured.StructuredWorkflowExample.class
                .getDeclaredClasses()[0]); // PipelineAgent
        // Correction: TaskAnalysis Record is inner
        System.out.println("(Schema is generated from Java Record via reflection)");
        System.out.println();
    }

    // ──────────────────────────────────────────────────────────────
    //  Demo 2: Full pipeline with dynamic schemas
    // ──────────────────────────────────────────────────────────────

    private static void demo2FullPipeline() {
        System.out.println("━━━ Demo 2: Volle Pipeline (Analyzer → transform → Planner) ━━━");
        System.out.println();

        ChatModel model = ModelFactory.createOpenAiFromEnv();

        PipelineAgent analyzer = new PipelineAgent();
        analyzer.setLlm(model);

        PipelineAgent planner = new PipelineAgent();
        planner.setLlm(model);

        AgentWorkflowBuilder workflow = new AgentWorkflowBuilder()
            // Step 1: Analyze task — dynamic schema
            .step("analyze", analyzer,
                ANALYZE_SYSTEM_MSG, ANALYZE_USER_MSG,
                StructuredOutputConfig.dynamicSchema(TASK_ANALYSIS_SCHEMA), "analysis")

            // Step 2: pass through only the tools
            .transform("analysis", "tools_for_planner",
                value -> FieldExtractor.extract(value, "recommendedTools"))

            // Step 3: Plan with dynamic schema
            .step("plan", planner,
                PLAN_SYSTEM_MSG, PLAN_USER_MSG,
                StructuredOutputConfig.dynamicSchema(IMPLEMENTATION_PLAN_SCHEMA), "plan");

        String task = args0();
        System.out.println("Task: " + task);
        System.out.println();

        AgentWorkflowResult result = workflow.execute(task);

        // ── Read results as JSON strings (no Record deserialization) ──
        Object analysisObj = result.scope().get("analysis");
        System.out.println("Analyse (roher JSON-String):");
        if (analysisObj instanceof String json) {
            System.out.println("  " + json);
        } else {
            System.out.println("  " + analysisObj);
        }
        System.out.println();

        Object planObj = result.scope().get("plan");
        System.out.println("Plan (roher JSON-String):");
        if (planObj instanceof String json) {
            System.out.println("  " + json);
        } else {
            System.out.println("  " + planObj);
        }
        System.out.println();

        System.out.println("Finale Antwort des Planners:");
        System.out.println(result.finalText());
        System.out.println();
    }

    private static String args0() {
        return "Create a REST API for user management";
    }

    // ──────────────────────────────────────────────────────────────
    //  Demo 3: JSON Input Mode — Agent with systemPrompt + jsonInput
    // ──────────────────────────────────────────────────────────────

    private static final String ANALYZER_SYSTEM_PROMPT = """
        Du bist ein erfahrener Software-Analyst.
        You receive a task as a JSON object with fields "task" and "context".
        Analyze the task and assess complexity, recommended tools, and estimated effort.
        Antworte NUR mit einem JSON-Objekt passend zum Schema.
        """;

    private static final String ANALYZER_USER_TEMPLATE = """
        Analysiere folgenden Task:

        Task: {{task}}
        Kontext: {{context}}
        """;

    private static void demo3JsonInputMode() {
        System.out.println("━━━ Demo 3: JSON Input Mode (systemPrompt + jsonInput) ━━━");
        System.out.println();

        ChatModel model = ModelFactory.createOpenAiFromEnv();

        // Configure agent with systemPrompt and jsonInput
        PipelineAgent analyzer = new PipelineAgent();
        analyzer.setLlm(model);
        analyzer.setSystemPrompt(ANALYZER_SYSTEM_PROMPT);
        analyzer.setUserMessageTemplate(ANALYZER_USER_TEMPLATE);
        analyzer.setJsonInput(true);
        analyzer.setStructuredOutputConfig(
            StructuredOutputConfig.dynamicSchema(TASK_ANALYSIS_SCHEMA));

        // Provide JSON input
        String jsonInput = """
            {
              "task": "REST API for user management",
              "context": "Bestehende Spring Boot App mit PostgreSQL"
            }
            """;

        System.out.println("JSON Input:");
        System.out.println(jsonInput);
        System.out.println();

        // Execute agent — expects JSON input and renders UserMessageTemplate
        AgentResult result = analyzer.executeStructured(jsonInput,
            analyzer.createSessionState());

        System.out.println("Analyse (roher JSON-String):");
        System.out.println("  " + result.structuredOutput());
        System.out.println();

        System.out.println("Finale Antwort:");
        System.out.println(result.finalAnswer());
        System.out.println();
    }
}
