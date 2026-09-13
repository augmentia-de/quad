# quad

Enterprise Java framework for LLM agents based on **LangChain4j** and **Quarkus**.

quad is a multi-module Java framework: a compact framework core (`quad-core`),
an optional workflow/tooling/search/sandbox module set, a Quarkus integration
(`quad-quarkus`), a React frontend (`quad-ui`) and examples (`quad-examples`).
At its heart is a **hook pipeline** (inspiration: `strands-agents/interceptor`)
through which guardrails, telemetry, resilience, human-in-the-loop, GDPR/PII and
other cross-cutting concerns are plugged into any agent as reusable building blocks.

> Goal: **Assemble an agent in a few lines** – prompt + tools, everything else comes
> as default features (retry, guardrails, workspace, events, ...).

---

## Contents

1. [Modules](#modules)
2. [Quick Start – Your First Agent](#quick-start--your-first-agent)
3. [Assembling Agents: Prompt + Tools + Defaults](#assembling-agents-prompt--tools--defaults)
4. [The Hook Pipeline](#the-hook-pipeline)
5. [Guardrails](#guardrails)
6. [Plugins](#plugins)
7. [Telemetry & Observability](#telemetry--observability)
8. [Resilience & Fault Tolerance](#resilience--fault-tolerance)
9. [Human-in-the-Loop (HITL) & Checkpoints](#human-in-the-loop-hitl--checkpoints)
10. [GDPR & PII](#gdpr--pii)
11. [Security (CapabilityToken)](#security-capabilitytoken)
12. [Gate](#gate)
13. [Feature Ports](#feature-ports)
14. [Session & Workspace](#session--workspace)
15. [Quarkus Integration (REST / SSE)](#quarkus-integration-rest--sse)
16. [ReAct Flow & Sub-Agents](#react-flow--sub-agents)
17. [Testing](#testing)
18. [Local Infrastructure](#local-infrastructure)
19. [Configuration](#configuration)
20. [Deployment](#deployment)
21. [Appendix A: Efficient Object Creation](#appendix-a-efficient-object-creation)
22. [Appendix B: Package Overview](#appendix-b-package-overview)
23. [Acknowledgements & Inspirations](#acknowledgements--inspirations)
24. [License](#license)

---

## Modules

| | Primary language | Description |
|---|---|---|
| `quad-core` | Java | Framework core: agent model (`Agent`, `AgentRuntime`, ReAct loop), hook pipeline, guardrails, plugins, telemetry, resilience, HITL, GDPR, sessions, security, capability/skills, channels, connectors |
| `quad-workflow-core` | Java | Framework-agnostic workflow model: DAG model, cron scheduling (`CronExpression`, `Schedule`, `ScheduledTask`), workflow transfer |
| `quad-tool-builtin` | Java | Default built-in tools (read/write/grep/web/bash sandbox client, patch) |
| `quad-tool-mcp` | Java | MCP tool bridge (`McpToolMethod`) |
| `quad-search-elasticsearch` | Java | Optional vector index (Elasticsearch) for capability search |
| `quad-search-pgvector` | Java | Optional vector index (Postgres pgvector) for capability search |
| `quad-sandbox-docker` | Java | Docker sandbox execution (dev fallback) |
| `quad-quarkus` | Java | Quarkus runtime: REST (`/api`, `/ui`, `/api/ui/*`), workflow engine (7 node executors), JDBC stores (memory, workspace, audit, automation, skills, runs), Kafka/AMQP/Email channels, HITL SSE, MCP manager |
| `quad-examples` | Java | Demo agents and two-stage workflow examples |
| `quad-ui` | TypeScript/React | React 19 frontend: chat UI, agent management, workflow canvas (`@xyflow/react`), mermaid diagrams |

Build & tests:

```bash
cd quad
mvn install -pl quad-core -DskipTests -q
mvn compile -pl quad-examples,quad-quarkus -q
mvn test -pl quad-core,quad-quarkus
```

---

## Quick Start – Your First Agent

An agent is a class that extends `Agent`. Every `@Tool` method is automatically
registered as a tool for the LLM.

```java
package example;

import agent.de.augmentia.quad.core.Agent;
import annotation.de.augmentia.quad.core.Param;
import annotation.de.augmentia.quad.core.Tool;
import config.de.augmentia.quad.core.ModelFactory;
import session.de.augmentia.quad.core.AgentSessionState;
import tool.de.augmentia.quad.core.QuadToolRegistry;
import tool.de.augmentia.quad.core.ToolArgsMapper;
import com.fasterxml.jackson.databind.ObjectMapper;

public class MyAgent extends Agent {

    @Override
    protected AgentSessionState newSessionState() {
        return new AgentSessionState();
    }

    @Tool(description = "Searches the web for a query and returns summaries.")
    public String searchWeb(@Param("query") String query) {
        return new builtin.tool.de.augmentia.quad.core.WebSearchTool().websearch(query, null);
    }

    public static void main(String... args) {
        MyAgent agent = new MyAgent();

        agent.setLlm(ModelFactory.createOpenAiFromEnv());          // OPENAI_API_KEY, OPENAI_MODEL, ...

        QuadToolRegistry tools = new QuadToolRegistry(new ToolArgsMapper(new ObjectMapper()));
        tools.registerFromAgent(agent);                            // scans all @Tool methods
        agent.setToolRegistry(tools);

        agent.setStateFactory(a -> new AgentSessionState());       // session state per invocation

        String answer = agent.execute("What are the latest AI trends?");
        System.out.println(answer);
    }
}
```

Configuration via environment variables (see [Configuration](#configuration)):

```bash
export OPENAI_API_KEY=sk-...
export OPENAI_MODEL=gpt-4o
java -cp ... example.MyAgent
```

---

## Assembling Agents: Prompt + Tools + Defaults

The core idea: you provide **prompt** and **tools**, everything else is a default.

### Prompt

Prompts are either the string parameter of `execute(...)` or annotated
generation methods:

```java
@GenerationMethod
@Prompt("""
    You are a senior research assistant.
    Context: {doc(state)}
    Task: {topic}
    """)
public String executeResearch(String topic) {
    return execute("Conduct research on topic: " + topic);
}
```

### Prompts & Context Management

**PromptRenderer** supports placeholders and dynamic context blocks:

| Placeholder | Meaning |
|---|---|
| `{doc(state)}` | Context documentation from state |
| `{tools}` | Dynamic tool list |
| `{skills}` | List of active skills |
| `{state.<key>}` | Value from AgentSessionState |

**ContextManager** defines three block types:
- **StaticBlock** – fixed text
- **DynamicBlock** – with `Supplier<String>` (lazy evaluate)
- **ProtectedBlock** – cannot be overwritten by prompt

```java
// Dynamic tool block
contextManager.addBlock(new ContextManager.DynamicBlock("tools", () -> toolListRenderer.renderWithTools(activeTools)));

// Skill block
contextManager.addBlock(new ContextManager.DynamicBlock("skills", () -> skillRegistry.renderActiveSkills()));
```

### Tools

1. **Custom tools** – methods on the agent with `@Tool`/`@Param` (see Quick Start).
2. **Built-in tools** – `ReadFileTool`, `WebSearchTool`, `FindTool`, `GrepTool`,
   `LsTool`, `WriteTool`, `WebFetchTool`, `MultiEditTool`, `ApplyPatchTool`,
   `BashSandboxTool` – in Quarkus context automatically available via `StandardToolProvider`,
   in plain Java register individually via `ReflectiveToolMethod`.
3. **Tool restriction** – enable only a subset:

   ```java
   QuadToolRegistry all = new QuadToolRegistry(new ToolArgsMapper(new ObjectMapper()));
   all.registerBuiltIn(standardToolProvider);            // Quarkus: injected
   QuadToolRegistry reduced = all.withOnly(Set.of("readFile", "websearch"));
   ```

### Enabling Defaults

The following sections show the individual building blocks. Here's what the **full**
default setup looks like in ~15 lines (retry, guardrails, events, logging, audit):

```java

import de.augmentia.quad.core.interceptor.telemetry.LoggingHook;

// 1. Events
AgentEventPublisher events = new AgentEventPublisher();
events.

        addEventListener(new LoggingHook()::onEvent);

        // 2. Guardrail (example: no email addresses in prompt)
        Guardrail noPii = (messages, context) -> {
            for (var m : messages) {
                if (m.toString().matches(".*[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}.*"))
                    return GuardrailResult.block("PII found in prompt");
            }
            return GuardrailResult.ok();
        };

        // 3. HITL for critical tools
        CheckpointService checkpoints = new CheckpointService("executeBash", 120_000);
        HITLPlugin hitl = new HITLPlugin(checkpoints);

// 4. Wire together
agent.

        setEventPublisher(events);
agent.

        addHook(new GuardrailPlugin(List.of(noPii),List.

        of()));
        agent.

        addHook(hitl);

        // 5. Resilience round is a feature of the ToolExecutor path / ResilientToolExecutor
        String result = agent.execute("Execute the task.");
```

---

## ReAct Flow & Sub-Agents

### Single-Shot (Default)

`execute(prompt)` / `generate(prompt)` makes exactly one LLM call with the
given prompt and returns the final answer. This remains the default and is
sufficient for simple tasks.

### ReAct: Reason → Act → Observation

For tasks where the LLM needs to iteratively access tools (analysis,
research, multi-step problem solving), there's `executeReAct`:

```java
String answer = agent.executeReAct("Analyze the project status and create a report");
```

The loop:
1. LLM receives system message (state + prompt + available tools) and returns
   either a **tool call** or **text** as answer.
2. On a tool call, the tool is executed, the result is appended as
   `ToolExecutionResultMessage`, and the LLM call is repeated.
3. On pure text, it's returned as the final answer.
4. After `reactMaxIterations` (default 10) iterations, the loop
   is aborted with a message.

```java
agent.setReActMaxIterations(20);   // optional, default 10
String answer = agent.executeReAct(prompt, state);   // with explicit session state
```

Note: `execute()` remains unchanged (single-shot). `executeReAct` is opt-in.

### Sub-Agents via `SubAgentTool`

`SubAgentTool` is a `ToolMethod` that exposes a second agent (with its own
tool registry and prompt) as a tool for the parent agent.
The sub-agent runs the same prompt as an `executeReAct` loop and shares
the **shared `AgentSessionState`** of the caller.

```java
Agent research = AgentBuilder.create(ResearchAgent.class)
    .withLlmFromEnv()
    .withTools(Set.of("readFile", "websearch"))
    .build();

Agent orchestrator = AgentBuilder.create(OrchestratorAgent.class)
    .withLlmFromEnv()
    .withSubAgent("research", research)   // register sub-agent as tool
    .build();

String result = orchestrator.executeReAct("Research market status and summarize");
```

The orchestrator agent sees `research` as a tool and can invoke it via tool call.
The sub-agent receives the same `AgentSessionState` and can share findings
and CWD changes with the parent.

**Recursion protection**: `SubAgentTool` limits nesting depth via
ThreadLocal (default: 5 levels). When the limit is reached, the tool
returns an error message instead of throwing an exception.

```java
// Configure depth limit (optional, default 5)
new SubAgentTool(research, "research", "Run research sub-agent", 3);
```

### Builder Support

`AgentBuilder` provides `withSubAgent(toolName, agent)` for clean wiring:

```java
AgentBuilder.create(MyAgent.class)
    .withLlmFromEnv()
    .withSubAgent("research", researchAgent)
    .withSubAgent("code_review", codeReviewAgent)
    .build();
```

Sub-agents are only registered if not filtered by `withTools(Set.of(...))`
(or if the sub-agent name is included in the filter).

---

## The Hook Pipeline

The pipeline intercepts the agent lifecycle at **six points**:

| Hook | When |
|---|---|
| `beforeAgent` | before prompt processing |
| `afterAgent` | after final answer |
| `beforeModelCall` | before each LLM call (can modify tools/messages) |
| `afterModelCall` | after each LLM call (can modify answer / trigger retry) |
| `beforeToolCall` | before each tool call (e.g. HITL, approval) |
| `afterToolCall` | after each tool call (e.g. audit trail) |

### Building Blocks

- **`HookResult`** (sealed): `Continue`, `Cancel(reason)`, `Modify(value)`, `Retry`
- **`HookContexts`**: typed records `BeforeAgentContext`, `AfterAgentContext`,
  `BeforeModelCallContext`, `AfterModelCallContext`, `BeforeToolCallContext`,
  `AfterToolCallContext`
- **`HookRegistry`**: thread-safe, sorts hooks by `order()`, isolates errors
  according to `HookFailurePolicy` (`ISOLATE` | `CHAIN_ABORT`)
- **`AgentHook`**: interface with default methods – only implement what you need

### Custom Hook

```java


public class MyHook implements AgentHook {
    @Override
    public String name() {
        return "my-hook";
    }

    @Override
    public int order() {
        return 0;
    }

    @Override
    public HookResult beforeModelCall(HookContexts.BeforeModelCallContext ctx) {
        if (ctx.systemPrompt().toString().contains("secret")) {
            return new HookResult.Cancel("Prompt contains forbidden terms");
        }
        return new HookResult.Continue();
    }
}

agent.

addHook(new MyHook());
```

A hook that rewrites the answer:

```java
@Override
public HookResult afterModelCall(HookContexts.AfterModelCallContext ctx, String llmResponse) {
    if (llmResponse == null) return new HookResult.Continue();
    return new HookResult.Modify<>(llmResponse.replace("TODO", "done"));
}
```

### Error Handling

```java
agent.getHookRegistry().setFailurePolicy(HookFailurePolicy.ISOLATE);   // default
agent.getHookRegistry().setFailurePolicy(HookFailurePolicy.CHAIN_ABORT); // abort on hook error
```

---

## Guardrails

Guardrails validate input/output and return a `GuardrailResult`
(`ok()` or `block(reason[, sanitized])`).

```java


Guardrail languageGuardrail = (messages, context) ->
        messages.toString().toLowerCase().contains("hit me")
                ? GuardrailResult.block("Violence directive detected")
                : GuardrailResult.ok();

GuardrailPlugin guardrails = new GuardrailPlugin(
        List.of(languageGuardrail),   // input guardrails
        List.of()                     // output guardrails
);
agent.

addHook(guardrails);
```

With custom `BlockAction` and fallback text:

```java
new GuardrailPlugin(
    List.of(languageGuardrail),
    List.of(),
    BlockAction.FALLBACK,                       // THROW | FALLBACK | ESCALATE
    "I cannot process this request."
);
```

- **`THROW`** throws a `GuardrailException` (`guardrailReason()`)
- **`FALLBACK`** returns the configured fallback text
- **`ESCALATE`** forwards the case to the designated escalation logic

Results / approvals as records: `ApprovalResult.approved(action)` or
`ApprovalResult.denied(action, feedback)`.

---

## Plugins

A `Plugin` bundles hooks, tools, and guardrails and is connected to an agent:

```java


public class MyPlugin implements Plugin {
    @Override
    public String name() {
        return "my-plugin";
    }

    @Override
    public void initAgent(Agent agent) {
        agent.addHook(new MyHook());
    }

    @Override
    public List<Guardrail> getInputGuardrails() {
        return List.of(...);
    }

    @Override
    public List<ToolMethod> getTools() {
        return List.of(...);
    }
}

new

PluginRegistry(List.of(myPlugin)).

initialize(agent);
```

`PluginRegistry.initialize(agent)` registers tools in `QuadToolRegistry`,
`GuardrailPlugin` from `getInputGuardrails()`/`getOutputGuardrails()` and the plugin
hook itself in the agent's `HookRegistry`. Guardrail-related plugins are ready-made:
`GuardrailPlugin`, `HITLPlugin`, `GdprAgentPlugin`.

---

## Telemetry & Observability

### Typed Lifecycle Events

All events implement the sealed interface `AgentEvent`:

- `AgentStartedEvent(sessionId, timestamp, initialPrompt)`
- `AgentFinishedEvent(sessionId, timestamp, finalAnswer)`
- `ModelRequestedEvent(sessionId, timestamp, promptHistory)`
- `ToolExecutionStartedEvent(sessionId, timestamp, toolExecutionRequest)`
- `ToolExecutionFinishedEvent(sessionId, timestamp, toolName, isError, result)`
- `TokenEvent`, `AgentStateChangedEvent`, `BeforeInvocationEvent`, `AfterInvocationEvent`

`Agent` fires `AgentStarted`, `ModelRequested`, `AgentFinished`; the
`AgentRuntime` (including ReAct loop) additionally fires tool events.

```java


agent.getEventPublisher().

addEventListener(event ->{
        switch(event){
        case
AgentStartedEvent s ->log.

info("Start: {}",s.initialPrompt());
        case
AgentFinishedEvent f ->log.

info("Done: {}",f.finalAnswer());
        case
AgentEvent e ->{}   // other
        }
        });
```

### Logging

```java
agent.getEventPublisher().addEventListener(new LoggingHook()::onEvent);
```

### Metrics (Micrometer)

```java

import io.micrometer.core.instrument.MeterRegistry;

// Quarkus: @Inject MeterRegistry meterRegistry
AgentMetrics metrics = new AgentMetrics(meterRegistry);
        MetricsHook metricsHook = new MetricsHook(metrics);
agent.

        getEventPublisher().

        addEventListener(metricsHook::onEvent);
```

Metrics: `agent.execution.duration`, `agent.llm.calls`, `agent.tool.executions`,
`agent.tokens.prompt`, `agent.tokens.completion`, `agent.errors`.

### Tracing (OpenTelemetry)

```java
import io.opentelemetry.api.OpenTelemetry;

// Quarkus: @Inject OpenTelemetry otel
AgentTracing tracing = new AgentTracing(otel);
TracingHook tracingHook = new TracingHook(tracing);
agent.getEventPublisher().addEventListener(tracingHook::onEvent);
```

### LLM Call Log (rotating)

```java
FileLlmLogger llmLog = new FileLlmLogger(Path.of("logs/llm.log"));
agent.setLlm(new LoggingChatModel(ModelFactory.createOpenAiFromEnv(), llmLog));
// llmLog.close();  // at the end
```

Rotation: max 2 MB per file, max 10 files (`*.0.log` … `*.9.log`).

### Event Filter Registry

For centralized routing with filters, there's a second `HookRegistry`
(`de.augmentia.quad.core.interceptor.telemetry.HookRegistry`):

```java
import de.augmentia.quad.core.interceptor.telemetry.HookRegistry;

HookRegistry telemetry = new HookRegistry();
telemetry.setDownstream(eventPublisher::fire);
telemetry.registerHook("errors", e -> e instanceof AgentFinishedEvent f && f.finalAnswer().contains("Error"),
                       new LoggingHook()::onEvent);
eventPublisher.addEventListener(telemetry);
```

---

## Resilience & Fault Tolerance

### Core building blocks (usable without Quarkus)

```java


// Retry with exponential backoff (non-retryable: 401/403/IllegalArgumentException/...)
String result = Retry.run(() -> tool.execute(args, state), RetryConfig.DEFAULT);

        // Circuit Breaker (CLOSED → OPEN → HALF_OPEN)
        CircuitBreaker cb = new CircuitBreaker(CircuitBreakerConfig.DEFAULT);
        String guarded = cb.call(() -> tool.execute(args, state), () -> "Tool unavailable");

        // Aggregate configuration
        ResilienceConfig cfg = ResilienceConfig.DEFAULT;   // or ResilienceConfig.NONE
```

`TokenRecovery` handles token limits: when `isTokenLimitError(e)` is true,
`ChatMemory` is halved (system messages are preserved).

```java
TokenRecovery recovery = new TokenRecovery();
if (TokenRecovery.isTokenLimitError(e) && recovery.recover(chatMemory)) {
    // retry
}
```

### Quarkus: MicroProfile Fault Tolerance

In the Quarkus module there's `ResilientToolExecutor` – an `@ApplicationScoped` bean with
`@Retry`, `@Timeout(30s)`, `@CircuitBreaker` and `@Fallback`:

```java
@Inject
ResilientToolExecutor tools;

String result = tools.executeTool("executeBash", "{\"script\":\"ls\"}", state);
```

Configuration (see [Configuration](#configuration)):
`quad.resilience.*` controls thresholds and timeouts.

---

## Human-in-the-Loop (HITL) & Checkpoints

### Provider-based

```java


HITLProvider provider = (action, context) -> {
    // e.g. REST call to approval backend
    return ApprovalResult.approved(action);
};

// Note: blocks until approval
HITLPlugin hitl = new HITLPlugin(provider, HITLAuthority.CONFIRM, List.of("executeBash"));
agent.

addHook(hitl);
```

`HITLAuthority`: `AUTO` (always pass), `CONFIRM` (query), `REVIEW`,
`DENY` (block by default). Console variant for interactive use:
`HITLPlugin.consoleProvider()`.

### Checkpoint-based (recommended for production)

```java


CheckpointService service = new CheckpointService(
        new InMemoryCheckpointStore(),   // custom implementation possible
        "executeBash,runSql",            // tools requiring approval
        120_000                          // timeout in ms
);

service.

registerChannel(new ConsoleChannel());     // or SSEChannel / EmailChannel
        service.

registerChannel(new SSEChannel());

HITLPlugin hitl = new HITLPlugin(service);
agent.

addHook(hitl);
```

The `Agent` knows the state via `pauseExecution()`, `approve()` and `reject(reason)`
(`isPaused()`, `pauseReason()`). The agent execution blocks in the
`beforeToolCall` hook until the checkpoint future resolves.

### Manual Checkpoints

```java
Checkpoint cp = service.createCheckpoint(sessionId, "runSql", "{}");
service.approve(cp.id(), "OK, please execute");
// or service.reject(cp.id(), "No");
service.getPendingCheckpoints(sessionId);
```

---

## GDPR & PII

### PII Anonymization (Hook)

```java
import de.augmentia.quad.core.interceptor.gdpr.PiiAnonymizerHook;
import java.util.Set;

agent.addHook(new PiiAnonymizerHook(
    Set.of(PiiAnonymizerHook.MaskType.EMAIL,
           PiiAnonymizerHook.MaskType.PHONE_NUMBER,
           PiiAnonymizerHook.MaskType.NAME_DE),
    PiiAnonymizerHook.BlockAction.REDACT,   // REDACT | THROW | MOCK
    "[REDACTED]"
));
```

`MaskType`: `EMAIL`, `PHONE_NUMBER`, `NAME_DE`, `CREDIT_CARD`, `ADDRESS`.

### Audit Trail (tamper-proof chain)

```java
import de.augmentia.quad.core.interceptor.gdpr.AuditTrailHook;

AuditTrailHook.AuditStore store = new AuditTrailHook.AuditStore() {
    final List<AuditTrailHook.AuditEntry> entries = new java.util.ArrayList<>();
    public void write(AuditTrailHook.AuditEntry entry) { entries.add(entry); }
    public List<AuditTrailHook.AuditEntry> findByUserId(String userId) { /* ... */ }
    public List<AuditTrailHook.AuditEntry> findBySessionId(String sessionId) { /* ... */ }
    public List<AuditTrailHook.AuditEntry> findAll() { return List.copyOf(entries); }
    public boolean verifyChain() {
        String prev = "";
        for (var e : entries) { if (e.hashPrevious() != null && !e.hashPrevious().equals(prev)) return false; prev = e.hashPayload(); }
        return true;
    }
};

agent.addHook(new AuditTrailHook(store));
```

### GDPR Tools + Plugin

```java
import de.augmentia.quad.core.interceptor.gdpr.GdprAgentPlugin;
import session.de.augmentia.quad.core.SessionManager;

GdprAgentPlugin gdpr = new GdprAgentPlugin(
    new SessionManager(),                              // session management
    Set.of(PiiAnonymizerHook.MaskType.EMAIL),
    PiiAnonymizerHook.BlockAction.REDACT,
    "[REDACTED]",
    store
);
new de.augmentia.quad.core.interceptor.plugin.PluginRegistry(List.of(gdpr)).initialize(agent);
```

Registers the tools `gdpr_export` (GDPR Art. 20) and `gdpr_delete` (GDPR Art. 17)
as well as PII anonymization and audit trail.

### PII Redaction on Trace/Export (Listener)

`PiiRedactionHook` (LangChain4j `ChatModelListener`) scrubs **copies** for traces and
exports – the live request to the model remains untouched:

```java
import de.augmentia.quad.core.interceptor.gdpr.PiiRedactionHook;
// register as ChatModelListener on ChatModel (see LangChain4j docs)
```

---

## Security (CapabilityToken)

`CapabilityToken` describes system capabilities for permission checks:

```java
import de.augmentia.quad.core.interceptor.security.CapabilityToken;

// FILE_READ, FILE_WRITE, DB_READ, DB_WRITE, NETWORK, EXECUTE, LLM_CALL,
// S3_READ, S3_WRITE, KAFKA_PUBLISH, KAFKA_CONSUME, VAULT_READ, VAULT_WRITE

Set<CapabilityToken> allowed = Set.of(CapabilityToken.FILE_READ, CapabilityToken.NETWORK);

boolean mayExecute = allowed.contains(CapabilityToken.EXECUTE);   // false
```

A tool can be checked against `CapabilityToken` in the `beforeToolCall` hook before
execution – combined with `@ToolRole` (SPEC §11.5) this creates an RBAC layer.

---

## Gate

Annotated methods can be equipped with time/condition-controlled gates:

```java


@Gate(type = GateType.COOLDOWN, duration = "5m")   // at most every 5 minutes
@Gate(type = GateType.CRON, schedule = "0 0 * * *") // only at certain times
public String expensiveOperation() { ...}
```

`GateType`: `COOLDOWN`, `CRON`, `CONDITION`, `EVENT`, `MANUAL`.
The actual mechanism lives in a `GateEvaluator` implementation that
provides `isOpen(method, gate)` and `recordExecution(method, gate, success)` –
applicable e.g. via CDI interceptor.

---

## Feature Ports

Six feature ports were added to bring additional capabilities into quad. Each port is **feature-flagged** with default `off`, uses its own database tables, and introduces no breaking changes.

| Port | Name | Module split | Table(s) | Config key |
|---|---|---|---|---|
| 01 | Memory Persistence (long-term memory) | core: `MemoryEntry`, `MemoryCategory`, `PersistentMemoryStore`; quarkus: `JdbcMemoryStore`, `MemoryResource` | `memories` | – |
| 02 | Workspace Permission Mode (command level) | core: `PermissionMode`, `PermissionEngine`; quarkus: `SessionPermissionRegistry`, `PermissionGuardHook`, `WorkspaceService`, `PermissionResource`, `WorkspaceResource` | `workspaces`, `project_bindings` | `quad.permission.enabled=false` |
| 03 | Structured Audit (DB-backed) | quarkus: `AuditStore`, `DbAuditLogger`, `AuditResource`; core-SPI bereits vorhanden | `audit_events` | `quad.security.audit.mode=file\|db\|both` |
| 04 | Automation Scheduler (CRON/Einmal-Tasks) | core: `CronExpression`, `Schedule`, `ScheduledTask`; quarkus: `AutomationStore`, `SchedulerService`, `AutomationService`, `AutomationTaskRunner`, `AutomationResource` | `automation_tasks`, `automation_runs` | `quad.automation.enabled=false` |
| 05 | Provenance Tracking (source tracking) | core: `ProvenanceTracker` (`AgentEventListener`), `ProvenanceEntry`; quarkus: `ProvenanceResource` | (none; in-memory per session) | – |
| 06 | SkillStore (DB-backed skills with tool restriction) | core: `SkillEntry`, `SkillStore`; quarkus: `JdbcSkillStore`, `SkillsConfig`, `SkillResource` | `skills` | `quad.skills.store.enabled=false` |

### Design principle: interface in core, implementation in quarkus

Each DB-backed feature follows a two-layer pattern:

- **quad-core**: framework-agnostic interfaces + pure logic/value objects. These classes carry no Quarkus-specific dependencies and can be reused by any backend module.
- **quad-quarkus**: JDBC store implementations (+ JAX-RS REST resources + `@Scheduled`). Built on top of core interfaces.

This means that e.g. `PersistentMemoryStore` (core) can be implemented as `JdbcMemoryStore` (quarkus) or as an in-memory stub for non-JDBC environments — without changing agent code.

### Core packages introduced

```
quad-core/src/main/java/de/augmentia/quad/core/
├── session/memory/
│   ├── MemoryEntry.java
│   ├── MemoryCategory.java
│   └── PersistentMemoryStore.java        (Interface)
├── guards/
│   ├── PermissionMode.java               (Enum: PLAN, INTERACTIVE, AUTO)
│   └── PermissionEngine.java             (canWrite / canExecuteCommand)
├── workflow/schedule/
│   ├── CronExpression.java               (5-field cron parser)
│   ├── Schedule.java                     (cron | once value object)
│   └── ScheduledTask.java                (task model with grants)
├── observability/provenance/
│   ├── ProvenanceTracker.java            (implements AgentEventListener)
│   └── ProvenanceEntry.java
└── capability/skill/
    ├── SkillEntry.java                   (persisted skill with filterAllowed)
    └── SkillStore.java                   (Interface)
```

### Quarkus packages introduced

```
quad-quarkus/src/main/java/de/augmentia/quad/quarkus/
├── memory/                             (Port 01)
│   ├── JdbcMemoryStore.java            (implements PersistentMemoryStore)
│   └── MemoryResource.java             (@Path("/api/ui/memory"))
├── permission/                         (Port 02)
│   ├── SessionPermissionRegistry.java  (per-session PermissionMode)
│   ├── PermissionGuardHook.java        (implements AgentHook)
│   ├── PermissionService.java          (wiring, enabled() flag)
│   └── PermissionResource.java         (@Path("/api/permission"))
├── workspace/                          (Port 02)
│   ├── WorkspaceRecord.java
│   ├── WorkspaceStore.java
│   ├── WorkspaceService.java
│   └── WorkspaceResource.java          (@Path("/api/workspace"))
├── security/                           (Port 03)
│   ├── AuditStore.java                 (JDBC reads from audit_events)
│   ├── DbAuditLogger.java              (implements core.AuditLogger)
│   └── AuditResource.java              (@Path("/api/audit"))
├── automation/                         (Port 04)
│   ├── TaskRun.java
│   ├── AutomationStore.java            (JSON-blob store)
│   ├── AutomationService.java          (CRUD + manual-run lifecycle)
│   ├── AutomationTaskRunner.java       (delegates to ChannelAgentFactory)
│   ├── SchedulerService.java           (@Scheduled every="10s")
│   └── AutomationResource.java         (@Path("/api/automation"))
├── provenance/                         (Port 05)
│   └── ProvenanceResource.java         (@Path("/api/provenance"))
├── skillstore/                         (Port 06)
│   ├── JdbcSkillStore.java             (implements SkillStore)
│   ├── SkillsConfig.java               (enabled + auto-load flags)
│   └── SkillResource.java              (@Path("/api/skills"))
```

### Activation / configuration

```properties
# Port 02: enable permission mode enforcement on executeBash/write/multiEdit
quad.permission.enabled=true

# Port 03: switch audit mode (file = current JSONL, db = structured rows, both = dual-write)
quad.security.audit.mode=db

# Port 04: activate scheduler loop (default off — does not run until enabled)
quad.automation.enabled=true

# Port 06: load DB skills and apply tool restrictions per skill
quad.skills.store.enabled=true
quad.skills.store.auto-load=false   # seed from personas.yaml on startup
```

All migrations are SQLite-compatible (`if not exists`, `autoincrement`, `CURRENT_TIMESTAMP`) and live in `quad-quarkus/src/main/resources/db/migration/`:

| File | Tables |
|---|---|
| `V5__memory.sql` | `memories` |
| `V6__workspaces.sql` | `workspaces`, `project_bindings` |
| `V7__audit.sql` | `audit_events` |
| `V8__automation.sql` | `automation_tasks`, `automation_runs` |
| `V9__skills.sql` | `skills` |

Integration points in `ChannelAgentFactory`:
- Port 02: `PermissionGuardHook` is registered on every agent build when `quad.permission.enabled=true`.
- Port 06: DB skills are loaded via `JdbcSkillStore.listAll()` and applied to the agent's `QuadToolRegistry` via `withOnly(...)`.

---

## Session & Workspace

### Session State

`AgentSessionState` holds per session: `sessionId`, `tenantId`, `findings`,
`sagaLog`, lock counter and mutation events.

```java
AgentSessionState state = new AgentSessionState();
state.setTenantId("acme");
state.addFinding("Fact 1");
state.setCurrentProject("Project Alpha");

// In request context (Quarkus):
CurrentSession.bind(state);   // static
// or injected @RequestScoped bean:
currentSession.bind(state);
```

`SessionManager` manages multiple sessions (`createSession`, `getSession`,
`removeSession`, `executeLocked`).

### Workspace

`WorkspaceResolver` resolves a working directory per session
(default `/work/quad` or `quad.workspace`):

```java
String dir = WorkspaceResolver.resolve(sessionId);        // /work/quad/<sessionId>
String file = WorkspaceResolver.resolve(sessionId, "notes.md");
```

Built-in tools (e.g. `ReadFileTool`) are workspace-scoped, so an agent
by default can only read/write within its session directory.

#### Session-Scoped Workspace (default)

Each session gets an isolated sandbox root `{quad.workspace}/{sessionId}`
which is always **read/write**. Paths are resolved relative to the current
CWD, and `..` escapes or absolute paths outside the root are rejected.

#### Granted Directories (user-shared paths)

Beyond the session workspace, users can additionally grant access to
arbitrary host directories on a per-session basis. Grants are **read-only
by default**; write access requires an explicit `READ_WRITE` grant.

```java
AgentSessionState state = new AgentSessionState();

state.grantDir(Path.of("/srv/shared"));            // read-only
state.grantDir(Path.of("/data/project"), true);    // read/write
state.revokeDir(Path.of("/srv/shared"));           // revoke
```

The granted directories are listed in the agent's system prompt so the LLM
knows exactly which paths (and their access level) it may touch.

#### Centralized Path Enforcement

All built-in tools go through `WorkspaceResolver`:

- `resolveForRead(state, path)` — session workspace **or** any granted dir
- `resolveForWrite(state, path)` — session workspace, or a granted dir with
  `READ_WRITE` (read-only grants are rejected for writes)

Resolution is **symlink-safe**: containment is checked against the real
(`toRealPath()`) location to prevent symlink escapes.

#### Granting Directories at Runtime

Per session, via the REST API (`AgentController`):

```bash
# Grant a read-only dir to session abc123
curl -X POST /api/ui/sessions/abc123/dirs \
  -H 'Content-Type: application/json' \
  -d '{"path":"/srv/shared","writable":false}'

# List granted dirs
curl /api/ui/sessions/abc123/dirs

# Revoke
curl -X DELETE /api/ui/sessions/abc123/dirs//srv/shared
```

#### Static Grants via Configuration

Globally granted directories can be configured with `QUAD_GRANTED_DIRS`
(applied to every new session). Format: comma-separated paths; append `:rw`
to allow writing (default read-only).

```bash
QUAD_GRANTED_DIRS="/data/project, /srv/shared:rw"
```

```properties
# application.properties
quad.workspace.granted-dirs=${QUAD_GRANTED_DIRS:}
```

Grants persist with the session state across restarts (see
`SessionStateSnapshot`).

---

## Quarkus Integration (REST / SSE)

Start:

```bash
cd quad-quarkus
mvn quarkus:dev -Dquarkus.http.port=8083
```

Endpoints (selection; full list in `docs/ARCHITECTURE.md §8`):

| Endpoint | Description |
|---|---|
| `GET  /api/health` | Health check |
| `POST /api/ui/dynamic/chat/start` | Start interactive chat session → returns generated workflow (`{"task":"...","name":"..."}`) |
| `POST /api/ui/dynamic/chat` | Continue chat session (`{"sessionId":"...","request":"..."}`) |
| `GET  /api/ui/dynamic/chat/sessions` | List active chat sessions |
| `GET  /api/checkpoints/{sessionId}/pending` | Pending checkpoints for a session |
| `POST /api/checkpoints/{id}/approve` | Approval (`{"feedback":"ok"}`) |
| `POST /api/checkpoints/{id}/reject` | Rejection (`{"feedback":"reason"}`) |
| `GET  /api/checkpoints/stream/{sessionId}` | SSE stream with checkpoint events |

Agent management, workflow CRUD/execution, skills, guardrails, HITL approvals,
metrics, audit, journal, MCP status and GDPR export/delete are served under
`/api/ui/*` (see `docs/UI-Spec.md §12` for the full mapping).

SSE push and mail notification run through `HitlService`:

```java
// application.properties
quad.hitl.approval-tools=executeBash,runSql,dockerRun
quad.hitl.notification.emails=ops@company.com
quad.hitl.timeout-seconds=120
quad.hitl.auto-approval=false
```

`ResilientToolExecutor` is automatically available as a CDI bean in Quarkus
(see [Resilience](#resilience--fault-tolerance)).

---

## Testing

### Unit Tests (default)

Standard tests run without real LLM calls and complete in seconds:

```bash
mvn test
```

Tests tagged `llm` and `e2e` are **excluded by default** via Surefire
`excludedGroups=llm,e2e`.

### Integration Tests (LLM / E2E)

Tests that make real LLM API calls are gated behind the `integration-tests`
Maven profile. These require a valid API key.

```bash
# 1. Load test environment
source .env.test

# 2. Run LLM integration tests
mvn test -Pintegration-tests

# 3. Or run only a specific module
mvn test -Pintegration-tests -pl quad-quarkus
```

### Test Tags

| Tag | Module | Tests |
|---|---|---|
| `llm` | `quad-quarkus` | `AgentExecutionIntegrationTest`, `E2E_FullFlowIntegrationTest`, `WorkflowExecutionIntegrationTest` |
| `e2e` | `quad-core` | `CodeActScenario` (full agent execution + LLM-as-judge evaluation) |

### Test Environment (.env.test)

```bash
OPENAI_API_KEY=sk-...           # Required for integration tests
OPENAI_BASE_URL=https://openrouter.ai/api/v1
OPENAI_MODEL=deepseek/deepseek-v4-flash
QUAD_DUMMY_MODE=false
QUAD_COST_LIMIT=0.50            # Max cost per test session (USD)
```

### CI Behavior

CI runs `mvn clean verify` without API key secrets — LLM tests are
automatically skipped via tag exclusion.

---

## Local Infrastructure

Two Docker Compose setups for local development and testing:

### Dev Infrastructure (Observability)

OTel Collector, Tempo, Grafana, Prometheus — for traces, metrics, dashboards.

```bash
./scripts/dev-infra.sh up       # Start
./scripts/dev-infra.sh down     # Stop
./scripts/dev-infra.sh status   # Container status + health
./scripts/dev-infra.sh logs     # Tail logs
./scripts/dev-infra.sh health   # Health checks only
```

| Service | Port |
|---|---|
| OTel Collector | 4317 (gRPC), 4318 (HTTP) |
| Tempo | 3200 |
| Grafana | 3001 (admin/admin) |
| Prometheus | 9090 |

### Prod Infrastructure (Local)

PostgreSQL + OTel + Tempo + Grafana + Prometheus — for integration testing
with a real database.

```bash
./scripts/prod-infra.sh up      # Start
./scripts/prod-infra.sh down    # Stop + remove volumes
./scripts/prod-infra.sh stop    # Stop (preserve volumes)
./scripts/prod-infra.sh status  # Container status + health
./scripts/prod-infra.sh logs    # Tail logs
./scripts/prod-infra.sh health  # Health checks only
```

| Service | Port | Details |
|---|---|---|
| PostgreSQL | 5432 | postgres / postgres |
| OTel Collector | 4317 (gRPC), 4318 (HTTP) |
| Tempo | 3200 |
| Grafana | 3001 (admin/admin) |
| Prometheus | 9090 |

Both setups use the `llm-net` Docker network. They are backed by the layered
[`deploy/*.yml`](#local-infrastructure) files — `dev-infra.sh` → `02-observability.yml`,
`prod-infra.sh` → `01-infrastructure.yml` + `02-observability.yml`. Use
`./stack.sh` to coordinate the full layer order (infra → keycloak → mcp →
app → frontend).

---

## Deployment

Build and containerize per profile, then deploy to Docker Compose, Cloud Run or GKE:

```bash
./scripts/build.sh --profile dev       # dev image (quad-quarkus:dev, quad-frontend:dev)
./scripts/build.sh --profile prod      # production image
./scripts/build.sh --profile cloud     # cloud image (+ --native for GraalVM)
./scripts/deploy.sh --profile cloud --target cloudrun   # Cloud Run
./scripts/deploy.sh --profile cloud --target gke        # GKE (Helm chart deploy/helm/quad)
```

The `deploy/` folder contains the layered Compose stack, Helm chart, Keycloak
realm and environment templates (`.env.prod`, `.env.dev`, `.env.secrets.*`).
A step-by-step GCP guide lives in `docs/GCP-DEPLOY.md`; `deploy/README.md`
documents the stack layout.

---

## Configuration

### Environment Variables (LLM)

| Variable | Default | Meaning |
|---|---|---|
| `OPENAI_API_KEY` | – | API key (required for real calls) |
| `OPENAI_BASE_URL` | OpenAI standard | Custom endpoint (e.g. Azure) |
| `OPENAI_MODEL` | `gpt-4o` | Model name |
| `LLM_TEMPERATURE` | – | Sampling temperature |
| `LLM_MAX_RETRIES` | – | LLM retries |
| `LLM_LOG_REQUESTS` / `LLM_LOG_RESPONSES` | – | Log LLM requests |

### `application.properties` (Quarkus)

```properties
# Workspace
quad.workspace=/work/quad

# Granted directories (expand sandbox beyond workspace)
# Format: comma-separated paths; append :rw to allow writing (default read-only)
#   quad.workspace.granted-dirs=/data/project, /srv/shared:rw
quad.workspace.granted-dirs=${QUAD_GRANTED_DIRS:}

# Sandbox (bash) — Docker-based isolation
# - enabled: run commands in a throw-away container (no host execution)
# - image:   runner image (auto-built from embedded Dockerfile.runner if missing)
# - memory:  container memory limit
# - timeout-ms: max execution time before the container is killed
# - network: false => `--network none` (no internet); true => allow network
quad.sandbox.enabled=true
quad.sandbox.image=quad-runner:latest
quad.sandbox.memory=512m
quad.sandbox.timeout-ms=120000
quad.sandbox.network=false

# HITL
quad.hitl.approval-tools=executeBash,runSql,dockerRun
quad.hitl.notification.emails=ops@company.com
quad.hitl.timeout-seconds=120
quad.hitl.auto-approval=false

# Resilience
quad.resilience.retry.max-attempts=3
quad.resilience.retry.delay-ms=1000
quad.resilience.retry.multiplier=2.0
quad.resilience.timeout.tool-ms=30000
quad.resilience.circuitbreaker.failure-threshold=0.5
quad.resilience.circuitbreaker.min-requests=10
quad.resilience.circuitbreaker.reset-timeout-ms=60000

# Observability (Quarkus extensions enable them automatically)
# quarkus.micrometer.enabled=true
# quarkus.opentelemetry.enabled=true

# Port 02 – Workspace Permission Mode (command level)
# - enabled: true → attaches PermissionGuardHook to every agent built by ChannelAgentFactory
# - default-mode: mode used when no per-session override exists
quad.permission.enabled=false
quad.permission.default-mode=auto    # auto | interactive | plan

# Port 03 – Structured Audit (DB-backed)
# - mode: "file" = current JSONL audit; "db" = structured rows in DB; "both" = dual-write
quad.security.audit.mode=file        # file | db | both

# Port 04 – Automation Scheduler (CRON/Einmal-Tasks)
# - enabled: false = scheduler loop does not run (default — opt-in)
quad.automation.enabled=false

# Port 06 – SkillStore (DB-backed skills with tool restriction)
# - enabled: load DB skills from skills table and apply tool restrictions
# - auto-load: seed skills from personas.yaml on startup (only if enabled=true)
quad.skills.store.enabled=false
quad.skills.store.auto-load=false   # seeds from personas.yaml into skills table
```

---

## Appendix A: Efficient Object Creation

Goal: no `new` flood per agent, reusable singleton building blocks, minimal
setup lines.

### 1. Create immutable / heavyweight objects once

These objects are **stateless or expensive** and belong as singletons in a
CDI bean (Quarkus) or static context (plain Java):

| Object | Why singleton |
|---|---|
| `ToolExecutor` | holds `IdempotencyStore`, `OTelAgentTracer`, `AgentEventPublisher`, `HookRegistry` |
| `QuadToolRegistry` | one registry object per application, `withOnly(...)` creates views |
| `AgentEventPublisher` | central event bus for all agents |
| `OTelAgentTracer` | binds to the global tracer |
| `IdempotencyStore` | must be consistent across sessions |
| `SessionManager` | one management for all sessions |
| `CheckpointService` | one HITL service for all agents |
| `FileLlmLogger` | one rotating log file |
| `AgentMetrics` / `AgentTracing` | bound to `MeterRegistry` / `OpenTelemetry` |

### 2. Default configurations instead of magic numbers

```java
Retry.run(callable, RetryConfig.DEFAULT);              // 3 attempts, 1s, *2.0
new CircuitBreaker(CircuitBreakerConfig.DEFAULT);      // 50%, 10s, 30s half-open
ResilienceConfig.DEFAULT;                              // both combined
GuardrailResult.ok();                                  // instead of new GuardrailResult(true,null,null)
GuardrailResult.block("reason");                       // instead of new GuardrailResult(false,"reason",null)
ApprovalResult.approved("runSql");                     // instead of 4-field record
HITLAuthority.CONFIRM;                                 // instead of string enums
```

### 3. Wire once instead of rebuilding per call

Example: a reusable "enterprise agent" wrapper that assembles all defaults
once:

```java
public final class Defaults {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Defaults() {}

    public static void wire(Agent agent) {
        if (agent.getHookRegistry() == null) agent.setHookRegistry(new HookRegistry());
        if (agent.getEventPublisher() == null) agent.setEventPublisher(new AgentEventPublisher());

        agent.getEventPublisher().addEventListener(new LoggingHook()::onEvent);
        agent.addHook(new GuardrailPlugin(List.of(noPii()), List.of()));
        agent.addHook(new AuditTrailHook(auditStore()));
    }

    private static Guardrail noPii() { return (messages, ctx) -> GuardrailResult.ok(); }
    private static AuditTrailHook.AuditStore auditStore() { return /* in-memory, see above */ null; }
}
```

### 4. Recommended pattern: Fluent `AgentBuilder` (proposal)

Short-term, a **project-internal builder** (in `quad-quarkus` or a separate
`quad-support` module) could reduce assembly to **one** line – the pattern:

```java
Agent agent = AgentBuilder.create(MyAgent.class)
    .withLlmFromEnv()                       // ModelFactory.createOpenAiFromEnv()
    .withTools(t -> t.registerFromAgent(...))
    .withSubAgent("research", researchAgent) // sub-agent as tool
    .withRetry(RetryConfig.DEFAULT)         // wraps ToolExecutor
    .withGuardrails(List.of(noPii), List.of())
    .withHitl("executeBash", 120_000)       // CheckpointService + HITLPlugin
    .withWorkspace(Path.of("/work/quad"))
    .withAuditTrail(auditStore)
    .withLogging(logs/llm.log)              // FileLlmLogger + LoggingChatModel
    .build();
```

Core idea: each `withXxx` step mutates an internal build bean (registry, hooks,
publisher) and calls `agent.addHook(...)` / `setLlm(...)` etc. once at the end –
exactly the same APIs described in this README, just pre-ordered with
sensible defaults. The setup remains testable and explicit, without distributed magic.

---

## Appendix B: Package Overview

> Current structure (verified 2026-09-13); the full tree lives in
> `docs/ARCHITECTURE.md §3`.

```
quad-core/src/main/java/de/augmentia/quad/core/
├── agent/                    Agent (abstract base), QuadAgent, AgentBuilder, SessionStateFactory,
│   ├── channels/             Channel, QueueChannel, TimerChannel, ChannelManager
│   ├── messaging/            InboundChannel, OutboundChannel, SyncChannel, QuadMessage
│   ├── runtime/              AgentRuntime (ReAct loop), ToolExecutor, AgentEventPublisher,
│   │                         DynamicToolProvider, OTelAgentTracer, OutputForcer, SpanFactory, IdempotencyStore
│   └── strategies/           PredictStrategy, ReflexionStrategy, TemplateStrategy
├── annotation/               @Tool, @Param, @Prompt, @GenerationMethod, @Hidden, @ToolRole, @SagaAgent, @Idempotent
├── capability/               Capability, CapabilityRegistry/Search, CapabilitySearch (SPI)
│   ├── doc/                  AgentDocExtractor
│   ├── prompt/               PromptRenderer, ContextManager, ToolListRenderer
│   └── skill/                Skill, SkillRegistry, SkillEntry, SkillStore (SPI), SKILL.md parsing
├── config/                   QuadConfig, ModelFactory, LlmConfig, RetryingChatModel
├── connectors/               Connector, ConnectorRegistry, OutboundMessaging, OutboundSender, MessageTarget
├── events/                   sealed AgentEvent hierarchy + AgentEventListener
├── gdpr/                     GdprAgentPlugin, PiiAnonymizerHook, PiiRedactionHook, AuditTrailHook, export/delete
├── guardrails/               Guardrail, GuardrailPlugin, GuardrailResult, BlockAction, ApprovalResult
├── guards/                   PermissionMode, PermissionEngine
├── hitl/                     HITLPlugin, HITLProvider, HITLAuthority, checkpoint/
├── hook/                     AgentHook, HookRegistry, HookContexts, HookResult, HookFailurePolicy
│   └── plugin/               Plugin, PluginRegistry, SkillActivationHook
├── observability/            AgentMetrics, AgentTracing, LoggingChatModel, FileLlmLogger,
│   │                         LoggingHook, MetricsHook, TracingHook, EventFilters
│   └── provenance/           ProvenanceTracker (implements AgentEventListener), ProvenanceEntry
├── patterns/                 planner patterns (Desire, VotingStrategy, ConflictResolutionStrategy)
├── resilience/               Retry, CircuitBreaker, TokenRecovery
├── scope/                    AgentScope, TypedStateKey, FieldExtractor
├── security/                 SecurityContext, SecurityConfig, SecretsProvider, TokenExchangeClient, ToolGuard
├── session/                  AgentSessionState, CurrentSession, SessionManager, WorkspaceResolver
│   ├── memory/               MemoryEntry, MemoryCategory, PersistentMemoryStore (SPI)
│   ├── query/                query abstractions
│   ├── saga/                 SagaAgent, CompensatingAction, SagaAuditRecord
│   └── store/                session / event stores
├── tool/                     QuadToolRegistry, ToolMethod, GizmoInvoker, McpToolMethod, ToolArgsMapper,
│   │                         SubAgentTool, DynamicSubAgentTool, ToolSpecificationBuilder, StandardToolProvider
│   ├── builtin/              built-in tool home-spot (impl in quad-tool-builtin)
│   └── sandbox/               sandbox SPI (impl in quad-sandbox-docker)
└── validation/               StructuredOutputValidator

quad-workflow-core/src/main/java/de/augmentia/quad/core/
└── workflow/                 DagModel, workflow node/step model
    ├── schedule/             CronExpression, Schedule, ScheduledTask
    └── transfer/             workflow serialization / transfer

quad-quarkus/src/main/java/de/augmentia/quad/quarkus/
├── agent/                    AgentBuilderFactory, TieredChatModel, QuarkusAgentRuntime
├── automation/               TaskRun, AutomationStore, AutomationService, AutomationTaskRunner, SchedulerService, AutomationResource
├── hitl/                     HitlService, CheckpointResource (REST + SSE)
├── mcp/                      McpManagerService
├── memory/                   JdbcMemoryStore (PersistentMemoryStore impl), MemoryResource
├── messaging/                MessagingRouter, ChannelAgentFactory, TopicAgentMapping
│   ├── kafka/                KafkaInboundChannel, KafkaOutboundChannel, KafkaHitlSender
│   ├── amqp/                 AmqpInboundChannel, AmqpOutboundChannel
│   └── email/                EmailInboundChannel, EmailOutboundChannel
├── permission/               SessionPermissionRegistry, PermissionGuardHook, PermissionService, PermissionResource
├── persistence/              SessionStore, RunStore, TelemetryStore, StepSnapshotStore, JournalService
├── provenance/               ProvenanceResource
├── resilience/               ResilientToolExecutor (MicroProfile Fault Tolerance)
├── security/                 QuarkusSecurityContext, DbAuditLogger, AuditResource, SecurityEnforcementFilter
├── skillstore/               JdbcSkillStore (SkillStore impl), SkillsConfig, SkillResource
├── ui/                       UiAppResource (/ui), SystemController + AgentController + ObservabilityController (/api, /api/ui)
├── workflow/                 WorkflowEngine, WorkflowScheduler, DynamicWorkflowResource (/api/ui/dynamic)
│   └── node/executor/        Agent, Async, Conditional, Fork, Join, Loop, NestedWorkflow
└── workspace/                WorkspaceStore, WorkspaceService, WorkspaceResource

quad-tool-builtin/            de.augmentia.quad.core.tool.builtin (default tools)
quad-tool-mcp/                de.augmentia.quad.core.tool (McpToolMethod, MCP registry)
quad-search-elasticsearch/    de.augmentia.quad.core.capability (ElasticsearchCapabilityIndex)
quad-search-pgvector/         de.augmentia.quad.core.capability (PgVectorCapabilityIndex)
quad-sandbox-docker/          de.augmentia.quad.core.tool.sandbox (SandboxClient dev fallback)
```

---

## Acknowledgements & Inspirations

This project was built from scratch in Java, incorporating concepts, patterns,
and architectural ideas from various open-source projects across different ecosystem
languages (such as Python, Rust, and TypeScript agent frameworks).

Special thanks to the open-source community for the inspiration:
* **LangChain4j & Quarkus** – core foundation of this stack
* **NOOA (NVIDIA Object-Oriented Agents)** 
* **Strands Agents / Interceptor pattern**
* **Openworker**
* ad Various open-source agent & LLM tools across the ecosystem

## License

[MIT](LICENSE) – Copyright (c) 2026 Torsten.