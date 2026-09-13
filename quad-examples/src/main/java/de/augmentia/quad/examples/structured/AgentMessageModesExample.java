package de.augmentia.quad.examples.structured;

import de.augmentia.quad.core.agent.Agent;
import de.augmentia.quad.core.agent.AgentResult;
import de.augmentia.quad.core.config.ModelFactory;
import de.augmentia.quad.core.config.StructuredOutputConfig;
import de.augmentia.quad.core.session.AgentSessionState;
import dev.langchain4j.model.chat.ChatModel;

/**
 * Demonstrates all 4 modes of agent message processing:
 *
 * <pre>
 * ┌─────────────────┬─────────────┬─────────────────────────────────────────────┐
 * │ systemPrompt    │ jsonInput   │ Behavior                                    │
 * ├─────────────────┼─────────────┼─────────────────────────────────────────────┤
 * │ null            │ false       │ DEFAULT: Prompt in SystemMessage (legacy)   │
 * │ set             │ false       │ systemPrompt + Prompt as UserMessage        │
 * │ null            │ true        │ JSON input → UserMessageTemplate rendered   │
 * │ set             │ true        │ systemPrompt + JSON → UserMessageTemplate   │
 * └─────────────────┴─────────────┴─────────────────────────────────────────────┘
 * </pre>
 *
 * <h3>Execution</h3>
 * <pre>
 * export OPENAI_API_KEY=sk-...
 * mvn exec:java -pl quad-examples \
 *     -Dexec.mainClass=de.augmentia.quad.examples.structured.AgentMessageModesExample
 * </pre>
 */
public class AgentMessageModesExample {

    static class DemoAgent extends Agent {
        @Override
        protected AgentSessionState newSessionState() {
            return new AgentSessionState();
        }
    }

    public static void main(String[] args) {
        System.out.println("╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║   Agent Message Modes — alle 4 Varianten                     ║");
        System.out.println("╚══════════════════════════════════════════════════════════════╝");
        System.out.println();

        String apiKey = System.getenv("OPENAI_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            System.out.println("Skipped: OPENAI_API_KEY not set.");
            System.out.println("  export OPENAI_API_KEY=sk-...");
            return;
        }

        ChatModel model = ModelFactory.createOpenAiFromEnv();

        demo1_Default(model);
        demo2_CustomSystemPrompt(model);
        demo3_JsonInput(model);
        demo4_JsonInputWithSystemPrompt(model);
    }

    // ════════════════════════════════════════════════════════════════
    //  MODE 1: DEFAULT (backward compatible)
    // ════════════════════════════════════════════════════════════════
    //
    //  SystemMessage: "agent_doc + Prompt: <user-prompt>"
    //  UserMessage:   (none)
    //
    //  This is the original behavior: everything goes into the SystemMessage.
    // ════════════════════════════════════════════════════════════════

    private static void demo1_Default(ChatModel model) {
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        System.out.println("MODUS 1: DEFAULT — Prompt in SystemMessage");
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        System.out.println();
        System.out.println("Verhalten:");
        System.out.println("  SystemMessage = agentDoc + \"\\nPrompt: \" + prompt");
        System.out.println("  UserMessage   = (keine)");
        System.out.println();

        DemoAgent agent = new DemoAgent();
        agent.setLlm(model);
        // No systemPrompt, no jsonInput → default

        String result = agent.execute("Explain what Java Records are");

        System.out.println("Input:     \"Explain what Java Records are\"");
        System.out.println("Antwort:   " + result);
        System.out.println();
    }

    // ════════════════════════════════════════════════════════════════
    //  MODE 2: CUSTOM SYSTEM PROMPT
    // ════════════════════════════════════════════════════════════════
    //
    //  SystemMessage: "<custom-system-prompt> + agent_doc"
    //  UserMessage:   "<user-prompt>"
    //
    //  The custom system prompt is prepended.
    //  The user prompt is sent as a UserMessage.
    // ════════════════════════════════════════════════════════════════

    private static void demo2_CustomSystemPrompt(ChatModel model) {
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        System.out.println("MODUS 2: CUSTOM SYSTEM PROMPT");
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        System.out.println();
        System.out.println("Verhalten:");
        System.out.println("  SystemMessage = customPrompt + agentDoc");
        System.out.println("  UserMessage   = prompt");
        System.out.println();

        DemoAgent agent = new DemoAgent();
        agent.setLlm(model);
        agent.setSystemPrompt("""
            Du bist ein erfahrener Java-Trainer.
            Antworte immer auf Deutsch und gib konkrete Code-Beispiele.
            """);
        // jsonInput = false (default) → prompt sent as UserMessage

        String result = agent.execute("Was sind Records in Java 21?");

        System.out.println("SystemPrompt: \"Du bist ein erfahrener Java-Trainer...\"");
        System.out.println("Input:        \"Was sind Records in Java 21?\"");
        System.out.println("Antwort:      " + result);
        System.out.println();
    }

