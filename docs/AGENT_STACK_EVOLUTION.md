# Java-Agent-Stack vs. QUAD — Abgleich, Vorteile, Ersetzbarkeit, Evolutionspfad

Stand: 2026-10-10. Scope: `quad-core`, `quad-workflow-*`, `quad-quarkus`, `kafka-platform`.
Zweck: Die vorgeschlagene 3-Schichten-Java-Architektur (Durable Execution / Graph-Engine /
Framework) gegen das, was wir **heute** haben, abgleichen — mit vollständiger Aufstellung der
Vorteile, was ersetzt werden kann, und einem Evolutionspfad.

---

## 0. Kurzfazit

- Wir haben die **drei Schichten bereits**, aber als **eine proprietäre, horizontal integrierte
  Implementierung**: der kafka-Orchestrator ist die „durable/saga"-Schicht, `WorkflowEngine` +
  `AgentRuntime` die „Graph/Agentic"-Schicht, `quad-core` (LangChain4j) die Framework-Schicht.
- Der **größte strategische Hebel** ist **nicht** „alles durch Temporal/LangGraph4j ersetzen",
  sondern **die Definition standardisieren** (Open Workflow Specification / v2) und **genau eine
  Schicht** durch einen Standard zu ersetzen — dort, wo uns echte Fähigkeiten fehlen
  (**durable Replay über Neustarts**, **long-running Timers**).
- Ersetzungs-Kandidaten mit klarem Nutzen: **Temporal** (durable Orchestrierung um den
  kafka-Orchestrator herum) und **LangGraph4j** (Graph/HITL-Interrupts, falls wir auf Spring AI
  gehen). **Nicht** ersetzen: unsere ReAct-Runtime mit bounded Context, Skills, Sub-Agenten,
  Guardrails, MCP und die querschnittliche Persistenz — das ist der eigentliche Wert.

---

## 1. Die Referenzarchitektur (3 Schichten)

```
Durable Execution (Temporal Java / Dapr Workflow)   ← State, Event-Sourcing, Saga, Timeouts
        │  managed/ruft
Graph/Agentic Engine (LangGraph4j / Spring AI Graph) ← State-Nodes, Edges, HITL, Guardrails
        │  nutzt
Framework/Tooling (Spring AI / LangChain4j)          ← ChatClient, MCP-Tools, Schema-JSON
```

Die Zuordnung ist **nicht** deckungsgleich mit unserem Schnitt: unser Framework-Kern
(`quad-core`) enthält bereits Teile der Graph- und Durable-Schicht (Hooks, Saga, HITL,
Checkpoints). Das ist genau die Stelle, an der wir uns entscheiden müssen.

---

## 2. Unser System heute (Bestandsaufnahme, mit Fundstellen)

