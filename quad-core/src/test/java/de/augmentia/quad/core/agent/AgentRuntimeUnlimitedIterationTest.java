package de.augmentia.quad.core.agent;

import de.augmentia.quad.core.agent.runtime.AgentEventPublisher;
import de.augmentia.quad.core.agent.runtime.AgentRuntime;
import de.augmentia.quad.core.agent.runtime.IdempotencyStore;
import de.augmentia.quad.core.agent.runtime.OTelAgentTracer;
import de.augmentia.quad.core.agent.runtime.StuckDetector;
import de.augmentia.quad.core.agent.runtime.ToolExecutor;
import de.augmentia.quad.core.session.AgentSessionState;
import de.augmentia.quad.core.session.memory.ConversationManager;
import de.augmentia.quad.core.session.memory.SlidingWindowConversationManager;
import de.augmentia.quad.core.session.memory.SummarizingSlidingWindowConversationManager;
import de.augmentia.quad.core.message.Message;
import de.augmentia.quad.core.tool.QuadToolRegistry;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unlimited agent runs and what they do to the context.
 *
 * <p>Two claims that did not hold before and have to hold now:
 *
 * <ol>
 *   <li>{@code reactMaxIterations = 0} means unlimited rounds. Previously
 *       {@code depth >= maxIterations} aborted immediately at 0, and
 *       {@code setReActMaxIterations} rejected 0 with an exception — so the value was not
 *       even reachable.</li>
 *   <li>Unlimited is only defensible with a bound on the transcript. Without one, the whole
 *       transcript goes to the model on every call and grows with every round.</li>
 * </ol>
 *
 * <p>The second point is why a model serves as the summariser here: a window mode that cuts
 * old messages would be wrong even for unlimited rounds — the agent would then know that it
 * is continuing, but no longer what about.
 */
@DisplayName("AgentRuntime — unlimited rounds and context budget")
class AgentRuntimeUnlimitedIterationTest {

    /** Always answers with a tool call, until the test stops doing so. */
    private static ChatModel toolCallingForever(AtomicInteger calls, int roundsBeforeAnswer) {
        var model = mock(ChatModel.class);
        when(model.chat(any(ChatRequest.class))).thenAnswer(invocation -> {
            int n = calls.incrementAndGet();
            if (n <= roundsBeforeAnswer) {
                return ChatResponse.builder()
                        .aiMessage(new AiMessage(java.util.List.of(
                                ToolExecutionRequest.builder()
                                        .id("call-" + n)
                                        .name("do_nothing")
                                        .arguments("{\"i\":" + n + "}")
                                        .build())))
                        .build();
            }
            return ChatResponse.builder().aiMessage(AiMessage.from("done")).build();
        });
        return model;
    }

    private static ToolExecutor instantToolSuccess() {
        return new ToolExecutor(new IdempotencyStore(), new OTelAgentTracer(), new AgentEventPublisher()) {
            @Override
            public de.augmentia.quad.core.tool.ToolResult execute(
                    de.augmentia.quad.core.tool.ToolMethod tool, String jsonArguments, AgentSessionState state) {
                return de.augmentia.quad.core.tool.ToolResult.success("result " + jsonArguments);
            }
        };
    }

    /** Like the default tool executor, but with a custom payload. */
    private static ToolExecutor instantToolSuccessWith(String result) {
        return new ToolExecutor(new IdempotencyStore(), new OTelAgentTracer(), new AgentEventPublisher()) {
            @Override
            public de.augmentia.quad.core.tool.ToolResult execute(
                    de.augmentia.quad.core.tool.ToolMethod tool, String jsonArguments, AgentSessionState state) {
                return de.augmentia.quad.core.tool.ToolResult.success(result);
            }
        };
    }

    /** A ChatModel that always returns the same text — usable as a summariser. */
    private static ChatModel repliesWith(String text) {
        var model = mock(ChatModel.class);
        when(model.chat(any(ChatRequest.class)))
                .thenReturn(ChatResponse.builder().aiMessage(AiMessage.from(text)).build());
        return model;
    }

    private static AgentRuntime runtimeWith(ChatModel model, int maxIterations,
                                            ConversationManager manager) {
        var registry = mock(QuadToolRegistry.class);
        when(registry.getSpecifications()).thenReturn(List.of(
                ToolSpecification.builder().name("do_nothing").build()));
        var runtime = new AgentRuntime(model, registry, null, maxIterations,
                null, new AgentEventPublisher(), null, instantToolSuccess());
        return manager == null ? runtime : runtime.withConversationManager(manager);
    }

