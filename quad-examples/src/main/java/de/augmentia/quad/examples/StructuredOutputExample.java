package de.augmentia.quad.examples;

import de.augmentia.quad.core.agent.Agent;
import de.augmentia.quad.core.agent.AgentBuilder;
import de.augmentia.quad.core.agent.AgentResult;
import de.augmentia.quad.core.config.StructuredOutputConfig;
import de.augmentia.quad.core.session.AgentSessionState;

/**
 * Executable example for {@link StructuredOutputConfig} in quad.
 * <p>
 * Demonstrates three approaches to structured JSON output from an LLM:
 *
 * <h3>1. Static Model — Java Record as schema source</h3>
 * <pre>
 *   record WeatherReport(String city, double temperature, String condition) {}
 *
 *   var config = StructuredOutputConfig.staticModel(WeatherReport.class);
 *   agent.setStructuredOutputConfig(config);
 *   AgentResult result = agent.executeStructured("Was ist das Wetter in Berlin?");
 *   // result.structuredOutput() → {"city":"Berlin","temperature":22.5,"condition":"sunny"}
 * </pre>
 *
 * <h3>2. Dynamic Schema — raw JSON-Schema string</h3>
 * <pre>
 *   String schema = """
 *       {"type":"object","properties":{"name":{"type":"string"},"score":{"type":"number"}}}
 *       """;
 *   var config = StructuredOutputConfig.dynamicSchema(schema);
 *   AgentResult result = agent.executeStructured("Bewerte dieses Projekt");
 * </pre>
 *
 * <h3>3. AgentBuilder — declarative configuration</h3>
 * <pre>
 *   Agent agent = AgentBuilder.create(MyAgent.class)
 *       .withLlmFromEnv()
 *       .withStructuredOutput(WeatherReport.class)   // ← statisches Model
 *       .build();
 *   AgentResult result = agent.executeStructured("Wetter?");
 * </pre>
 *
 * <h3>How it works</h3>
 * <ol>
 *   <li>The JSON-Schema is injected into the LLM request via {@code responseFormat}
 *       (requires an OpenAI-compatible API with structured output support).</li>
 *   <li>If the LLM returns invalid JSON, the framework retries once with a force prompt
 *       that asks the LLM to correct its output.</li>
 *   <li>The result is available as both a plain-text answer and structured JSON.</li>
 * </ol>
 *
 * <h3>Prerequisites</h3>
 * <ul>
 *   <li>Environment variable {@code OPENAI_API_KEY} with a valid key</li>
 *   <li>Environment variable {@code OPENAI_MODEL} (optional, default: {@code gpt-4o})</li>
 * </ul>
 *
 * <h3>Execution</h3>
 * <pre>
 *   cd quad
 *   mvn install -pl quad-core -DskipTests -q
 *   mvn compile -pl quad-examples -q
 *   mvn exec:java -pl quad-examples \
 *       -Dexec.mainClass=de.augmentia.quad.examples.StructuredOutputExample
 * </pre>
 */
public class StructuredOutputExample {

    /** Sample record for static model output — the LLM must produce JSON matching this shape. */
    record WeatherReport(String city, double temperature, String condition) {}

    /** Nested record to demonstrate complex schema generation. */
    record MovieReview(String title, int year, String genre, Rating rating) {}

    record Rating(double score, String summary) {}

    public static void main(String[] args) {
        System.out.println("╔══════════════════════════════════════════════════╗");
        System.out.println("║   Structured Output Example                     ║");
        System.out.println("╚══════════════════════════════════════════════════╝");
        System.out.println();

        try {
            demo1StaticModel();
            demo2DynamicSchema();
            demo3AgentBuilder();
            demo4NestedRecord();
        } catch (Exception e) {
            System.err.println("Fehler: " + e.getMessage());
            System.err.println();
            System.err.println("Stelle sicher, dass OPENAI_API_KEY gesetzt ist:");
            System.err.println("  export OPENAI_API_KEY=sk-...");
            System.exit(1);
        }
    }