| Schicht (Referenz) | Bei uns vorhanden | Fundstelle |
|---|---|---|
| Framework: LLM/Abstraktion | LangChain4j `ChatModel`, `ToolSpecification`, `AiMessage`; `ModelFactory`, `TieredChatModel`, `StructuredOutputConfig` | `quad-core/config/*`, `quad-quarkus-core` |
| Framework: Tools/MCP | `@Tool`/`@Param`, `QuadToolRegistry`, `ReflectiveToolMethod`, `quad-tool-builtin`, `quad-tool-mcp`, `McpManagerService` | `quad-core/tool/*` |
| Framework: Agent-Runtime | `Agent`, `AgentBuilder`, `AgentConfig`, `Agents`, `AgentRuntime` (ReAct, bounded context, `StuckDetector`, `OutputForcer`) | `quad-core/agent/*` |
| Graph: Workflow-Engine | `WorkflowEngine`, `NodeExecutorRegistry`, Executors: Agent, Async (deferred), Conditional, Fork, Join, Loop, NestedWorkflow; `InputBuilder` (Templates), `JsonPathResolver` | `quad-workflow-engine/*` |
| Graph: DSL | `AgentWorkflowBuilder`, `WorkflowNode` (AgentStep/Loop/Conditional/Nested), `AgentWorkflowConfig` | `quad-workflow-core/workflow/*` |
| Graph: HITL | `HitlFacade`, `HitlService`, `/runs/{id}/continue`, `/api/ui/dynamic/complete` (Deferred), UI-Overlay | `quad-core`, `quad-quarkus/hitl` |
| Durable: Event/State | `WorkflowRunState`/`Snapshot`/`NodeResult`; neu: `run/event/*` (Event-Sourcing-Fundament); `run_checkpoints`; Kafka-DLQ, Idempotency | `quad-workflow-core`, `kafka-platform` |
| Durable: Orchestrierung | kafka-Orchestrator (DAG, Retries, HITL, Cancel, Checkpoints), `workflow_definitions` (JSONB), `workflow_executions`, `workflow_checkpoints` | `kafka-platform/agent-orchestrator` |
| Durable: Saga | `SagaAgent`, `CompensatingAction`, `SagaAuditRecord`, `SagaAgentInterceptor`, `IdempotencyStore` | `quad-core/saga`, `runtime` |
| Durable: Scheduling | `CronExpression`, `ScheduledTask`, `Schedule`, `Automation` | `quad-workflow-core/schedule`, UI |
| Framework: Skills | `SkillRegistry`, `SkillParser`, `AgentSkillsPlugin`, JIT `load_skill`/`skill_search` | `quad-core/capability/skill` |
| Framework: Sub-Agenten | `SubAgentTool`, `DynamicSubAgentTool`, `ChannelAgentFactory` | `quad-core/tool`, `quad-quarkus` |
| Cross-Cutting | Hooks/Guardrails, GDPR/PII, Permissions, Provenance, OTel/Micrometer, Journal | `quad-core/{hook,guardrails,gdpr,security,observability,events}` |
| Definition/Transfer | v1-Schema + Validator (alt); neu v2-Schema, Upcaster, PublishCompiler, SemanticValidator, HITL-Normalizer; `workflow_documents` (V25) | `quad-workflow-core/transfer`, `docs/WORKFLOW_FORMAT_PROPOSAL.md` |

---

## 3. Abgleich je Schicht

### 3.1 Durable Execution (Temporal / Dapr)

| Kriterium | Temporal Java SDK | Dapr Workflow | QUAD heute |
|---|---|---|---|
| Durable Replay über Neustart | **Ja** (deterministischer Replay der Workflow-Logik) | Ja (Durable Task, Replay) | **Teilweise**: Snapshots/Events + Idempotency + Kafka-Redelivery; **kein** Code-Replay |
| Long-running (Std./Tage) | **Ja**, Timers/Signals first-class | Ja | Schedules/Cron + `TimerChannel`; keine durablen Timer-Signale |
| Saga/Kompensation | **Ja** | Ja | `SagaAgent`/`CompensatingAction` vorhanden |
| HITL | Signals + `Workflow.await` | externe Events | Kafka `PAUSED_HITL` + Tickets + Continue-Endpoint |
| Betrieb | eigener Server/Cluster | Sidecar + Durable Task | **bereits vorhanden** (Kafka/Postgres), kein neuer Dienst |
| Sprache/Bindung | Java SDK + `temporal-spring-ai` | `dapr-spring-boot-starter` | Spring- **und** Quarkus-fähig (framework-neutraler Kern) |
| Beobachtbarkeit | Web-UI, History | Dapr Dashboard | OTel/Prometheus/Grafana, eigene Run-Sichten |

**Verdikt:** Temporal/Dapr sind klar stärker bei **Durability/Replay/Timern**. Unser Vorteil ist
**Null-Zusatzinfrastruktur** und die Kafka-native, multi-tenant Event-Verarbeitung.

### 3.2 Graph/Agentic Engine (LangGraph4j / Spring AI Graph)

| Kriterium | LangGraph4j | Spring AI Agentic Patterns | QUAD |
|---|---|---|---|
| Graph-Modell | `StateGraph`, Nodes/Edges, conditional edges, **reducers/channels** | Orchestrator-Worker, Routing, Chains | `WorkflowEngine` + `NodeExecutor` (typisiert), `AgentWorkflowBuilder` |
| HITL | **`interruptBefore` / `InterruptionMetadata` / `updateState`** (mechanistisch) | `AskUserQuestionTool` | Checkpoint + Continue + Deferred-Completion |
| Checkpointing | **`CheckpointSaver`** (In-Memory/JDBC) | — | `run_checkpoints`, `workflow_state` |
| LLM-Loop | baut auf LangChain4j **oder** Spring AI | Spring AI ChatClient | eigener `AgentRuntime` (ReAct, bounded, Stuck-Detection, Output-Forcing) |
| Parallel/Loop/Sub-Graph | ja | ja | Fork/Join/Loop/Nested (eigene Executors) |
| Reifegrad/Ökosystem | klein, aktiv (bsorrentino) | Spring-offiziell (Tzonov-Serie) | proprietär, im Einsatz |

