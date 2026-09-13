# quad: Quarkus + LangChain4j Specification

## Overview

This specification defines **quad**, a Java port of the NVIDIA Object-Oriented Agents (QUAD) framework built on **Quarkus** and **LangChain4j**. The system implements QUAD's core philosophy—agents as objects with state, methods as tools, and generation methods for LLM-driven reasoning—while replacing Python's CodeAct REPL with **typed Java Function Calling** and a **Bash Sandbox** fallback.

Inspired by **strands-agents**, this implementation provides a sophisticated dynamic tool discovery system using capability search, pluggable built-in tools, and caching for efficient agent execution.

> **Specification status (2026-09-13):** this is the *functional* design spec.
> Implementation progress markers were removed; keep sections in sync with the
> code. Where the spec still describes planned abstractions (build-time Gizmo
> invokers, `quad-sandbox`/`quad-tracing` modules, `CapabilitySearchRouter`
> in/out wiring), the realized structure differs — see the drift notes in
> §2 and [Open Points](#open-points) at the end.

---

## 1. Architecture

### 1.1 High-Level Flow

```
User Input
    |
    v
Capability Search (Vector DB) --> Top-K relevant Java tools
    |
    +---> LLM receives: Agent State (doc(this)) + Tool Schemas (Java + Bash)
    |
    v
ReAct Loop:
  - LLM selects tool (Java method OR executeBash)
  - Execute via Reflection (Java) or Docker (Bash)
  - Result appended to memory
  - Repeat until final answer
```

### 1.2 Core Principles (QUAD Port)

| QUAD Python          | quad (Quarkus)                    |
|----------------------|----------------------------------------|
| `class Agent`        | `abstract class Agent` (CDI Bean)      |
| `def method(...): ...` (ellipsis) | `@GenerationMethod` abstract method |
| `def method(...): code` | `@Tool` annotated Java method        |
| `doc(self)`          | `docExtractor.doc(sessionState)` via instance (§3.9) |
| `@hidden`            | `@Hidden` annotation                   |
| CodeAct REPL         | **Removed** → Java Function Calling + Bash Sandbox |
| Event Sourcing       | OpenTelemetry + Quarkus Micrometer     |

---

## 2. Module Structure

> **Drift note (2026-09-13):** this section was reconciled with the current
> Maven reactor (9 modules + `quad-ui`). A full, verified package tree lives in
> `ARCHITECTURE.md §3`; the per-module detail below highlights the mapping from
> the originally planned layout.

```
quad/
├── quad-core/                         // framework core (agent, hooks, tools, sessions, security, capability)
│   ├── agent/                         // Agent (stateless definition), QuadAgent, AgentBuilder
│   ├── agent/runtime/                 // AgentRuntime (ReAct loop), ToolExecutor, AgentEventPublisher,
│   │                                  //   DynamicToolProvider, SpanFactory, OutputForcer,
│   │                                  //   IdempotencyStore, SagaAgentInterceptor, StateDiffMemoryBridge
│   ├── agent/channels/                // Channel, QueueChannel, TimerChannel, ChannelManager
│   ├── annotation/                    // @Tool, @Param, @Prompt, @Hidden, @ToolRole, @SagaAgent, ...
│   ├── tool/                          // QuadToolRegistry, ToolMethod, GizmoInvoker, McpToolMethod,
│   │                                  //   ToolArgsMapper, ToolSpecificationBuilder, SubAgentTool
│   ├── capability/                    // Capability (name, type, api, fn*, advantages), CapabilityRegistry/
│   │                                  //   Search, context blocks (doc/prompt/skill rendering)
│   ├── capability/doc/                // AgentDocExtractor (token-budgeted state serialization)
│   ├── capability/skill/              // Skill, SkillRegistry, markdown SKILL.md parsing
│   ├── capability/prompt/             // prompt rendering engine
│   ├── hook/                          // AgentHook SPI, HookRegistry, HookResult, HookFailurePolicy
│   │   └── plugin/                    // Plugin, PluginRegistry, SkillActivationHook, agent skill injection
│   ├── events/                        // sealed AgentEvent hierarchy + AgentEventListener
│   ├── session/                       // AgentSessionState, SessionManager, CurrentSession scope
│   │   ├── memory/                    // memory managers + PersistentMemoryStore SPI
│   │   ├── saga/                      // SagaAgent, CompensatingAction, SagaAuditRecord
│   │   └── store/                     // session / event stores
│   ├── scope/                         // AgentScope, TypedStateKey, FieldExtractor (typed session state)
│   ├── observability/                 // MetricsHook, TracingHook, LoggingHook, AgentTracing,
│   │   │                              //   EventFilters, LoggingChatModel, FileLlmLogger
│   │   └── provenance/                // ProvenanceTracker (+ ProvenanceEntry)
│   ├── guardrails/                    // Guardrail, GuardrailPlugin, BlockAction, ApprovalResult
│   ├── hitl/                          // HITLPlugin, HITLProvider, HITLAuthority, checkpoints
│   ├── gdpr/                          // PiiAnonymizerHook, PiiRedactionHook, AuditTrailHook, export/delete
│   ├── resilience/                    // Retry, CircuitBreaker, TokenRecovery
│   ├── security/                      // SecurityContext, SecretsProvider, TokenExchangeClient,
│   │                                  //   ToolGuard, AuditEvent
│   ├── connectors/                    // Connector, ConnectorRegistry, OutboundMessaging, MessageTarget
│   ├── message/                       // minimal message wrappers
│   ├── config/                        // QuadConfig, ModelFactory, RetryingChatModel
│   ├── validation/                    // StructuredOutputValidator
│   └── patterns/                      // planner patterns (Desire, VotingStrategy, ConflictResolutionStrategy)
├── quad-workflow-core/               // framework-agnostic workflow model
│   ├── workflow/                     // DagModel, WorkflowDefinition, node/step model
│   ├── workflow/schedule/            // CronExpression, Schedule, ScheduledTask
│   └── workflow/transfer/            // workflow serialization / transfer
├── quad-tool-builtin/                // default built-in tools (read/write/grep/web/bash sandbox client)
├── quad-tool-mcp/                    // MCP bridge: McpToolMethod, MCP registry bindings
├── quad-search-elasticsearch/        // Optional vector index (ElasticsearchCapabilityIndex)
├── quad-search-pgvector/             // Optional vector index (PgVectorCapabilityIndex)
├── quad-sandbox-docker/              // Docker sandbox dev fallback (SandboxClient producer)
├── quad-quarkus/                     // Quarkus runtime + REST + engine + persistence
│   ├── agent/                        // AgentBuilderFactory, TieredChatModel, QuarkusAgentRuntime
│   ├── workflow/                     // WorkflowEngine, WorkflowScheduler, NodeExecutorRegistry
│   │   └── node/executor/            // Agent, Async, Conditional, Fork, Join, Loop, NestedWorkflow
│   ├── messaging/{kafka,amqp,email}/ // Kafka/AMQP/Email inbound+outbound channels
│   ├── hitl/                         // HitlService, CheckpointResource, SSEChannel
│   ├── mcp/                          // McpManagerService
│   ├── permission/ persistence/ provenance/ resilience/ security/ skillstore/ workspace/
│   └── ui/                           // UiAppResource + System/Agent/Observability controllers
└── quad-examples/                    // Runnable demo applications
```

> Notable mapping from the original plan → reality:
> `quad-sandbox/` → `quad-sandbox-docker` (dev fallback); `quad-tracing/`
> folded into `quad-core/observability`; `workspace/` moved into `quad-quarkus`;
> `session/` persists as `saga`/`store` sub-packages; `OpenTelemetry` tracing
> via `AgentTracing`/`SpanFactory` (OTelAgentTracer) lives in the core runtime.

---

## 3. Core Components

### 3.1 Agent: Definition vs. Session State (Lifecycle Safety)

**P0 Correctness Rule:** An `Agent` is a **stateless definition** (singleton, `@ApplicationScoped`) that exposes tool metadata and prompt config. Mutable runtime state lives in an **`AgentSessionState`** instance — a **plain POJO** created per session by the CDI-managed `SessionStateFactory` (never a CDI bean itself, never `@RequestScoped`). This decoupling prevents cross-request state leakage and multi-tenant data corruption.

```
+------------------------------+          +----------------------------------+
| Agent (Definition)           |          | AgentSessionState (Per-Session)  |
| @ApplicationScoped, stateless|          | plain POJO, created per call     |
| - @Tool method metadata      | creates  | - mutable fields (findings, ...) |
| - system prompt template     | -------> | - ChatMemory                     |
| - ToolRegistry refs          |  1:1     | - HITL checkpoint state          |
+------------------------------+          +----------------------------------+
```

```java
// ── Stateless agent definition (safe singleton) ─────────────────────
public abstract class Agent {

    // Injected by CDI (stateless services only). Superclass fields ARE injected
    // by Quarkus CDI when annotated (@Inject works on inherited fields).
    @Inject protected ChatLanguageModel llm;
    @Inject protected CapabilitySearchRouter router;
    @Inject protected SandboxClient sandbox;               // §3.8: pool or dev Docker via CDI alternative
    @Inject protected OTelAgentTracer tracer;
    @Inject protected QuadToolRegistry toolRegistry;
    @Inject protected AgentDocExtractor docExtractor;      // §3.9 instance method (NOT static)
    @Inject protected SessionStateFactory stateFactory;    // CDI only wires the factory,
    //                                                     //  the state itself is a plain POJO

    // Static-First (§3.7): by default the agent runs on its OWN @Tool methods
    // with ZERO vector search. Override to `true` only for agents that pull
    // tools from a large dynamic pool (MCP/server catalog) at every turn.
    protected boolean requiresDynamicDiscovery() { return false; }

    // Creates a fresh, isolated state object for each execution flow.
    // Implementations return a plain new POJO (e.g. `new EnterpriseDevSession()`).
    protected abstract AgentSessionState newSessionState();

    public final String generate(String prompt) {
        return generate(prompt, stateFactory.create(this));   // seeded + listeners attached
    }

    // Non-final so sub-agents (§3.12) can override to delegate to their OWN
    // AiServices instance (own registry/router/provider/state). The default
    // implementation below drives the shared static-first loop.
    public String generate(String prompt, AgentSessionState state) {
        // Bind the state to the current request/execution so the per-call
        // ToolProvider (§6.2) can thread it into every tool invocation (§3.3).
        CurrentSession.current().bind(state);

        // 1. Render doc from the session-bound state via the INJECTED instance
        //    (AgentDocExtractor is a CDI bean with injected services — §3.9).
        String stateDoc = docExtractor.doc(state);

        // 2. Static-First (§3.7): the agent's own @Tool methods are active from
        //    the first turn — router.findRelevantTools() returns them WITHOUT a
        //    vector search. Capability search runs ONLY on-demand (findTool).
        List<ToolMethod> tools = router.findRelevantTools(this, prompt, 5);

        // 3. ReAct loop via LangChain4j AiServices, passing `state` through
        //    a per-call ToolProvider so every tool mutates THIS session's fields.
        // Loop continues until LLM returns final text (no tool call).
    }

    // Default bash tool - can be overridden. Two equivalent forms:
    //   (a) declare `AgentSessionState state` as FIRST @Tool parameter → §3.3
    //       ToolArgsMapper injects the current call's state into the args array;
    //   (b) omit it (below) → the tool relies on a per-call holder such as
    //       SandboxClient's internal CurrentSession (§3.8). Both are supported;
    //       (a) is preferred for tools that MUTATE session fields, (b) for
    //       self-contained delegators like the sandbox.
    @Tool("Executes a Bash script in an isolated sandbox. Use ONLY when no Java tool fits.")
    public String executeBash(@Param("script") String script) { return sandbox.run(script); }
}

// ── State factory: the ONLY CDI component that creates session state ──
// CDI beans are never `new`-ed directly and `AgentSessionState` has no
// injected dependencies — CDI services needed by tools live on the Agent
// and are passed as @Tool parameters, never stored on the state.
@ApplicationScoped
public class SessionStateFactory {

    @Inject Instance<StateMutationListener> mutationListeners;   // framework diff-listeners

    public AgentSessionState create(Agent agent) {
        AgentSessionState state = agent.newSessionState();       // plain POJO subclass
        state.sessionId = UUID.randomUUID().toString();
        state.tenantId = currentTenantId();
        mutationListeners.stream().forEach(state::attach);       // incremental diffing (§3.9)
        return state;
    }

    private String currentTenantId() {
        try { return TenantContext.current().tenantId(); }
        catch (Exception e) { return "default"; }                // no active request context
    }
}

// ── Mutable, per-session state (the real QUAD object) — plain POJO ──
public class AgentSessionState {   // NO CDI annotations — pure data container
    // User/tenant context (package-private fields, public accessors)
    String sessionId;
    String tenantId;
    public String getSessionId() { return sessionId; }
    public String getTenantId() { return tenantId; }

    // QUAD state fields — mutated directly by @Tool methods
    private final List<String> findings = new ArrayList<>();
    public List<String> findings() { return List.copyOf(findings); }   // read-only view
    private String currentProject = "Project Alpha";

    // State mutation listener (P2: QUAD pass-by-reference parity)
    private final List<StateMutationListener> mutationListeners = new ArrayList<>();
    public void attach(StateMutationListener l) { mutationListeners.add(l); }

    // Pending mutation events (P2 diffing, §3.9): collected DURING a @Tool call
    // and flushed by GizmoInvoker.execute() via dispatchMutationEvents() — one
    // batched diff round per tool invocation instead of per field write.
    private final List<StateMutationEvent> pendingMutations = new ArrayList<>();

    record StateMutationEvent(String sessionId, String field, Object oldValue, Object newValue) {}

    public <T> T set(String field, T value) {
        T old = ...;
        pendingMutations.add(new StateMutationEvent(sessionId, field, old, value));
        return value;
    }

    public void addFinding(String finding) {
        findings.add(finding);
        pendingMutations.add(new StateMutationEvent(sessionId, "findings", null, finding));
    }

    // Flush buffered mutations to the attached listeners (§3.3 GizmoInvoker).
    public void dispatchMutationEvents() {
        if (pendingMutations.isEmpty()) return;
        List<StateMutationEvent> events = List.copyOf(pendingMutations);
        pendingMutations.clear();
        mutationListeners.forEach(l -> events.forEach(e ->
            l.onStateChanged(e.sessionId(), e.field(), e.oldValue(), e.newValue())));
    }

    // Saga execution log (P2, §11.11): LIFO compensation stack, in-memory per request
    private final Deque<SagaStep> sagaLog = new ArrayDeque<>();
    private boolean sagaFailed = false;
    public void registerCompensation(String toolName, CompensatingAction action) {
        sagaLog.push(new SagaStep(toolName, UUID.randomUUID().toString(), action));
    }
    public Deque<SagaStep> getSagaLog() { return sagaLog; }
    public void markSagaFailed() { this.sagaFailed = true; }
    public boolean isSagaFailed() { return sagaFailed; }

    // Tool-set diff state (P2, §3.11): bound to the SESSION so parallel
    // sessions never observe each other's tool changes.
    private Set<String> lastToolNames = Set.of();
    public Set<String> getLastToolNames() { return lastToolNames; }
    public void setLastToolNames(Set<String> names) { lastToolNames = Set.copyOf(names); }
}

// ── Agent definition wires state into tools per execution ───────────
@ApplicationScoped
public class EnterpriseDevAgent extends Agent {
    @Override
    protected AgentSessionState newSessionState() { return new EnterpriseDevSession(); }

    @Tool("Query customer database by ID")
    public CustomerData getCustomer(AgentSessionState state,
                                    @Param("customerId") String id) {
        return dbClient.findCustomer(id);
    }
}
```

> **Concurrency Guarantee:** The singleton `Agent` holds **zero mutable instance state**; all state flows through `AgentSessionState`, which is isolated per request/session. This satisfies both `@ApplicationScoped` safety and QUAD's "agent as object" model.

### 3.2 QuadToolRegistry (Strands-like Central Registry)

Central registry for all tools — agent methods, built-in tools, MCP tools, and dynamically loaded tools. Mirrors strands `ToolRegistry` with `ToolMethod` abstraction.

```java
// CDI bean (constructor injection — the SAME constructor serves plain-POJO
// sub-agent registries via the builder below, §3.12). Never holds session state.
@ApplicationScoped
public class QuadToolRegistry {

    private final Map<String, ToolMethod> tools = new LinkedHashMap<>();   // deterministic order
    private final Map<Class<?>, List<String>> agentTools = new HashMap<>();// which @Tool methods belong to which agent class
    private final List<ToolDescriptionValidator> validators = ToolDescriptionValidator.defaults();
    private final ToolArgsMapper argsMapper;    // needed for agent-method registration (§3.3);
    //                                          //  null is legal for plain tool-only registries
    private final Event<ToolSetChangedEvent> toolSetEvent;  // null for plain-POJO registries
    private boolean strictValidation = false;

    // Quarkus resolves (ToolArgsMapper producer, §3.3) + (Event<ToolSetChangedEvent>).
    public QuadToolRegistry(ToolArgsMapper argsMapper, Event<ToolSetChangedEvent> toolSetEvent) {
        this.argsMapper = argsMapper;
        this.toolSetEvent = toolSetEvent;
    }

    // Registration
    public void registerFromAgent(Agent agent) {
        for (Method m : agent.getClass().getDeclaredMethods()) {
            if (m.isAnnotationPresent(Tool.class)) {
                ToolSpecification spec = ToolSpecifications.toolSpecificationFrom(m);
                validate(spec);
                // P0: Gizmo-generated invoker (build-time), not runtime reflection.
                // 5-arg constructor (§3.3): the mapper is passed so the generated
                // invoker never resolves CDI per call.
                ToolInvoker invoker = ToolInvokerFactory.forMethod(agent.getClass(), m);
                tools.put(spec.name(), new GizmoInvoker(agent, m, spec, invoker, argsMapper));
                agentTools.computeIfAbsent(agent.getClass(), k -> new ArrayList<>())
                          .add(spec.name());
            }
        }
    }

    // Static-First (§3.7) STAGE 1: the agent's OWN @Tool methods, in declaration
    // order, WITHOUT any vector search. This is the default tool set for every
    // agent; capability search only runs on-demand (see router).
    public List<ToolMethod> getStaticToolsForAgent(Agent agent) {
        List<String> names = agentTools.get(agent.getClass());
        if (names == null) return List.of();
        return names.stream().map(tools::get).filter(Objects::nonNull).toList();
    }

    public void registerBuiltIn(BuiltInToolProvider provider) {
        for (ToolMethod tm : provider.getTools()) {
            validate(tm.spec());
            tools.put(tm.spec().name(), tm);
        }
    }

    public void registerMcpTools(List<ToolSpecification> specs, McpToolExecutor executor) {
        for (ToolSpecification spec : specs) {
            validate(spec);
            tools.put(spec.name(), new McpToolMethod(executor, spec));
        }
    }

    public void register(String name, ToolSpecification spec, ToolMethod method) {
        validate(spec);
        tools.put(name, method);
    }

    // Convenience overload — used by ToolActivatorTool (§3.11). The ToolMethod
    // already carries its spec, so no separate spec argument is needed.
    public void register(String name, ToolMethod method) {
        validate(method.spec());
        tools.put(name, method);
    }

    public void remove(String name) {
        tools.remove(name);
        agentTools.values().forEach(list -> list.remove(name));
    }

    // Announce a tool-set change WITHOUT knowing the consumers (decoupled). Fires a
    // CDI ToolSetChangedEvent; CapabilitySearchRouter observes it (§3.7) and
    // invalidates/reindexes its caches. The ToolSetChangeNotifier (§3.11) separately
    // injects the human/LLM-readable "[tools updated]" note via beforeModelCall.
    public void notifyToolSetChanged(Set<String> added, Set<String> removed) {
        if (toolSetEvent != null) {
            toolSetEvent.fire(new ToolSetChangedEvent(Set.copyOf(added), Set.copyOf(removed)));
        }
    }

    // Lookup
    public ToolMethod get(String name) {
        return tools.get(name);
    }

    public List<ToolMethod> getByNames(Collection<String> names) {
        return names.stream().map(tools::get).filter(Objects::nonNull).toList();
    }

    public List<ToolMethod> getAll() {
        return List.copyOf(tools.values());
    }

    public List<ToolSpecification> getSpecifications() {
        return tools.values().stream().map(ToolMethod::spec).toList();
    }

    // Filtering (for capability search results)
    public QuadToolRegistry withOnly(Set<String> names) {
        QuadToolRegistry filtered = new QuadToolRegistry(argsMapper, toolSetEvent);
        for (String name : names) {
            ToolMethod tm = tools.get(name);
            if (tm != null) filtered.register(name, tm.spec(), tm);
        }
        return filtered;
    }

    // Builder — sub-agent scoped registries (§3.12): plain POJO, same validators
    // and declaration order, no CDI lookup. The mapper/event are optional because
    // tool-only registries never generate invokers or fire change events.
    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private final List<ToolMethod> tools = new ArrayList<>();
        private ToolArgsMapper argsMapper;
        private Event<ToolSetChangedEvent> toolSetEvent;

        public Builder with(ToolMethod tm) { tools.add(tm); return this; }
        public Builder withSandbox(SandboxClient sandbox) {   // §3.8 client (pool or dev Docker)
            tools.add(new SandboxClientTool(sandbox));
            return this;
        }
        public Builder withToolArgsMapper(ToolArgsMapper m) { this.argsMapper = m; return this; }
        public QuadToolRegistry build() {
            QuadToolRegistry r = new QuadToolRegistry(argsMapper, toolSetEvent);
            for (ToolMethod tm : tools) r.register(tm.spec().name(), tm);
            return r;
        }
    }

    // Validation
    private void validate(ToolSpecification spec) { /* ... */ }
}

// Decoupled notification payload (§3.11 runtime activation / hot reload)
record ToolSetChangedEvent(Set<String> added, Set<String> removed) {}

// Sandbox as a registered tool — wraps the injected SandboxClient (§3.8)
public class SandboxClientTool implements ToolMethod {
    private final SandboxClient sandbox;
    private final ToolSpecification spec;
    public SandboxClientTool(SandboxClient sandbox) {
        this.sandbox = sandbox;
        this.spec = ToolSpecification.builder()
            .name("executeBash")
            .description("Executes a Bash script in an isolated sandbox. Use ONLY when no Java tool fits.")
            .addParameter("script", String.class)
            .build();
    }
    @Override public ToolSpecification spec() { return spec; }
    @Override public String execute(String jsonArguments, AgentSessionState state) {
        return sandbox.run(...);   // current session cwd via CurrentSession (§3.8)
    }
}
```

### 3.3 ToolMethod & ToolSpecificationBuilder

Executable wrapper combining `ToolSpecification` + invocation logic. Auto-generates specs from `@Tool` methods using LangChain4j's `ToolSpecifications.toolSpecificationFrom()`.

**P0 Native-Image Correctness:** Runtime `method.invoke()` breaks GraalVM native builds (reflection is stripped). Tools are instead invoked via **Gizmo-generated bytecode invokers** produced at build time by `QuadProcessor` (`@BuildStep`). A compile-time fallback to reflection is available for JVM mode only.

```java
// Executable tool abstraction (like strands ToolMethod)
public interface ToolMethod {
    ToolSpecification spec();
    // `state` is threaded PER CALL — never captured in the singleton registry.
    // The same ToolMethod instance serves every session concurrently.
    String execute(String jsonArguments, AgentSessionState state) throws Exception;
    default CapabilityToken requiredCapability() { return null; }

    // Factory for classpath-scanned @Tool methods (JVM-mode wrapper, §3.11).
    // `instance` is the bean that declares the method; in native mode runtime
    // loading is restricted to MCP/directory tools (Gizmo generation is build-time
    // only, §3.3). LazyToolLoader resolves instances via CDI lookup for plain
    // @ApplicationScoped agent classes; other tools use their declared class.
    static ToolMethod from(Method method, ToolSpecification spec,
                           Object instance, ToolArgsMapper argsMapper) {
        return new ReflectiveToolMethod(instance, method, spec, argsMapper);
    }
}

// ── Per-execution session binding (CDI @RequestScoped holder) ───────
// DynamicToolProvider (§6.2) and the invokers read the current session state
// from this holder; it is bound at the start of Agent.generate() (§3.1) and
// torn down with the request. No state is ever stored on the singleton tools.
@RequestScoped
public class CurrentSession {
    private AgentSessionState state;
    public void bind(AgentSessionState s) { this.state = s; }
    public AgentSessionState get() { return state; }
}

// ── Gizmo-generated invoker (GraalVM-native safe) ────────────────────
// Generated at BUILD TIME by QuadProcessor.buildInvokers() — no reflection.
public class GizmoInvoker implements ToolMethod {
    private final Object instance;          // stateless agent bean (singleton)
    private final Method method;            // source @Tool method (for arg names/schema)
    private final ToolSpecification spec;
    private final ToolInvoker invoker;      // generated interface impl
    private final ToolArgsMapper argsMapper;// POJO with the Quarkus ObjectMapper (§3.3)
    // NO AgentSessionState field — state is passed per execute() call.

    public GizmoInvoker(Object instance, Method method, ToolSpecification spec,
                        ToolInvoker invoker, ToolArgsMapper argsMapper) {
        this.instance = instance; this.method = method; this.spec = spec;
        this.invoker = invoker; this.argsMapper = argsMapper;
    }

    // The generated bytecode implements this interface, replacing reflection:
    //   new EnterpriseDevInvoker().invoke(instance, new Object[]{...})
    public interface ToolInvoker {
        Object invoke(Object instance, Object[] args) throws Throwable;
    }

    @Override
    public ToolSpecification spec() { return spec; }

    @Override
    public String execute(String jsonArguments, AgentSessionState state) throws Exception {
        // P2: abort early if the HTTP request was cancelled mid-loop
        if (Thread.currentThread().isInterrupted()) {
            throw new TaskCancelledException("Tool execution aborted before invoke: " + spec().name());
        }
        // State is injected into the args array at the AgentSessionState parameter
        Object[] args = argsMapper.mapArguments(jsonArguments, method, state);
        Object result = invoker.invoke(instance, args);       // direct call
        state.dispatchMutationEvents();                        // P2 state diffing
        return result != null ? String.valueOf(result) : "null";
    }
}

// ── Build-time generation (quad-quarkus QuadProcessor) ──────────────
@BuildStep
void buildToolInvokers(BeanArchiveIndex index,
                       BuildItemMultiConsumer<GeneratedToolInvokerBuildItem> out) {
    for (var clazz : index.findClassesWithAnnotation(Tool.class)) {
        for (var method : clazz.declaredMethods()) {
            if (method.hasAnnotation(Tool.class)) {
                // Gizmo generates an implementation of ToolInvoker with a
                // direct (non-reflective) call:  target.methodName(arg0, arg1)
                var invoker = Gizmo.createInvoker(clazz, method);  // quarkus.gizmo
                out.produce(new GeneratedToolInvokerBuildItem(clazz, method, invoker));
            }
        }
    }
    // Result: no reflect-config.json entries needed for @Tool methods.
}

// ── JVM-mode fallback (dev only, guarded) ────────────────────────────
public class ReflectiveToolMethod implements ToolMethod {
    private final Object instance;
    private final Method method;
    private final ToolSpecification spec;
    private final ToolArgsMapper argsMapper;    // same POJO as GizmoInvoker (§3.3)

    public ReflectiveToolMethod(Object instance, Method method,
                                ToolSpecification spec, ToolArgsMapper argsMapper) {
        this.instance = instance;
        this.method = method;
        this.spec = spec;
        this.argsMapper = argsMapper;
    }

    @Override
    public ToolSpecification spec() { return spec; }

    @Override
    public String execute(String jsonArguments, AgentSessionState state) throws Exception {
        Map<String, Object> argsMap = argsMapper.readTreeAsMap(jsonArguments);
        Object[] args = argsMapper.coerce(argsMap, method.getParameterTypes(), state);
        return String.valueOf(method.invoke(instance, args));
    }
}

// MCP tool execution
public class McpToolMethod implements ToolMethod {
    private final McpToolExecutor executor;
    private final ToolSpecification spec;

    @Override public ToolSpecification spec() { return spec; }
    @Override public String execute(String jsonArguments, AgentSessionState state) throws Exception {
        return executor.execute(jsonArguments);     // state is MCP-server-scoped
    }
}

// Builds ToolSpecification from Method (uses LangChain4j internals)
public class ToolSpecificationBuilder {
    public static ToolSpecification fromMethod(Method method) {
        return ToolSpecifications.toolSpecificationFrom(method);
    }
    
    public static ToolSpecification fromAgentTool(AgentTool<?> tool) {
        return ToolSpecification.builder()
            .name(tool.name())
            .description(tool.description())
            .parameters(convertSchema(tool.parameterSchema()))
            .build();
    }
}

// ── Native-safe argument coercion (Quarkus-managed ObjectMapper) ──────
// P2 Edge case: LLM-produced JSON for complex @Tool POJOs must deserialize
// under GraalVM native. Using the Quarkus-managed ObjectMapper guarantees
// pre-registered modules + native serialization hints (no manual config).
//
// Plain POJO with CONSTRUCTOR injection — the mapper is wired by CDI (producer
// below) or passed directly into GizmoInvoker/ReflectiveToolMethod. There is
// deliberately NO `ToolArgsMapper.mapArguments(...)` static call: the mapper
// cannot be resolved by a build-time-generated invoker without a CDI reference,
// so the invoker receives a ToolArgsMapper instance instead (§3.3).
public class ToolArgsMapper {

    private final ObjectMapper objectMapper;

    public ToolArgsMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    // Primary path (GizmoInvoker) — JSON string → Object[] honoring @Param names.
    // `state` is threaded per-call (§3.1) and injected into the args array at the
    // AgentSessionState parameter — the registry never holds per-session state.
    public Object[] mapArguments(String jsonArguments, Method method, AgentSessionState state) throws Exception {
        JsonNode root = objectMapper.readTree(jsonArguments);
        Parameter[] parameters = method.getParameters();
        Object[] args = new Object[parameters.length];
        for (int i = 0; i < parameters.length; i++) {
            // Session/tenant params are injected per-execution, never from JSON
            if (AgentSessionState.class.isAssignableFrom(parameters[i].getType())) {
                args[i] = state;
                continue;
            }
            if (TenantContext.class.isAssignableFrom(parameters[i].getType())) {
                args[i] = TenantContext.current();
                continue;
            }
            JsonNode node = root.get(paramName(parameters[i]));
            args[i] = node == null || node.isNull() ? null : coerce(node, parameters[i].getType());
        }
        return args;
    }

    // Legacy JVM-mode path helper — raw Map binding for ReflectiveToolMethod.
    public Map<String, Object> readTreeAsMap(String jsonArguments) throws Exception {
        return objectMapper.readValue(jsonArguments, new TypeReference<Map<String, Object>>() {});
    }

    // ExecutableTool bridge — LangChain4j hands us a Map, tools want a JSON string.
    public String toJson(Map<String, Object> argsMap) {
        try { return objectMapper.writeValueAsString(argsMap); }
        catch (JsonProcessingException e) { return "{}"; }
    }

    // Lenient coercion — handles common LLM encoding quirks WITHOUT reflection:
    //   • single JSON string when a POJO/array is expected  → re-parse the string
    //   • stringified JSON for nested objects               → tree → POJO mapping
    //   • scalar passed where record expected               → convertValue
    private Object coerce(JsonNode node, Class<?> target) {
        if (target == String.class) return node.isValueNode() ? node.asText() : node.toString();
        if (target == int.class || target == Integer.class) return node.asInt();
        if (target == long.class || target == Long.class)   return node.asLong();
        if (target == double.class || target == Double.class) return node.asDouble();
        if (target == boolean.class || target == Boolean.class) return node.asBoolean();
        // Complex domain POJOs / records / lists — Quarkus ObjectMapper binding.
        // Scalars are already handled above, so any remaining Textual node is a
        // stringified object/array that must be re-parsed before binding.
        if (node.isTextual()) {
            try { return objectMapper.readValue(node.asText(), target); }
            catch (Exception ignored) { /* fall through to strict binding */ }
        }
        return objectMapper.treeToValue(node, target);   // native-safe, module-aware
    }

    private static String paramName(Parameter p) {
        var param = p.getAnnotation(Param.class);
        return param != null && !param.value().isBlank()
            ? param.value() : p.getName();   // requires -parameters compiler flag (§4.3)
    }

    // Legacy JVM-mode path (ReflectiveToolMethod) — Map → typed args.
    // `state` fills the AgentSessionState parameter position (see §3.3 mapArguments).
    public Object[] coerce(Map<String, Object> argsMap, Class<?>[] types, AgentSessionState state) {
        Object[] args = new Object[types.length];
        for (int i = 0; i < types.length; i++) {
            if (AgentSessionState.class.isAssignableFrom(types[i])) { args[i] = state; continue; }
            if (TenantContext.class.isAssignableFrom(types[i]))     { args[i] = TenantContext.current(); continue; }
            // same coercion rules as mapArguments
        }
        return args;
    }
}

// CDI producer — the @ApplicationScoped singleton with the Quarkus-managed
// ObjectMapper. GizmoInvoker/ReflectiveToolMethod get the SAME instance via
// constructor arg; nothing is resolved by static CDI lookup per call.
@ApplicationScoped
public class ToolArgsMapperProducer {
    @Inject ObjectMapper objectMapper;
    @Produces @ApplicationScoped
    ToolArgsMapper toolArgsMapper() { return new ToolArgsMapper(objectMapper); }
}
```

### 3.4 BuiltInToolProvider (Configurable Interface)

Strands-like `Builder.standard()` pattern — pluggable built-in tool sets. Default implementation provides filesystem, web, docker, bash tools.

```java
// Interface for built-in tool providers (extensible)
public interface BuiltInToolProvider {
    List<ToolMethod> getTools();
    Set<String> getToolNames();
    default boolean includes(String name) { return getToolNames().contains(name); }
}

// Default standard tools (like strands Builder.standard())
// Every fs/bash tool operates on the SESSION sub-directory {quad.workspace}/{sessionId}
// so concurrent sessions sharing the RWX PVC can never overwrite each other (§10.2).
@ApplicationScoped
public class StandardToolProvider implements BuiltInToolProvider {

    private final SandboxPoolClient sandbox;
    private final WorkspaceResolver resolver;

    public StandardToolProvider(SandboxPoolClient sandbox, WorkspaceResolver resolver) {
        this.sandbox = sandbox; this.resolver = resolver;
    }

    @Override
    public List<ToolMethod> getTools() {
        return List.of(
            new ReadTool(resolver),    // resolves sessionDir() per call
            new WriteTool(resolver),
            new ListTool(resolver),
            new GlobTool(resolver),
            new GrepTool(resolver),
            new WebFetchTool(),
            new WebSearchTool(),
            new BashSandboxTool(sandbox)  // Always included as fallback (warm pool)
        );
    }

    @Override public Set<String> getToolNames() { /* ... */ }
}

// Resolves the session-scoped workspace sub-directory on the shared RWX PVC.
@ApplicationScoped
public class WorkspaceResolver {
    @Inject CurrentSession currentSession;              // §3.3 per-execution holder
    @ConfigProperty(name = "quad.workspace", defaultValue = "/work/quad")
    Path baseWorkspace;

    public Path sessionDir() { return sessionDir(currentSession.get().sessionId()); }
    public Path sessionDir(String sessionId) {
        return baseWorkspace.resolve(sessionId);   // /work/quad/{sessionId}
    }
}

// Configuration-driven tool selection
@ApplicationScoped
public class ConfigurableToolProvider implements BuiltInToolProvider {
    
    private final List<BuiltInToolProvider> providers;
    
    @ConfigProperty(name = "quad.tools.built-in.enabled", defaultValue = "true")
    boolean enabled;
    
    @ConfigProperty(name = "quad.tools.built-in.includes")  // comma-separated
    Optional<String> includes;
    
    @ConfigProperty(name = "quad.tools.built-in.excludes")
    Optional<String> excludes;

    @Override
    public List<ToolMethod> getTools() {
        if (!enabled) return List.of();
        return providers.stream()
            .flatMap(p -> p.getTools().stream())
            .filter(t -> includes.map(i -> i.contains(t.spec().name())).orElse(true))
            .filter(t -> excludes.map(e -> !e.contains(t.spec().name())).orElse(true))
            .toList();
    }
}
```

### 3.5 CapabilityIndex (Interface + Implementations)

Pluggable vector store interface for tool embeddings — built-in HNSWLib for dev, Qdrant/PGVector for prod.

```java
// Interface for capability vector index
// Every operation is tenant-scoped: search takes the tenantId explicitly so a
// multi-tenant request can never observe another tenant's capabilities.
public interface CapabilityIndex {
    void add(Capability capability);
    void addAll(Collection<Capability> capabilities);
    void remove(String toolName);
    void clear();
    List<CapabilityMatch> search(String tenantId, String query, int topK);
    void persist(); // for disk-backed implementations
}

// Dev: In-memory HNSWLib (via LangChain4j quarkus-langchain4j-hnsw)
// Quarkus-style conditional beans — NOT Spring's @ConditionalOnProperty.
// @IfBuildProperty selects the implementation at BUILD TIME (per-env image);
// the metadata "tenantId" payload filter keeps the dev index tenant-safe.
@ApplicationScoped
@IfBuildProperty(name = "quad.capability.index.type", stringValue = "hnsw", enableIfMissing = true)
public class HnswCapabilityIndex implements CapabilityIndex {
    private final EmbeddingStore<TextSegment> store;
    private final EmbeddingModel embeddingModel;

    @Override
    public void add(Capability cap) {
        store.add(TextSegment.from(cap.description(),
            Map.of("toolName", cap.name(), "source", cap.source(),
                   "type", cap.type().name(), "tenantId", cap.tenantId())));
    }
    
    @Override
    public List<CapabilityMatch> search(String tenantId, String query, int topK) {
        var embedding = embeddingModel.embed(query).content();
        return store.findRelevant(embedding, topK).matches().stream()
            .filter(m -> tenantId.equals(m.embedded().metadata().getString("tenantId")))  // hard filter
            .map(m -> new CapabilityMatch(
                m.embedded().metadata().getString("toolName"),
                m.embedded().metadata().getString("source"),
                CapabilityType.valueOf(m.embedded().metadata().getString("type")),
                m.score()
            ))
            .toList();
    }
    // ...
}

// Prod: Qdrant — same signature; payload-filtered per tenant (§11.13)
@ApplicationScoped
@IfBuildProperty(name = "quad.capability.index.type", stringValue = "qdrant")
public class QdrantCapabilityIndex implements CapabilityIndex { /* ... */ }

// Prod: PGVector
@ApplicationScoped
@IfBuildProperty(name = "quad.capability.index.type", stringValue = "pgvector")
public class PgVectorCapabilityIndex implements CapabilityIndex { /* ... */ }
```

> **Conditional-bean note:** `@IfBuildProperty` is build-time (image is fixed per environment). For a runtime-switchable deployment use `@LookupIfProperty` on a producer instead — never Spring's `@ConditionalOnProperty`, which Quarkus does not process.

### 3.6 CapabilitySearch (Cached Similarity Search)

Coordinates embedding search + registry lookup with per-session caching.

```java
@ApplicationScoped
public class CapabilitySearch {

    private final CapabilityIndex index;
    private final QuadToolRegistry registry;
    // Two SEPARATE caches — the prefilter stores embedding matches, the search
    // stores resolved ToolMethod lists. One cache can never serve both types.
    private final Cache<String, List<CapabilityMatch>> prefilterCache;
    private final Cache<String, List<ToolMethod>> searchCache;
    private final int defaultTopK;

    public CapabilitySearch(CapabilityIndex index, QuadToolRegistry registry, 
                            @ConfigProperty("quad.capability.search.top-k") int defaultTopK) {
        this.index = index;
        this.registry = registry;
        this.defaultTopK = defaultTopK;
        this.prefilterCache = Caffeine.newBuilder()
            .maximumSize(1000)
            .expireAfterWrite(Duration.ofMinutes(5))
            .build();
        this.searchCache = Caffeine.newBuilder()
            .maximumSize(1000)
            .expireAfterWrite(Duration.ofMinutes(5))
            .build();
    }

    // Stage 1 (pre-filter): embedding similarity → candidate capabilities.
    // Returns MORE candidates than topK; the LLM ToolSelectionAgent (§3.10) prunes
    // them down to the final topK. Pure-embedding shortcut when LLM selector disabled.
    // CACHE TYPE: List<CapabilityMatch> (raw embedding matches, not resolved tools).
    public List<CapabilityMatch> prefilter(String query, int candidateCount) {
        String cacheKey = "prefilter:" + currentTenantId() + ":" + query + ":" + candidateCount;
        return prefilterCache.get(cacheKey, k -> index.search(currentTenantId(), query, candidateCount));
    }

    // Main entry point (embedding-only fast path / fallback): query → top-K ToolMethod instances
    public List<ToolMethod> findRelevant(String query, int topK) {
        String cacheKey = "find:" + currentTenantId() + ":" + query + ":" + topK;
        return searchCache.get(cacheKey, k -> doSearch(query, topK));
    }

    private List<ToolMethod> doSearch(String query, int topK) {
        List<CapabilityMatch> matches = index.search(currentTenantId(), query, topK);
        Set<String> toolNames = matches.stream().map(CapabilityMatch::toolName).collect(toSet());
        
        // Always include bash sandbox as fallback
        toolNames.add("executeBash");
        
        return registry.getByNames(toolNames);
    }

    // Tenant key — a multi-tenant cache must never serve tenant B with tenant A's
    // candidate lists. Falls back to "default" outside a request context (reindex).
    private String currentTenantId() {
        try { return TenantContext.current().tenantId(); }
        catch (Exception e) { return "default"; }
    }

    // Refresh index from registry (call on agent init / hot reload)
    public void reindex() {
        index.clear();
        for (ToolMethod tm : registry.getAll()) {
            index.add(new Capability(
                tm.spec().name(),
                tm.spec().description(),
                tm.getClass().getSimpleName(), // source
                inferType(tm),
                currentTenantId()
            ));
        }
        index.persist();
        prefilterCache.invalidateAll();
        searchCache.invalidateAll();
    }

    // P2: Called by registry mutation listeners (§3.7 ToolSetChangedEvent) — drop
    // stale entries from BOTH caches only.
    public void invalidate() {
        prefilterCache.invalidateAll();
        searchCache.invalidateAll();
    }
}

record CapabilityMatch(String toolName, String source, CapabilityType type, double score) {}
record Capability(String name, String description, String source, CapabilityType type, String tenantId) {
    enum CapabilityType { AGENT_METHOD, BUILT_IN, MCP, BASH_SANDBOX }
}
```

### 3.7 CapabilitySearchRouter (Orchestrator)

Replaces old `CapabilitySearchRouter` — orchestrates the **Static-First with On-Demand Fallback** pipeline (§3.1 `requiresDynamicDiscovery`): every agent's own `@Tool` methods are returned directly **without any vector search**; the two-stage capability selection (embedding pre-filter → LLM `ToolSelectionAgent`) runs only for dynamic agents (MCP catalogs), pure delegators without their own tools, and explicit `findTool()` calls.

```java
@ApplicationScoped
public class CapabilitySearchRouter {

    private final CapabilitySearch capabilitySearch;
    private final QuadToolRegistry registry;
    private final BuiltInToolProvider builtInProvider;   // null for sub-agent routers
    private final ToolSelectionAgent toolSelectionAgent; // nullable if disabled
    private final boolean llmSelectorEnabled;
    private final int prefilterTopK;

    // Constructor injection (CDI). Every collaborator is a CDI bean or a config
    // value, so Quarkus wires the singleton. The SAME constructor is usable as a
    // plain-POJO for sub-agents (§3.12): SubAgentFactory passes explicit values,
    // nothing is left un-injected. There are NO field-level @Inject/@ConfigProperty
    // members to leak on manual `new`.
    @Inject
    public CapabilitySearchRouter(CapabilitySearch capabilitySearch,
                                  QuadToolRegistry registry,
                                  BuiltInToolProvider builtInProvider,
                                  ToolSelectionAgent toolSelectionAgent,
                                  @ConfigProperty(name = "quad.capability.llm-selector.enabled", defaultValue = "true")
                                  boolean llmSelectorEnabled,
                                  @ConfigProperty(name = "quad.capability.llm-selector.prefilter-top-k", defaultValue = "20")
                                  int prefilterTopK) {
        this.capabilitySearch = capabilitySearch;
        this.registry = registry;
        this.builtInProvider = builtInProvider;
        this.toolSelectionAgent = toolSelectionAgent;
        this.llmSelectorEnabled = llmSelectorEnabled;
        this.prefilterTopK = prefilterTopK;
    }

    // CDI rules: @PostConstruct must be parameterless. Startup wiring happens on
    // StartupEvent, where every bean (incl. the @ApplicationScoped Agent) exists.
    // Sub-agent routers built via `new` (§3.12) never observe this event.
    void init(@Observes StartupEvent ev, Agent agent) {
        // 1. Register agent's @Tool methods
        registry.registerFromAgent(agent);
        
        // 2. Register built-in tools
        registry.registerBuiltIn(builtInProvider);
        
        // 3. Build capability index
        capabilitySearch.reindex();
    }

    // Called per-turn by DynamicToolProvider (§6.2) and Agent.generate() (§3.1).
    //
    // STATIC-FIRST (§3.7): Stage 0 short-circuits the whole pipeline. An agent
    // class declares its tools statically — those are returned directly: ZERO
    // embedding lookups, deterministic declaration order, fully OOP-encapsulated.
    // Capability search (Stages 1+2) runs ONLY when:
    //   • the agent opts in via requiresDynamicDiscovery() (e.g. MCP catalogs), or
    //   • the agent has no @Tool methods of its own (pure delegator / sub-agent).
    public List<ToolMethod> findRelevantTools(Agent agent, String userQuery, int topK) {
        // Stage 0 — Static-First: the agent's own tools are always active.
        List<ToolMethod> staticTools = registry.getStaticToolsForAgent(agent);
        if (!agent.requiresDynamicDiscovery() && !staticTools.isEmpty()) {
            return staticTools;    // fast path — no embeddings, no LLM selector
        }

        // Stage 1 — cheap embedding pre-filter (more candidates than final topK)
        List<CapabilityMatch> candidates = capabilitySearch.prefilter(userQuery, prefilterTopK);
        Set<String> names = candidates.stream()
            .map(CapabilityMatch::toolName)
            .collect(toSet());

        // Stage 2 — LLM agent prunes/reorders candidates into the final tool set
        if (llmSelectorEnabled && toolSelectionAgent != null) {
            ToolSelection sel = toolSelectionAgent.select(agent, userQuery, candidates);
            names = new LinkedHashSet<>(sel.recommendedTools());  // ordered, LLM-ranked
        }

        // Static tools remain part of the result even on the dynamic path.
        staticTools.forEach(tm -> names.add(tm.spec().name()));

        // Fallback guarantee: sandbox must always be available
        names.add("executeBash");
        return registry.getByNames(names);
    }

    // On-demand capability search (the `findTool` system tool, §3.11): the agent
    // explicitly asks "is there a tool that does X?" — the ONE runtime path that
    // pays the embedding cost. Highest-ranked match is returned (already resolved
    // through the registry) so the caller can activate it for the current session.
    public ToolMethod findTool(String query, int topK) {
        List<ToolMethod> matches = capabilitySearch.findRelevant(query, topK);
        return matches.isEmpty() ? null : matches.get(0);
    }

    // Direct call from ToolSetChangeNotifier (§3.11) when a mid-loop diff is
    // detected between the model's current tool set and the session baseline.
    public void onToolSetChanged(Set<String> added, Set<String> removed) {
        capabilitySearch.invalidate();
        capabilitySearch.reindex();   // or incremental add/remove on the index
    }

    // Decoupled path: QuadToolRegistry.notifyToolSetChanged (§3.2) fires the CDI
    // event, e.g. from ToolActivatorTool (§3.11). Both mutation paths land here.
    void onToolSetChangedEvent(@Observes ToolSetChangedEvent evt) {
        onToolSetChanged(evt.added(), evt.removed());
    }
}
```

### 3.8 Bash Sandbox Tool (Warm Pool Client + Local Dev Fallback)

Production uses the warm microVM worker pool (`SandboxPoolClient`, §10.2). A local Docker-based implementation is retained for development only (never in K8s). Both implement the shared `SandboxClient` interface; Quarkus picks the active implementation via CDI alternatives (§4.1 `%dev.quarkus.arc.selected-alternatives`).

```java
// ── Common sandbox contract — Agent injects this, never a concrete class ──
public interface SandboxClient {
    String run(String bashScript);
    String run(String bashScript, String sessionId);   // session-scoped cwd
}

// ── Production: warm pool client (≈50ms per call, no container spawn) ──
@ApplicationScoped
public class SandboxPoolClient implements SandboxClient {

    private final SandboxWorkerClient workerClient;  // gRPC/HTTP → sandbox StatefulSet

    @ConfigProperty(name = "quad.sandbox.pool.url")
    String poolUrl;

    @Inject WorkspaceResolver workspaceResolver;    // §3.4 session sub-dir
    @Inject CurrentSession currentSession;          // §3.3 per-execution holder

    @Override
    public String run(String bashScript) { return run(bashScript, currentSession.get().sessionId()); }

    // Session-scoped: the worker executes with cwd=/work/quad/{sessionId}, so
    // parallel sessions on the shared RWX PVC never clobber each other (§10.2).
    @Override
    public String run(String bashScript, String sessionId) {
        Path cwd = workspaceResolver.sessionDir(sessionId);
        return workerClient.execute(poolUrl, bashScript, cwd);   // worker cd's into cwd
    }
}

// ── Dev-only fallback: ephemeral Docker container (never in production) ──
// @Alternative is NOT auto-activated by Quarkus — it must be selected explicitly:
//   %dev.quarkus.arc.selected-alternatives=DockerSandboxService   (§4.1)
// Without that property the warm-pool SandboxPoolClient remains active.
@Alternative
@ApplicationScoped
public class DockerSandboxService implements SandboxClient {

    private final DockerClient dockerClient;

    @ConfigProperty(name = "quad.sandbox.image", defaultValue = "ubuntu:latest")
    String image;

    @ConfigProperty(name = "quad.sandbox.memory-mb", defaultValue = "512")
    long memoryMb;

    @ConfigProperty(name = "quad.sandbox.timeout-seconds", defaultValue = "30")
    int timeoutSeconds;

    @Inject WorkspaceResolver workspaceResolver;
    @Inject CurrentSession currentSession;

    @Override
    public String run(String bashScript) { return run(bashScript, currentSession.get().sessionId()); }

    @Override
    public String run(String bashScript, String sessionId) {
        Path cwd = workspaceResolver.sessionDir(sessionId);       // per-session bind mount
        CreateContainerResponse container = dockerClient.createContainerCmd(image)
            .withCmd("bash", "-c", "cd " + cwd + " && " + bashScript)
            .withHostConfig(HostConfig.newHostConfig()
                .withMemoryLimit(memoryMb * 1024 * 1024L)
                .withCpuQuota(50000L)
                .withNetworkMode("none")
                .withReadonlyRootfs(true)
                .withUser("nobody")
                .withBinds(new Bind(cwd.toString(), new Volume(cwd.toString()), AccessMode.rw))
            )
            .exec();

        try {
            dockerClient.startContainerCmd(container.getId()).exec();
            dockerClient.waitContainerCmd(container.getId())
                .start()
                .awaitStatusCode(timeoutSeconds, TimeUnit.SECONDS);
            return getContainerLogs(container.getId());
        } finally {
            dockerClient.removeContainerCmd(container.getId()).withForce(true).exec();
        }
    }
}
```

### 3.9 AgentDocExtractor (Progressive Disclosure + Token Budgeting)

Renders the session state into a compact, **token-budgeted** prompt. Large collections are truncated per-field with a deterministic placeholder, and the output honors a hard token budget computed from the model context window.

```java
// ── Build-time state accessor (P0 GraalVM-safe) ──────────────────────
// NooshaProcessor generates ONE DocAccessor per AgentSessionState subclass at
// build time (mirroring ToolInvoker). getters are direct field reads — the
// @Hidden annotation is baked in, so hidden fields are never even touched.
// This replaces runtime reflection, keeping native images free of
// reflect-config.json entries for state classes.
public interface DocAccessor {
    List<StateField> fields(AgentSessionState state);
}
public record StateField(String name, Object value) {}

// ── Generated by QuadProcessor.buildDocAccessors() ──────────────────
//   new EnterpriseDevSessionAccessor() → fields() reads currentProject,
//   findings via direct bytecode; skips the @Hidden dbPassword field.

public class AgentDocExtractor {

    @Inject Tokenizer tokenizer;  // LangChain4j Tokenizer (model-specific)

    @Inject Instance<DocAccessor> generatedAccessors;   // generated per state subclass

    private final Map<Class<?>, DocAccessor> accessors = new HashMap<>();

    void init(@Observes StartupEvent ev) {
        // Register each generated accessor under the state class it was built for.
        // Class.forName throws a CHECKED exception — startup must fail loudly,
        // wrapped in IllegalStateException, so a missing accessor can never leave
        // the app half-initialized with a null DocAccessor for some state class.
        for (DocAccessor a : generatedAccessors) {
            try {
                Class<?> stateClass = Class.forName(accessorStateClassName(a.getClass().getName()));
                accessors.put(stateClass, a);
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(
                    "No state class found for generated DocAccessor " + a.getClass().getName(), e);
            }
        }
    }

    // Naming contract enforced by QuadProcessor at build time: the accessor for
    // `com.example.EnterpriseDevSession` is generated as
    // `com.example.EnterpriseDevSession$DocAccessor` → strip the suffix.
    private static String accessorStateClassName(String accessorName) {
        return accessorName.substring(0, accessorName.length() - "$DocAccessor".length());
    }

    @ConfigProperty(name = "quad.doc.state-budget-tokens", defaultValue = "4000")
    int stateBudgetTokens;

    @ConfigProperty(name = "quad.doc.max-field-tokens", defaultValue = "800")
    int maxFieldTokens;

    public String doc(AgentSessionState state) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Agent State\n\n");

        // P0: direct Gizmo getters (no reflection). @Hidden filtering is baked
        // into the generated accessor — see §5, `@Hidden` is the single mechanism.
        DocAccessor accessor = accessors.get(state.getClass());
        if (accessor == null) {
            throw new IllegalStateException(
                "No build-time DocAccessor generated for " + state.getClass());
        }
        for (StateField f : accessor.fields(state)) {

            // P1: Token budgeting — truncate oversized collections/strings
            int fieldTokens = tokenizer.estimateTokenCountInText(String.valueOf(f.value()));
            if (fieldTokens > maxFieldTokens) {
                f = new StateField(f.name(), compact(f.value(), maxFieldTokens));
            }
            sb.append("- **").append(f.name()).append("**: ")
              .append(safeToString(f.value())).append("\n");

            if (tokenizer.estimateTokenCountInText(sb.toString()) > stateBudgetTokens) {
                sb.append("- **…truncated** (state exceeds token budget)\n");
                break;
            }
        }

        sb.append("\n# Available Tools\n\n");
        // ... tool list (as before) ...
        return sb.toString();
    }

    private Object compact(Object value, int maxTokens) {
        if (value instanceof Collection<?> c) {
            // keep first N items, emit "… N more"
            return c.stream().limit(maxTokens / 8).toList() + " … " + (c.size() - maxTokens / 8) + " more";
        }
        String s = String.valueOf(value);
        return s.substring(0, Math.min(s.length(), maxTokens * 4)) + "…";
    }
}
```

**P2 — Incremental state diffing (QUAD pass-by-reference parity):** Instead of re-serializing the entire state each turn, `AgentSessionState` tracks field writes and emits diffs appended to memory:

```java
// In AgentSessionState — mutation listeners push diffs
interface StateMutationListener {
    void onStateChanged(String sessionId, String field, Object oldValue, Object newValue);
}

@ApplicationScoped
public class StateDiffMemoryBridge implements StateMutationListener {

    // LangChain4j ChatMemoryStore — THE interface for session-keyed persistence:
    //   getMessages(chatMemoryId), updateMessages(chatMemoryId, List<ChatMessage>),
    //   deleteMessages(chatMemoryId). `chatMemoryId` == our sessionId.
    // A custom store (Redis/JDBC) is wired into MessageWindowChatMemory via
    // MessageWindowChatMemory.builder().chatMemoryStore(store).build().
    private final ChatMemoryStore chatMemoryStore;

    // ChatMemoryStore has NO atomic append — a plain getMessages→updateMessages
    // round-trip races between two PARALLEL state mutations of the same session and
    // silently drops one diff (lost update). Serialize per sessionId via stripe
    // locks; mutations of DIFFERENT sessions stay fully parallel.
    private final ConcurrentHashMap<String, Object> sessionLocks = new ConcurrentHashMap<>();

    // Constructor injection — CDI wires it; unit tests construct it directly.
    @Inject
    public StateDiffMemoryBridge(ChatMemoryStore chatMemoryStore) {
        this.chatMemoryStore = chatMemoryStore;
    }

    @Override
    public void onStateChanged(String sessionId, String field, Object oldValue, Object newValue) {
        // Append a compact UserMessage to this session's memory, e.g.:
        //   "[state] findings += <newValue>"
        // so the LLM observes live transitions WITHOUT re-rendering doc(state).
        Object lock = sessionLocks.computeIfAbsent(sessionId, k -> new Object());
        synchronized (lock) {
            var history = chatMemoryStore.getMessages(sessionId);
            history.add(UserMessage.from("[state] " + field + " changed: " + maskSecrets(newValue)));
            chatMemoryStore.updateMessages(sessionId, history);
        }
        // Long-lived sessions accumulate one lock per sessionId; drop the stripe on
        // session close (JdbcSessionStore.deleteSession → sessionLocks.remove(sessionId, lock)).
    }
}
```

### 3.10 ToolSelectionAgent (LLM-Based Tool Selection)

Port of the strands `CapabilitySearchTool`/`CapabilitySearchAgent` pattern: an **LLM sub-agent** decides which tools the main loop sees. Vector search only pre-filters candidates; the LLM ranks, prunes, corrects typos, and enriches tool sets — matching the semantic judgment of the main model instead of pure cosine similarity.

**Pipeline:** `DynamicToolProvider` → `CapabilitySearchRouter` (§3.7) → *Stage 1* `CapabilitySearch.prefilter()` (embedding candidates) → *Stage 2* `ToolSelectionAgent.select()` → final ordered tool names → registry lookup.

```java
// ── Structured output (LangChain4j JSON-schema response format) ──────
record ToolSelection(
    String analysis,                  // why these tools were chosen
    List<String> recommendedTools,    // ORDERED: LLM-ranked final tool names
    List<String> recommendedSkills,   // matching skills (for SkillActivationHook)
    String reasoning,
    List<ToolEnrichment> toolEnrichments   // per-skill corrections/additions
) {
    record ToolEnrichment(String skillName, List<String> enrichedTools) {}

    // Fallback when the selector LLM times out/errors: pure embedding result
    static ToolSelection fromCandidates(List<CapabilityMatch> candidates) {
        return new ToolSelection(
            "Fallback: LLM selector unavailable — embedding result used.",
            candidates.stream().map(CapabilityMatch::toolName).toList(),
            List.of(), "fallback", List.of());
    }
}

// ── Sub-agent: analyzes task + prefiltered candidates → ToolSelection ──
@ApplicationScoped
public class ToolSelectionAgent {

    interface ToolSelector {
        @dev.langchain4j.service.SystemMessage(TOOL_SELECTOR_PROMPT)
        ToolSelection select(String analysisRequestJson);
    }

    static final String TOOL_SELECTOR_PROMPT = """
        You are a tool-selection agent. Given a task and a list of candidate
        capabilities (name + description), recommend the BEST-MATCHING tools.

        RULES:
        1. Recommend the FEWEST tools that fully cover the task — never dump all candidates.
        2. Order recommendedTools by relevance (most relevant first).
        3. When a skill matches, recommend it AND its declared tools.
        4. TOOL ENRICHMENT: if a declared tool is misspelled (e.g. "wite" → "write")
           or implies a related tool (e.g. "find" → "read"), add the correction to
           toolEnrichments and explain in analysis.
        5. If nothing matches, return an EMPTY recommendedTools list.
        """;

    private final ToolSelector assistant;                     // AiServices, structured output
    private final Cache<String, ToolSelection> selectionCache;
    private final int timeoutMs;

    public ToolSelectionAgent(@ModelName("selector") ChatLanguageModel selectorModel,
                              @ConfigProperty(name = "quad.capability.llm-selector.timeout-ms",
                                              defaultValue = "2000") int timeoutMs) {
        this.assistant = AiServices.builder(ToolSelector.class)
            .chatLanguageModel(selectorModel)
            .build();
        this.selectionCache = Caffeine.newBuilder()
            .maximumSize(500)
            .expireAfterWrite(Duration.ofMinutes(5))
            .build();
        this.timeoutMs = timeoutMs;
    }

    // Stage-2 entry point — cached, timeout-guarded, never blocks the loop
    public ToolSelection select(Agent agent, String task, List<CapabilityMatch> candidates) {
        String cacheKey = task + ":" + candidateKey(candidates);
        return selectionCache.get(cacheKey, k -> doSelect(agent, task, candidates));
    }

    private ToolSelection doSelect(Agent agent, String task, List<CapabilityMatch> candidates) {
        String request = buildRequestJson(task, candidates);
        try {
            // Latency guard: selector runs on a separate model/thread pool
            return CompletableFuture.supplyAsync(() -> assistant.select(request))
                .get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException | ExecutionException e) {
            // Fallback: pure embedding result (top-K) — degrade, never fail the loop
            return ToolSelection.fromCandidates(candidates);
        }
    }

    private String buildRequestJson(String task, List<CapabilityMatch> candidates) {
        // {"task": "...", "prefilteredTools": [{"name","description"}, ...]}
        //   — mirrors strands CapabilitySearchTool's input shape
    }
}
```

**Fallback & safety contract:** The selector is a **sub-agent with its own model** (`@ModelName("selector")` — NOT `@Named`, which Quarkus LangChain4j does not resolve; config keys must be **quoted** because the model name contains no dots but the qualifier requires `"selector"` as a literal segment, §4.1) from `quarkus.langchain4j.openai."selector".*`, e.g. `gpt-4o-mini`, separate token budget, and a hard timeout. On timeout/error it degrades to the Stage-1 embedding result — the loop always proceeds. Results are cached by task+candidates and invalidated on registry mutation (§3.7).

### 3.11 Hot Reload of Tools into the Current Context

Port of the strands `ToolActivator` + `AgentLoop.handleBeforeModelCall` + `SkillActivationHook` pattern. Three layers keep a live session's tool set current **without restart and without re-rendering the whole prompt**:

**① Mutable registry + runtime activation tool.** `QuadToolRegistry` is backed by `ConcurrentHashMap` (thread-safe). A `tool_activator` tool mutates it at runtime, mirroring strands' `ToolActivator`:

```java
// ── tool_activator: add/remove tools in the CURRENT session ──────────
@ApplicationScoped
public class ToolActivatorTool {
    private final QuadToolRegistry registry;

    @Inject LazyToolLoader toolLoader;    // §3.11 source of inactive tools (never an empty map)

    @Tool("Activate or deactivate a tool by name. action='add' → available, 'remove' → hidden.")
    public String activate(@Param("action") String action, @Param("tool") String tool) {
        if (action == null || tool == null) return "Both 'action' and 'tool' are required";
        return switch (action) {
            case "add"    -> {
                ToolMethod m = toolLoader.load(tool);
                if (m == null) yield "Unknown tool '" + tool + "'. Check quad.tools.dir / classpath / MCP sources.";
                registry.register(tool, m);
                registry.notifyToolSetChanged(Set.of(tool), Set.of());
                yield "Tool '" + tool + "' activated.";
            }
            case "remove" -> { registry.remove(tool);
                               registry.notifyToolSetChanged(Set.of(), Set.of(tool));
                               yield "Tool '" + tool + "' deactivated."; }
            default       -> "Unknown action. Use 'add' or 'remove'.";
        };
    }
}

// ── LazyToolLoader: on-demand discovery + Gizmo compilation of inactive tools ──
// Source of the "available" tool set: a scanned directory (`quad.tools.dir`),
// the classpath, or an MCP server. Native-image limitation (§3.3): runtime Gizmo
// generation is impossible; therefore native images only load pre-registered tools
// and MCP tools. JVM mode produces ReflectiveToolMethod wrappers on first load.
@ApplicationScoped
public class LazyToolLoader {

    private final Map<String, ToolMethod> compiled = new ConcurrentHashMap<>();

    @Inject QuadToolRegistry registry;
    @Inject ToolSpecificationBuilder specBuilder;
    @Inject ToolArgsMapper toolArgsMapper;            // POJO with Quarkus ObjectMapper (§3.3)

    public ToolMethod load(String tool) {
        return compiled.computeIfAbsent(tool, name -> {
            // 1. Resolve the candidate @Tool method from the configured source:
            //    quad.tools.dir directory scan → classpath scan → MCP tool list.
            // 2. Build the ToolMethod. JVM mode: ReflectiveToolMethod wrapper.
            //    Native mode: only pre-registered (build-time Gizmo) or MCP tools available.
            Method m = resolve(name);                // throws if absent from every source
            Object instance = CDI.current().select(m.getDeclaringClass()).get();
            return ToolMethod.from(m, specBuilder.fromMethod(m), instance, toolArgsMapper);
        });
    }

    private Method resolve(String name) { /* directory/classpath/MCP lookup */ }
}
```

**② `isDynamic() = true`.** LangChain4j re-queries the `ToolProvider` on **every** model call, so a registry change is picked up on the very next iteration — no rebuild of the AiServices instance.

**③ Context injection (the crucial part).** Because the model was already mid-conversation, the changed tool set must be **announced into the current context** — exactly strands' `AgentLoop.handleBeforeModelCall` tool-diff notice and `SkillActivationHook` skill-instruction injection:

```java
// ── before-model-call hook: notify the model about tool-set changes ──
@ApplicationScoped
public class ToolSetChangeNotifier implements AgentHook {

    private final CapabilitySearchRouter router;   // invalidate index on change

    @Inject CurrentSession currentSession;         // §3.1 per-execution session holder

    @Override
    public HookResult beforeModelCall(HookContexts.BeforeModelCallContext ctx) {
        // lastToolNames is bound to the SESSION state — parallel requests never
        // overwrite each other's diff baseline (each sees its own added/removed).
        var state = currentSession.get();
        var current = ctx.tools().stream().map(ToolSpecification::name).collect(toSet());
        var lastToolNames = state.getLastToolNames();
        if (current.equals(lastToolNames)) return HookResult.Continue.INSTANCE;

        var added   = new HashSet<>(current); added.removeAll(lastToolNames);
        var removed = new HashSet<>(lastToolNames); removed.removeAll(current);

        // SYSTEM NOTE lands in THIS turn's context — the model sees its new tools
        ctx.additionalMessages().add(Message.system(
            "SYSTEM NOTE: Your available tools were updated. "
            + "Added: " + added + "  Removed: " + removed));
        state.setLastToolNames(current);
        router.onToolSetChanged(added, removed);
        return HookResult.Continue.INSTANCE;
    }
}

// ── after tool_activator: inject full skill instructions into context ──
@ApplicationScoped
public class SkillActivationHook implements AgentHook {

    @Override
    public HookResult afterToolCall(HookContexts.AfterToolCallContext ctx, String toolResult) {
        if (!"tool_activator".equals(ctx.toolName())) return HookResult.Continue.INSTANCE;
        var skill = findActivatedSkill(toolResult);          // parse 'activate <skill>' from result
        if (skill == null) return HookResult.Continue.INSTANCE;

        // <activated_skill> block is appended to the conversation — the model
        // now follows the skill's instructions in this session without a restart.
        ctx.additionalMessages().add(Message.system(
            "<activated_skill name=\"" + escapeXml(skill.name()) + "\">\n"
            + skill.instructions() + "\n"
            + "<allowed_tools>" + String.join(", ", skill.allowedTools()) + "</allowed_tools>\n"
            + "</activated_skill>"));
        return HookResult.Continue.INSTANCE;
    }
}
```

Every mutation path invalidates the capability-search cache (§3.6 `invalidate()`), reindexes incrementally, and fires a `ToolSetChangedEvent` on the Event Bus for audit/tracing.

### 3.12 Sub-Agent Composition

Sub-agents are **first-class agents** that reuse the whole selection pipeline. `ToolSelectionAgent` (§3.10) is itself a sub-agent.

**Isolation contract:** each sub-agent gets its **own** `DynamicToolProvider`, `AgentSessionState`, `ChatMemory`, and tool-selection scope — never the parent's.

```java
// ── Factory: builds isolated sub-agents (own provider, session, memory) ──
@ApplicationScoped
public class SubAgentFactory {

    // CDI-managed collaborators are INJECTED — never `new`-ed. Sub-agent routers
    // and searches are constructed as plain POJOs with explicit constructor args
    // (§3.6/§3.7), which is safe because they have NO field-level CDI annotations:
    // config values are passed explicitly, CDI beans are passed from here.
    @Inject ChatLanguageModel llm;
    @Inject ToolSelectionAgent selectorAgent;         // CDI sub-agent (§3.10)
    @Inject CapabilityIndex sharedIndex;              // CDI-managed (tenant-filtered)
    @Inject WorkspaceResolver workspace;
    @Inject ToolArgsMapper toolArgsMapper;            // POJO with Quarkus ObjectMapper (§3.3)
    @Inject CurrentSession currentSession;            // @RequestScoped proxy (§3.1)
    @Inject SandboxClient sandbox;                    // §3.8 pool or dev Docker alternative

    public CodeReviewSubAgent newCodeReviewAgent() {
        // 0. Own tool registry — the sub-agent's OWN @Tool methods (Static-First
        //    Stage 0, §3.7) plus explicit review tools (grep/read/diff) + sandbox.
        //    registerFromAgent wires the Gizmo invokers with the shared mapper.
        QuadToolRegistry reviewRegistry = QuadToolRegistry.builder()
            .with(new ReadTool(workspace)).with(new GrepTool(workspace))
            .with(new GitDiffTool()).withSandbox(sandbox)
            .withToolArgsMapper(toolArgsMapper)
            .build();

        // 1. Own capability search + LLM selector, scoped to this agent.
        //    Plain-POJO constructor wiring — the SAME constructors CDI uses for the
        //    singleton, here fed with sub-agent-scoped collaborators.
        CapabilitySearch reviewSearch = new CapabilitySearch(
            sharedIndex, reviewRegistry, /* topK */ 5);
        CapabilitySearchRouter reviewRouter = new CapabilitySearchRouter(
            reviewSearch, reviewRegistry, /* builtInProvider */ null,
            selectorAgent, /* llmSelectorEnabled */ true, /* prefilterTopK */ 20);

        // 2. ONE stateless definition, wired to its own assistant. Two-phase
        //    construction because the assistant needs the agent (DynamicToolProvider
        //    selection identity, §6.2) and the agent needs the assistant (generate).
        CodeReviewSubAgent reviewAgent = new CodeReviewSubAgent(workspace);
        reviewRegistry.registerFromAgent(reviewAgent);
        reviewAgent.wire(createAssistant(reviewAgent, reviewRegistry, reviewRouter));
        return reviewAgent;
    }

    private AgentAssistant createAssistant(Agent agent, QuadToolRegistry reg,
                                           CapabilitySearchRouter router) {
        return AiServices.builder(AgentAssistant.class)
            .chatLanguageModel(llm)
            .chatMemory(MessageWindowChatMemory.withMaxMessages(20))
            .toolProvider(new DynamicToolProvider(router, reg, agent, 5,
                                                  toolArgsMapper, currentSession))
            .build();
    }
}

// ── Review sub-agent: stateless definition + own assistant (§3.1 override) ──
// Plain subclass of Agent — collaborators passed explicitly by the factory, NO
// field-level CDI so `new` is safe. Static-First (§3.7): its OWN @Tool methods
// form the Stage-0 tool set; the definition is the `agent` identity for selection.
public class CodeReviewSubAgent extends Agent {
    private final WorkspaceResolver workspace;   // passed by factory, never @Inject here
    private AgentAssistant assistant;            // own AiServices — wired after construction

    public CodeReviewSubAgent(WorkspaceResolver workspace) {
        this.workspace = workspace;
    }

    void wire(AgentAssistant assistant) { this.assistant = assistant; }

    // Static-First Stage 0 tools (§3.7)
    @Tool("List the files of the current project")
    public String listProjectFiles() { return workspace.describe(); }

    @Tool("Read a file from the project workspace")
    public String readFile(@Param("path") String path) { return workspace.read(path); }

    @Override
    protected AgentSessionState newSessionState() { return new ReviewSession(); }

    // Runs on THIS instance's assistant — own registry/router/provider/state.
    // The previous CurrentSession binding (the manager's state) is restored, so
    // the parent's ReAct loop continues with its OWN state after the delegation.
    @Override
    public String generate(String prompt, AgentSessionState state) {
        var holder = CurrentSession.current();
        AgentSessionState previous = holder.get();
        holder.bind(state);
        try {
            return assistant.chat(prompt);
        } finally {
            holder.bind(previous);
        }
    }
}

// ── Manager agent delegates via @Tool — sub-agent runs in its own context ──
@Tool("Delegate a code review to the review sub-agent. Use for any review request.")
public String reviewCode(@Param("description") String description) {
    // Sub-agent session state is separate from the manager's — no field sharing
    CodeReviewSubAgent reviewAgent = subAgentFactory.newCodeReviewAgent();
    AgentSessionState reviewState = reviewAgent.newSessionState();
    return reviewAgent.generate(description, reviewState);   // own assistant + state (§3.1)
}
```

**Selection scoping per sub-agent:** `CapabilitySearchRouter.findRelevantTools(agent, query, topK)` already receives the `Agent`; the registry can scope results via a per-agent allowlist (`@ToolOnAgent` annotation or a dedicated `QuadToolRegistry` per sub-agent). This prevents, e.g., a review sub-agent from being handed `executeBash`-free but file-writing tools it must not use, and keeps each sub-agent's LLM selector context small.

---

## 4. Quarkus Integration

### 4.1 Configuration (`application.properties`)

```properties
# LLM
quarkus.langchain4j.openai.api-key=${OPENAI_API_KEY}
quarkus.langchain4j.openai.model-name=gpt-4o
quarkus.langchain4j.openai.temperature=0.1

# Embeddings for Capability Search (same or separate model)
quarkus.langchain4j.openai.embedding-model-name=text-embedding-3-small

# Vector Store Selection (built-in HNSWLib for dev, Qdrant/PGVector for prod)
quad.capability.index.type=hnsw  # hnsw | qdrant | pgvector (default: hnsw)

# HNSW-specific
quarkus.langchain4j.embedding-store.hnsw.enabled=true
quarkus.langchain4j.embedding-store.hnsw.dimensions=1536

# Qdrant-specific
quad.capability.qdrant.url=http://localhost:6334
quad.capability.qdrant.collection=quad_capabilities

# PGVector-specific
quad.capability.pgvector.jdbc-url=jdbc:postgresql://localhost:5432/quad
quad.capability.pgvector.table=tools_index

# Capability Search Tuning
quad.capability.search.top-k=5
quad.capability.search.cache-size=1000
quad.capability.search.cache-expiry-minutes=5

# LLM Tool Selection Agent (Stage 2, §3.10) — separate, cheaper model
quad.capability.llm-selector.enabled=true
quad.capability.llm-selector.model=gpt-4o-mini
quad.capability.llm-selector.prefilter-top-k=20
quad.capability.llm-selector.cache-size=500
quad.capability.llm-selector.timeout-ms=2000
# Selector runs a SECOND, cheaper model — configured as a NAMED LangChain4j bean
# ("selector") and injected via @ModelName("selector") in ToolSelectionAgent (§3.10).
# The main model above stays untouched. Quarkus property values use plain ${...}
# interpolation — no `\$` escaping is needed (or valid) here.
# NOTE: the qualifier keys MUST be quoted — `"selector"` is a literal config segment;
# without the quotes LangChain4j reads `langchain4j.openai.selector...` as a nested
# object path and silently falls back to the default model.
quarkus.langchain4j.openai."selector".api-key=${OPENAI_API_KEY}
quarkus.langchain4j.openai."selector".model-name=gpt-4o-mini

# Hot Reload / Tool Activation (§3.11)
quad.tools.activator.enabled=true
quad.tools.hot-reload.notify=true

# Sub-Agent Factory (§3.12)
quad.subagent.code-review.enabled=true

# Built-in Tools Configuration
quad.tools.built-in.enabled=true
quad.tools.built-in.includes=executeBash,readFile,writeFile,listDir,grep,webFetch,webSearch,dockerRun
quad.tools.built-in.excludes=toolName1,toolName2
quad.workspace=/tmp/quad-workspace   # BASE dir; sessions run in {base}/{sessionId} (§10.2)

# Sandbox (warm worker pool, microVM isolation)
quad.sandbox.pool.url=http://quad-sandbox:8080
quad.sandbox.pool.workers=3
quad.sandbox.timeout-seconds=30
quad.sandbox.pool.acquire-timeout-seconds=5   # fail fast when pool saturated (§11.15)
quad.sandbox.network-enabled=false
quad.sandbox.memory-mb=512

# Distributed Locks (§11.12)
quad.lock.provider=redis
quad.lock.ttl-seconds=30
quad.lock.wait-seconds=2
quad.lock.watchdog.enabled=true              # LockHeartbeatTask renewal (interval = ttl/3)

# Sandbox client selection (§3.8) — warm pool is default; dev profile opts into Docker
%dev.quarkus.arc.selected-alternatives=DockerSandboxService

# Saga compensation & idempotency (§11.11)
quad.saga.compensation.enabled=true
quad.saga.idempotency-store.type=redis       # redis | jdbc

# Agent loop guards (§11.15)
quad.agent.max-iterations=15
quad.agent.stuck-repeat-limit=3

# Tracing
quarkus.otel.exporter.otlp.endpoint=http://localhost:4317
quarkus.otel.exporter.otlp.traces.enabled=true
```

### 4.2 Agent Wiring & SessionStateFactory (no manual producer)

`EnterpriseDevAgent` is a **normal CDI bean** (`@ApplicationScoped` with `@Inject` fields) — a manual `@Produces` method using `new EnterpriseDevAgent()` would bypass CDI and break `@Inject`. No producer is needed for the agent.

```java
// Agent is a normal @ApplicationScoped bean — obtained via @Inject / Instance,
// never via `new`. The router wires it at StartupEvent (§3.7).
@Inject
EnterpriseDevAgent agent;

// If a custom producer were ever required (e.g. bean-per-tenant), use CDI
// lookup instead of `new` so injections are honored:
@Produces
@ApplicationScoped
public EnterpriseDevAgent createAgent() {
    return Arc.container().instance(EnterpriseDevAgent.class).get();
}

// Session state is a plain POJO created by the CDI SessionStateFactory (§3.1).
// It is NOT a CDI bean, so it must NOT be @Produces/@RequestScoped — tools
// receive it as a per-call parameter via CurrentSession (§3.3).
@Inject
SessionStateFactory stateFactory;   // stateFactory.create(agent) → seeded POJO
```

### 4.3 Build-Time Agent Discovery

```java
// quad-quarkus/QuadProcessor.java (Annotation Processor)
@BuildStep
void discoverAgents(BuildContext ctx, 
        BeanArchiveIndex index,
        BuildItemMultiConsumer<AgentBuildItem> agents) {
    
    index.getAnnotationsWithName("quad.core.annotations.Agent")
        .forEach(ai -> agents.produce(new AgentBuildItem(ai.target().asClass())));
}
```

> **Compiler flag (required):** `ToolArgsMapper.paramName()` falls back to `Parameter.getName()` when a `@Param` value is missing (§3.3). That name is only meaningful if the compiler retained parameter names — build with `-parameters` (`maven.compiler.parameters=true` or `<parameters>true</parameters>` in `pom.xml`). Without it, unnamed parameters become `arg0`, `arg1`, … and the LLM cannot bind arguments to the tool schema. All `@Tool`/`@Param` annotations in this spec use explicit values, but the flag is the safety net and is enabled by Quarkus' default `quarkus-maven-plugin` parent POM anyway.

---

## 5. Agent Implementation Example

```java
// Stateless agent definition — safe as @ApplicationScoped (NO mutable fields!)
@ApplicationScoped
public class EnterpriseDevAgent extends Agent {

    // Injected stateless services only
    @Inject DbClient dbClient;

    // Creates a fresh, isolated session state per execution flow
    @Override
    protected AgentSessionState newSessionState() {
        return new EnterpriseDevSession();
    }

    // Typed Java Tool — receives the session state to mutate
    @Tool("Query customer database by ID")
    public CustomerData getCustomer(AgentSessionState state,
                                    @Param("customerId") String id) {
        return dbClient.findCustomer(id);
    }

    @Tool("Execute SQL query and return results as JSON")
    public String runSql(AgentSessionState state,
                         @Param("query") String sql) {
        return dbClient.executeQuery(sql);
    }

    // Generation Method (LLM-driven reasoning) — state passed through
    @GenerationMethod
    @Prompt("""
        You are a senior DevOps engineer. Analyze the issue and use tools to resolve it.
        Available context: {doc(state)}
        """)
    public String resolveIssue(AgentSessionState state, String issueDescription) {
        return generate(issueDescription, state); // Triggers ReAct loop on THIS session state
    }
}

// Per-session mutable state — the QUAD "object" the agent manipulates.
// Plain POJO (NO CDI annotations); created by SessionStateFactory (§3.1).
public class EnterpriseDevSession extends AgentSessionState {
    private String currentProject = "Project Alpha";
    private final List<String> findings = new ArrayList<>();

    @Hidden
    private String dbPassword = "secret"; // Never serialized to LLM
}
```

---

## 6. Execution Loop Details

### 6.1 ReAct Loop (LangChain4j AiServices)

```java
public interface AgentAssistant {
    String chat(String userMessage);
}

// Wired at startup (Quarkus main / @Startup configurator). CDI proxies are
// resolved once; DynamicToolProvider is a plain POJO, so `new` is safe (§6.2).
AgentAssistant assistant = AiServices.builder(AgentAssistant.class)
    .chatLanguageModel(llm)
    .chatMemory(MessageWindowChatMemory.withMaxMessages(20))
    .toolProvider(new DynamicToolProvider(router, registry, agent, 5,
                                          toolArgsMapper, currentSession))
    .build();
```

### 6.2 DynamicToolProvider

Two-stage selection (§3.7) + `isDynamic()` for hot reload (§3.11).

// Plain POJO with FULL constructor injection (same policy as §3.6/§3.7): every
// collaborator is passed explicitly, so there are no @Inject fields that stay null
// under `new`. `currentSession` is the @RequestScoped client proxy resolved once at
// wiring time; provideTools() runs inside the request context, so `get()` returns
// THIS request's session state.
public class DynamicToolProvider implements ToolProvider {

    private final CapabilitySearchRouter router;   // prefilter + LLM ToolSelectionAgent
    private final QuadToolRegistry registry;
    private final Agent agent;                     // stateless definition (§3.1) — DECLARED
    private final int defaultTopK;
    private final ToolArgsMapper argsMapper;       // POJO with Quarkus ObjectMapper (§3.3)
    private final CurrentSession currentSession;   // @RequestScoped proxy (§3.1)

    public DynamicToolProvider(CapabilitySearchRouter router, QuadToolRegistry registry,
                               Agent agent, int defaultTopK,
                               ToolArgsMapper argsMapper, CurrentSession currentSession) {
        this.router = router; this.registry = registry; this.agent = agent;
        this.defaultTopK = defaultTopK;
        this.argsMapper = argsMapper; this.currentSession = currentSession;
    }

    @Override
    public ToolProviderResult provideTools(ToolProviderRequest request) {
        // P2: bail out if the HTTP request was cancelled mid-loop
        if (Thread.currentThread().isInterrupted()) {
            throw new TaskCancelledException("Tool selection aborted: request cancelled");
        }
        String userQuery = request.userMessage().singleText();

        // Stage 1: embedding pre-filter → Stage 2: LLM ToolSelectionAgent (§3.10)
        List<ToolMethod> methods = router.findRelevantTools(agent, userQuery, defaultTopK);
        AgentSessionState state = currentSession.get();   // state of THIS execution

        ToolProviderResult.Builder builder = ToolProviderResult.builder();
        for (ToolMethod tm : methods) {
            // LangChain4j ExecutableTool signature: String execute(Map<String,Object> args).
            // The Map is serialized to JSON, then the state is threaded per-call into the
            // Gizmo invoker (§3.3) — the registry itself never holds session state.
            builder.add(tm.spec(), argsMap -> tm.execute(argsMapper.toJson(argsMap), state));
        }
        return builder.build();
    }

    @Override
    public boolean isDynamic() {
        // P2: re-query on every model call → tool_activator additions/removals
        // (§3.11) take effect on the NEXT iteration without rebuilding AiServices.
        return true;
    }
}
```

---

## 7. Security Model

| Layer                    | Mechanism                                    |
|--------------------------|----------------------------------------------|
| **Secrets in State**     | `@Hidden` annotation → excluded from `doc(this)` and JSON serialization |
| **Bash Execution**       | Warm sandbox worker pool in kata-containers/gVisor microVMs: no privileged mode, read-only FS, non-root, CPU/RAM limits, shared RWX workspace |
| **Tool Invocation**      | Gizmo build-time invokers (no runtime reflection); param validation via `@ToolInputGuardrails` |
| **LLM Prompt Injection** | Strict system prompt, tool descriptions only from annotations |
| **Container Isolation**  | Kata/gVisor microVM, `--network=none --read-only --user=nobody --memory=512m --cpus=0.5`, no DinD |

---

## 8. Observability

### 8.1 OpenTelemetry Spans

| Span Name                 | Attributes                                      |
|---------------------------|-------------------------------------------------|
| `agent.loop`              | agent.class, iteration, user_query              |
| `llm.call`                | model, tokens_in, tokens_out, latency_ms        |
| `tool.java.*`             | tool.name, args_json, result_json, duration_ms  |
| `tool.bash.execute`       | script_hash, exit_code, stdout_len, stderr_len  |
| `capability.search`       | query, top_k, matched_tools                     |
| `tool.selection`          | query, candidates, selected_tools, selector_model, latency_ms, fallback_reason |

### 8.2 Quarkus Micrometer Metrics

```properties
quarkus.micrometer.export.prometheus.enabled=true
quarkus.micrometer.binder.jvm.enabled=true
```

---

## 9. Testing Strategy

### 9.1 Unit Tests (JUnit 5 + Mockito)

```java
@QuarkusTest
class EnterpriseDevAgentTest {

    @InjectMock ChatLanguageModel llm;
    @InjectMock SandboxPoolClient sandbox;
    @InjectMock ToolSelector selectorAssistant;     // §3.10 LLM selector
    @Inject CapabilitySearchRouter router;
    @Inject ToolActivatorTool activatorTool;
    @Inject DynamicToolProvider provider;
    @InjectMock Event<ToolSetChangedEvent> eventBus;
    @Inject SubAgentFactory subAgentFactory;
    @Inject SummarizingConversationManager conversationManager;   // §11.7
    @Inject ToolArgsMapper toolArgsMapper;                        // §3.3
    @Inject SessionLockService lockService;                       // §11.12
    @Inject AgentSessionState state;
    Method method;   // a @Tool method with a complex POJO param for coercion tests

    @Test
    void testGetCustomerTool() {
        // Verify tool registration, schema generation, Gizmo invoker invocation
    }

    @Test
    void testCapabilitySearchFiltersTools() {
        // Embed query, verify top-K matches
    }

    @Test
    void testBuiltInToolsConfiguration() {
        // Verify includes/excludes filtering works
    }

    @Test
    void testSessionStateIsolation() {
        // Two AgentSessionState instances must never share mutable fields
        var a = agent.newSessionState();
        var b = agent.newSessionState();
        a.addFinding("x");
        assertTrue(b.findings().isEmpty());   // P0: no cross-session leakage
    }

    @Test
    void testLlmToolSelection() {
        // Stage 2 (§3.10): mock ToolSelector → returns ordered recommendations;
        // verify router returns exactly those + executeBash fallback, in order.
        when(selectorAssistant.select(anyString())).thenReturn(
            new ToolSelection("analysis", List.of("grepTool", "readTool"),
                              List.of(), "reasoning", List.of()));
        var tools = router.findRelevantTools(agent, "find the failing test", 5);
        assertEquals(List.of("grepTool", "readTool", "executeBash"),
                     tools.stream().map(t -> t.spec().name()).toList());
    }

    @Test
    void testLlmSelectionTimeoutFallsBack() {
        // Latency guard (§3.10): selector times out → Stage-1 embedding result,
        // loop still gets tools (degrade, never fail).
        when(selectorAssistant.select(anyString())).thenThrow(new TimeoutException());
        var tools = router.findRelevantTools(agent, "find the failing test", 5);
        assertFalse(tools.isEmpty());
    }

    @Test
    void testToolActivationHotReload() {
        // §3.11: tool_activator 'add' → registry + index updated, ToolSetChangedEvent fired;
        // next provideTools() (isDynamic=true) returns the new tool.
        activatorTool.activate("add", "writeTool");
        var provided = provider.provideTools(request("write a file"));
        assertTrue(provided.contains("writeTool"));
        verify(eventBus).fire(any(ToolSetChangedEvent.class));
    }

    @Test
    void testSubAgentIsolation() {
        // §3.12: sub-agent runs with its OWN provider/session — the manager's
        // parent state is never passed to it, so parent stays untouched.
        var parent = agent.newSessionState();
        var reviewAgent = subAgentFactory.newCodeReviewAgent();
        reviewAgent.generate("review", reviewAgent.newSessionState());
        assertTrue(parent.findings().isEmpty());
    }

    @Test
    void testStateDiffFolding() {
        // §11.7: consecutive [state] diffs collapse into ONE <state_delta> block.
        var history = List.of(
            UserMessage.from("[state] findings += finding1"),
            UserMessage.from("[state] findings += finding2"),
            UserMessage.from("user", "continue"));
        var folded = conversationManager.prepareContext(history, state);
        assertEquals(2, folded.size());   // one <state_delta> + the user message
        assertTrue(folded.get(0).text().contains("findings += finding1"));
        assertTrue(folded.get(0).text().contains("findings += finding2"));
    }

    @Test
    void testToolArgsMapperStringifiedJson() {
        // §3.3: LLM passes a JSON-stringified POJO → re-parsed and bound natively.
        AgentSessionState state = new AgentSessionState();
        Object[] args = toolArgsMapper.mapArguments(
            "{\"customer\": \"{\\\"id\\\":7,\\\"name\\\":\\\"A\\\"}\"}", method, state);
        assertInstanceOf(CustomerData.class, args[0]);
        assertEquals(7, ((CustomerData) args[0]).id());
    }

    @Test
    void testStateThreadedPerCallNotShared() {
        // §3.1/§3.3: the same GizmoInvoker (registered once in the singleton
        // registry) must receive the CURRENT call's state — never a field bound
        // at construction (would leak state across requests/sessions).
        var s1 = new AgentSessionState(); s1.sessionId = "s1";
        var s2 = new AgentSessionState(); s2.sessionId = "s2";
        invoker.execute("{...}", s1);
        invoker.execute("{...}", s2);
        verify(tool, times(2)).getCustomer(any(), eq("id"));
        verify(tool).getCustomer(s2, "id");     // second call observed s2, not s1
    }

    @Test
    void testLockReleasedOnInterrupt() {
        // §11.12: interrupted thread → TaskCancelledException AND lock released.
        Thread.currentThread().interrupt();
        assertThrows(TaskCancelledException.class,
            () -> lockService.executeLocked("s1", () -> "work"));
        assertTrue(Thread.currentThread().isInterrupted());   // flag restored
        assertFalse(lockService.isHeld("quad:lock:s1"));
    }

    @Test
    void testLockWatchdogRenewsDuringLongTool() {
        // §11.12: the LockHeartbeatTask re-arms the lease every ttl/3, so a tool
        // longer than ttlSeconds must NOT lose the lock mid-execution (no TTL-race).
        long result = lockService.executeLocked("s1", () -> {
            // simulate > ttlSeconds tool; the heartbeat must renew the Redis lease
            for (int i = 0; i < ttlSeconds + 2; i++) { Thread.sleep(1000); }
            return 42L;
        });
        assertEquals(42L, result);
        assertTrue(lockService.isHeld("quad:lock:s1"));
    }

    @Test
    void testOwnerTokenPreventsStaleUnlock() {
        // §11.12: release/renew are CAS-guarded Lua — a holder that lost its lease
        // (crash) can never delete or extend another pod's lock. Pod A acquires,
        // its lease lapses (crash), pod B takes over; A's stale heartbeat is a no-op.
        lockService.executeLocked("s1", () -> {
            redis.getConnection().send(Command.create(RedisCommand.of("DEL")), "quad:lock:s1");
            return 1;                                  // simulate A's crash + lease expiry
        });
        lockService.executeLocked("s1", () -> true);   // pod B acquires the freed lock
        Long stale = lockService.renewIfOwner("quad:lock:s1", tokenOfCrashA());  // old token
        assertEquals(0L, stale);                       // RENEW rejected — B's lock intact
        assertTrue(lockService.isHeld("quad:lock:s1"));
    }

    @Test
    void testToolDiffBaselineIsPerSession() {
        // §3.11: lastToolNames is bound to AgentSessionState, so parallel sessions
        // each compute their OWN added/removed — session A never sees B's changes.
        var s1 = new AgentSessionState(); s1.setLastToolNames(Set.of("readFile"));
        var s2 = new AgentSessionState(); s2.setLastToolNames(Set.of("grep"));
        CurrentSession.current().bind(s1);
        var note1 = toolSetChangeNotifier.beforeModelCall(ctxTools(Set.of("readFile", "webFetch")));
        assertTrue(note1.additionalMessages().get(0).text().contains("Added: [webFetch]"));
        CurrentSession.current().bind(s2);
        var note2 = toolSetChangeNotifier.beforeModelCall(ctxTools(Set.of("grep", "webFetch")));
        assertTrue(note2.additionalMessages().get(0).text().contains("Added: [webFetch]"));
        assertEquals(Set.of("readFile"), s1.getLastToolNames());   // s1 baseline untouched
    }

    @Test
    void testStateDiffAppendSerializedPerSession() throws Exception {
        // §3.9: StateDiffMemoryBridge serializes the read-modify-write round-trip
        // PER sessionId, so 10 parallel mutations of the same session all land.
        var bridge = new StateDiffMemoryBridge(chatMemoryStore);
        var futures = new ArrayList<Future<?>>();
        for (int i = 0; i < 10; i++) {
            final int v = i;
            futures.add(executor.submit(() -> bridge.onStateChanged("s1", "findings", null, v)));
        }
        for (var f : futures) f.get();
        assertEquals(10, chatMemoryStore.getMessages("s1").size());   // no lost updates
    }

    @Test
    void testSagaCompensationLifoOrder() {
        // §11.11: closures registered during execution are unwound LIFO.
        var state = new AgentSessionState();
        state.registerCompensation("createFile", s -> fileService.deleteIfExists("/a"));
        state.registerCompensation("appendFile", s -> fileService.deleteIfExists("/b"));
        sagaAgentInterceptor.rollbackSaga(state, new RuntimeException("boom"));
        InOrder order = inOrder(fileService);
        order.verify(fileService).deleteIfExists("/b");   // newest first
        order.verify(fileService).deleteIfExists("/a");
        assertTrue(state.isSagaFailed());
        assertTrue(state.getSagaLog().isEmpty());
    }

    @Test
    void testCompensationFailureStillReported() {
        // §11.11: a failing compensation is alerted via AgentEvent, does NOT
        // mask the original loop error.
        var state = new AgentSessionState();
        state.registerCompensation("createFile", s -> { throw new RuntimeException("perm"); });
        assertThrows(RuntimeException.class, () -> sagaAgentInterceptor.runInSaga(invocationThatThrows(state)));
        verify(eventBus).fire(any(AgentEvent.ToolFinished.class));   // compensation:createFile
        assertThrows(AssertionError.class, () -> verifyNoMoreInteractions(eventBus));
    }

    @Test
    void testIdempotentToolReplaysCachedResult() {
        // §11.11: @Idempotent tools return the recorded result on re-run
        // instead of re-executing the side-effect.
        toolExecutor.execute("{...}", idempotentTool, tx);
        String second = toolExecutor.execute("{...}", idempotentTool, tx);
        verify(idempotentTool, times(1)).invoke(any());   // executed once
        assertEquals(firstResult, second);                // second run replayed
    }

    @Test
    void testSagaRollbackAppendsMemoryNotice() {
        // §11.11: after rollback, SagaAwareConversationManager injects a
        // [CRITICAL SYSTEM NOTICE] SystemMessage so the LLM does not
        // hallucinate effects that were undone (§11.7 interplay).
        assertThrows(RuntimeException.class, () -> sagaAgent.resolveIssue(state, "x"));
        verify(sagaContext).handleSagaRollbackContext(state, List.of("createConfigFile"));
        // The notice lands in the SESSION's memory (ChatMemoryStore keyed by sessionId,
        // §11.11) — LangChain4j ChatMemory has no `supports(String)` query.
        boolean noticeAppended = memoryStore.getMessages(state.getSessionId()).stream()
            .anyMatch(m -> m instanceof SystemMessage sm
                && sm.text().contains("REVERSED via Saga Compensation"));
        assertTrue(noticeAppended);
    }

    @Test
    void testSandboxSaturationFailsFast() {
        // §11.15: acquire(timeout) → SandboxUnavailable, never queues behind lock.
        when(poolClient.acquire(anyDuration())).thenThrow(SandboxUnavailable.class);
        assertThrows(SandboxUnavailable.class, () -> sandbox.executeBash("make"));
    }
}
```

### 9.2 Integration Tests (Testcontainers)

```java
@QuarkusTest
@Testcontainers
class SandboxPoolIntegrationTest {

    @Container
    static GenericContainer<?> worker = new GenericContainer<>("quad-sandbox-worker:1.0")
        .withExposedPorts(8080)                       // long-running worker, not DinD
        .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig()
            .withMemory(512L * 1024 * 1024)
            .withNetworkMode("none"));

    @Test
    void testBashExecutionIsolated() {
        String result = poolClient.run("echo hello");
        assertEquals("hello\n", result);
    }
}
```

---

## 10. Deployment

### 10.1 Container Image (Quarkus Native)

```dockerfile
FROM registry.access.redhat.com/ubi9/ubi-minimal:9.5
WORKDIR /work
COPY target/*-runner /work/application
COPY target/lib /work/lib
EXPOSE 8080
ENTRYPOINT ["./application", "-Dquarkus.http.host=0.0.0.0"]
```

### 10.2 Kubernetes Sandbox — MicroVM Isolation (no privileged DinD)

**P0 Security/Latency Fix:** `privileged: true` DinD violates Pod Security Standards and grants host root. Ephemeral container spawn per `executeBash` adds 1.5–4 s/turn. Replaced with:

- **Runtime isolation:** `runtimeClassName: kata-containers` (or gVisor/Firecracker) for workload-pod microVMs.
- **Warm sandbox pool:** a small pool of pre-started sandbox workers; `executeBash` grabs an idle worker instead of creating a container.
- **Shared workspace:** a `ReadWriteMany` PVC mounted at the identical path in both app pod and sandbox workers (fixes host/container path mismatch).
- **Session isolation on the shared PVC:** the base mount is `/work/quad`, but every session works in its own sub-directory `/work/quad/{sessionId}` (resolved by `WorkspaceResolver`, §3.4). Bash and fs tools `cd` into that sub-directory, so concurrent sessions can never overwrite each other. Session dirs are cleaned up on session end (see §11.15).

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: quad-agent
spec:
  template:
    spec:
      runtimeClassName: kata-containers        # microVM isolation, no privileged
      securityContext:
        runAsNonRoot: true
        runAsUser: 1000
        fsGroup: 1000
      containers:
      - name: quad-agent
        image: quad:1.0
        env:
        - name: OPENAI_API_KEY
          valueFrom:
            secretKeyRef:
              name: llm-secrets
              key: api-key
        - name: QUAD_WORKSPACE
          value: /work/quad                    # BASE path — sessions use {sessionId} subdirs
        volumeMounts:
        - name: workspace
          mountPath: /work/quad            # identical path as sandbox workers
      volumes:
      - name: workspace
        persistentVolumeClaim:
          claimName: quad-workspace-pvc     # RWX
---
apiVersion: apps/v1
kind: StatefulSet
metadata:
  name: quad-sandbox-pool
spec:
  serviceName: quad-sandbox
  replicas: 3                                # warm pool, pre-started
  selector:
    matchLabels:
      app: quad-sandbox
  template:
    metadata:
      labels:
        app: quad-sandbox
    spec:
      runtimeClassName: kata-containers
      containers:
      - name: sandbox-worker
        image: quad-sandbox-worker:1.0        # long-running bash server, not ephemeral
        env:
        - name: QUAD_WORKSPACE
          value: /work/quad
        volumeMounts:
        - name: workspace
          mountPath: /work/quad
      volumes:
      - name: workspace
        persistentVolumeClaim:
          claimName: quad-workspace-pvc
---
apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: quad-workspace-pvc
spec:
  accessModes: [ReadWriteMany]
  resources:
    requests:
      storage: 10Gi
```

> `SandboxPoolClient` (see §3.8) is the app-side client for this pool: it grabs an idle warm worker, streams the script, and returns combined stdout/stderr. The worker enforces resource limits, read-only FS, non-root user, and timeout — eliminating the 1.5–4 s per-turn container-spawn latency of ephemeral DinD.

---

## 11. Production Features

### 11.1 Session & Memory Management

Enterprise-grade state persistence for multi-user, multi-session scenarios.

```properties
# Session persistence (choose one)
quad.session.providers=in-memory,jdbc
quad.session.jdbc.url=jdbc:postgresql://localhost:5432/quad
quad.session.jdbc.table=sessions
quad.session.jdbc.schema=public

# Chat memory store (persistent conversation history)
quad.memory.store.type=jdbc
quad.memory.store.jdbc.table=chat_memory
quad.memory.store.jdbc.max-size=10000

# Conversation compaction / summarization (§11.7)
quad.memory.max-context-tokens=32000
quad.memory.keep-last-user-messages=10
```

```java
// Multiple session store implementations (strands-inspired)
@ApplicationScoped
public class SessionStoreProvider {
    
    @Inject Optional<JdbcSessionStore> jdbcStore;
    @Inject Optional<RedisSessionStore> redisStore;
    
    public SessionManager createSessionManager(SessionConfig config) {
        return switch (config.type()) {
            case JDBC -> jdbcStore.orElseThrow(() -> 
                new IllegalStateException("JDBC SessionStore not configured"));
            case REDIS -> redisStore.orElseThrow(() -> 
                new IllegalStateException("Redis SessionStore not configured"));
            case IN_MEMORY -> new InMemorySessionManager();
        };
    }
}

// JDBC-backed session storage
public class JdbcSessionStore implements SessionManager {
    // Persists AgentState, messages, metadata to relational database
    // Schema: sessions(id PK, agent_name, messages_json CLOB, state_json CLOB, ...)
}
```

### 11.2 Resilience & Fault Tolerance

MicroProfile Fault Tolerance patterns for production reliability.

```java
@ApplicationScoped
public class ResilientToolExecutor {
    
    @Retry(maxRetries = 3, delay = 1000, multiplier = 2.0)
    @Timeout(30_000)  // 30 second timeout per tool
    @CircuitBreaker(
        requestVolumeThreshold = 10,
        failureRatio = 0.5f,
        delay = 60_000
    )
    @Fallback(fallbackMethod = "defaultResult")
    public String executeTool(String toolName, String argsJson) {
        ToolMethod tool = registry.get(toolName);
        return tool.execute(argsJson);
    }
    
    public String defaultResult(String toolName, String argsJson) {
        return "Tool '" + toolName + "' unavailable. Error logged to OTel.";
    }
}

// Configuration
quad.resilience.retry.max-attempts=3
quad.resilience.retry.delay-ms=1000
quad.resilience.retry.multiplier=2.0
quad.resilience.timeout.tool-ms=30000
quad.resilience.circuitbreaker.failure-threshold=0.5
quad.resilience.circuitbreaker.min-requests=10
quad.resilience.circuitbreaker.reset-timeout-ms=60000
```

### 11.3 Model Tiering & Routing

Multiple LLM providers with automatic failover and cost optimization.

```java
// Tiered model configuration (inspired by strands)
@ApplicationScoped
public class TieredModelProvider {
    
    private final Map<ModelTier, ChatLanguageModel> models;
    
    public ChatLanguageModel getModel(ModelTier tier, String query) {
        // Smart routing based on query complexity, cost, latency
        return switch (tier) {
            case SIMPLE -> models.get(ModelTier.SIMPLE);   // e.g., GPT-3.5, Claude-3.5 Haiku
            case ADVANCED -> models.get(ModelTier.ADVANCED); // e.g., GPT-4, Claude-3.7 Sonnet
            case ROUTING -> routeModel(query);              // Dynamic selection
        };
    }
    
    private ChatLanguageModel routeModel(String query) {
        // Route simple queries to cheaper model, complex to advanced
        return query.length() > 500 ? models.get(ADVANCED) : models.get(SIMPLE);
    }
}

enum ModelTier { SIMPLE, ADVANCED, ROUTING }

// Configuration
quad.models.simple.provider=openai
quad.models.simple.model=gpt-4o-mini
quad.models.simple.api-key=${OPENAI_SIMPLE_KEY}

quad.models.advanced.provider=openai
quad.models.advanced.model=gpt-4o
quad.models.advanced.api-key=${OPENAI_API_KEY}

quad.models.routing.enabled=true
```

### 11.4 Human-in-the-Loop (HITL)

Approval workflows for sensitive operations.

```java
// HITL checkpoint mechanism (strands-inspired)
@ApplicationScoped
public class HitlService {
    
    private final CheckpointStore checkpointStore;
    private final NotificationProvider notificationProvider;
    
    public boolean requiresApproval(String toolName) {
        // Configurable via:
        // quad.hitl.approval-tools=executeBash,runSql,dockerRun
        return approvalTools.contains(toolName);
    }
    
    public Checkpoint createApprovalCheckpoint(String sessionId, String toolName, String args) {
        var cp = checkpointStore.createCheckpoint(sessionId, toolName, args);
        notificationProvider.notifyApprovers(cp);
        return cp;
    }
    
    public Checkpoint.Status awaitApproval(String checkpointId, Duration timeout) {
        return checkpointStore.await(cp, timeout.toMillis(), TimeUnit.MILLISECONDS);
    }
}

// Configuration
quad.hitl.approval-tools=executeBash,runSql,dockerRun
quad.hitl.notification.emails=ops-team@company.com,security@company.com
quad.hitl.timeout-seconds=120
quad.hitl.auto-approval=false
```

### 11.5 Tool Authorization & RBAC

Fine-grained access control for tools based on user context.

```java
@Retention(RUNTIME)
@Target({METHOD, TYPE})
@Repeatable(ToolAuthorizations.class)
public @interface ToolRole {
    String[] value() default {"user"};
}

// Usage
public class EnterpriseDevAgent extends Agent {
    
    @Tool("Query customer database")
    @ToolRole({"user", "admin"})
    public CustomerData getCustomer(@Param("id") String id) { /* ... */ }
    
    @Tool("Execute admin commands")
    @ToolRole("admin")
    public String executeAdmin(@Param("cmd") String cmd) { /* ... */ }
    
    @Tool("Delete customer record")
    @ToolRole("admin")
    public void deleteCustomer(@Param("id") String id) { /* ... */ }
}