    private static Agent agentWithRounds(int maxIterations) {
        var agent = new Agent() {
            @Override
            protected AgentSessionState newSessionState() {
                return new AgentSessionState();
            }
        };
        agent.setReActMaxIterations(maxIterations);
        return agent;
    }

    /**
     * An agent as production builds it: with model and tool registry, via
     * {@link Agent#run(String, AgentSessionState)}.
     *
     * <p><b>Why via {@code Agent} and not via {@code new AgentRuntime(...)}.</b> The direct
     * constructor path bypasses exactly the place where it used to break: {@code Agent} never
     * called {@code withConversationManager} at any of its three constructor sites,
     * {@code limitContext} therefore ran into the void, and {@code 0 = unlimited} meant
     * unlimited rounds <em>and</em> unlimited context. A test that sets the manager itself
     * does not check this wiring — it bypasses it. That is why the two context tests here run
     * via {@code Agent} and set nothing.
     */
    private static Agent agentWith(ChatModel model, int maxIterations) {
        var registry = mock(QuadToolRegistry.class);
        when(registry.getSpecifications()).thenReturn(List.of(
                ToolSpecification.builder().name("do_nothing").build()));
        // get() has to answer. Without it the registry returns null and the runtime writes
        // "Tool not found" into the message — the payload the test feeds in then never
        // reaches the model and the budget is never hit.
        when(registry.get(any())).thenReturn(mock(de.augmentia.quad.core.tool.ToolMethod.class));
        var agent = agentWithRounds(maxIterations);
        agent.setLlm(model);
        agent.setToolRegistry(registry);
        agent.setToolExecutor(instantToolSuccess());
        return agent;
    }

    /**
     * A tool result on the scale a real one has: file content, a hit list, an API response.
     *
     * <p><b>Why this is necessary.</b> With "result {}" as the payload, a 25-round run sits
     * at around 300 estimated tokens and thus far below any sensible budget — the test would
     * be green even though the bound never engaged. That would be a green test that checks
     * nothing. The manager's estimate is {@code chars / 4 + 4}, so a run that breaks the
     * budget needs real payload.
     */
    private static final String REALISTIC_RESULT = "x".repeat(2_000);

    /** The same estimate as {@code SummarizingSlidingWindowConversationManager}: chars/4 + 4. */
    private static int tokensEstimated(List<dev.langchain4j.data.message.ChatMessage> messages) {
        int sum = 0;
        for (var m : messages) {
            String c = textOf(m);
            sum += (c != null ? c.length() : 0) / 4 + 4;
        }
        return sum;
    }

    /**
     * Text from a message, without the {@code toString()} detour.
     *
     * <p>{@code toString()} returns the langchain4j wrapper
     * ({@code UserMessage { name = null, contents = [TextContent { text = "…" }] }}), and an
     * estimate on that measures the wrapper instead of the content. That is why it is read
     * via the type — the same reasoning from which
     * {@code StructuralCompactor.textOf()} in the core originated.
     */
    private static String textOf(dev.langchain4j.data.message.ChatMessage m) {
        if (m instanceof dev.langchain4j.data.message.UserMessage u) {
            return u.singleText();
        }
        if (m instanceof dev.langchain4j.data.message.SystemMessage s) {
            return s.text();
        }
        if (m instanceof dev.langchain4j.data.message.AiMessage a) {
            // null when the answer only carries tool calls and no text. Precisely the case
            // in every round of this test — and the reason the estimate in the core
            // computes defensively instead of blindly dereferencing.
            return a.text();
        }
        if (m instanceof dev.langchain4j.data.message.ToolExecutionResultMessage t) {
            return t.text();
        }
        return "";
    }

    /** A manager that trims nothing — so that "no bound" can be requested explicitly. */
    private static ConversationManager unchanged() {
        return messages -> messages;
    }

    // ── 0 = unlimited ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("reactMaxIterations = 0")
    class Unlimited {

        @Test
        @DisplayName("runs well past the previous limit of 10")
        void runsUnlimited() {
            var calls = new AtomicInteger();
            var runtime = runtimeWith(toolCallingForever(calls, 40), 0, null);

            var result = runtime.run(agentWithRounds(0), "keep going", new AgentSessionState());

            assertThat(result)
                    .as("40 rounds have to be possible, not 10")
                    .isEqualTo("done");
            assertThat(calls.get())
                    .as("the model was asked 41 times")
                    .isGreaterThan(40);
        }