**Verdikt:** LangGraph4j liefert **standardisierte, kompakte Graph-Primitive inkl. Reducern und
Interrupts**. Unser Vorteil ist die **produktive Tiefe** (Skills, Sub-Agenten, MCP, Guardrails,
Persistenz, Multi-Runtime). Der Austausch lohnt vor allem, wenn wir ohnehin auf Spring AI gehen.

### 3.3 Framework/Tooling (Spring AI / LangChain4j)

| Kriterium | Spring AI | LangChain4j | QUAD |
|---|---|---|---|
| LLM/Embedding/Vector | breit, Spring-first | breit, framework-neutral | LangChain4j direkt |
| Tools | `@Tool`, MCP, Advisors | `@Tool`, MCP | `@Tool`/`@Param`, Reflective-Bindung, MCP-Modul |
| Agentic Patterns | **Skills, Subagents/Task, TodoWrite, AskUserQuestion** | AiServices, Tools | Skills (JIT), SubAgentTools, HITL |
| Durable-Integration | **`temporal-spring-ai`** | — | eigener kafka-Worker |
| Runtime | Spring Boot | neutral | Quarkus **und** Spring (worker) |

**Verdikt:** Spring AI bringt **Patterns + Temporal-Bindung**; LangChain4j bleibt der
neutralere Kern. Unser Tool-/Skill-/MCP-Fundus ist bereits auf Enterprise-Niveau.

---

## 4. Vollständige Aufstellung: Baustein — Vorteil — Ersetzbar?

| Baustein (Java-Welt) | Kernvorteil | Unser Äquivalent | Ersetzen/Augmentieren? | Nutzen für uns |
|---|---|---|---|---|
| **Temporal Java SDK** | Deterministischer Replay, Timers, Saga, Versioning, UI | kafka-Orchestrator + `workflow_state`/Checkpoints | **Augmentieren**: Temporal **um** lange/ausfallsichere Workflows; Kafka bleibt Transport | Höchster Durability-Sprung; überlebt Neustarts/Netzausfälle ohne Datenverlust |
| **Dapr Workflow** | Durable Task, CNCF, Sidecar | wie oben | **Alternative** zu Temporal, wenn Sidecar/CNCF gewünscht | Geringere Einstiegshürde, aber Sidecar-Betrieb. `dapr-spring-boot-starter` ≥ 1.19 zielt auf **Spring Boot 4.0** (passt zu unserem kafka-platform 4.0.8); 3.5 ⇒ 1.18.x |
| **LangGraph4j** | `StateGraph`, Reducer/Channels, **Interrupts/HITL**, Checkpoint-Saver | `WorkflowEngine`/`AgentWorkflowBuilder` | **Augmentieren/Ersetzen** der Graph-Schicht (v.a. bei Spring AI) | Standard-Graph-API, weniger Eigenbau, sauberes HITL. **MIT**, 1.9.3 (Okt 2026), 1.8.x **LTS**, Java 17+, breites Saver-/Studio-/OTel-Ökosystem |
| **Spring AI Agentic Patterns** | Skills, Subagent/Task, TodoWrite, Routing | Skills/SubAgents (eigene) | **Ideen übernehmen** (TodoWrite, Task-Isolation) | Kontext-Isolation, weniger „Agent-Bloat". **Achtung:** Patterns kommen aus dem Community-Modul `org.springaicommunity:spring-ai-agent-utils` 0.4.x und setzen Spring AI **2.0-Milestones** voraus |
| **Spring AI (Framework)** | Spring-First, Advisors, Vector, Observability | LangChain4j + Quarkus | **Optional** für den kafka-Worker (Spring) | Konsistenz im Spring-Reactor |
| **LangChain4j** | Framework-neutral, breite Provider | bereits genutzt | **Behalten** | Basis von `quad-core` |
| **Open Workflow Specification (CNCF)** | Vendor-neutrales DSL + JSON-Schema + Java-SDK | v1/v2-Schema (eigen) | **Adoptieren** (v2 → OWS) | Interoperabilität, Tooling; `docs/WORKFLOW_FORMAT_PROPOSAL.md` |
| **Quarkus Flow** | OWS-konform, agentic via LangChain4j | quad-workflow-engine + Quarkus-App | **Prüfen/teilweise ersetzen** | Fertige OWS-Runtime statt Eigenbau |
| **BPMN/DMN** | Business-Modeling, Entscheidungstabellen | proprietäre Nodes | **Nur als Import/Export-Projektion** | Fachbereichs-Tooling, nicht internes Modell |
| **Embabel (Spring AI)** | Agent-Framework (Rod Johnson), Goal-orientiert | `AgentRuntime` | **Beobachten** | Alternative Agent-Orchestrierung |
| **MCP** | Standardisierte Tool-Grenze | `quad-tool-mcp` | **Behalten/Ausbauen** | Tool-Ökosystem ohne Neubau |

