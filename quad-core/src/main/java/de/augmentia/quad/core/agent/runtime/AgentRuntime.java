package de.augmentia.quad.core.agent.runtime;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import de.augmentia.quad.core.agent.Agent;
import de.augmentia.quad.core.agent.AgentResult;
import de.augmentia.quad.core.capability.CapabilitySearch;
import de.augmentia.quad.core.agent.channels.ChannelManager;
import de.augmentia.quad.core.config.StructuredOutputConfig;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import de.augmentia.quad.core.hook.pipeline.HookContexts;
import de.augmentia.quad.core.hook.pipeline.HookRegistry;
import de.augmentia.quad.core.hook.pipeline.HookResult;
import de.augmentia.quad.core.events.AgentFinishedEvent;
import de.augmentia.quad.core.events.AgentStartedEvent;
import de.augmentia.quad.core.events.ModelRequestedEvent;
import de.augmentia.quad.core.events.ModelRespondedEvent;
import de.augmentia.quad.core.events.ToolExecutionFinishedEvent;
import de.augmentia.quad.core.events.ToolExecutionStartedEvent;
import de.augmentia.quad.core.session.AgentSessionState;
import de.augmentia.quad.core.session.CurrentSession;
import de.augmentia.quad.core.message.Message;
import de.augmentia.quad.core.session.memory.ConversationManager;
import dev.langchain4j.data.message.ChatMessageType;
import de.augmentia.quad.core.tool.QuadToolRegistry;
import de.augmentia.quad.core.tool.ToolMethod;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;

public class AgentRuntime {
    private static final java.util.logging.Logger log =
            java.util.logging.Logger.getLogger(AgentRuntime.class.getName());

    private final ChatModel llm;
    private final QuadToolRegistry toolRegistry;
    private final CapabilitySearch capabilitySearch;
    private final int maxIterations;
    private final HookRegistry hookRegistry;
    private final AgentEventPublisher eventPublisher;
    private final SpanFactory spanFactory;
    private final ToolExecutor toolExecutor;
    private final ChannelManager channelManager;

    /**
     * Bounds the conversation transcript. Without this manager the context grows untrimmed
     * until the round limit — see {@link #limitContext(List)}.
     *
     * <p>Set after construction via {@link #withConversationManager(ConversationManager)},
     * because otherwise every existing constructor would have to grow by one argument —
     * and there are at least six of them, including one in the Quarkus layer.
     */
    private ConversationManager conversationManager;

    /**
     * Detects tool loops that are not getting anywhere. Active by default — see
     * {@link #withStuckDetector(StuckDetector)}.
     */
    private StuckDetector stuckDetector = new StuckDetector();

    /** Below this message count nothing is compacted. A short transcript fits. */
    private static final int MIN_MESSAGES_BEFORE_PRUNE = 10;

    private OutputForcer outputForcer;
    private ChatRequestParameters agentChatParameters;

    public AgentRuntime(ChatModel llm, QuadToolRegistry toolRegistry,
                        CapabilitySearch capabilitySearch, int maxIterations) {
        this(llm, toolRegistry, capabilitySearch, maxIterations,
                new HookRegistry(), new AgentEventPublisher(), new OTelAgentTracer(), null, null);
    }

    public AgentRuntime(ChatModel llm, QuadToolRegistry toolRegistry,
                        CapabilitySearch capabilitySearch, int maxIterations,
                        HookRegistry hookRegistry, AgentEventPublisher eventPublisher,
                        OTelAgentTracer tracer, ToolExecutor toolExecutor) {
        this(llm, toolRegistry, capabilitySearch, maxIterations,
                hookRegistry, eventPublisher, tracer, toolExecutor, null);
    }

    public AgentRuntime(ChatModel llm, QuadToolRegistry toolRegistry,
                        CapabilitySearch capabilitySearch, int maxIterations,
                        HookRegistry hookRegistry, AgentEventPublisher eventPublisher,
                        OTelAgentTracer tracer, ToolExecutor toolExecutor,
                        ChannelManager channelManager) {
        this.llm = llm;
        this.toolRegistry = toolRegistry;
        this.capabilitySearch = capabilitySearch;
        this.maxIterations = maxIterations;
        this.hookRegistry = hookRegistry;
        this.eventPublisher = eventPublisher;
        this.spanFactory = new SpanFactory(tracer);
        this.toolExecutor = toolExecutor;
        this.channelManager = channelManager;
        this.conversationManager = null;
    }