        @Test
        @DisplayName("without 0 the limit still applies")
        void normalLimitStillApplies() {
            var calls = new AtomicInteger();
            var runtime = runtimeWith(toolCallingForever(calls, 40), 5, null);

            runtime.run(agentWithRounds(5), "keep going", new AgentSessionState());

            assertThat(calls.get())
                    .as("at 5 rounds the run aborts")
                    .isLessThan(10);
        }

        @Test
        @DisplayName("setReActMaxIterations accepts 0 and rejects negative")
        void boundaryAcceptsNullAndRejectsNegative() {
            assertThatCode(() -> agentWithRounds(0)).doesNotThrowAnyException();
            assertThatThrownBy(() -> agentWithRounds(-1))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("0 = unlimited");
        }
    }

    // ── Context budget ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("bounding the transcript")
    class ContextBudget {

        @Test
        @DisplayName("without a bound the context grows with every round")
        void withoutBoundContextGrows() {
            // Documents the problem the bound solves — not the behaviour we want. The
            // assertions describe the current state.
            var calls = new AtomicInteger();
            var seenLengths = new ArrayList<Integer>();
            var model = mock(ChatModel.class);
            when(model.chat(any(ChatRequest.class))).thenAnswer(inv -> {
                seenLengths.add(((ChatRequest) inv.getArgument(0)).messages().size());
                int n = calls.incrementAndGet();
                if (n <= 25) {
                    return ChatResponse.builder()
                            .aiMessage(new AiMessage(List.of(ToolExecutionRequest.builder()
                                    .id("c" + n).name("do_nothing").arguments("{\"i\":" + n + "}").build())))
                            .build();
                }
                return ChatResponse.builder().aiMessage(AiMessage.from("done")).build();
            });

            // Via the real agent path with an explicitly unchanged manager. That is not a
            // route one takes in production — the default always applies — but it is the
            // option the context manager leaves open. That it exists is the reason this
            // test stays here: it shows what happens without a bound.
            var agent = agentWith(model, 0);
            agent.setConversationManager(unchanged());
            agent.run("task", new AgentSessionState());

            assertThat(seenLengths)
                    .as("without a bound the message count rises monotonically — that is the "
                            + "reason for the bound")
                    .isSorted();
            assertThat(seenLengths.get(seenLengths.size() - 1))
                    .as("at the end around 50 messages have accumulated")
                    .isGreaterThan(40);
        }

        @Test
        @DisplayName("with a bound the message count stays limited")
        void withBoundContextStaysLimited() {
            var calls = new AtomicInteger();
            var seenTokens = new ArrayList<Integer>();
            var model = mock(ChatModel.class);
            when(model.chat(any(ChatRequest.class))).thenAnswer(inv -> {
                var req = (ChatRequest) inv.getArgument(0);
                boolean withTools = req.toolSpecifications() != null
                        && !req.toolSpecifications().isEmpty();
                if (!withTools) {
                    // The manager's summarisation request: a single user message, without
                    // tools. Answering that with a tool call — which a blunt mock does —
                    // leaves the summary empty and the transcript collapses to nothing. The
                    // mock has to tell the two request kinds apart, otherwise it does not
                    // test the bound but a collapse case.
                    return ChatResponse.builder().aiMessage(AiMessage.from("summary")).build();
                }
                seenTokens.add(tokensEstimated(req.messages()));
                int n = calls.incrementAndGet();
                if (n <= 25) {
                    return ChatResponse.builder()
                            .aiMessage(new AiMessage(List.of(ToolExecutionRequest.builder()
                                    .id("c" + n).name("do_nothing").arguments("{\"i\":" + n + "}").build())))
                            .build();
                }
                return ChatResponse.builder().aiMessage(AiMessage.from("done")).build();
            });

            // No manager set. That is the point: the agent has to get one on its own. If
            // the test set one here, it would be checking the wiring in Agent rather than
            // its absence.
            var agent = agentWith(model, 0);
            agent.setToolExecutor(instantToolSuccessWith(REALISTIC_RESULT));
            assertThat(agent.getConversationManager())
                    .as("the default applies before the first run begins")
                    .isNotNull();

            agent.run("task", new AgentSessionState());

            int[] estimated = seenTokens.stream().mapToInt(Integer::intValue).toArray();
            int budget = agent.getContextMaxTokens();
            int max = Arrays.stream(estimated).max().orElseThrow();
            assertThat(max)
                    .as("the run had to exceed the budget, otherwise this test checks nothing — "
                            + "25 rounds x %d chars sit about %d tokens above it",
                            REALISTIC_RESULT.length(), 25 * REALISTIC_RESULT.length() / 4)
                    .isGreaterThan(budget);
            assertThat(estimated[estimated.length - 1])
                    .as("and is back below it at the end — that is the bound")
                    .isLessThanOrEqualTo(budget);
        }