---

## 5. Was konkret ersetzt werden kann — und was nicht

**Ersetzen/augmentieren (klarer Nutzen):**
1. **Durable-Schicht** — kafka-Orchestrator-Interna (Retries, Checkpoints, Timeouts, Saga) sind
   Kandidaten für **Temporal** (oder Dapr). Kafka bleibt als **Transport** (`agent.tasks.*`,
   Results, DLQ); Temporal übernimmt die **Workflow-Historie/Replay**.
2. **Graph-Schicht** — unser `WorkflowEngine` + Executors durch **LangGraph4j `StateGraph`**, wenn
   wir auf Spring AI gehen. Gewinn: Reducer, Interrupts, Checkpoint-Saver statt Eigenbau.
3. **Definition** — `{nodes,edges}`/v1 durch **OWS** (v2-Vorschlag existiert bereits).
4. **HITL-Mechanik** — eigene Continue-Logik durch **LangGraph4j-Interrupts** bzw.
   **Temporal-Signals**.
5. **Kontext-Patterns** — **TodoWrite/Subagent-Isolation** aus Spring AI in unsere Sub-Agenten.

**Nicht ersetzen (echter Besitzstand):**
- `AgentRuntime` (bounded Context per Round, Stuck-Detector, Output-Forcer, Cache-Prefix-Kette).
- Tool-/Skill-/MCP-Fundus inkl. JIT-`load_skill` und dynamischer Tool-Aktivierung.
- Querschnitt: Guardrails, GDPR/PII, Permissions, Provenance, Audit, OTel.
- Framework-Neutralität (Quarkus **und** Spring) und die Zwei-Reaktoren-Trennung.

---

## 6. Die drei „Grenzen in Java" — bei uns konkret

| Grenze (aus der Einschätzung) | Bei uns | Empfehlung |
|---|---|---|
| **State-/Blob-Größe beim Checkpointing** | `workflow_state.step_results`, `node_states_json`, `run_checkpoints` (JSONB/TEXT) | Nur IDs + schlanke JSONs im State; große Artefakte in Objektstore/Vector-DB; `workflow_documents` (v2) als schlankes kanonisches Dokument |
| **Dynamische MCP-Tool-Schemata vs. Java-Typen** | `ReflectiveToolMethod` (JSON-Schema aus Signatur), `quad-tool-mcp` (dynamisch) | Jackson `JsonNode`/Records für MCP; Schema-Drift über v2-Semantic-Validator |
| **Idempotenz externer Tool-/MCP-Aufrufe** | `IdempotencyStore`, `@Idempotent`, `ToolExecutor`, Kafka-DLQ | Idempotency-Key pro Effekt; Saga-Kompensation; „at-least-once + idempotent" explizit dokumentieren |

---

## 7. Evolutionspfad (Stufen)

### Stufe 0 — Heute
Eigenbau, zwei Runtimes, reich an Fähigkeiten; Definition proprietär; Durability = Kafka + Checkpoints.

### Stufe 1 — Definition standardisieren (läuft)
v2-Schema + Upcaster/Publish/SemanticValidator + `workflow_documents` (siehe
`docs/WORKFLOW_FORMAT_PROPOSAL.md`). **Aufwand: klein, Risiko: klein, Nutzen: hoch**
(Interoperabilität, ein Wahrheitsformat).