    /**
     * Demo 1: Static Model — the simplest approach.
     * <p>
     * A Java record defines the expected JSON structure. The schema is
     * automatically generated from the record components.
     */
    private static void demo1StaticModel() {
        System.out.println("━━━ Demo 1: Static Model (Record als Schema) ━━━");
        System.out.println();

        Agent agent = new WeatherAgent();
        agent.setStructuredOutputConfig(StructuredOutputConfig.staticModel(WeatherReport.class));

        System.out.println("Prompt: \"Was ist das Wetter in Berlin?\"");
        System.out.println("Erwartetes Schema:");
        System.out.println("  " + StructuredOutputConfig.staticModel(WeatherReport.class).effectiveSchema());
        System.out.println();

        AgentResult result = agent.executeStructured("Was ist das Wetter in Berlin?");

        System.out.println("Finale Antwort:  " + result.finalAnswer());
        System.out.println("Strukturiert:    " + result.structuredOutput());
        System.out.println("Dauer:           " + result.metrics().durationMs() + " ms");
        System.out.println();
    }

    /**
     * Demo 2: Dynamic Schema — a JSON schema string is provided at runtime.
     * <p>
     * Useful when the schema is not known at compile time
     * (e.g. loaded from a configuration file).
     */
    private static void demo2DynamicSchema() {
        System.out.println("━━━ Demo 2: Dynamic Schema (JSON-Schema-String) ━━━");
        System.out.println();

        String schema = """
            {
              "type": "object",
              "properties": {
                "name":    { "type": "string" },
                "score":   { "type": "number" },
                "summary": { "type": "string" }
              },
              "required": ["name", "score", "summary"]
            }
            """;

        var config = StructuredOutputConfig.dynamicSchema(schema);
        System.out.println("Schema: " + schema);
        System.out.println();

        Agent agent = new WeatherAgent();
        agent.setStructuredOutputConfig(config);

        AgentResult result = agent.executeStructured("Bewerte das Projekt quad");

        System.out.println("Finale Antwort:  " + result.finalAnswer());
        System.out.println("Strukturiert:    " + result.structuredOutput());
        System.out.println();
    }

    /**
     * Demo 3: AgentBuilder — declarative configuration via the builder.
     * <p>
     * The StructuredOutputConfig is set when building the agent.
     * All subsequent {@code executeStructured()} calls use the schema.
     */
    private static void demo3AgentBuilder() {
        System.out.println("━━━ Demo 3: AgentBuilder (withStructuredOutput) ━━━");
        System.out.println();

        Agent agent = AgentBuilder.create(WeatherAgent.class)
            .withStructuredOutput(WeatherReport.class)
            .build();

        System.out.println("Agent gebaut mit withStructuredOutput(WeatherReport.class)");
        System.out.println("Config aktiv: " + agent.getStructuredOutputConfig().isEnabled());
        System.out.println();

        AgentResult result = agent.executeStructured("What is the weather in Munich?");

        System.out.println("Finale Antwort:  " + result.finalAnswer());
        System.out.println("Strukturiert:    " + result.structuredOutput());
        System.out.println();
    }

    /**
     * Demo 4: Nested Record — nested records produce nested JSON schemas.
     * <p>
     * The schema automatically generates nested {@code properties} and
     * {@code required} arrays for each nested record.
     */
    private static void demo4NestedRecord() {
        System.out.println("━━━ Demo 4: Nested Record (verschachtelte Schemas) ━━━");
        System.out.println();

        var config = StructuredOutputConfig.staticModel(MovieReview.class);
        System.out.println("Generiertes Schema:");
        System.out.println(config.effectiveSchema());
        System.out.println();

        Agent agent = new WeatherAgent();
        agent.setStructuredOutputConfig(config);

        AgentResult result = agent.executeStructured("Create a movie review for Inception (2010)");

        System.out.println("Finale Antwort:  " + result.finalAnswer());
        System.out.println("Strukturiert:    " + result.structuredOutput());
        System.out.println();
    }

    /**
     * Minimal agent for this demo only — no tools, no hooks.
     * Reads {@code OPENAI_API_KEY} from environment variables.
     */
    static class WeatherAgent extends Agent {
        @Override
        protected AgentSessionState newSessionState() {
            return new AgentSessionState();
        }

        public WeatherAgent() {
            initLlm();
        }
    }
}