    /**
     * Sets the transcript bound.
     *
     * <p><b>Required for unlimited runs.</b> {@code reactMaxIterations = 0} means unlimited
     * rounds; without a bound the context then grows with every round. With a manager it is
     * compacted per round — typically a
     * {@link de.augmentia.quad.core.session.memory.SummarizingSlidingWindowConversationManager},
     * which summarises older history with a model instead of cutting it.
     *
     * <p>If it stays {@code null}, the behaviour from before this extension applies: the
     * transcript grows untrimmed until the round limit.
     */
    public AgentRuntime withConversationManager(ConversationManager conversationManager) {
        this.conversationManager = conversationManager;
        return this;
    }

    /**
     * Detects a tool loop that is not getting anywhere.
     *
     * <p><b>On by default.</b> The detector does not end the cases in which a round limit
     * would be the right answer — a task that repeats while making progress triggers
     * nothing. It ends the cases in which the limit was only an alibi: the same tool with
     * the same arguments, a call sequence in a cycle, a tool inventory that changes and the
     * work still does not.
     *
     * <p>Precisely at {@code maxIterations == 0} it is what bounds the cost. Without it,
     * unlimited is only defensible with a {@code ConversationManager}; with it, unlimited is
     * defensible even when a caller switches the manager off.
     *
     * <p>{@code null} switches it off. That is why the warning matters in the off case: only
     * the round limit still applies then, and at {@code 0} not even that.
     */
    public AgentRuntime withStuckDetector(StuckDetector stuckDetector) {
        this.stuckDetector = stuckDetector;
        return this;
    }

    public void setStructuredOutputConfig(StructuredOutputConfig config) {
        this.outputForcer = config != null ? new OutputForcer(config) : null;
    }

    // ── Entry points ──

    public String run(Agent agent, String prompt, AgentSessionState state) {
        CurrentSession.setCurrent(state);
        if (outputForcer != null) outputForcer.reset();
        agentChatParameters = agent != null ? agent.getChatParameters() : null;

        try (var agentSpan = spanFactory.startSpan("agent.execute", state.getSessionId(), prompt)) {
            if (hookRegistry != null) {
                var beforeAgent = hookRegistry.triggerBeforeAgent(
                        new HookContexts.BeforeAgentContext(state.getSessionId(), prompt, Map.of()));
                if (beforeAgent instanceof HookResult.Cancel c) {
                    agentSpan.markCancelled("cancelled");
                    return "Request cancelled: " + c.reason();
                }
                if (beforeAgent instanceof HookResult.Modify<?> m) {
                    prompt = (String) m.value();
                }
            }

            if (eventPublisher != null) {
                eventPublisher.fire(new AgentStartedEvent(state.getSessionId(), Instant.now(), prompt));
            }

            List<ChatMessage> messages = new ArrayList<>(agent != null
                    ? agent.initialMessages(prompt, state)
                    : List.of(new SystemMessage(prompt)));

            if (state != null && state.memory() != null && state.memory().hasSummary()) {
                messages.add(0, new SystemMessage("Summary of earlier conversations:\n" + state.memory().summary()));
            }

            String result = runReActLoop(messages, 0);

            if (state != null && state.memory() != null) {
                state.memory().record(messages);
            }

            if (hookRegistry != null) {
                var afterAgent = hookRegistry.triggerAfterAgent(
                        new HookContexts.AfterAgentContext(state.getSessionId(), result), result);
                if (afterAgent instanceof HookResult.Modify<?> m) result = (String) m.value();
                if (afterAgent instanceof HookResult.Cancel c) result = "Request cancelled: " + c.reason();
            }

            if (eventPublisher != null) {
                String structuredResult = outputForcer != null ? outputForcer.getResult() : null;
                eventPublisher.fire(new AgentFinishedEvent(state.getSessionId(), Instant.now(), result, structuredResult));
            }

            agentSpan.markCompleted(result);
            return result;
        } catch (Exception e) {
            throw e;
        }
    }