@ApplicationScoped
public class ToolAuthorizationChecker {
    
    public boolean isAuthorized(Method toolMethod, String userId, Set<String> userRoles) {
        var toolRole = toolMethod.getAnnotation(ToolRole.class);
        if (toolRole == null) return true;  // No restriction
        
        for (String role : toolRole.value()) {
            if (userRoles.contains(role)) return true;
        }
        return false;
    }
}
```

### 11.6 PII Redaction & Audit Trail

Automatic scrubbing of sensitive data from logs and traces.

```java
// PII Redaction Hook — LangChain4j ChatModelListener.
// LangChain4j listener contexts (BeforeModelRequestContext etc.) are IMMUTABLE:
// there is no `systemPrompt()`/StringBuffer to mutate, and the live request sent
// to the model must NOT be altered (it would corrupt the LLM input). Redaction is
// therefore applied to every AUDITED COPY — trace attributes, audit records,
// streamed UI events — at the boundaries where we persist/export data.
public class PiiRedactionHook implements ChatModelListener {

    private final Pattern[] piiPatterns = {
        Pattern.compile("\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}\\b", CASE_INSENSITIVE),
        Pattern.compile("\\b\\d{3}-\\d{2}-\\d{4}\\b"),  // SSN
        Pattern.compile("\\b\\d{16}\\b")               // Credit card
    };

