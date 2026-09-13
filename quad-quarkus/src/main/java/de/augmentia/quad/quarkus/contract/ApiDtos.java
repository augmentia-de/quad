package de.augmentia.quad.quarkus.contract;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;
import java.util.Map;

public class ApiDtos {

    @RegisterForReflection
    public static class UiTaskRequest {
        private String task;
        private String agentId;
        /** When set, continues this session (including chat history) instead of starting fresh */
        private String sessionId;
        public String getTask() { return task; }
        public void setTask(String task) { this.task = task; }
        public String getAgentId() { return agentId; }
        public void setAgentId(String agentId) { this.agentId = agentId; }
        public String getSessionId() { return sessionId; }
        public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    }

    @RegisterForReflection
    public static class UiExecutionResult {
        public String sessionId;
        public boolean success;
        public String result;
        public int toolCount;
        public long durationMs;
        public double costLimit;
        public String model;

        public UiExecutionResult() {}
        public UiExecutionResult(String sessionId, boolean success, String result,
                int toolCount, long durationMs, double costLimit, String model) {
            this.sessionId = sessionId; this.success = success; this.result = result;
            this.toolCount = toolCount; this.durationMs = durationMs;
            this.costLimit = costLimit; this.model = model;
        }
        public static UiExecutionResult error(String message) {
            return new UiExecutionResult(java.util.UUID.randomUUID().toString(), false, message, 0, 0L, 0.0, "error");
        }
    }

    @RegisterForReflection
    public static class ValidationException extends RuntimeException {
        public ValidationException(String message) { super(message); }
    }

    @RegisterForReflection
    public static class AgentDefinition {
        public String id;
        public String name;
        public String description;
        public String category;
        public String model;
        public double temperature;
        public int maxTokens;
        public double topP;
        public String[] tools;
        public String[] guardrailsInput;
        public String[] guardrailsOutput;
        public String[] hooks;
        public String agentType;
        public String systemPrompt;
        public String userMessageTemplate;
        /** true = agent produces structured JSON; jsonOutputSchema is the enforced schema. */
        public boolean jsonOutput;
        /** JSON Schema (JSON-String) for strukturierte Ausgabe, if jsonOutput aktiv ist. */
        public String jsonOutputSchema;
        public String chatParameters;
        /** true = sichtbar in der klassischen UI; false = intern generierter WF-Step-Agent. */
        public boolean active = true;
        /** Individual runtime limit in seconds per agent execution. NULL = global default. */
        public Integer timeoutSeconds;

