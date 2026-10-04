package de.augmentia.quad.quarkus.workflow;

import de.augmentia.quad.contract.ApiDtos;
import de.augmentia.quad.core.agent.Agent;
import de.augmentia.quad.core.config.LlmConfig;
import de.augmentia.quad.core.session.AgentSessionState;
import de.augmentia.quad.quarkus.messaging.ChannelAgentFactory;
import de.augmentia.quad.quarkus.ui.PersistenceStore;
import de.augmentia.quad.quarkus.ui.SharedState;
import de.augmentia.quad.quarkus.ui.UiConfig;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Live smoke test: drives {@code WorkflowGeneratorService.generate} against the
 * <em>real</em> model configured in {@code kafka-platform/quad-workflow-worker/.env}.
 *
 * <p>Purpose: everything else in this package is deterministic and proves our parsing,
 * not the model's cooperation. This test answers the one question the unit tests
 * cannot — does the configured model actually return JSON that
 * {@code WorkflowGeneratorService} can parse into a valid DAG?
 *
 * <p>Configuration resolution, first hit wins:
 * <ol>
 *   <li>environment {@code OPENAI_API_KEY} / {@code OPENAI_BASE_URL} / {@code OPENAI_MODEL}</li>
 *   <li>{@code -Dquad.llm.env=<path>} — defaults to the {@code .env} above</li>
 * </ol>
 * If no key resolves, the test skips instead of failing.
 *
 * <p>Tagged {@code e2e-llm}: excluded from the default build, runs with
 * {@code -Pintegration-tests}. It is a network test and must never gate a normal
 * {@code mvn test} — a flaky upstream must not turn the build red.
 */
@Tag("e2e-llm")
class WorkflowGeneratorLiveLlmTest {

    private static final String ENV_RELATIVE =
        "kafka-platform/quad-workflow-worker/.env";

    private static Map<String, String> cfg;

    @BeforeAll
    static void loadConfig() {
        cfg = resolve();
        // The configured endpoint accepts unauthenticated requests, so a key is NOT
        // required — base URL and model name are. Only skip when neither the
        // environment nor the .env file yields a model.
        assumeTrue(cfg != null && !cfg.getOrDefault("OPENAI_MODEL", "").isBlank(),
            "No OPENAI_MODEL resolved from the environment or " + envFile()
                + " — skipping the live LLM test");
    }

