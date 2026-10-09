package de.augmentia.quad.examples;

import de.augmentia.quad.core.agent.Agent;
import de.augmentia.quad.core.agent.QuadAgent;
import de.augmentia.quad.core.agent.CachePrefixGuard;
import de.augmentia.quad.core.config.ModelFactory;
import de.augmentia.quad.core.tool.QuadToolRegistry;
import de.augmentia.quad.core.tool.ToolArgsMapper;
import de.augmentia.quad.core.tool.ReflectiveToolMethod;
import de.augmentia.quad.core.tool.builtin.ReadFileTool;
import de.augmentia.quad.core.tool.builtin.WriteTool;
import de.augmentia.quad.core.session.AgentSessionState;
import de.augmentia.quad.core.session.CurrentSession;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.lang.reflect.Method;

/**
 * Verifies the shared cache-prefix (Block A) layout across two agents
 * that each receive the same prefix, and exercises the built-in
 * {@code read_file} / {@code write_file} tools.
 *
 * <p>The prefix is injected at the very first position of the system message
 * (see {@link Agent#setCachePrefix(String)} and
 * {@code PromptAssembler#buildInitialMessages}). The layout is asserted
 * offline via {@link Agent#initialMessages(String, AgentSessionState)} so no
 * LLM call is required to verify the shared-prefix placement.</p>
 *
 * <p>Run with:
 * {@code mvn -pl quad-examples -am exec:java -Dexec.mainClass=de.augmentia.quad.examples.PrefixProbeExample}
 * (requires OPENAI_API_KEY for the real LLM calls; the layout probe works
 * offline via {@link Agent#initialMessages(String, AgentSessionState)}).</p>
 */
public class PrefixProbeExample {

    /** Shared static prefix (Block A) — byte-identical across agents. */
    private static final String SHARED_PREFIX = """
            # SHARED WORKFLOW CONTEXT (static — shared prefix, cacheable)

            ## Role Definitions
            - Analyst: extracts requirements and policy tags.
            - Architect: turns the analysis into a concrete design.

            ## Conventions
            - Use relative paths only.
            - Findings are accumulated in the session state.
            """;