    @Override
    public void onBeforeModelRequest(BeforeModelRequestContext ctx) {
        // Scrub the OTel attributes of this request (audit copy only)
        String scrubbed = redactPII(ctx.request().messages().toString());
        Span.current().setAttribute("llm.request.scrubbed", scrubbed);
    }

    @Override
    public void onAfterModelResponse(AfterModelResponseContext ctx) {
        String scrubbed = redactPII(ctx.response().aiMessage().text());
        Span.current().setAttribute("llm.response.scrubbed", scrubbed);
    }

    @Override
    public void onError(OnErrorContext ctx) {
        Span.current().setAttribute("llm.error.scrubbed",
            redactPII(ctx.error().toString()));
    }

    public String redactPII(String text) {
        for (Pattern p : piiPatterns) {
            text = p.matcher(text).replaceAll("[PII_REDACTED]");
        }
        return text;
    }
}

// Audit trail recording — redaction happens HERE, at the persistence boundary,
// not by mutating live model contexts (§11.6).
@ApplicationScoped
public class AuditTrail {

    private final MeterRegistry meterRegistry;

    @Inject PiiRedactionHook redaction;

    public void recordToolCall(String toolName, String sessionId, String userId, 
                               String status, long durationMs) {
        // NOTE: every attribute may carry PII — scrub before exporting
        meterRegistry.counter("quad.tool.calls",
            "tool", redaction.redactPII(toolName),
            "session", redaction.redactPII(sessionId),
            "user", redaction.redactPII(userId),
            "status", status)
            .increment();
    }
}
```

### 11.7 Conversation Memory Management

Automatic pruning, summarization, and **state-diff compaction** for long-running sessions. Prevents the incremental `[state] ...` diff messages (§3.9) from flooding the context window during long ReAct loops.

```java
// Strands-parity strategy interface — prune keeps base contract; prepareContext
// additionally folds state diffs and injects a doc(state) snapshot.
public sealed interface ConversationManager
    permits SlidingWindowConversationManager, SummarizingConversationManager {
    List<ChatMessage> prune(List<ChatMessage> messages);                    // strand-style
    default List<ChatMessage> prepareContext(List<ChatMessage> history,
                                             AgentSessionState state) {
        return prune(history);                                             // fallback: no folding
    }
}