    /**
     * Locates the {@code .env} by walking up from the working directory. Surefire runs with
     * CWD = the module base dir ({@code quad-quarkus/quad-quarkus}), so a fixed relative path
     * would have to climb two levels; walking up is robust against module layout changes.
     */
    private static Path envFile() {
        String override = System.getProperty("quad.llm.env");
        if (override != null && !override.isBlank()) return Path.of(override).toAbsolutePath();
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++, dir = dir.getParent()) {
            Path candidate = dir.resolve(ENV_RELATIVE);
            if (Files.isReadable(candidate)) return candidate;
        }
        return Path.of(ENV_RELATIVE).toAbsolutePath();
    }

    private static Map<String, String> resolve() {
        Map<String, String> out = new HashMap<>();
        for (String k : List.of("OPENAI_API_KEY", "OPENAI_BASE_URL", "OPENAI_MODEL")) {
            String v = System.getenv(k);
            if (v != null && !v.isBlank()) out.put(k, v);
        }
        Path f = envFile();
        if (!Files.isReadable(f)) return out;
        try {
            for (String line : Files.readAllLines(f)) {
                String t = line.trim();
                if (t.isEmpty() || t.startsWith("#")) continue;
                int eq = t.indexOf('=');
                if (eq <= 0) continue;
                String k = t.substring(0, eq).trim();
                String v = t.substring(eq + 1).trim();
                // strip surrounding quotes
                if (v.length() >= 2 && (v.startsWith("\"") && v.endsWith("\"")
                        || v.startsWith("'") && v.endsWith("'"))) {
                    v = v.substring(1, v.length() - 1);
                }
                out.putIfAbsent(k, v);
            }
        } catch (Exception e) {
            return out;
        }
        return out;
    }

    /**
     * Checks that the resolved credentials actually work against the resolved base URL,
     * and returns a config that does.
     *
     * <p>Reason this exists: the {@code .env} currently pairs a key with a base URL that
     * rejects that key (HTTP 401 "Invalid API key"), while the same endpoint answers fine
     * without an Authorization header. Detecting that here turns an 8-minute retry storm
     * into an immediate, actionable message.
     *
     * <p>Set {@code -Dquad.llm.requireKey=true} to fail instead of falling back to an
     * anonymous call.
     */
    private static LlmConfig usableConfig() {
        String model = cfg.get("OPENAI_MODEL");
        String baseUrl = cfg.getOrDefault("OPENAI_BASE_URL", "https://api.openai.com/v1");
        String key = cfg.getOrDefault("OPENAI_API_KEY", "");
        boolean requireKey = Boolean.parseBoolean(System.getProperty("quad.llm.requireKey", "false"));

        if (!key.isBlank() && responds(baseUrl, key, model)) {
            return new LlmConfig(key, baseUrl, model, 2, false, false);
        }
        if (!key.isBlank()) {
            System.out.println("[live-llm] the configured API key is rejected by " + baseUrl
                + " — continuing without an Authorization header");
        }
        if (requireKey) {
            throw new IllegalStateException("the API key from the .env is rejected by " + baseUrl
                + "; fix the credentials or unset -Dquad.llm.requireKey");
        }
        return new LlmConfig("", baseUrl, model, 2, false, false);
    }

    /** One cheap round trip to find out whether these credentials are accepted. */
    private static boolean responds(String baseUrl, String key, String model) {
        try {
            var http = java.net.http.HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(10)).build();
            var body = "{\"model\":\"" + model + "\",\"max_tokens\":1,"
                + "\"messages\":[{\"role\":\"user\",\"content\":\"ping\"}]}";
            var req = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(baseUrl.replaceAll("/+$", "") + "/chat/completions"))
                .timeout(java.time.Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + key)
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body))
                .build();
            return http.send(req, java.net.http.HttpResponse.BodyHandlers.ofString())
                .statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    /** Minimal agent: the real model, no tools, so the reply is pure planner JSON. */
    private static final class LiveAgent extends Agent {
        @Override
        protected AgentSessionState newSessionState() {
            return new AgentSessionState();
        }
    }

    private static final class CapturingFactory extends ChannelAgentFactory {
        final List<ApiDtos.AgentDefinition> requested = new ArrayList<>();
        private final Agent agent;

        CapturingFactory(Agent agent) {
            this.agent = agent;
        }

        @Override
        public Agent buildFromDefinition(ApiDtos.AgentDefinition def) {
            requested.add(def);
            return agent;
        }
    }

    private static final class NoopStore extends PersistenceStore {
        @Override
        public void upsertAgent(ApiDtos.AgentDefinition agent) {
            // The live test asserts on the returned DAG, not on persisted rows.
        }
    }

    @Test
    @DisplayName("the configured model returns a parsable workflow DAG")
    void modelReturnsParsableWorkflow() {
        String model = cfg.get("OPENAI_MODEL");
        LlmConfig llm = usableConfig();
        ChatModel chatModel = OpenAiChatModel.builder()
            .apiKey(llm.apiKey())
            .baseUrl(llm.baseUrl())
            .modelName(model)
            // space-bunny-free is a reasoning model: for the Workflow-Architect prompt it
            // spends ~4000 tokens on reasoning before the answer. Two consequences, both
            // measured rather than guessed:
            //   * the langchain4j default timeout of 60s is far too short (real: ~140s),
            //     and a timeout surfaces as "retries exhausted", not as a timeout;
            //   * max_tokens must cover reasoning AND content. At 2000 the model returns
            //     finish_reason="length" with completely empty content, which parseSteps
            //     reports as "JSON missing 'steps' array". Observed need: ~8200.
            .timeout(java.time.Duration.ofMinutes(6))
            .maxTokens(16000)
            .temperature(0.0)
            .maxRetries(0)
            .build();
        assertNotNull(chatModel);

        var agent = new LiveAgent();
        agent.setLlm(chatModel);

        var svc = new WorkflowGeneratorService();
        svc.agentFactory = new CapturingFactory(agent);
        svc.state = new SharedState();
        try {
            var dbField = SharedState.class.getDeclaredField("db");
            dbField.setAccessible(true);
            dbField.set(svc.state, new NoopStore());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot inject stub PersistenceStore", e);
        }
        // Deliberately a small tool set. With the full 26-tool default the reasoning model
        // needs minutes per call over the tool schema alone and the request times out; the
        // Workflow-Architect prompt explicitly allows an empty tool list, and what this test
        // verifies is the JSON/DAG contract, not tool-picking breadth.
        svc.uiConfig = UiConfig.builder().model(model)
            .enabledTools(java.util.Set.of("read_file", "grep_search")).build();

        // The endpoint is flaky: observed one HTTP 500 ("Unknown Error") in five calls, and a
        // successful call needs 85-165s. Three attempts so a single upstream hiccup does not
        // decide the result.
        ApiDtos.WorkflowDef wf = svc.generate(
            "Research the e-scooter market and summarise the key findings",
            "Live LLM check", 3);

        assertNotNull(wf, "the model produced no workflow");
        assertNotNull(wf.id);
        assertTrue(wf.id.startsWith("workflow-dyn-"), wf.id);
        assertNotNull(wf.nodes);
        assertFalse(wf.nodes.isEmpty(), "generated workflow has no nodes");
        assertNotNull(wf.edges);

        for (var n : wf.nodes) {
            assertNotNull(n.get("id"), "node without id: " + n);
            assertNotNull(n.get("type"), "node without type: " + n);
            assertNotNull(n.get("config"), "node without config: " + n);
            @SuppressWarnings("unchecked")
            Map<String, Object> config = (Map<String, Object>) n.get("config");
            assertNotNull(config.get("agentId"), "node " + n.get("id") + " has no agentId");
        }

        // Every edge must reference existing nodes, otherwise the engine deadlocks.
        var ids = new java.util.HashSet<String>();
        for (var n : wf.nodes) ids.add(String.valueOf(n.get("id")));
        for (var e : wf.edges) {
            assertTrue(ids.contains(String.valueOf(e.get("source"))),
                "edge source not a node: " + e);
            assertTrue(ids.contains(String.valueOf(e.get("target"))),
                "edge target not a node: " + e);
        }

        System.out.println("[live-llm] model=" + model + " nodes=" + wf.nodes.size()
            + " edges=" + wf.edges.size());
    }
}