    public static void main(String[] args) throws Exception {
        System.out.println("=== Prefix Probe: two agents, shared Block A ===");
        System.out.println();

        // 1. Build a registry with the built-in read/write file tools.
        QuadToolRegistry registry = new QuadToolRegistry(new ToolArgsMapper(new ObjectMapper()));
        registerBuiltinTools(registry);
        System.out.println("Registered built-in tools: " + registry.getAll().size());

        // 2. Two agents, same shared prefix, different role system prompts.
        Agent analyst = new QuadAgent();
        analyst.setLlm(ModelFactory.createOpenAiFromEnv());
        analyst.setToolRegistry(registry);
        analyst.setSystemPrompt(
                "You are the Requirement Analyst. Extract requirements and policy tags.");
        analyst.setCachePrefix(SHARED_PREFIX);

        Agent architect = new QuadAgent();
        architect.setLlm(ModelFactory.createOpenAiFromEnv());
        architect.setToolRegistry(registry);
        architect.setSystemPrompt(
                "You are the Solution Architect. Turn the analysis into a concrete design.");
        architect.setCachePrefix(SHARED_PREFIX);

        // 2b. Cache-prefix guard: both agents must expose byte-identical native
        //     tool lists, otherwise the shared prefix would not stay cacheable.
        try {
            CachePrefixGuard.requireIdenticalTools(analyst, architect);
            System.out.println("Cache-prefix guard: identical tools: true");
        } catch (IllegalStateException e) {
            System.out.println("Cache-prefix guard: identical tools: FALSE");
            System.out.println(e.getMessage());
        }

        // 3. Offline layout probe: render the initial messages for both agents
        //    and assert the shared prefix sits at the very first position.
        AgentSessionState s1 = new AgentSessionState();
        AgentSessionState s2 = new AgentSessionState();
        var m1 = analyst.initialMessages("task", s1);
        var m2 = architect.initialMessages("task", s2);

        String sys1 = ((dev.langchain4j.data.message.SystemMessage) m1.get(0)).text();
        String sys2 = ((dev.langchain4j.data.message.SystemMessage) m2.get(0)).text();

        System.out.println("Analyst system message (first 400 chars):");
        System.out.println("--------------------------------------------------");
        System.out.println(sys1.substring(0, Math.min(400, sys1.length())));
        System.out.println("--------------------------------------------------");
        System.out.println("Architect system message (first 400 chars):");
        System.out.println("--------------------------------------------------");
        System.out.println(sys2.substring(0, Math.min(400, sys2.length())));
        System.out.println("--------------------------------------------------");

        boolean prefixFirst1 = sys1.startsWith(SHARED_PREFIX);
        boolean prefixFirst2 = sys2.startsWith(SHARED_PREFIX);
        boolean sharedHead = sys1.substring(0, SHARED_PREFIX.length())
                .equals(sys2.substring(0, SHARED_PREFIX.length()));
        System.out.println();
        System.out.println("Prefix at position 0 (analyst):  " + prefixFirst1);
        System.out.println("Prefix at position 0 (architect): " + prefixFirst2);
        System.out.println("Shared head identical:          " + sharedHead);
        System.out.println("Analyst role text present:      " + sys1.contains("Requirement Analyst"));
        System.out.println("Architect role text present:    " + sys2.contains("Solution Architect"));

        // 4. Direct tool smoke test (no LLM needed): write a temp file, then read it back.
        System.out.println();
        System.out.println("=== Tool smoke test (write_file / read_file) ===");

        // Tools resolve paths against the current session state (CurrentSession)
        // and an injected WorkspaceResolver. Wire both like the built-in tests do.
        java.nio.file.Path probeRoot = java.nio.file.Path.of("probe-session");
        java.nio.file.Files.createDirectories(probeRoot);

        AgentSessionState toolState = new AgentSessionState();
        setSessionId(toolState, "probe-session");
        CurrentSession.setCurrent(toolState);

        de.augmentia.quad.core.session.WorkspaceResolver resolver =
                new de.augmentia.quad.core.session.WorkspaceResolver() {
                    @Override
                    public java.nio.file.Path sessionDir(String sessionId) {
                        return probeRoot.resolve(sessionId);
                    }
                };

        WriteTool writeFile = new WriteTool();
        writeFile.setWorkspaceResolver(resolver);
        ReadFileTool readFile = new ReadFileTool();
        readFile.setWorkspaceResolver(resolver);

        String writeResult = writeFile.writeFile("temp.md",
                "# Analysis\n\nPayment processing design for e-commerce.\n");
        System.out.println("write_file -> " + writeResult.split("\n")[0]);

        String readResult = readFile.readFile("temp.md", null, null);
        System.out.println("read_file  -> " + readResult.split("\n")[0]);
        System.out.println("read_file body contains 'Payment': "
                + readResult.contains("Payment"));

        System.out.println();
        System.out.println("=== Offline probe complete ===");
    }

    private static void setSessionId(AgentSessionState state, String id) {
        try {
            var f = AgentSessionState.class.getDeclaredField("sessionId");
            f.setAccessible(true);
            f.set(state, id);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Registers the built-in read/write tools via the public SPI. */
    private static void registerBuiltinTools(QuadToolRegistry registry) {
        ReadFileTool readFile = new ReadFileTool();
        WriteTool writeFile = new WriteTool();
        for (Object bean : new Object[]{readFile, writeFile}) {
            for (Method m : bean.getClass().getDeclaredMethods()) {
                if (m.isAnnotationPresent(
                        de.augmentia.quad.core.annotation.Tool.class)) {
                    registry.register(m.getName(),
                            new ReflectiveToolMethod(
                                    m, bean, new ToolArgsMapper(new ObjectMapper())));
                }
            }
        }
    }
}