@ApplicationScoped
public class SummarizingConversationManager implements ConversationManager {

    @Inject AgentDocExtractor docExtractor;    // renders a clean state snapshot
    @Inject ChatLanguageModel summarizer;
    @Inject Tokenizer tokenizer;               // §3.9 — optional; null → char heuristic

    @ConfigProperty(name = "quad.memory.max-context-tokens", defaultValue = "32000")
    int maxContextTokens;

    @ConfigProperty(name = "quad.memory.keep-last-user-messages", defaultValue = "10")
    int keepLastUserMessages;

    // Cost protection — never pay a summarizer LLM call for negligible gains
    private static final double SYSTEM_AGGREGATE_THRESHOLD = 0.30;  // share of system msgs
    private static final int    SYSTEM_AGGREGATE_MIN       = 2;     // min system msgs to fold

    public List<ChatMessage> prepareContext(List<ChatMessage> history, AgentSessionState state) {
        // 1. Fold consecutive state-diff messages into a single <state_delta> block
        List<ChatMessage> folded = foldStateDiffs(history);

        // 2. Strands-parity guards — do nothing unless compaction is worthwhile
        if (estimateTokens(folded) <= maxContextTokens) return folded;
        if (folded.size() < 2) return folded;
        List<ChatMessage> systemMsgs = folded.stream().filter(m -> m instanceof SystemMessage).toList();
        List<ChatMessage> nonSystem = folded.stream().filter(m -> !(m instanceof SystemMessage)).toList();
        if (nonSystem.size() <= 10) return folded;               // too little to summarize

        // 3. Keep the last keepLastUserMessages USER turns (a full block) in full
        List<ChatMessage> toKeep = selectLastUserMessagesBlock(nonSystem);
        if (toKeep == nonSystem) return folded;

        List<ChatMessage> toSummarize = nonSystem.subList(0, nonSystem.size() - toKeep.size());
        if (toKeep.size() * 2 >= toSummarize.size()) return folded;           // not worth it
        if (estimateTokens(toSummarize) <= estimateTokens(toKeep) * 2) return folded;

        // 4. Compact: aggregate system spam, summarize old turns, inject fresh state
        var out = new ArrayList<ChatMessage>();
        out.addAll(maybeAggregateSystem(systemMsgs, folded.size()));
        out.add(SystemMessage.from("Previous conversation summary:\n" + buildSummary(toSummarize)));
        out.add(SystemMessage.from("# Current Agent State\n" + docExtractor.doc(state)));
        out.add(SystemMessage.from("--- Previous Messages End ---"));
        out.addAll(toKeep);
        return out;
    }