    public AgentResult runStructured(Agent agent, String prompt, AgentSessionState state) {
        long start = System.nanoTime();
        var result = run(agent, prompt, state);
        long durationMs = (System.nanoTime() - start) / 1_000_000;
        String structuredResult = outputForcer != null ? outputForcer.getResult() : null;
        return new AgentResult(result, structuredResult, durationMs);
    }

    public AgentResult runStructured(Agent agent, String systemMessage, String userMessage, AgentSessionState state) {
        long start = System.nanoTime();
        var result = runWithMessages(agent, systemMessage, userMessage, state);
        long durationMs = (System.nanoTime() - start) / 1_000_000;
        String structuredResult = outputForcer != null ? outputForcer.getResult() : null;
        return new AgentResult(result, structuredResult, durationMs);
    }

    private String runWithMessages(Agent agent, String systemMessage, String userMessage, AgentSessionState state) {
        CurrentSession.setCurrent(state);
        if (outputForcer != null) outputForcer.reset();
        agentChatParameters = agent != null ? agent.getChatParameters() : null;

        try (var agentSpan = spanFactory.startSpan("agent.execute", state.getSessionId(), userMessage)) {
            if (hookRegistry != null) {
                var beforeAgent = hookRegistry.triggerBeforeAgent(
                        new HookContexts.BeforeAgentContext(state.getSessionId(), userMessage, Map.of()));
                if (beforeAgent instanceof HookResult.Cancel c) {
                    agentSpan.markCancelled("cancelled");
                    return "Request cancelled: " + c.reason();
                }
            }

            if (eventPublisher != null) {
                eventPublisher.fire(new AgentStartedEvent(state.getSessionId(), Instant.now(), userMessage));
            }

            List<ChatMessage> messages = new ArrayList<>();
            messages.add(new SystemMessage(systemMessage));
            messages.add(UserMessage.from(userMessage));

            if (state != null && state.memory() != null && state.memory().hasSummary()) {
                messages.add(0, new SystemMessage("Summary of earlier conversation:\n" + state.memory().summary()));
            }

            String result = runReActLoop(messages, 0);

            if (state != null && state.memory() != null) {
                state.memory().record(messages);
            }

            if (hookRegistry != null) {
                var afterAgent = hookRegistry.triggerAfterAgent(
                        new HookContexts.AfterAgentContext(state.getSessionId(), result), result);
                if (afterAgent instanceof HookResult.Modify<?> m) result = (String) m.value();
                if (afterAgent instanceof HookResult.Cancel c) result = "Request cancelled: " + c.reason();
            }

            if (eventPublisher != null) {
                String structuredResult = outputForcer != null ? outputForcer.getResult() : null;
                eventPublisher.fire(new AgentFinishedEvent(state.getSessionId(), Instant.now(), result, structuredResult));
            }

            agentSpan.markCompleted(result);
            return result;
        } catch (Exception e) {
            throw e;
        }
    }

    // ── ReAct loop (Phase 1) ──

/**
     * ReAct loop.
     *
     * <p><b>Round limit:</b> {@code maxIterations == 0} means unlimited. That is
     * defensible because {@link #limitContext(List)} writes the transcript back into a token
     * budget every round — the context does not grow without bound, it gets compacted.
     * Without this step "unlimited" would not be a gain but growing memory and latency
     * cost on every model call.
     *
     * <p><b>Context:</b> {@code limitContext} runs before every model call. Compacting
     * rather than cutting is the difference between an agent that still knows what to do
     * after 200 rounds and one that has forgotten the task.
     */
    /**
     * Writes the conversation transcript back into a token budget.
     *
     * <p>Falls back to {@link SummarizingSlidingWindowConversationManager} when one is set —
     * that one summarises older history with a real model and leaves the last
     * {@code keepLastUserMessages} user blocks untouched. Without a manager nothing is
     * trimmed, which matches the behaviour from before this change.
     *
     * <p><b>The return value is a new list.</b> The manager returns a reduced list, it does
     * not modify the one passed in; the running loop keeps its full {@code messages} so the
     * tool history does not get lost successively.
     *
     * <p>System and task messages are always pulled forward, because
     * {@link SummarizingSlidingWindowConversationManager} does preserve them, but a summary
     * in their place is a worse choice for the task.
     *
     * @return the messages that may go to the model
     */
    private List<ChatMessage> limitContext(List<ChatMessage> messages) {
        if (conversationManager == null || messages.size() <= MIN_MESSAGES_BEFORE_PRUNE) {
            return messages;
        }

        int leading = 0;
        while (leading < messages.size() && isPromptMessage(messages.get(leading))) {
            leading++;
        }
        if (leading == messages.size()) {
            return messages; // only system/task messages — nothing to trim
        }

        var prompt = List.copyOf(messages.subList(0, leading));
        var rest = messages.subList(leading, messages.size());

        try {
            var reduced = conversationManager.prune(rest.stream()
                    .map(Message::from)
                    .toList());
            var result = new ArrayList<ChatMessage>(prompt);
            reduced.forEach(m -> result.add(m.toChatMessage()));
            return result;
        } catch (Exception e) {
            // A failure while compacting must not abort the run — a broken second call
            // would be worse than an oversized context.
            log.warning("Context could not be compacted, "
                    + "the transcript goes to the model untrimmed: " + e.getMessage());
            return messages;
        }
    }