### Stufe 2 — Graph/HITL vereinheitlichen
Entweder (a) `WorkflowEngine` behalten und **Interrupt/Reducer-Semantik** nachrüsten, oder
(b) **LangGraph4j** einführen (v.a. bei Spring-AI-Entscheidung). **Mittlerer Aufwand.**

### Stufe 3 — Durable Execution ergänzen
**Temporal Java SDK** (oder Dapr) als **Ummantelung** für lange/ausfallsichere Workflows.
Node-/Agent-Ausführung wird zur **Activity**; Kafka bleibt Transport; HITL via Signals.
**Höchster Nutzen, mittlerer Betriebsaufwand** (neuer Cluster) — nur für Workflows, die
über lange Zeit/Neustarts laufen müssen.

### Stufe 4 — Framework/Observability konsolidieren
Framework-Schicht bewusst wählen: **LangChain4j im neutralen Kern** behalten; für den
**Spring-Reactor** Spring AI + `temporal-spring-ai`; MCP als einzige Tool-Grenze; OTel/Micrometer
durchgängig. **Nutzen: Konsistenz, weniger Sonderwege.**

### Stufe 5 — Interop
OWS↔BPMN/DMN als Import/Export-Projektion; A2A/MCP für externe Agenten/Tools.

**Entscheidungsreihenfolge (empfohlen):** Stufe 1 abschließen → Stufe 2 (Graph/HITL) →
Stufe 3 nur bei echtem Long-Running-Bedarf → Stufe 4 konsolidieren.

---

## 8. Entscheidungsmatrix

| Wenn … | dann … |
|---|---|
| Workflows laufen über Stunden/Tage und müssen Neustarts überleben | **Temporal** (oder Dapr) als Durability-Schicht ergänzen |
| Wir wollen weniger Eigenbau im Graph/HITL | **LangGraph4j** (bei Spring AI) oder Interrupt-Semantik nachrüsten |
| Interoperabilität mit anderen Runtimes/Tooling nötig | **OWS** (v2) als kanonisches Format durchziehen |
| Der Spring-Reactor soll konsistent werden | **Spring AI** + `temporal-spring-ai` im worker |
| Alles läuft ausreichend stabil im kafka-Orchestrator | **Nichts ersetzen**, nur Stufe 1 + 2 |

---

## 9. Quellen (2026)

- LangGraph4j (Java): GitHub `langgraph4j/langgraph4j`; „Human-in-the-Loop at ease" (B. Sorrentino, dev.to/2025) — `StateGraph`, `CheckpointSaver`, `InterruptionMetadata`, `updateState`, `interruptBefore`, Integration LangChain4j/Spring AI.
- Spring AI Agentic Patterns (C. Tzolov, spring.io): #3 TodoWrite (2026‑01‑20), #4 **Subagent Orchestration / Task** (2026‑01‑27), Agent Skills (2026‑01‑13), AskUserQuestionTool (2026‑01‑16).
- Temporal: „Spring AI integration" (`io.temporal:temporal-spring-ai`, SDK ≥1.35, Spring Boot 3.x); „Build resilient Agentic AI with Temporal"; OpenAI Agents SDK + Temporal.
- Dapr: „Workflows with Java Spring Boot" (Diagrid, Dapr 1.16); Dapr vs. Temporal (2026).
- Open Workflow Specification (CNCF, v1.0.3) + Quarkus Flow (OWS-konform, LangChain4j) — siehe `docs/WORKFLOW_FORMAT_PROPOSAL.md`.
- Repo-Evidenz: siehe §2.

---

## 10. Verifikation, Korrekturen & Risiken (Review 2026-10-10)

### 10.1 Punkt-für-Punkt-Verifikation