    // Strands parity: retain the block containing the last N USER messages so the
    // newest user intents survive compaction verbatim.
    private List<ChatMessage> selectLastUserMessagesBlock(List<ChatMessage> nonSystem) {
        int userCount = 0, cutIndex = nonSystem.size();
        for (int i = nonSystem.size() - 1; i >= 0; i--) {
            if (nonSystem.get(i) instanceof UserMessage && ++userCount == keepLastUserMessages) {
                cutIndex = i;
                break;
            }
        }
        return userCount < keepLastUserMessages ? nonSystem : nonSystem.subList(cutIndex, nonSystem.size());
    }

    // Strands parity: tool-change notes + skill activations (§3.11) inject a system
    // message each turn — when they dominate (≥30%, ≥2), fold them into ONE directive.
    private List<ChatMessage> maybeAggregateSystem(List<ChatMessage> systemMsgs, int total) {
        if (systemMsgs.size() < SYSTEM_AGGREGATE_MIN) return systemMsgs;
        if ((double) systemMsgs.size() / total < SYSTEM_AGGREGATE_THRESHOLD) return systemMsgs;
        // Use the canonical LangChain4j API `generate(List<ChatMessage>)` — the
        // convenience `generate(String)` overload is version/provider-dependent
        // and must not be assumed. Response<AiMessage>.content().text() is stable.
        String combined = summarizer.generate(List.of(SystemMessage.from(
            "Condense the following system instructions into one concise directive.\n"
            + numbered(systemMsgs)))).content().text();
        return List.of(SystemMessage.from(combined));
    }