        @Test
        @DisplayName("ends a tool loop, even without a round limit")
        void endsToolLoopWithoutRoundLimit() {
            var calls = new AtomicInteger();
            var model = mock(ChatModel.class);
            when(model.chat(any(ChatRequest.class))).thenAnswer(inv -> {
                // Always the same call with the same arguments. This is the case a round
                // limit can only end expensively: it does not stop the run, it
                return ChatResponse.builder()
                        .aiMessage(new AiMessage(List.of(ToolExecutionRequest.builder()
                                .id("c" + calls.incrementAndGet())
                                .name("do_nothing").arguments("{}").build())))
                        .build();
            });

            var result = agentWith(model, 0).run("endless loop", new AgentSessionState());

            assertThat(result)
                    .as("the detector ends the run instead of letting it run to session end")
                    .startsWith(StuckDetector.Pattern.MARKER)
                    .contains("REPETITION");
            assertThat(calls.get())
                    .as("and does so after few rounds, not after 40")
                    .isLessThan(10);
        }

        @Test
        @DisplayName("the default manager keeps a tool result with its request")
        void defaultManagerDoesNotSeparateResultFromRequest() {
            // The reason the default is not SlidingWindowConversationManager: it cuts bluntly
            // from the back and can land in the middle of a request-result pair. The model
            // then gets a ToolExecutionResult without an AiMessage and hallucinates the
            // request to go with it. Check both in one run: 25 rounds, each call with its
            // own result.
            var calls = new AtomicInteger();
            var requestPositions = new ArrayList<Integer>();
            var model = mock(ChatModel.class);
            when(model.chat(any(ChatRequest.class))).thenAnswer(inv -> {
                var messages = ((ChatRequest) inv.getArgument(0)).messages();
                // Every ToolExecutionResult needs an AiMessage with a tool call before it.
                for (int i = 0; i < messages.size(); i++) {
                    if (messages.get(i).type() == dev.langchain4j.data.message.ChatMessageType.TOOL_EXECUTION_RESULT
                            && (i == 0 || messages.get(i - 1).type()
                                    != dev.langchain4j.data.message.ChatMessageType.AI)) {
                        requestPositions.add(i);
                    }
                }
                int n = calls.incrementAndGet();
                if (n <= 25) {
                    return ChatResponse.builder()
                            .aiMessage(new AiMessage(List.of(ToolExecutionRequest.builder()
                                    .id("c" + n).name("do_nothing").arguments("{\"i\":" + n + "}").build())))
                            .build();
                }
                return ChatResponse.builder().aiMessage(AiMessage.from("done")).build();
            });

            agentWith(model, 0).run("task", new AgentSessionState());

            assertThat(requestPositions)
                    .as("no tool result without its request in the transcript")
                    .isEmpty();
        }

        @Test
        @DisplayName("the task never goes into the summary")
        void taskIsPreserved() {
            var messages = new ArrayList<dev.langchain4j.data.message.ChatMessage>();
            messages.add(dev.langchain4j.data.message.SystemMessage.from("SYSTEM-TASK"));
            messages.add(UserMessage.from("USER-TASK"));
            for (int i = 0; i < 30; i++) {
                messages.add(AiMessage.from("round " + i));
                messages.add(dev.langchain4j.data.message.ToolExecutionResultMessage.from(
                        "id" + i, "do_nothing", "result " + i));
            }

            var kept = new ArrayList<dev.langchain4j.data.message.ChatMessage>();
            // SlidingWindowConversationManager is the window variant without a model — it
            // lets the wiring be checked without needing a summariser model.
            // ConversationManager accepts lambdas now that it is no longer sealed.
            ConversationManager pruner = new SlidingWindowConversationManager(4);
            // same logic as limitContext: leading messages are pulled forward
            int leading = 0;
            while (leading < messages.size() && (messages.get(leading).type()
                    == dev.langchain4j.data.message.ChatMessageType.SYSTEM
                    || messages.get(leading).type()
                    == dev.langchain4j.data.message.ChatMessageType.USER)) {
                leading++;
            }
            kept.addAll(messages.subList(0, leading));
            pruner.prune(messages.subList(leading, messages.size()).stream()
                    .map(Message::from).toList()).forEach(m -> kept.add(m.toChatMessage()));

            assertThat(kept.get(0).toString()).contains("SYSTEM-TASK");
            assertThat(kept.get(1).toString()).contains("USER-TASK");
        }