    /** System, user and task messages belong to the task, not to the transcript. */
    private static boolean isPromptMessage(ChatMessage message) {
        return message.type() == ChatMessageType.SYSTEM
                || message.type() == ChatMessageType.USER;
    }

    private String runReActLoop(List<ChatMessage> messages, int depth) {
        if (maxIterations > 0 && depth >= maxIterations) {
            return fallbackToSummary(messages);
        }

        // The transcript is written back into a token budget before every model call.
        // System and task messages sit at the front and are not compacted — they are the
        // task, not the transcript.
        List<ChatMessage> boundedContext = limitContext(messages);

        List<ToolSpecification> specs = toolRegistry != null
                ? toolRegistry.getSpecifications().stream().map(s -> s).toList()
                : List.of();

        List<ChatMessage> additional = new ArrayList<>();
        if (hookRegistry != null) {
            var beforeMc = hookRegistry.triggerBeforeModelCall(new HookContexts.BeforeModelCallContext(
                    sessionId(), new StringBuilder(), boundedContext, specs, additional));
            if (beforeMc instanceof HookResult.Cancel c) {
                return "Request cancelled: " + c.reason();
            }
            if (beforeMc instanceof HookResult.Modify<?> m && m.value() instanceof List<?> l) {
                specs = l.stream().map(x -> (ToolSpecification) x).toList();
            }
        }

        var requestMessages = new ArrayList<>(boundedContext);
        requestMessages.addAll(additional);

        if (channelManager != null) {
            channelManager.injectNotifications(requestMessages);
        }

        if (eventPublisher != null) {
            eventPublisher.fire(new ModelRequestedEvent(sessionId(), Instant.now(), List.copyOf(requestMessages)));
        }

        var builder = dev.langchain4j.model.chat.request.ChatRequest.builder()
                .messages(requestMessages);

        // =========================================================================
        // DECISION: single-pass vs. ReAct phase 1
        // =========================================================================
        boolean isSinglePassJson = specs.isEmpty() && outputForcer != null;

        if (agentChatParameters != null) {
            var merged = dev.langchain4j.model.chat.request.DefaultChatRequestParameters.builder()
                    .overrideWith(agentChatParameters);

            if (!specs.isEmpty()) {
                merged.toolSpecifications(specs);
            } else if (isSinglePassJson) {
                // immediate JSON formatting in the 1st call (no tools present)
                outputForcer.applyToParams(merged);
            }
            builder.parameters(merged.build());
        } else {
            if (!specs.isEmpty()) {
                builder.toolSpecifications(specs);
            } else if (isSinglePassJson) {
                // immediate JSON formatting in the 1st call (no tools present)
                outputForcer.applyToRequest(builder);
            }
        }

        var request = builder.build();

        try (var llmSpan = spanFactory.startLlmSpan(depth)) {
            var response = llm.chat(request);
            var aiMessage = response.aiMessage();
            if (aiMessage == null) {
                llmSpan.setAttribute("error", true);
                return "No response from LLM";
            }

            if (response.tokenUsage() != null) {
                llmSpan.recordTokenUsage(
                        response.tokenUsage().inputTokenCount(),
                        response.tokenUsage().outputTokenCount());
            }
            fireModelResponded(response);

            messages.add(aiMessage);

            // Condition for tool execution (Act)
            if (aiMessage.hasToolExecutionRequests() && aiMessage.toolExecutionRequests() != null
                    && !aiMessage.toolExecutionRequests().isEmpty()) {
                var requests = aiMessage.toolExecutionRequests();
                for (var req : requests) {
                    String toolResult = executeTool(req);
                    messages.add(new ToolExecutionResultMessage(req.id(), req.name(), toolResult));
                }
                // After execution, not before: the detector judges what was done, not what
                // was intended. And before the recursion step, so that a stuck run ends
                // instead of paying for the next model call.
                if (stuckDetector != null) {
                    StuckDetector.Pattern pattern = stuckDetector.observe(requests);
                    if (pattern != null) {
                        return pattern.marker();
                    }
                }
                return runReActLoop(messages, depth + 1);
            }

            // =========================================================================
            // REACTION / COMPLETION
            // =========================================================================

            // Case A: ReAct loop with tools completed -> execute Phase 2 formatting
            if (outputForcer != null && !specs.isEmpty()) {
                return executeFormattingPass(messages, depth + 1);
            }

            String text = aiMessage.text();
            // Not only in the single-pass case: with tools isSinglePassJson is false, and a
            // StructuredOutputConfig that was set was previously discarded silently — neither a
            // schema reached the request nor did the fallback prompt apply. An agent with
            // tools could therefore not deliver a structured result at all. The fallback
            // carries the schema in the prompt and works everywhere; the answer is checked
            // here, regardless of whether tools are in play.
            if (outputForcer != null && outputForcer.handleResponse(aiMessage, messages)) {
                return runReActLoop(messages, depth + 1);
            }

            if (hookRegistry != null) {
                int in = response.tokenUsage() != null ? response.tokenUsage().inputTokenCount() : 0;
                int out = response.tokenUsage() != null ? response.tokenUsage().outputTokenCount() : 0;
                var afterMc = hookRegistry.triggerAfterModelCall(new HookContexts.AfterModelCallContext(
                        sessionId(), text, in, out), text);
                if (afterMc instanceof HookResult.Modify<?> m) text = (String) m.value();
                if (afterMc instanceof HookResult.Retry r) return runReActLoop(messages, depth + 1);
                if (afterMc instanceof HookResult.Cancel c) text = "Request cancelled: " + c.reason();
            }

            return text != null ? text : "No response";
        } catch (Exception e) {
            throw e;
        }
    }

