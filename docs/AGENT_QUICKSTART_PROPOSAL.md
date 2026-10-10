# Proposal: Simple, config-driven entry into `Agent` (Level 2)

Status: **implemented** (quad-core); verification in section 9.
Audience: quad-core maintainers and the two consumers — `quad-quarkus`
(Quarkus/CDI) and `kafka-platform/quad-workflow-worker` (Spring, no CDI).

## 0. Context: `quad-core` is a shared artifact across two reactors

```
agentic-platform/                      de.augmentia.platform:agentic-platform (aggregator)
├── quad-quarkus/                      Quarkus reactor (de.augmentia.quad)
│   └── quad-core                     ← Agent / AgentBuilder / QuadAgent
├── kafka-platform/                    Spring reactor (com.agentplatform)
│   └── quad-workflow-worker          consumes de.augmentia.quad:quad-core (NO CDI/Quarkus)
└── integration-tests/
```

The worker is forbidden (maven-enforcer) from depending on `quad-quarkus-core` or
`io.quarkus:*`. Any "simple entry" must therefore be **plain Java, config-driven,
per-task instantiable**, and the `Agent` API must stay **backward compatible**
because `quad-core` is consumed as a jar.

Level 1 (already done) delivered correctness fixes and `QuadAgent.create()`. This
document is Level 2: one canonical **config object** and **assembly** shared by both
consumers.

## 1. Findings from `kafka-platform`

`WorkerAgentFactory` (323 LOC) wires every agent by hand and **does not use
`AgentBuilder` at all**, because the builder did not fit:

| Worker need | What existed | Gap |
|---|---|---|
| Per-task fresh agent, no CDI | `new WorkerAgent()` + setters | ok |
| `CurrentSession` + `WorkspaceResolver` injection | `WorkerAgent.wire()` pokes **protected fields** | no `setCurrentSession` |
| Skills | re-implements `AgentBuilder.registerSkillPlugins` | logic duplicated |
| Config from DB/YAML | `WorkerProperties.AgentConfig` + `AgentConfigSource` | no canonical config in quad-core |
| systemPrompt / ReAct limit / structured output / chat params | manual setters + `defaultJsonSchema()` | builder lacked these |
| Built-in tools | `ToolCatalog` + name allow-list | no tool-object registration |

Additionally `AgentDefinitionBridge` (agent-common) converts the rich Quarkus
`QuarkusAgent` into the reduced `WorkerAgentConfig` and documents the **lossy**
fields (skills, hooks, permissions, MCP/sandbox). Two diverging config models plus a
hand-maintained bridge is the core maintainability problem.

## 2. Goal

1. One canonical config object: **`AgentConfig`** (in `quad-core`).
2. One reusable assembly path: **`AgentAssembler`** (skills etc.) used by
   `AgentBuilder` and callable from the worker.
3. `AgentBuilder` complete + instance-capable (`of(instance)`), so it fits the worker.
4. Fail fast when no model is configured.
5. Facade `Agents` for discoverability.
6. Everything additive — no breaking change for existing consumers.

## 3. `AgentConfig` (canonical, framework-neutral)

```java
AgentConfig cfg = AgentConfig.builder()
    .modelFromEnv()                       // or .model(chatModel)
    .systemPrompt("You are ...")
    .cachePrefix("...")
    .temperature(0.2).maxOutputTokens(1024)
    .maxReActIterations(10).contextMaxTokens(8_000)
    .structuredOutput(StructuredOutputConfig.dynamicSchema(schema))
    .allowedTools(Set.of("read_file", "write_file"))
    .skillDir(Path.of("skills")).initialSkills(List.of("code-review")).skillSearch(true)
    .workspace(Path.of(base))
    .build();

// Config-file driven:
AgentConfig json = AgentConfig.fromJson("""
  { "modelFromEnv": true,
    "systemPrompt": "...",
    "maxReActIterations": 10,
    "allowedTools": ["read_file"],
    "structuredOutputSchema": "{...}" }
  """);
```

Fields: `model`, `modelFromEnv`, `modelKey`, `systemPrompt`, `userMessageTemplate`,
`cachePrefix`, `temperature`, `maxOutputTokens`, `maxReActIterations`,
`contextMaxTokens`, `structuredOutput`, `allowedTools`, `skillDirs`,
`initialSkills`, `skillSearchEnabled`, `workspace`.
`toChatParameters()` derives LangChain4j `ChatRequestParameters`.

## 4. `AgentBuilder` (complete surface)

```java
Agent a1 = AgentBuilder.create(MyAgent.class).withConfig(cfg).build();   // subclass
Agent a2 = AgentBuilder.of(myAgentInstance).withConfig(cfg).build();     // instance, no reflection
Agent a3 = AgentBuilder.defaultAgent().withModelFromEnv().withToolObjects(tools).build();
```