    // ════════════════════════════════════════════════════════════════
    //  MODE 3: JSON INPUT (with template)
    // ════════════════════════════════════════════════════════════════
    //
    //  SystemMessage: "agent_doc"
    //  UserMessage:   "template rendered with JSON fields"
    //
    //  The agent expects JSON input and renders the template
    //  with the values from the JSON.
    // ════════════════════════════════════════════════════════════════

    private static void demo3_JsonInput(ChatModel model) {
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        System.out.println("MODUS 3: JSON INPUT (Template-Rendierung)");
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        System.out.println();
        System.out.println("Verhalten:");
        System.out.println("  SystemMessage = agentDoc");
        System.out.println("  UserMessage   = template mit {{feld}} aus JSON ersetzt");
        System.out.println();

        DemoAgent agent = new DemoAgent();
        agent.setLlm(model);
        agent.setJsonInput(true);
        agent.setUserMessageTemplate("""
            Analyze the following task:

            Task:      {{task}}
            Complexity: {{complexity}}
            Deadline:  {{deadline}}
            """);
        agent.setStructuredOutputConfig(StructuredOutputConfig.dynamicSchema("""
            {
              "type": "object",
              "properties": {
                "steps": { "type": "array", "items": { "type": "string" } },
                "hours": { "type": "integer" }
              },
              "required": ["steps", "hours"]
            }
            """));

        String jsonInput = """
            {
              "task": "REST API for user management",
              "complexity": "medium",
              "deadline": "2 Wochen"
            }
            """;

        System.out.println("JSON Input:");
        System.out.println(jsonInput);
        System.out.println();
        System.out.println("Template:");
        System.out.println("  \"Task: {{task}}, Complexity: {{complexity}}, Deadline: {{deadline}}\"");
        System.out.println();

        AgentResult result = agent.executeStructured(jsonInput, agent.createSessionState());

        System.out.println("Gerenderte UserMessage:");
        System.out.println("  \"Task: REST API for user management, Complexity: medium, Deadline: 2 weeks\"");
        System.out.println();
        System.out.println("JSON Output: " + result.structuredOutput());
        System.out.println("Antwort:     " + result.finalAnswer());
        System.out.println();
    }

    // ════════════════════════════════════════════════════════════════
    //  MODE 4: JSON INPUT + CUSTOM SYSTEM PROMPT
    // ════════════════════════════════════════════════════════════════
    //
    //  SystemMessage: "<custom-system-prompt> + agent_doc"
    //  UserMessage:   "template rendered with JSON fields"
    //
    //  Combination of mode 2 and 3.
    // ════════════════════════════════════════════════════════════════

    private static void demo4_JsonInputWithSystemPrompt(ChatModel model) {
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        System.out.println("MODUS 4: JSON INPUT + CUSTOM SYSTEM PROMPT");
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        System.out.println();
        System.out.println("Verhalten:");
        System.out.println("  SystemMessage = customPrompt + agentDoc");
        System.out.println("  UserMessage   = template mit {{feld}} aus JSON ersetzt");
        System.out.println();

        DemoAgent agent = new DemoAgent();
        agent.setLlm(model);
        agent.setSystemPrompt("""
            You are an experienced software architect.
            You analyze tasks and create implementation plans.
            Answer ONLY with a JSON object.
            """);
        agent.setJsonInput(true);
        agent.setUserMessageTemplate("""
            Create a plan for:

            Task:       {{task}}
            Technology: {{tech}}
            Effort:    {{hours}} hours
            """);
        agent.setStructuredOutputConfig(StructuredOutputConfig.dynamicSchema("""
            {
              "type": "object",
              "properties": {
                "plan": { "type": "array", "items": { "type": "string" } },
                "risks": { "type": "array", "items": { "type": "string" } }
              },
              "required": ["plan", "risks"]
            }
            """));

        String jsonInput = """
            {
              "task": "User-Authentifizierung implementieren",
              "tech": "Spring Security + JWT",
              "hours": 16
            }
            """;

        System.out.println("JSON Input:");
        System.out.println(jsonInput);
        System.out.println();

        AgentResult result = agent.executeStructured(jsonInput, agent.createSessionState());

        System.out.println("JSON Output: " + result.structuredOutput());
        System.out.println("Antwort:     " + result.finalAnswer());
        System.out.println();
    }
}