    /** Publishes the token usage of a completed model call (0 when the provider reports none). */
    private void fireModelResponded(dev.langchain4j.model.chat.response.ChatResponse response) {
        if (eventPublisher == null || response == null) {
            return;
        }
        int in = response.tokenUsage() != null ? response.tokenUsage().inputTokenCount() : 0;
        int out = response.tokenUsage() != null ? response.tokenUsage().outputTokenCount() : 0;
        eventPublisher.fire(new ModelRespondedEvent(sessionId(), Instant.now(), in, out));
    }

    // ── Formatting Pass (Phase 2) ──

    private String executeFormattingPass(List<ChatMessage> messages, int depth) {
        messages.add(UserMessage.from("""
            All tools executed successfully and all required information is available in the history.
            Produce the final result now in the required JSON format.
            """));

        var builder = dev.langchain4j.model.chat.request.ChatRequest.builder()
                .messages(messages);

        // IMPORTANT: Do not pass tools, but enforce response_format NOW!
        if (agentChatParameters != null) {
            var merged = dev.langchain4j.model.chat.request.DefaultChatRequestParameters.builder()
                    .overrideWith(agentChatParameters);

            outputForcer.applyToParams(merged);
            builder.parameters(merged.build());
        } else {
            outputForcer.applyToRequest(builder);
        }

        var request = builder.build();

        try (var llmSpan = spanFactory.startLlmSpan(depth)) {
            var response = llm.chat(request);
            var aiMessage = response.aiMessage();

            if (aiMessage == null || aiMessage.text() == null) {
                llmSpan.setAttribute("error", true);
                return "Failed to generate structured output.";
            }
            fireModelResponded(response);

            messages.add(aiMessage);
            if (outputForcer != null && outputForcer.handleResponse(aiMessage, messages)) {
                return runReActLoop(messages, depth + 1);
            }


            return aiMessage.text();
        } catch (Exception e) {
            throw e;
        }
    }