Added (all opt-in): `withModel`, `withModelFromEnv` (alias of `withLlmFromEnv`),
`withSystemPrompt`, `withUserMessageTemplate`, `withCachePrefix`,
`withReActMaxIterations`, `withContextMaxTokens`, `withToolObjects(Object...)`,
`withGuardrail`, `withHook`, `withCurrentSession`, `withToolRegistry`,
`withEventPublisher`, `withHookRegistry`, `withToolExecutor`, `withWorkspaceResolver`,
`withConfig`, `of(instance)`.

## 5. `AgentAssembler` (shared assembly)

Public, static: `registerSkills(agent, registry, skillDirs, initialSkills, skillSearch)`.
`AgentBuilder` delegates to it; `WorkerAgentFactory` can call it instead of
duplicating the two-phase skill loading.

## 6. `QuadToolRegistry.registerFromObject(Object)`

Registers `@Tool` methods of any object — enables `withToolObjects(...)` and removes
the need to subclass an agent just to attach tools.

## 7. `Agent` — small additive changes

- `setCurrentSession(CurrentSession)` (removes the worker's field-poking).
- Fail fast: `requireLlm()` throws `IllegalStateException` instead of returning the
  literal `"[LLM not configured]"`.

## 8. `Agents` facade

```java
String answer = Agents.simple().run("...");            // = QuadAgent.create()
Agent a = Agents.builder(MyAgent.class).withConfig(cfg).build();
Agent b = Agents.of(new WeatherTools());               // tools without subclassing
Agent c = Agents.fromConfig(cfg);
```

## 9. Verification

- `mvn -o -DskipTests compile` (full quad-quarkus reactor): OK.
- `quad-core` agent tests: 58 tests, 0 failures.
- `kafka-platform/quad-workflow-worker` against the new `quad-core`: compiles, and
  45 worker unit tests pass — including `WorkerSkillLoadingTest`, which drives the
  ported `WorkerAgentFactory.create(...)` path.
- Full `kafka-platform` reactor `test` (Spring/Testcontainers E2E) was not run here.

## 10. Cross-project mapping (target)

| Source model | → `AgentConfig` |
|---|---|
| Quarkus `AgentDefinition` / `QuarkusAgent` (rich) | map all fields; skills/hooks become `skillDirs`/hooks |
| Kafka `WorkerProperties.AgentConfig` (reduced) | map directly; `allowedTools`→`allowedTools`, `skillsDir`→`skillDirs` |
| Kafka `WorkerAgentConfig` (bridge) | map directly; bridge becomes a thin adapter |

Both consumers then construct agents through the same `AgentBuilder.withConfig(...)`
path; `AgentDefinitionBridge` shrinks to a pure field mapper.

## 11. Implementation status

- [x] `AgentConfig` (+ builder + `fromJson` + `toChatParameters`)
- [x] `AgentAssembler.registerSkills`
- [x] `QuadToolRegistry.registerFromObject`
- [x] `AgentBuilder`: `of`, `withConfig`, `withToolRegistry`, `withEventPublisher`, `withHookRegistry`, `withToolExecutor`, `withWorkspaceResolver`, full method surface, tool objects, hooks/guardrails, current session
- [x] `Agent.setCurrentSession`, fail-fast `requireLlm`
- [x] `Agents` facade
- [x] Tests for the above (58 quad-core agent tests green; full reactor compiles)
- [x] kafka-platform `WorkerAgentFactory` + `AgentToolRegistryFactory` ported onto `AgentConfig`/`AgentBuilder`
      (worker compiles; 45 worker unit tests green, incl. `WorkerSkillLoadingTest`)

### 11.1 Port applied to `quad-workflow-worker`

The worker now builds agents through the shared path (`WorkerAgentFactory.create`):

```java
AgentConfig config = AgentConfig.builder()
        .systemPrompt(systemPrompt)
        .maxReActIterations(maxReActIterations)
        .temperature(agentConfig.temperature())
        .maxOutputTokens(agentConfig.maxOutputTokens())
        .structuredOutput(agentConfig.structuredOutputEnabled()
                ? StructuredOutputConfig.dynamicSchema(defaultJsonSchema()) : null)
        .build();

WorkerAgent built = AgentBuilder.of(agent)
        .withModel(model)
        .withToolRegistry(registryFactory.createCatalogOnly(agentConfig.allowedTools()))
        .withWorkspaceResolver(workspaceResolver)
        .withCurrentSession(currentSession)
        .withEventPublisher(events)
        .withHookRegistry(hooks)
        .withToolExecutor(new ToolExecutor(idempotencyStore, tracer, events, hooks))
        .withConfig(config)
        .build();
built.setContextManager(new ContextManager());
```

`AgentToolRegistryFactory.createCatalogOnly(...)` returns a catalog-only registry; the
builder registers the agent's own tools, avoiding duplicate registration. The worker's
mtime-cached skill loader is intentionally kept (it is not the same as
`AgentAssembler.registerSkills`, which re-parses per call).

## 12. Backward compatibility

Every change is additive. Existing `quad-workflow-worker` code (setters,
`WorkerAgent.wire`, `registerFromAgent`) keeps compiling; the port in section 10–11
is optional and can be done per module.