    // Strands parity: role-aware input preserves assistant/tool-call structure for the summarizer
    private String buildSummary(List<ChatMessage> messages) {
        return messages.stream()
            .map(m -> role(m) + ": " + m.text())
            .collect(Collectors.joining("\n"));
    }

    private String role(ChatMessage m) {
        if (m instanceof ToolExecutionResultMessage t)  return "Tool (" + t.toolName() + ")";
        if (m instanceof AiMessage ai) return ai.hasToolExecutionRequests() ? "Assistant (Tool-Call)" : "Assistant";
        if (m instanceof UserMessage)   return "User";
        if (m instanceof SystemMessage) return "System";
        return "Unknown";
    }

    // Collapses "[state] findings += x" / "[state] currentProject = y" runs into
    // a single consolidated block: <state_delta>field: value\n…</state_delta>
    private List<ChatMessage> foldStateDiffs(List<ChatMessage> history) {
        var folded = new ArrayList<ChatMessage>();
        var delta = new StringBuilder();
        for (var msg : history) {
            String text = msg.text();
            if (text != null && text.startsWith("[state]")) {
                delta.append(text.substring("[state] ".length())).append('\n');
                continue;                       // absorb — don't emit one message per diff
            }
            if (!delta.isEmpty()) {             // boundary reached — flush consolidated block
                folded.add(SystemMessage.from("<state_delta>\n" + delta + "</state_delta>"));
                delta.setLength(0);
            }
            folded.add(msg);
        }
        if (!delta.isEmpty()) {
            folded.add(SystemMessage.from("<state_delta>\n" + delta + "</state_delta>"));
        }
        return folded;
    }