| # | Behauptung im Dokument | Status | Beleg / Korrektur |
|---|---|---|---|
| 1 | LangGraph4j = Java-Port mit StateGraph/Interrupts | **bestätigt** | GitHub `langgraph4j`; `StateGraph`, `InterruptionMetadata`, `updateState`, `interruptBefore` |
| 2 | „klein, aktiv" (Ökosystem) | **korrigiert (zu schwach)** | **1.9.3 (01.10.2026)**, **1.8.x LTS**, **MIT**, Java 17+, Saver für Postgres/MySQL/Oracle/DynamoDB/Cockroach/Redis/Hazelcast/SQLite, **DSL**, **AG-UI**, **Studio** (Quarkus+Spring), **OTel-Hook** |
| 3 | Checkpointing/Reducer vorhanden | **bestätigt** | Checkpoints, Breakpoints (Pause/Resume), Channels/Reducer |
| 4 | HITL via Interrupts | **bestätigt** | `approvalOn(...)`, `InterruptionMetadata`, `updateState(...APPROVED/REJECTED)` |
| 5 | Spring AI liefert Subagents/Task „offiziell" | **korrigiert** | kommt aus **Community** `org.springaicommunity:spring-ai-agent-utils` **0.4.x**, benötigt Spring AI **2.0-M2/M5** (noch Milestone) |
| 6 | Temporal + Spring AI | **bestätigt** | `io.temporal:temporal-spring-ai`; behandelt Determinismus für durable Tools |
| 7 | Temporal benötigt deterministischen Workflow-Code | **bestätigt, präzisiert** | LLM/Tool/API/DB **müssen Activities** sein; `Date.now`/Random = häufige Fehlerquelle |
| 8 | Dapr Workflow Java | **bestätigt, präzisiert** | `dapr-spring-boot-starter` ≥1.19 ⇒ **Spring Boot 4.0** (passt zu uns); 3.5 ⇒ 1.18.x |
| 9 | Unsere 3 Schichten „vorhanden" | **bestätigt** | kafka-Orchestrator (durable), `WorkflowEngine`+7 Executors, `AgentRuntime` (LangChain4j) |
| 10 | Saga vorhanden | **präzisiert** | `@SagaAgent` (Annotation) + `SagaAgentInterceptor` + `CompensatingAction`/`SagaAuditRecord` (kein `class SagaAgent`) |
| 11 | Idempotenz | **bestätigt** | `IdempotencyStore`, `@Idempotent`, Kafka-DLQ |
| 12 | HITL/Channels/Deferred | **bestätigt** | `HITLPlugin/-Provider/-Authority`, `HitlFacade`, `Queue-/TimerChannel`, `AsyncNodeExecutor` |
| 13 | Context-Guards | **bestätigt** | `StuckDetector`, `OutputForcer`, bounded `SummarizingSlidingWindowConversationManager` |
| 14 | Skills JIT | **bestätigt** | `SkillLoadTool`, `SkillSearchTool`, `SkillActivationHook` |
| 15 | MCP-Modul | **präzisiert** | MCP-Client liegt in `quad-core/tool` (`McpToolClient`, `SseMcpProxy`, `McpToolMethod`) **plus** Modul `quad-tool-mcp` |
| 16 | Persistenz-Tabellen | **bestätigt** | `workflow_state`, `run_checkpoints`, `workflow_documents`, `sessions`, `skills`, `memories` |
| 17 | Versionen | **bestätigt** | LangChain4j **1.15.0**, kafka-platform **Spring Boot 4.0.8**, Java 21 |
| 18 | „Null-Zusatzinfrastruktur" bei uns | **bestätigt, relativiert** | stimmt betrieblich; dafür **kein** deterministischer Replay/Timers |

### 10.2 Was geht besser (konkrete Verbesserungen)

1. **Durability-Grenze ziehen statt Stack verdoppeln.** Genau **eine** Klasse von Workflows
   (lang laufend, neustart-überlebend, lange Timer) an Temporal/Dapr delegieren; kurze/kafka-native
   Workflows bleiben beim Orchestrator. Sonst **zwei Orchester** (Split-Brain).
2. **Interrupt-/Reducer-Semantik zuerst klauen, nicht die Abhängigkeit.** LangGraph4j-Konzepte
   (Channels/Reducer, `interruptBefore`, `updateState`) sind klein und lassen sich in
   `WorkflowEngine`/`HitlFacade` nachbauen → hoher Nutzen, niedriges Risiko, **ohne** Framework-Wechsel.
3. **Definition entkoppeln (Stufe 1) vor Engine-Tausch.** Erst OWS/v2 als Wahrheit; dann ist der
   Graph-/Durable-Austausch ein Adapterproblem statt einer Grundsatzentscheidung.
4. **Checkpoint-Serialisierung absichern.** Nur JSON + Schema, **keine** native Java-Deserialisierung
   (RCE-Klasse), Checkpoints signieren/validieren; PII/GDPR-Regeln auf Checkpoints anwenden.
