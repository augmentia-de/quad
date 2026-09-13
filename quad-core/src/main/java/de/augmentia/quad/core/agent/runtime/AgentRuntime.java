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
import de.augmentia.quad.core.events.ToolExecutionFinishedEvent;
import de.augmentia.quad.core.events.ToolExecutionStartedEvent;
import de.augmentia.quad.core.session.AgentSessionState;
import de.augmentia.quad.core.session.CurrentSession;
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
    private final ChatModel llm;
    private final QuadToolRegistry toolRegistry;
    private final CapabilitySearch capabilitySearch;
    private final int maxIterations;
    private final HookRegistry hookRegistry;
    private final AgentEventPublisher eventPublisher;
    private final SpanFactory spanFactory;
    private final ToolExecutor toolExecutor;
    private final ChannelManager channelManager;

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

    private String runReActLoop(List<ChatMessage> messages, int depth) {
        if (depth >= maxIterations) {
            return fallbackToSummary(messages);
        }

        List<ToolSpecification> specs = toolRegistry != null
                ? toolRegistry.getSpecifications().stream().map(s -> (ToolSpecification) s).toList()
                : List.of();

        List<ChatMessage> additional = new ArrayList<>();
        if (hookRegistry != null) {
            var beforeMc = hookRegistry.triggerBeforeModelCall(new HookContexts.BeforeModelCallContext(
                    sessionId(), new StringBuilder(), messages, specs, additional));
            if (beforeMc instanceof HookResult.Cancel c) {
                return "Request cancelled: " + c.reason();
            }
            if (beforeMc instanceof HookResult.Modify<?> m && m.value() instanceof List<?> l) {
                specs = l.stream().map(x -> (ToolSpecification) x).toList();
            }
        }

        var requestMessages = new ArrayList<>(messages);
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
        // ENTSCHEIDUNG: Single-Pass vs. ReAct Phase 1
        // =========================================================================
        boolean isSinglePassJson = specs.isEmpty() && outputForcer != null;

        if (agentChatParameters != null) {
            var merged = dev.langchain4j.model.chat.request.DefaultChatRequestParameters.builder()
                    .overrideWith(agentChatParameters);

            if (!specs.isEmpty()) {
                merged.toolSpecifications(specs);
            } else if (isSinglePassJson) {
                // Sofortige JSON-Formatierung im 1. Call (Keine Tools vorhanden)
                outputForcer.applyToParams(merged);
            }
            builder.parameters(merged.build());
        } else {
            if (!specs.isEmpty()) {
                builder.toolSpecifications(specs);
            } else if (isSinglePassJson) {
                // Sofortige JSON-Formatierung im 1. Call (Keine Tools vorhanden)
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

            messages.add(aiMessage);

            // Condition for tool execution (Act)
            if (aiMessage.hasToolExecutionRequests() && aiMessage.toolExecutionRequests() != null
                    && !aiMessage.toolExecutionRequests().isEmpty()) {
                var requests = aiMessage.toolExecutionRequests();
                for (var req : requests) {
                    String toolResult = executeTool(req);
                    messages.add(new ToolExecutionResultMessage(req.id(), req.name(), toolResult));
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
            if (isSinglePassJson && outputForcer != null && outputForcer.handleResponse(aiMessage, messages)) {
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

    private String fallbackToSummary(List<ChatMessage> messages) {
        SystemMessage summaryPrompt = SystemMessage.from("""
            The agent has reached its iteration limit. Please summarize all previous tool calls and their results.
            Compose a final answer based on the provided context.
            """);

        var builder = dev.langchain4j.model.chat.request.ChatRequest.builder()
                .messages(summaryPrompt)
                .messages(messages);

        var request = builder.build();

        try {
            var response = llm.chat(request);
            var aiMessage = response.aiMessage();
            if (aiMessage != null && aiMessage.text() != null) {
                return "[Incomplete Loop, max iterations reached, summarized response]:\n" + aiMessage.text();
            }
            return "[No summarization possible — max iterations reached (" + maxIterations + ")]";
        } catch (Exception e) {
            return "[Error during summarization: " + e.getMessage() + " — max iterations reached (" + maxIterations + ")]";
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