    // Tokenizer primary (§3.9); strand-style char heuristic (÷4 + 4) as cheap fallback
    private int estimateTokens(List<ChatMessage> messages) {
        if (tokenizer != null) {
            return messages.stream()
                .mapToInt(m -> tokenizer.estimateTokenCountInText(String.valueOf(m.text())))
                .sum();
        }
        return messages.stream().mapToInt(m -> m.text().length() / 4 + 4).sum();
    }
}
```

> **Design note (§11.7 ↔ §3.11):** `maybeAggregateSystem` is the complement of state-diff folding. `ToolSetChangeNotifier`/`SkillActivationHook` (§3.11) append a system message on every tool-set change — over a long ReAct loop these alone can consume the window. Folding them into a single directive (when they exceed 30% of the conversation) keeps the injected tool/skill context but caps its cost.

### 11.8 Containerization Best Practices

Production-ready Kubernetes deployment with security and observability.

```yaml
# quad-deployment.yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: quad-agent
spec:
  replicas: 3
  selector:
    matchLabels:
      app: quad-agent
  template:
    metadata:
      labels:
        app: quad-agent
    spec:
      runtimeClassName: kata-containers  # Secure sandboxing
      securityContext:
        runAsNonRoot: true
        runAsUser: 1000
        fsGroup: 1000
      containers:
      - name: quad-agent
        image: quad:1.0
        ports:
        - containerPort: 8080
        env:
        - name: OPENAI_API_KEY
          valueFrom:
            secretKeyRef:
              name: llm-secrets
              key: api-key
        - name: DB_URL
          valueFrom:
            secretKeyRef:
              name: db-secrets
              key: jdbc-url
        resources:
          requests:
            memory: "512Mi"
            cpu: "250m"
          limits:
            memory: "1Gi"
            cpu: "500m"
        livenessProbe:
          httpGet:
            path: /health
            port: 8080
          initialDelaySeconds: 60
          periodSeconds: 30
        readinessProbe:
          httpGet:
            path: /ready
            port: 8080
          initialDelaySeconds: 30
          periodSeconds: 10
        volumeMounts:
        - name: otel-logs
          mountPath: /logs
      volumes:
      - name: otel-logs
        emptyDir: {}
      # No DinD - use external sandbox service
---
apiVersion: v1
kind: ConfigMap
metadata:
  name: quad-config
data:
  application.properties: |
    quad.capability.index.type=qdrant
    quad.session.store.type=jdbc
    quad.resilience.retry.max-attempts=3
    quad.hitl.approval-tools=executeBash,runSql
```

### 11.9 Health Checks & Monitoring

Comprehensive health endpoints for production observability.

```java
@Path("/health")
@ApplicationScoped
public class HealthResource {
    
    @GET
    public HealthResponse health() {
        return HealthResponse.up()
            .with("llm", testLlmConnection())
            .with("vectorStore", testVectorStore())
            .with("sandbox", testSandbox())
            .build();
    }
    
    @GET
    @Path("/metrics")
    public Map<String, Object> metrics() {
        return Map.of(
            "agent.sessions.active", sessionCount.get(),
            "agent.llm.calls.total", llmCallCounter.count(),
            "agent.tools.executed.total", toolCounter.count(),
            "agent.errors.total", errorCounter.count()
        );
    }
}
```

### 11.10 Quarkus Event Bus Integration (`@Observes` AgentEvent)

**P2 QUAD-parity + decoupled observability:** Every tool invocation, state change, and reasoning step emits a typed CDI event on the Quarkus Event Bus. Async consumers drive audit logging, SSE/WebSocket streaming to the UI, and real-time monitoring — without coupling the loop to any sink.

```java
// ── Typed event hierarchy ───────────────────────────────────────────
public sealed interface AgentEvent {
    record ToolStarted(String sessionId, String toolName, String argsJson) implements AgentEvent {}
    record ToolFinished(String sessionId, String toolName, String result, boolean isError, long durationMs) implements AgentEvent {}
    record StateChanged(String sessionId, String field, Object newValue) implements AgentEvent {}
    record LlmStep(String sessionId, int inputTokens, int outputTokens) implements AgentEvent {}
    record LoopFinished(String sessionId, String stopReason, long totalDurationMs) implements AgentEvent {}
}

// ── Emitted from the ReAct loop / GizmoInvoker ──────────────────────
@ApplicationScoped
public class AgentEventPublisher {

    @Inject Event<AgentEvent> eventBus;

    public void toolStarted(String sid, String tool, String args) {
        eventBus.fire(new AgentEvent.ToolStarted(sid, tool, maskSecrets(args)));
    }
    public void toolFinished(String sid, String tool, String result, boolean err, long ms) {
        eventBus.fire(new AgentEvent.ToolFinished(sid, tool, maskSecrets(result), err, ms));
    }
    public void stateChanged(String sid, String field, Object value) {
        eventBus.fire(new AgentEvent.StateChanged(sid, field, maskSecrets(value)));
    }
}

// ── Async consumers (decoupled, non-blocking) ───────────────────────
@ApplicationScoped
public class AuditTrailConsumer {
    @Inject AuditStore auditStore;

    @ObservesAsync AgentEvent.ToolFinished evt) {
        auditStore.record("tool", evt.sessionId(), evt.toolName(), evt.durationMs(), evt.isError());
    }
    @ObservesAsync AgentEvent.LoopFinished evt) {
        auditStore.record("loop", evt.sessionId(), evt.stopReason(), evt.totalDurationMs(), false);
    }
}

@ApplicationScoped
public class UiSseConsumer {
    @Inject SseBroadcaster sse;          // per-session WebSocket/SSE hub

    @ObservesAsync AgentEvent.StateChanged evt) {
        sse.push(evt.sessionId(), evt);  // live state to UI
    }
}

// ── OpenTelemetry bridge ────────────────────────────────────────────
@ApplicationScoped
public class TracingConsumer {
    @Inject Span span;                    // active agent.loop span

    @Observes AgentEvent.ToolStarted evt {
        span.addEvent("tool.start", Attributes.of("tool", evt.toolName()));
    }
    @Observes AgentEvent.ToolFinished evt {
        span.addEvent("tool.end", Attributes.of("tool", evt.toolName(),
            "duration.ms", evt.durationMs(), "error", evt.isError()));
    }
}
```

### 11.11 Saga Compensation Guardrails (`@SagaAgent`)

**P2 Consistency:** A ReAct loop that runs 3 of 5 tools then fails cannot be rescued by a database rollback alone — external side-effects (files on the RWX PVC, reservation calls, emails) are invisible to `tx.rollback()`. The correct model is a **Saga**: while a tool executes, it registers an explicit *compensation closure*; on failure the `@SagaAgent` interceptor unwinds the execution log **LIFO**. Compensations are closures that captured their exact arguments (`path`, `txId`) at execution time — no re-lookup of tools, no schema coupling.

**Saga execution log (per session, stored in `AgentSessionState`, §3.1):**

```java
// ── 1. Compensation action ───────────────────────────────────────────
@FunctionalInterface
public interface CompensatingAction {
    // NOT Serializable on purpose (P0 native-image rule): compensation closures
    // capture their arguments (path, txId) and live only in the in-memory sagaLog
    // of a single request. GraalVM native has no Java Lambda serialization support,
    // and SerializationFeature registration for them would be pointless complexity.
    void compensate(AgentSessionState state) throws Exception;
}

public record SagaStep(String toolName, String executionId, CompensatingAction compensation) {}

// Audit-only projection of a saga step — the ONLY thing that is ever serialized
// or persisted. Metadata (toolName + executionId) is a plain record; the closure
// itself never leaves the request's memory. Record serialization of the metadata
// works under native image (registered via quarkus.serialization.include-classes).
public record SagaAuditRecord(String sessionId, String toolName, String executionId)
        implements Serializable {}

// ── 2. AgentSessionState additions (full class in §3.1) ──────────────
private final Deque<SagaStep> sagaLog = new ArrayDeque<>();   // LIFO stack
private boolean sagaFailed = false;

public void registerCompensation(String toolName, CompensatingAction action) {
    sagaLog.push(new SagaStep(toolName, UUID.randomUUID().toString(), action));
}
public Deque<SagaStep> getSagaLog() { return sagaLog; }
public void markSagaFailed() { this.sagaFailed = true; }
public boolean isSagaFailed() { return sagaFailed; }
```

**Tools register their own compensation** at successful execution — the closure captures the exact resources to undo:

```java
@Tool("Creates a new configuration file on the RWX workspace")
public String createConfigFile(AgentSessionState state,
                               @Param("path") String path,
                               @Param("content") String content) {
    fileService.write(path, content);
    // Compensation: delete the file again
    state.registerCompensation("createConfigFile", s -> fileService.deleteIfExists(path));
    return "File " + path + " created successfully.";
}

@Tool("Reserves a customer quota in the system")
public String reserveQuota(AgentSessionState state,
                           @Param("customerId") String id,
                           @Param("amount") int amount) {
    String txId = dbClient.reserve(id, amount);
    // Compensation: cancel the reservation
    state.registerCompensation("reserveQuota", s -> dbClient.cancelReservation(txId));
    return "Quota reserved with TxID: " + txId;
}
```

**`@SagaAgent` interceptor** — catches an unhandled failure in the ReAct loop, unwinds the log LIFO, and emits one `AgentEvent` per compensation step for OTel + audit (§11.10):

```java
@InterceptorBinding
@Target({TYPE, METHOD})
@Retention(RUNTIME)
public @interface SagaAgent {}

@SagaAgent
@Interceptor
@Priority(Interceptor.Priority.APPLICATION + 10)
public class SagaAgentInterceptor {

    @Inject Event<AgentEvent> eventBus;
    @Inject SagaAwareConversationManager sagaContext;   // LLM resync (§11.7 interplay)
    @Inject SessionManager sessionManager;              // §11.1/§11.12 — marks the session FAILED

    @AroundInvoke
    public Object runInSaga(InvocationContext ctx) throws Exception {
        AgentSessionState state = findSessionState(ctx.getParameters());
        try {
            return ctx.proceed();                       // full ReAct loop
        } catch (Throwable t) {
            if (state != null) rollbackSaga(state, t);  // Saga rollback, then rethrow
            throw t;
        }
    }

    private void rollbackSaga(AgentSessionState state, Throwable cause) {
        state.markSagaFailed();
        Deque<SagaStep> log = state.getSagaLog();
        List<String> compensated = new ArrayList<>();
        while (!log.isEmpty()) {
            SagaStep step = log.pop();                  // LIFO: undo newest first
            try {
                step.compensation().compensate(state);
                compensated.add(step.toolName());
                eventBus.fire(new AgentEvent.StateChanged(state.getSessionId(),
                    "saga.compensated", step.toolName()));
            } catch (Exception e) {
                // Compensation failure → alerting, must NOT mask the original error
                eventBus.fire(new AgentEvent.ToolFinished(state.getSessionId(),
                    "compensation:" + step.toolName(),
                    "Saga Compensation failed: " + e.getMessage(), true, 0));
            }
        }
        sagaContext.handleSagaRollbackContext(state, compensated);  // LLM context resync
        sessionManager.markFailed(state.getSessionId());           // session status → FAILED
    }

    private AgentSessionState findSessionState(Object[] params) {
        for (Object p : params) if (p instanceof AgentSessionState s) return s;
        return null;
    }
}
```

**LLM context resynchronisation (the critical risk):** after compensation the LLM's prompt history still contains the *success* results of the now-undone tools — it would hallucinate about effects that no longer exist. `SagaAwareConversationManager` injects an explicit rollback notice at the tail of the context:

```java
@ApplicationScoped
public class SagaAwareConversationManager {

    // Session-keyed conversation store — the SAME ChatMemoryStore wired into the
    // AiServices ChatMemory (§3.9 StateDiffMemoryBridge / §11.7). `chatMemoryId`
    // == sessionId, so the rollback notice lands in THIS session's memory.
    private final ChatMemoryStore memoryStore;

    @Inject
    public SagaAwareConversationManager(ChatMemoryStore memoryStore) {
        this.memoryStore = memoryStore;
    }

    public void handleSagaRollbackContext(AgentSessionState state, List<String> compensated) {
        if (compensated.isEmpty()) return;
        // Atomic read-modify-write on the session's memory (same pattern and
        // serialization guarantee as the §3.9 StateDiffMemoryBridge).
        var history = new ArrayList<>(memoryStore.getMessages(state.getSessionId()));
        history.add(SystemMessage.from("""
            [CRITICAL SYSTEM NOTICE]: A system error occurred during execution.
            All side-effects from previous tools in this turn have been REVERSED via Saga Compensation:
            %s.
            Please re-evaluate the task from the last stable state and try an alternative approach.
            """.formatted(compensated)));
        memoryStore.updateMessages(state.getSessionId(), history);
    }
}
```

**Interplay with §11.7 (conversation memory):** the notice is a `SystemMessage` at the context tail, so `maybeAggregateSystem`/folding (§11.7) must never collapse it before the model has seen it — it is treated as *recent* system context and protected by the same keep-last-user-messages guard. The in-memory `AgentSessionState` is reset to its turn-start snapshot (captured by `AgentDocExtractor` before the loop); external effects are undone by the closures above.

**Idempotent retry (complementary, from §11.11 review round):** non-compensatable tools (email, external API) must NOT register compensation; instead they are `@Idempotent` + `IdempotencyStore`-deduped (§3.3 executor wrapper) so a retry or a second pod replays the recorded result instead of re-sending. After a compensation the dedup keys are deleted, so a later retry executes fresh.

**Native-mode note (P0):** compensation closures are kept in-memory per request and are **never serialized/persisted** — `CompensatingAction`/`SagaStep` deliberately do **not** implement `Serializable` (GraalVM has no lambda serialization). The durable, replayable record of every compensation is the `AgentEvent` audit trail (§11.10); the optional persisted snapshot uses the plain `SagaAuditRecord` metadata projection (toolName + executionId), registered for native serialization via `quarkus.serialization.include-classes`.

### 11.12 Distributed Lock Management (Multi-Instance)

**P2 Concurrency:** Persistent session stores shared across K8s pods can receive concurrent requests for the same `sessionId`. Wrap per-session execution in a distributed lock so only one pod processes a given session at a time. **Orphan-lock safety:** on HTTP timeout/abort the thread is interrupted; the lock must be released immediately — never left to a 30s Redis TTL.

**Lock-lease races (risk 1):** a *fixed* TTL (30 s) guarantees crash-recovery, but a legitimately long tool (bash build) that outlives the lease silently releases the lock → a second pod acquires it and runs concurrently with the first. Lock leasing therefore needs **heartbeats/auto-renewal**:

- **Primary — Quarkus `quarkus-redis-client` + Lua (P0 native-safe):** acquire is a single atomic `SET key token NX PX ttl`. The lease is owned via a **random owner token**; renewal and release are CAS-guarded Lua scripts (`only-if-token-matches`), so no pod can renew or release a lock it no longer owns. **Deliberately NOT Redisson** — Redisson relies on reflection, proxies, and bytecode generation, which is impractical in GraalVM native image; the plain-client version below is ~40 lines, trivially auditable, and fully native-compatible.
- **Renewal heartbeat (risk 1 mitigation):** a `LockHeartbeatTask` (ScheduledExecutorService) re-arms `PEXPIRE key token ttl` every `ttl / 3` while the owner token matches; it is cancelled *before* the CAS release in `finally` and exits on `Thread.currentThread().interrupt()`. Crash → last lease expires → lock is recoverable; long tool → renewed indefinitely → no TTL-race.
- **Lease-loss detection (risk 2):** after (re-)acquiring, the owner verifies it still holds the lock (fence-check) right before executing; if the lease was lost mid-tool (network partition), the `@SagaAgent` interceptor treats the step as failed and unwinds the compensation closures (§11.11) — the next pod's execution is then idempotent-safe.

```java
// P0 native-image: locking uses the Quarkus-managed quarkus-redis-client
// (RedisDataSource → Vert.x Redis), NOT Redisson. All operations are atomic
// Redis commands / EVAL scripts — no client-side reflection or bytecode.
@ApplicationScoped
public class SessionLockService {

    @Inject RedisDataSource redis;                 // quarkus-redis-client

    @ConfigProperty(name = "quad.lock.ttl-seconds", defaultValue = "30")
    int ttlSeconds;

    @ConfigProperty(name = "quad.lock.wait-seconds", defaultValue = "2")
    int waitSeconds;

    @ConfigProperty(name = "quad.lock.watchdog.enabled", defaultValue = "true")
    boolean renewalEnabled;

    // SET key token NX PX ttl — atomic compare-and-set, returns 1 iff acquired
    private static final String ACQUIRE =
        "return redis.call('SET', KEYS[1], ARGV[1], 'NX', 'PX', ARGV[2]) and 1 or 0";
    // CAS release — delete ONLY if we still own the lease (token match)
    private static final String RELEASE =
        "if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) else return 0 end";
    // CAS renew (heartbeat) — re-arm the lease only if we still own it
    private static final String RENEW =
        "if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('PEXPIRE', KEYS[1], ARGV[2]) else return 0 end";

    private ScheduledExecutorService heartbeats = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "quad-lock-heartbeat");
        t.setDaemon(true);
        return t;
    });

    public <T> T executeLocked(String sessionId, Supplier<T> action) {
        String key = "quad:lock:" + sessionId;
        String token = UUID.randomUUID().toString();        // unique owner token per attempt
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(waitSeconds);
        RedisConnection connection = redis.getConnection(); // MAIN thread's connection

        while (System.nanoTime() < deadline) {
            if (acquire(connection, key, token)) {          // atomic — no lost-update race
                LockHeartbeatTask heartbeatTask = null;
                ScheduledFuture<?> heartbeat = null;
                if (renewalEnabled) {
                    heartbeatTask = new LockHeartbeatTask(redis, key, token, ttlSeconds);
                    heartbeat = heartbeats.scheduleWithFixedDelay(
                        heartbeatTask, ttlSeconds / 3, ttlSeconds / 3, TimeUnit.SECONDS);
                }
                try {
                    // Abort BEFORE starting work if the HTTP request was cancelled
                    if (Thread.currentThread().isInterrupted()) {
                        throw new TaskCancelledException("Execution aborted prior to lock entry: " + sessionId);
                    }
                    return action.get();
                } finally {
                    // Stop renewing FIRST (heartbeat owns its own connection, closed
                    // here), then CAS-release our lease on the MAIN connection.
                    if (heartbeat != null) { heartbeat.cancel(true); heartbeatTask.close(); }
                    release(connection, key, token);
                }
            }
            try { Thread.sleep(25); }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();          // MUST restore interrupted flag
                throw new TaskCancelledException("Thread interrupted while waiting for session lock.", e);
            }
        }
        throw new ConcurrentSessionException("Session lock acquired by another pod: " + sessionId);
    }

    private boolean acquire(RedisConnection c, String key, String token) {
        Long ok = eval(c, ACQUIRE, key, token, ttlSeconds * 1000L);
        return ok != null && ok == 1L;
    }

    private void release(RedisConnection c, String key, String token) {
        eval(c, RELEASE, key, token);                        // no-op if we lost the lease
    }

    private Long eval(RedisConnection c, String script, String... args) {
        return c.send(Command.create(RedisCommand.of("EVAL")), argsOf(script, args))
                .map(r -> r == null ? null : r.toLong()).toCompletionStage().toCompletableFuture().get();
    }

    private List<String> argsOf(String script, String... args) { /* [script, 1, key, arg...] */ }
}

// Heartbeat renewal — re-arms the lease while we still own it (CAS Lua). The
// loop exits on interrupt() or when the lock is no longer ours (returns 0).
public class LockHeartbeatTask implements Runnable {
    // RedisConnection is NOT thread-safe, so the heartbeat NEVER shares the main
    // thread's connection: it opens its OWN connection from the same data source
    // (lazily on the scheduler thread) and closes it on cancel/interrupt.
    private final RedisDataSource redis;
    private final String key, token;
    private final long ttlSeconds;
    private RedisConnection connection;   // own connection — created on the heartbeat thread

    public LockHeartbeatTask(RedisDataSource redis, String key, String token, long ttlSeconds) {
        this.redis = redis;
        this.key = key;
        this.token = token;
        this.ttlSeconds = ttlSeconds;
    }

    @Override
    public void run() {
        if (Thread.currentThread().isInterrupted()) { close(); return; }
        if (connection == null) connection = redis.getConnection();   // dedicated per-task conn
        // eval(RENEW, key, token, ttlSeconds * 1000L) — only-if-token-matches
        Long renewed = evalRenew(connection);
        if (renewed != null && renewed == 0L) {
            // lease lost to a crash/timeout — the next fence-check or tool call fails
        }
    }