5. **Payload-/State-Disziplin.** Nur IDs + schlanke JSONs in `workflow_state`/`run_checkpoints`;
   Artefakte in Objektstore/Vector-DB; Größen-Caps + Monitoring.
6. **Kontext-Isolation** aus den Spring-AI-Patterns übernehmen (Task-Subagent, TodoWrite) — unser
   `SubAgentTool` ist die Basis; ein „Todo"-Tool fehlt uns.
7. **Retry-Budgets differenzieren** (LLM teuer vs. Metadaten billig) — bei Aktivitäten explizit setzen.

### 10.3 Risiken (mit Gegenmaßnahmen)

| Risiko | Warum kritisch | Gegenmaßnahme |
|---|---|---|
| **Zwei Orchester (Kafka + Temporal/Dapr)** | Doppelte Retries/HITL, inkonsistente Historie, Split-Brain | Eine Ownership-Grenze pro Workflow-Typ; Kafka bleibt Transport, Temporal nur Orchestrierung |
| **Determinismus-/Replay-Fehler** | `Date.now`, Random, direkte Calls brechen Replay (bekannte Temporal-Schmerzstelle) | LLM/Tool/IO strikt als Activities; deterministische Workflow-Logik; Versioning-Disziplin |
| **Checkpoint-Deserialisierung (RCE)** | LangGraph-CVE-Cluster (SQLi→RCE) zeigt die Klasse | JSON-only, kein Java-Native-Deser, Signatur, Schema-Validierung |
| **PII/GDPR in Checkpoints** | Rohe Prompts/Tool-Payloads in DB | Unsere PII-Anonymisierung/Audit auch auf Checkpoints anwenden; Retention |
| **Operative Komplexität** | Temporal-Server (PG/Cassandra) bzw. Dapr-Sidecars/Scheduler (bekannte Start-Job-Bugs) | Managed/Cloud oder Dapr nur wo Sidecar akzeptabel; Betriebskonzept vorher |
| **Ökosystem-Churn** | Spring AI 2.0 = Milestones; `spring-ai-agent-utils` 0.4.x; LangGraph4j 2.0 angekündigt | Core (LangChain4j) neutral halten; Standards hinter `agentRef`/Interface kapseln |
| **Framework-Lock-in (Spring AI)** | Würde den Quarkus-Zweig gefährden | Spring AI nur im **Spring-Reactor** (worker); `quad-core` bleibt framework-neutral |
| **LLM-Kosten durch Retries** | Activities wiederholen teure Calls | Idempotenz + Retry-Budgets + „best-effort" je Aktivität |
| **Multi-Tenant-/Datenresidenz** | Temporal-Namespaces/Checkpoints = neue Datenhaltung | Tenant-Mapping, Namespace-Strategie, DSGVO-Folgenabschätzung |
| **Regressionsrisiko beim Engine-Tausch** | 491+ Tests, zwei Runtimes, laufende Pipelines | Gestufte Migration, Adapter, Parallelbetrieb mit Trockenläufen |

### 10.4 Angepasste Empfehlung

- **Sofort (klein/risikoarm):** Stufe 1 (OWS/v2) abschließen; **Interrupt-/Reducer-Semantik
  nachrüsten**; **Checkpoint-Sicherheit** härten; Payload-Disziplin; **Todo-Tool** + Subagent-Isolation.
- **Bedingt (mittel):** LangGraph4j **oder** Beibehaltung der Engine — Entscheidung erst nach Stufe 1
  und Framework-Wahl (Quarkus-first vs. Spring-first).
- **Nur bei hartem Bedarf (mittel/hoch):** Temporal/Dapr als **dünne** Durability-Kapsel mit strikter
  Owner-Grenze — nicht als zweites Orchester.

**Nettokorrektur zum ersten Entwurf:** LangGraph4j und Dapr sind **reifer** als im ersten Entwurf
dargestellt (MIT/LTS bzw. SB4-Support); die Spring-AI-*Patterns* sind dagegen **weniger** offiziell
(Community + 2.0-Milestones). Der Hauptrisikofaktor bleibt **nicht** die Technologie, sondern die
**Verdopplung der Orchestrierung** und die **Checkpoint-Sicherheit**.