        public AgentDefinition() {}
        public AgentDefinition(String id, String name, String description, String category, String model,
                double temperature, int maxTokens, double topP,
                String[] tools, String[] guardrailsInput, String[] guardrailsOutput) {
            this.id = id; this.name = name; this.description = description; this.category = category;
            this.model = model; this.temperature = temperature; this.maxTokens = maxTokens; this.topP = topP;
            this.tools = tools; this.guardrailsInput = guardrailsInput; this.guardrailsOutput = guardrailsOutput;
            this.agentType = "ua";
        }
        public AgentDefinition(String id, String name, String description, String category, String model,
                double temperature, int maxTokens, double topP,
                String[] tools, String[] guardrailsInput, String[] guardrailsOutput, String agentType) {
            this.id = id; this.name = name; this.description = description; this.category = category;
            this.model = model; this.temperature = temperature; this.maxTokens = maxTokens; this.topP = topP;
            this.tools = tools; this.guardrailsInput = guardrailsInput; this.guardrailsOutput = guardrailsOutput;
            this.agentType = agentType;
        }
        public AgentDefinition(String id, String name, String description, String category, String model,
                double temperature, int maxTokens, double topP,
                String[] tools, String[] guardrailsInput, String[] guardrailsOutput, String agentType,
                String systemPrompt, String userMessageTemplate, boolean jsonOutput) {
            this(id, name, description, category, model, temperature, maxTokens, topP,
                 tools, guardrailsInput, guardrailsOutput, agentType);
            this.systemPrompt = systemPrompt;
            this.userMessageTemplate = userMessageTemplate;
            this.jsonOutput = jsonOutput;
        }
        public AgentDefinition(String id, String name, String description, String category, String model,
                double temperature, int maxTokens, double topP,
                String[] tools, String[] guardrailsInput, String[] guardrailsOutput, String agentType,
                String systemPrompt, String userMessageTemplate, boolean jsonOutput, String chatParameters) {
            this(id, name, description, category, model, temperature, maxTokens, topP,
                 tools, guardrailsInput, guardrailsOutput, agentType,
                 systemPrompt, userMessageTemplate, jsonOutput);
            this.chatParameters = chatParameters;
        }
        public AgentDefinition(String id, String name, String description, String category, String model,
                double temperature, int maxTokens, double topP,
                String[] tools, String[] guardrailsInput, String[] guardrailsOutput, String agentType,
                String systemPrompt, String userMessageTemplate, boolean jsonOutput, String chatParameters,
                boolean active) {
            this(id, name, description, category, model, temperature, maxTokens, topP,
                 tools, guardrailsInput, guardrailsOutput, agentType,
                 systemPrompt, userMessageTemplate, jsonOutput, chatParameters);
            this.active = active;
        }
        public AgentDefinition(String id, String name, String description, String category, String model,
                double temperature, int maxTokens, double topP,
                String[] tools, String[] guardrailsInput, String[] guardrailsOutput, String agentType,
                String systemPrompt, String userMessageTemplate, boolean jsonOutput, String chatParameters,
                boolean active, String[] hooks) {
            this(id, name, description, category, model, temperature, maxTokens, topP,
                 tools, guardrailsInput, guardrailsOutput, agentType,
                 systemPrompt, userMessageTemplate, jsonOutput, chatParameters, active);
            this.hooks = hooks;
        }
        public AgentDefinition(String id, String name, String description, String category, String model,
                double temperature, int maxTokens, double topP,
                String[] tools, String[] guardrailsInput, String[] guardrailsOutput, String agentType,
                String systemPrompt, String userMessageTemplate, boolean jsonOutput, String chatParameters,
                boolean active, String[] hooks, Integer timeoutSeconds) {
            this(id, name, description, category, model, temperature, maxTokens, topP,
                 tools, guardrailsInput, guardrailsOutput, agentType,
                 systemPrompt, userMessageTemplate, jsonOutput, chatParameters, active, hooks);
            this.timeoutSeconds = timeoutSeconds;
        }
        public Map<String, Object> toJson() {
            var b = new java.util.LinkedHashMap<String, Object>();
            b.put("id", id);
            b.put("name", name);
            b.put("description", description);
            b.put("category", category);
            b.put("model", model);
            b.put("temperature", temperature);
            b.put("maxTokens", maxTokens);
            b.put("topP", topP);
            b.put("tools", tools != null ? List.of(tools) : List.of());
            b.put("guardrailsInput", guardrailsInput != null ? List.of(guardrailsInput) : List.of());
            b.put("guardrailsOutput", guardrailsOutput != null ? List.of(guardrailsOutput) : List.of());
            b.put("hooks", hooks != null ? List.of(hooks) : List.of());
            b.put("agentType", agentType);
            b.put("systemPrompt", systemPrompt);
            b.put("userMessageTemplate", userMessageTemplate);
            b.put("jsonOutput", jsonOutput);
            b.put("jsonOutputSchema", jsonOutputSchema);
            b.put("chatParameters", chatParameters);
            b.put("active", active);
            b.put("timeoutSeconds", timeoutSeconds);
            return b;
        }
    }

    @RegisterForReflection
    public static class AgentCreateRequest {
        public String name;
        public String description;
        public String category;
        public String model;
        public double temperature;
        public int maxTokens;
        public double topP;
        public String[] tools;
        public String[] guardrailsInput;
        public String[] guardrailsOutput;
        public String[] hooks;
        public String agentType = "ua";
        public String systemPrompt;
        public String userMessageTemplate;
        public boolean jsonOutput;
        public String jsonOutputSchema;
        public String chatParameters;
        /** Optional: false erzeugt an unsichtbaren (internen) Agenten. Default true. */
        public Boolean active;
        /** Optionale Laufzeitbegrenzung in Sekunden. NULL = globaler Default. */
        public Integer timeoutSeconds;
    }