    // ── Fallback ──

    /**
     * The answer when the round limit is reached.
     *
     * <p><b>The marker is deliberate.</b> The text goes into the workflow output, and a
     * workflow checks conditions like {@code contains:OK} against it. Before this change a
     * summary stood here that looked like success — an agent giving up after ten rounds was
     * indistinguishable for the workflow from one that finished, and
     * {@code exitCondition: iter>=3} reported success for an aborted run.
     *
     * <p>That is why the marker stands at the front and is unambiguous: an
     * {@code [INCOMPLETE_...]} cannot be a domain output, because a domain output does not
     * begin with it. Anyone setting a limit gets a recognisable value; anyone unaware of it
     * sees it.
     */
    static final String ITERATION_LIMIT_MARKER = "[INCOMPLETE: iteration limit reached";

    private String fallbackToSummary(List<ChatMessage> messages) {
        SystemMessage summaryPrompt = SystemMessage.from("""
            The agent has reached its iteration limit and must stop now.
            Summarize what has been accomplished so far and state plainly what is NOT done.
            Do not claim success for unfinished work.
            """);

        var builder = dev.langchain4j.model.chat.request.ChatRequest.builder()
                .messages(summaryPrompt)
                .messages(limitContext(messages));

        var request = builder.build();

        try {
            var response = llm.chat(request);
            var aiMessage = response.aiMessage();
            fireModelResponded(response);
            if (aiMessage != null && aiMessage.text() != null) {
                return ITERATION_LIMIT_MARKER + " — " + maxIterations + " rounds]:\n"
                        + aiMessage.text();
            }
            return ITERATION_LIMIT_MARKER + " — " + maxIterations + " rounds, "
                    + "the model delivered no answer]";
        } catch (Exception e) {
            return ITERATION_LIMIT_MARKER + " — " + maxIterations + " rounds, "
                    + "the summarization failed: " + e.getMessage() + "]";
        }
    }

    // ── Tool execution ──

    private String executeTool(ToolExecutionRequest req) {
        AgentSessionState state = CurrentSession.getCurrent();
        String sessionId = state != null ? state.getSessionId() : "";

        ToolMethod tool = toolRegistry != null ? toolRegistry.get(req.name()) : null;
        if (tool == null) {
            return "Tool not found: " + req.name();
        }

        if (toolExecutor != null) {
            var result = toolExecutor.execute(tool, req.arguments(), state);
            return result.text();
        }

        if (eventPublisher != null) {
            eventPublisher.fire(new ToolExecutionStartedEvent(sessionId, Instant.now(), req));
        }

        if (hookRegistry != null) {
            var before = hookRegistry.triggerBeforeToolCall(new HookContexts.BeforeToolCallContext(
                    sessionId, req.name(), Map.of()));
            if (before instanceof HookResult.Cancel c) {
                return "Tool call cancelled: " + c.reason();
            }
        }

        String result;
        boolean isError = false;
        try {
            var toolResult = tool.execute(req.arguments(), state);
            result = toolResult.text();
        } catch (Exception e) {
            result = "Error: " + e.getMessage();
            isError = true;
        }

        if (hookRegistry != null) {
            var after = hookRegistry.triggerAfterToolCall(new HookContexts.AfterToolCallContext(
                    sessionId, req.name(), result, isError), result);
            if (after instanceof HookResult.Modify<?> m) result = (String) m.value();
            if (after instanceof HookResult.Cancel c) result = "Tool call cancelled: " + c.reason();
        }

        if (eventPublisher != null) {
            eventPublisher.fire(new ToolExecutionFinishedEvent(sessionId, Instant.now(), req.name(), isError, result));
        }

        return result;
    }

    private static String sessionId() {
        AgentSessionState state = CurrentSession.getCurrent();
        return state != null ? state.getSessionId() : "";
    }
}