        @Test
        @DisplayName("an error while trimming does not abort the run")
        void errorWhileTruncatingDoesNotAbort() {
            var calls = new AtomicInteger();
            // The realistic failure: not the manager throwing but the model that is supposed
            // to produce the summary.
            var brokenSummariser = mock(ChatModel.class);
            when(brokenSummariser.chat(any(ChatRequest.class)))
                    .thenThrow(new IllegalStateException("summariser failed"));
            var runtime = runtimeWith(toolCallingForever(calls, 30), 0,
                    new SummarizingSlidingWindowConversationManager(
                            brokenSummariser, 200, 3));

            var result = runtime.run(agentWithRounds(0), "task", new AgentSessionState());

            assertThat(result)
                    .as("a broken second call must not end the run — an oversized context would "
                            + "be the smaller evil")
                    .isEqualTo("done");
        }
    }

    // ── The abort answer has to be honest ───────────────────────────────────

    @Test
    @DisplayName("a reached limit is recognisable in the result")
    void boundaryReachedIsDetectable() {
        var calls = new AtomicInteger();
        // Never answers with a decision — the run runs into the limit.
        var model = mock(ChatModel.class);
        when(model.chat(any(ChatRequest.class))).thenAnswer(inv -> {
            int n = calls.incrementAndGet();
            return ChatResponse.builder()
                    .aiMessage(new AiMessage(List.of(ToolExecutionRequest.builder()
                            .id("c" + n).name("do_nothing").arguments("{\"i\":" + n + "}").build())))
                    .build();
        });

        var runtime = new AgentRuntime(model, registry(), null, 3,
                null, new AgentEventPublisher(), null, instantToolSuccess());

        var result = runtime.run(agentWithRounds(3), "task", new AgentSessionState());

        assertThat(result)
                .as("previously a summary stood here that looked like success — a workflow "
                        + "with exitCondition: iter>=3 reported success for an aborted run")
                .startsWith("[INCOMPLETE");
        assertThat(result)
                .as("the old wording is gone")
                .doesNotContain("No summarization possible")
                .doesNotContain("Incomplete Loop");
    }

    private static QuadToolRegistry registry() {
        var registry = mock(QuadToolRegistry.class);
        when(registry.getSpecifications()).thenReturn(List.of(
                ToolSpecification.builder().name("do_nothing").build()));
        return registry;
    }

    // ── The defaults stay ───────────────────────────────────────────────────

    @Test
    @DisplayName("the defaults stay unchanged")
    void defaultsStay() {
        assertThat(de.augmentia.quad.core.config.QuadConfig.class)
                .isNotNull();
        assertThat(de.augmentia.quad.core.tool.DynamicSubAgentTool.DEFAULT_SUB_AGENT_MAX_ITERATIONS)
                .as("the subagent default stays 5")
                .isEqualTo(5);

        var subagent = new de.augmentia.quad.core.tool.DynamicSubAgentTool(
                mock(ChatModel.class), registry());
        assertThat(subagent.subAgentMaxIterations())
                .as("and the value really is the default")
                .isEqualTo(5);
    }

    @Test
    @DisplayName("subagents can be set to unlimited")
    void subagentToUnlimited() {
        var subagent = new de.augmentia.quad.core.tool.DynamicSubAgentTool(
                mock(ChatModel.class), registry()).withSubAgentMaxIterations(0);

        assertThat(subagent.subAgentMaxIterations()).isZero();
        assertThatThrownBy(() -> subagent.withSubAgentMaxIterations(-3))
                .isInstanceOf(IllegalArgumentException.class);
    }
}