    @RegisterForReflection
    public static class WorkflowDef {
        public String id;
        public String name;
        public List<Map<String, Object>> nodes;
        public List<Map<String, Object>> edges;
        public String initialTask;
        public String createdAt;
        public String updatedAt;

        public WorkflowDef() {}
        public WorkflowDef(String id, String name, List<Map<String, Object>> nodes,
                List<Map<String, Object>> edges, String createdAt, String updatedAt) {
            this(id, name, nodes, edges, null, createdAt, updatedAt);
        }
        public WorkflowDef(String id, String name, List<Map<String, Object>> nodes,
                List<Map<String, Object>> edges, String initialTask,
                String createdAt, String updatedAt) {
            this.id = id; this.name = name; this.nodes = nodes; this.edges = edges;
            this.initialTask = initialTask;
            this.createdAt = createdAt; this.updatedAt = updatedAt;
        }
    }

    @RegisterForReflection
    public static class WorkflowCreateRequest {
        public String id;
        public String name;
        public java.util.List<java.util.Map<String, Object>> nodes;
        public java.util.List<java.util.Map<String, Object>> edges;
        public String initialTask;
    }

    @RegisterForReflection
    public static class WorkflowExecuteRequest {
        public String initialData;
        public Map<String, Object> inputs;

        public WorkflowExecuteRequest() {}

        public String initialDataString() {
            if (initialData != null && !initialData.isBlank()) return initialData;
            if (inputs == null || inputs.isEmpty()) return null;
            try {
                return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(inputs);
            } catch (Exception e) {
                return String.valueOf(inputs);
            }
        }
    }

    /** Ein einzelner Schritt an Run-Sicht (from session_events STEP/SPAN-Records). */
    @RegisterForReflection
    public static class StepView {
        public String stepId;
        public String kind;
        public String status;
        public long durationMs;
        public String eventType;
        public String timestamp;

        public StepView() {}
        public StepView(String stepId, String kind, String status, long durationMs, String eventType, String timestamp) {
            this.stepId = stepId; this.kind = kind; this.status = status;
            this.durationMs = durationMs; this.eventType = eventType; this.timestamp = timestamp;
        }
    }

    /** Run-Sicht (aggregiert from TelemetryStore.query) for the UI-Runs-Ansicht. */
    @RegisterForReflection
    public static class RunView {
        public String runId;
        public String kind;
        public String status;
        public int stepIndex;
        public String startedAt;
        public String finishedAt;
        public long durationMs;
        public List<StepView> steps;

        public RunView() {}
        public RunView(String runId, String kind, String status, int stepIndex,
                       String startedAt, String finishedAt, long durationMs, List<StepView> steps) {
            this.runId = runId; this.kind = kind; this.status = status; this.stepIndex = stepIndex;
            this.startedAt = startedAt; this.finishedAt = finishedAt; this.durationMs = durationMs;
            this.steps = steps;
        }
    }

    @RegisterForReflection
    public static class MessagingInConfig {
        public String topic;
        public String agentId;
        public String tenantId = "default";
        public long timeoutMs = 30000;

        public MessagingInConfig() {}
        public MessagingInConfig(String topic, String agentId, String tenantId, long timeoutMs) {
            this.topic = topic; this.agentId = agentId; this.tenantId = tenantId; this.timeoutMs = timeoutMs;
        }
    }

    @RegisterForReflection
    public static class MessagingOutConfig {
        public String channel;
        public String topic;

        public MessagingOutConfig() {}
        public MessagingOutConfig(String channel, String topic) {
            this.channel = channel; this.topic = topic;
        }
    }

    @RegisterForReflection
    public static class MessagingChannelConfig {
        public String name;
        public String transport;
        public String topic;
        public String agentId;
        public String tenantId;

        public MessagingChannelConfig() {}
        public MessagingChannelConfig(String name, String transport, String topic, String agentId) {
            this.name = name; this.transport = transport; this.topic = topic; this.agentId = agentId;
        }
        public MessagingChannelConfig(String name, String transport, String topic, String agentId, String tenantId) {
            this.name = name; this.transport = transport; this.topic = topic; this.agentId = agentId; this.tenantId = tenantId;
        }
    }
}