    // CAS renew on the TASK's own connection (same Lua as SessionLockService.RENEW)
    private Long evalRenew(RedisConnection c) {
        return c.send(Command.create(RedisCommand.of("EVAL")), argsOf(
            "if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('PEXPIRE', KEYS[1], ARGV[2]) else return 0 end",
            key, token, ttlSeconds * 1000L)).map(r -> r == null ? null : r.toLong())
            .toCompletionStage().toCompletableFuture().get();
    }

    private List<String> argsOf(String script, String... args) { /* [script, 1, key, arg...] */ }

    void close() {
        if (connection != null) { connection.close(); connection = null; }
    }
}
```

**Orphan rules:** every lock key carries an **owner token**; `unlock`/renew are CAS-guarded (Lua `if token matches then del`). A node crash or network loss therefore releases the lock by lease expiry — never by a stale pod — and a pod can never release a lock it no longer owns. Interrupt-triggered release in `finally` stays the fast path; the lease is only the crash-safety net, not the concurrency guarantee.

```java
// Cancellation-aware exception hierarchy
public class ConcurrentSessionException extends RuntimeException { ... }
public class TaskCancelledException extends RuntimeException { ... }

// Integration with SessionManager (strands-inspired interface):
public interface SessionManager {
    default <T> T executeLocked(String sessionId, Supplier<T> work) {
        return distributedLockService.executeLocked(sessionId, work);
    }

    // §11.11: after a saga rollback the session is durably marked FAILED so
    // clients/OTel see the terminal state. Default: load → status FAILED → save.
    // (Replaces the previously undefined `AgentStateStore.markFailed`.)
    default void markFailed(String sessionId) { /* load → AgentStatus.FAILED → save */ }
}

// Config
quad.lock.provider=redis
quarkus.redis.hosts=redis://redis:6379
quad.lock.ttl-seconds=30
quad.lock.wait-seconds=2
quad.lock.watchdog.enabled=true    # LockHeartbeatTask renewal (interval = ttl/3)
```

**Cancellation propagation (§11.12 + §6.2 + §3.3):** interrupt checks are enforced at every blocking boundary — `SessionLockService.executeLocked()` (before work starts), `DynamicToolProvider.provideTools()` (before selection), and `GizmoInvoker.execute()` (before tool invoke). A cancelled HTTP request therefore unblocks the lock within one check, instead of holding it until TTL expiry.

### 11.13 Multi-Tenant Guardrails & Vector Index Segregation

**P2 Isolation:** Tenant A must never discover or execute capabilities scoped to Tenant B. All capability searches, tool registration, and state stores are partitioned by `tenantId`.

```java
// Tenant context — propagated via CDI request context
@RequestScoped
public class TenantContext {
    private String tenantId;
    public static TenantContext current() { return CDI.current().select(TenantContext.class).get(); }
    public String tenantId() { return tenantId; }
}

// Tenant-scoped capability search
@ApplicationScoped
public class TenantAwareCapabilitySearch {

    @Inject TenantContext tenant;

    public List<ToolMethod> findRelevant(String query, int topK) {
        // Filter every search by tenant
        return index.search(tenant.tenantId(), query, topK);   // per-tenant partition
    }
}

// Qdrant — collection-per-tenant or payload filter
public class TenantQdrantIndex implements CapabilityIndex {
    @Override
    public void add(Capability cap) {
        store.add(TextSegment.from(cap.description(),
            Map.of("toolName", cap.name(), "tenantId", TenantContext.current().tenantId())));
    }

    @Override
    public List<CapabilityMatch> search(String tenantId, String query, int topK) {
        return store.findRelevant(query, topK)
            .matches().stream()
            .filter(m -> tenantId.equals(m.embedded().metadata().getString("tenantId")))  // hard filter
            .map(toMatch).toList();
    }
}

// Tool authorization at execution time (defense-in-depth):
@ApplicationScoped
public class TenantGuard {
    public void assertTenantTool(ToolMethod tool, String tenantId) {
        if (!tool.allowedTenants().contains(tenantId)) {
            throw new SecurityException("Tool not available for tenant " + tenantId);
        }
    }
}
```

### 11.14 Production Readiness Matrix

Consolidated status of every operational concern in this spec — JVM and GraalVM Native.

| Feature / Component | Implementation Strategy | Native Compatibility | Status |
|---------------------|-------------------------|----------------------|--------|
| **Concurrency Safety** | Stateless `@ApplicationScoped Agent` definition + per-session plain-POJO state via `SessionStateFactory`/`CurrentSession` (§3.1) | 🟢 High | 🟢 **Production Ready** |
| **Tool Execution** | Build-time Gizmo bytecode invokers (`QuadProcessor`) — no runtime reflection | 🟢 Full | 🟢 **Production Ready** |
| **Argument Deserialization** | Quarkus-managed Jackson `ObjectMapper` in `ToolArgsMapper` + lenient coercion (§3.3) | 🟢 Full (native hints) | 🟢 **Production Ready** |
| **Sandbox Environment** | Kata/gVisor warm microVM pods + shared RWX PVC, no privileged DinD (§10.2) | 🟢 Full | 🟢 **Production Ready** |
| **Context & Token Budgeting** | Token-bounded `AgentDocExtractor` + state-diff folding & snapshot in `SummarizingConversationManager` (§3.9, §11.7) | 🟢 Full | 🟢 **Production Ready** |
| **Tool Selection** | Two-stage: embedding pre-filter → LLM `ToolSelectionAgent` ranking with timeout fallback (§3.7, §3.10) | 🟢 Full | 🟢 **Production Ready** |
| **Tool Hot Reload** | Mutable registry + `tool_activator` + `isDynamic()` + context injection hooks (§3.11) | 🟢 Full | 🟢 **Production Ready** |
| **Sub-Agent Composition** | `SubAgentFactory` — isolated provider/session/memory per sub-agent (§3.12) | 🟢 Full | 🟢 **Production Ready** |
| **Runtime Resilience & Locks** | Interruptible loops + Quarkus Redis Lua lock (SET NX PX + owner-token CAS), release on abort (§11.12) | 🟢 Full | 🟢 **Production Ready** |
| **Multi-Tenancy** | `TenantContext` + per-tenant index partitions + `TenantGuard` enforcement (§11.13) | 🟢 Full | 🟢 **Production Ready** |
| **Observability** | OpenTelemetry spans (`agent.loop`, `tool.selection`, …) + Micrometer counters (§8) | 🟢 Full | 🟢 **Production Ready** |
| **Saga Compensation** | `@SagaAgent` + closure-based `sagaLog` in `AgentSessionState`, LIFO unwind, `AgentEvent` audit, LLM context resync (§11.11) | 🟢 Full | 🟢 **Production Ready** |
| **Lock Leasing** | Quarkus Redis Lua lock — `LockHeartbeatTask` renewal (ttl/3) + owner-token CAS + fence-check (§11.12) | 🟢 Full | 🟢 **Production Ready** |
| **Long-Running Processes** | Pool acquisition timeout/backpressure + stale-tool guard + loop cap (§11.15) | 🟢 Full | 🟢 **Production Ready** |

### 11.15 Long-Running Process Hardening

**P3 Robustness:** unbounded agent loops, pool saturation, and hot-reload during an in-flight tool chain are the classic long-running failures. Three countermeasures:

**(1) Loop termination & context sharpness.** A stuck agent re-invokes tools forever and the compaction (§11.7) only bounds tokens, not progress. Guardrails:

- `maxIterations` hard cap (default 15) on the ReAct loop; exceeding it emits a `SystemMessage` and returns the partial result with a `truncated=true` flag (not an error).
- **Stuck detection:** if the same `(tool, args-hash)` pair repeats 3× without an intervening user message, the loop is `STUCK` — the model is told to change strategy; the 4th repeat aborts the turn.
- Compaction lowers context *precision*; the `doc(state)` snapshot plus `keep-last-user-messages` (§11.7) bound that loss, and the compaction watermark event (§8 `agent.compact`) lets operators/audit know precision was traded.

**(2) Sandbox pool saturation.** All N warm workers busy → `executeBash` must never queue indefinitely behind a session lock. `SandboxPoolClient.acquire(timeout)` returns a lease or fails fast; the caller then either waits a bounded backoff and retries (max 3) or aborts the tool with a `SandboxUnavailable` result the LLM can reason about — the HTTP layer maps pool exhaustion to `503 + Retry-After` (§9.2). Per-worker exec timeout (already §10.2) is the inner bound; the pool acquire timeout is the outer bound, and both are shorter than the session-lock lease so a blocked sandbox never holds the distributed lock (§11.12) beyond the agent turn.

**(3) Stale tools mid-chain.** Hot-reload (§3.11) re-resolves the tool set on the *next* model call, so a tool removed while a multi-step chain is running would be unknown to the model mid-chain. Rules:

- `tool_activator` `remove()` is **deferred** while the tool has an in-flight execution for that session (in-flight guard in `QuadToolRegistry`); removal takes effect before the next model call.
- The `ToolSetChangeNotifier` already injects an explicit tool-set notice per turn, so the model is told `[tools updated: X removed, Y added]` before it plans the next step — no hallucinated tool calls.
- A step referencing a tool that vanished between model-call and execution fails with `ToolNotFound` and is surfaced in the model message, never silently skipped.

```java
@ConfigProperty(name = "quad.agent.max-iterations", defaultValue = "15")
int maxIterations;

@ConfigProperty(name = "quad.sandbox.pool.acquire-timeout-seconds", defaultValue = "5")
int sandboxAcquireTimeout;
```

---

## 12. Migration Checklist (QUAD Python → quad)

- [ ] Define `@GenerationMethod`, `@Tool`, `@Param`, `@Prompt`, `@Hidden` annotations
- [ ] Implement `Agent` (stateless definition) + `AgentSessionState` (per-session state)
- [ ] Build `AgentDocExtractor` with token budgeting (Gizmo-generated field getters for native mode)
- [ ] Implement `QuadToolRegistry` (strands-like tool registry)
- [ ] Create `ToolSpecificationBuilder` for auto-generating specs
- [ ] Define `BuiltInToolProvider` interface and `StandardToolProvider` implementation
- [ ] Implement `CapabilityIndex` interface with HNSW/Qdrant/PGVector implementations
- [ ] Build `CapabilitySearch` with caching (Caffeine) + `prefilter()` stage for LLM selection
- [ ] Create `CapabilitySearchRouter` orchestrating prefilter → LLM selection → registry
- [ ] Build `ToolSelectionAgent` (LLM sub-agent, §3.10) with structured `ToolSelection` output, timeout + embedding fallback
- [ ] Implement tool hot reload (§3.11): mutable `QuadToolRegistry`, `tool_activator`, `isDynamic()`, `ToolSetChangeNotifier` + `SkillActivationHook`
- [ ] Implement `SubAgentFactory` for isolated sub-agents (§3.12)
- [ ] Create `SandboxPoolClient` + dev-only `DockerSandboxService` fallback
- [ ] Wire `DynamicToolProvider` for LangChain4j AiServices
- [ ] Add OpenTelemetry tracing for loop, LLM, tools, sandbox
- [ ] Create Quarkus extension for build-time agent discovery
- [ ] Write example agents demonstrating patterns
- [ ] Document configuration properties
- [ ] Add integration tests with Testcontainers
- [ ] Implement `SessionManager` with JDBC/Redis support
- [ ] Add resilience patterns (Retry, Timeout, CircuitBreaker)
- [ ] Implement HITL approval workflows
- [ ] Add tool authorization RBAC system
- [ ] Configure PII redaction hooks
- [ ] Add health check endpoints
- [ ] Add conversation summarization
- [ ] Test with production Kubernetes deployment
- [ ] **P0:** Decouple agent definition from per-session `AgentSessionState` (no `@ApplicationScoped` mutable state)
- [ ] **P0:** Generate Gizmo build-time invokers for `@Tool` methods (GraalVM-native safe)
- [ ] **P0:** Replace privileged DinD with kata/gVisor microVM sandbox pool + shared RWX workspace
- [ ] **P1:** Add token budgeting to `AgentDocExtractor` state serialization
- [ ] **P1:** Fold state diffs + aggregate system messages in `SummarizingConversationManager` (strands-parity guards, user-block retention, `<state_delta>`, `doc(state)` snapshot)
- [ ] **P1:** Use Quarkus-managed Jackson `ObjectMapper` in `ToolArgsMapper` with lenient coercion for complex POJOs (GraalVM-native safe)
- [ ] **P2:** Emit typed `AgentEvent` objects on the Quarkus Event Bus (`@Observes`/`@ObservesAsync`)
- [ ] **P2:** Add state-mutation listeners for incremental state diffs (QUAD pass-by-reference parity)
- [ ] **P2:** Wrap loops in `@SagaAgent` compensation boundaries (closure-based `sagaLog` LIFO in `AgentSessionState`)
- [ ] **P2:** Add distributed per-session locks (Quarkus Redis Lua: `SET NX PX` + owner-token CAS) with `finally`-unlock + interrupt checks at lock/provider/invoker boundaries
- [ ] **P2:** Partition capability index + tools by `tenantId`
- [ ] **P3:** Add `SagaAgentInterceptor` + `CompensatingAction`/`SagaStep` closure log + `SagaAwareConversationManager` rollback notice (§11.11)
- [ ] **P3:** Enable lock renewal heartbeat (`LockHeartbeatTask`, ttl/3) + owner-token CAS unlock + fence-check (§11.12)
- [ ] **P3:** Add long-running guards: `max-iterations` cap, stuck-detection, `SandboxPoolClient.acquire(timeout)`, in-flight tool removal guard (§11.15)
- [ ] **P3:** `@Idempotent` + `IdempotencyStore` dedup for non-compensatable tools (at-least-once retry) (§11.11)

---

## 13. Future Extensions

| Feature                        | Description                                      |
|--------------------------------|--------------------------------------------------|
| **Long-Term Memory**           | `quad-memory` module with PGVector persistence   |
| **Human-in-the-Loop**          | `@RequiresApproval` annotation pauses loop       |
| **Structured Output**          | `@GenerationMethod(returnType = MySchema.class)` |
| **Agent Marketplace**          | Registry of shareable agent classes              |
| **MCP Tool Discovery**         | Auto-discover tools from MCP servers             |
| **Skill Directory Scanning**   | Hot-reload skills from filesystem (incremental index, not just activation) |
| **Plugin System**              | Extensibility via DI and `BuiltInToolProvider`   |

---

## 14. Priority Guide

> Priority labels (P0–P3) describe design importance, **not** implementation
> status. Current status and open items are tracked in the
> "Open Points" section at the end of this document.

| Priority | Feature / Fix | Section | Solution |
|----------|---------------|---------|----------|
| **P0** | Scope correction (state safety) | 3.1, 5 | Stateless `@ApplicationScoped` `Agent` definition + per-session plain-POJO state via `SessionStateFactory` (never `@RequestScoped` state bean) |
| **P0** | GraalVM native tool invocation | 3.3, 4.3 | Gizmo build-time invokers in `QuadProcessor`; no runtime reflection |
| **P0** | Sandbox isolation & latency | 10.2, 11.8 | Kata/gVisor microVMs + warm worker pool + shared RWX workspace (no privileged DinD) |
| **P1** | LLM tool selection | 3.7, 3.10 | `ToolSelectionAgent` sub-agent ranks embedding candidates; typo/enrichment correction; timeout → embedding fallback |
| **P1** | Context/token budgeting | 3.9, 11.7 | Token-bounded `AgentDocExtractor` + `SummarizingConversationManager` with strands-parity guards, user-block retention, `<state_delta>` folding, system-message aggregation, `doc(state)` snapshot |
| **P1** | Native-safe arg mapping | 3.3 | `ToolArgsMapper` via Quarkus-managed Jackson `ObjectMapper` with lenient coercion (stringified JSON, scalars vs POJOs) |
| **P2** | Tool hot reload | 3.11 | Mutable registry + `tool_activator` + `isDynamic()` + `ToolSetChangeNotifier`/`SkillActivationHook` context injection |
| **P2** | Sub-agent composition | 3.12 | `SubAgentFactory` builds isolated sub-agents (own provider/session/memory); per-agent tool scoping |
| **P2** | Quarkus Event Bus | 11.10 | Typed `AgentEvent` hierarchy fired via `Event<AgentEvent>`; async audit/UI/OTel consumers |
| **P2** | State diffing (QUAD parity) | 3.9 | `StateMutationListener` pushes incremental diffs to memory |
| **P2** | Saga compensation | 11.11 | `@SagaAgent` interceptor + closure-based `sagaLog` LIFO in `AgentSessionState`; `AgentEvent` audit per step; `SagaAwareConversationManager` rollback notice into context |
| **P2** | Distributed locks | 11.12 | Quarkus Redis Lua lock (`SET NX PX` + owner-token CAS) + `finally`-unlock on abort; interrupt checks at lock, provider, and invoker boundaries |
| **P2** | Multi-tenant isolation | 11.13 | `TenantContext` + tenant-filtered capability index + tool authorization |
| **P3** | Saga hardening | 11.11 | `@Idempotent` + `IdempotencyStore` dedup for non-compensatable tools; in-memory state reset to turn-start snapshot after unwind |
| **P3** | Lock leasing hardening | 11.12 | `LockHeartbeatTask` renewal (ttl/3) + owner-token CAS + fence-check (§11.12) |
| **P3** | Long-running process guards | 11.15 | `max-iterations` cap + stuck-detection; `SandboxPoolClient.acquire(timeout)` backpressure; in-flight tool removal guard |

---

## 15. References

- QUAD Python: https://github.com/NVIDIA-NeMo/labs-OO-Agents
- Strands Agents: https://github.com/stanfordnlp/strands-agents
- Strands Agents Quarkus: https://github.com/stanfordnlp/strands-agents-quarkus
- LangChain4j: https://github.com/langchain4j/langchain4j
- Quarkus LangChain4j: https://quarkus.io/guides/langchain4j
- Docker Java API: https://github.com/docker-java/docker-java
- OpenTelemetry Java: https://opentelemetry.io/docs/instrumentation/java/
- Caffeine Cache: https://github.com/ben-manes/caffeine

---

## 16. Open Points

Items in this spec that still diverge from the current codebase or are not yet
fully realized. Each is phrased as a verification/decision task.

1. **Build-time Gizmo invokers (§3.3, 4.3)** — the spec mandates build-time
   generation (`QuadProcessor`, no runtime reflection); the current
   `GizmoInvoker` in `quad-core/tool` is reflective. Decide whether
   native-image-safety still requires bytecode generation or whether the JDK
   `MethodHandles` / Quarkus reflection-registration route is sufficient.
2. **Production sandbox (§4.1, 11.8)** — only the dev Docker fallback
   (`quad-sandbox-docker`) is realized; the planned kata/gVisor microVM worker
   pool with a shared RWX workspace is still open.
3. **Capability search (§3.7, 3.10)** — the spec's `CapabilitySearchRouter` →
   `ToolSelectionAgent` pipeline predates the current
   prediction/selection-strategy implementation in `agent/strategies`
   (`PredictStrategy`, `ReflexionStrategy`, `TemplateStrategy`). Reconcile terms
   and flow.
4. **Memory & summarization (§3.9)** — verify `AgentDocExtractor` token
   budgeting and `SummarizingConversationManager`/`SagaAwareConversationManager`
   match the current `session/memory` managers.
5. **Distributed locking (§11.12)** — a Redis Lua
   `SessionLockService`/`LockHeartbeatTask` is specified; the current
   `SessionManager`/`executeLocked` approach and store wiring must be verified
   against §11.12 (lock leasing is listed as P3).
6. **Idempotency (§11.11)** — `IdempotencyStore` exists in the core runtime;
   confirm the `@Idempotent` + dedup path for non-compensatable tools is
   actually wired end-to-end.
7. **Prioritized Roadmap → status** — status was removed from §14 by design;
   decide where implementation status should live (e.g. referenced from
   `ARCHITECTURE.md §12 Open Points` or an issue tracker).