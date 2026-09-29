# Optional Improvements — Learnings from service-builder/java-spring/ai-agent-service

This document consolidates what the quad agent platform can learn from the Spring AI reference
project `service-builder/java-spring/ai-agent-service`. The comparison is scoped to **agent
features only** (workflows are explicitly out of scope — quad's workflow engine is strictly more
powerful and is taken as a given).

It contains three parts:

1. **Improvement candidates ordered by net value** (value × frequency ÷ effort), each with source
   reference, benefit, effort, and a concrete adoption plan for quad.
2. **Relative variability comparison** — how flexibly each agent feature is definable per agent
   in quad vs. the reference project.
3. **Structure lessons and explicit non-goals.**

---

## 1. Improvement candidates, ordered by net value

| # | Improvement | Effort | Value |
|---|-------------|--------|-------|
| 1 | Cost tracking + budget brake | S | ★★★★★ |
| 2 | Eval harness + LLM judge | L | ★★★★★ |
| 3 | Semantic cache | S | ★★★★ |
| 4 | Agent versioning (snapshots, diff, rollback) | M | ★★★★ |
| 5 | Promotion pipeline DRAFT→TEST→PRODUCTION | M | ★★★★ |
| 6 | Context compaction (window + tiered) | M | ★★★ |
| 7 | Long-term memory tool | M | ★★★ |
| 8 | Rate-limit filter | S | ★★★ |
| 9 | Trace-id filter (MDC + header) | S | ★★★ |
| 10 | Feedback loop | S–M | ★★ |
| 11 | Multi-tenant API-key auth | M | ★★ |
| 12 | Agent streaming via SSE | M | ★★ |

### 1.1 Cost tracking + budget brake
- **What:** Per-request cost limit aborts the ReAct loop; monthly budget; per-model price table;
  `usageCount`/`avgCostPerRun` folded back into the agent definition; cost endpoints + daily totals.
- **Source:** `service/CostTracker.java`, `CostController.java`, `AiAgentService.java:253-262`
  (loop abort), `AgentConfig.java:55-57`, `AgentRegistryService.recordUsage` (302-316).
- **Benefit:** Operational cost control (LLM calls are direct money); stops runaway runs caused by
  faulty agents/workflows. Highest immediate value.
- **Effort:** S. Standalone.
- **Adoption in quad:** pricing/budget module in `quad-core` (enum + config); persistence columns
  `usage_count`, `avg_cost_per_run` in `PersistenceStore` + migration; abort check in
  `workflow/node/executor/AgentNodeExecutor.java` and `core/agent/runtime/AgentRuntime.java:210`
  (before the next LLM call); REST endpoints analogous to `CostController`; per-step accounting via
  the existing `AgentStepExporter`.

### 1.2 Eval harness + LLM judge
- **What:** JSONL datasets (examples/golden); keyword and LLM-as-judge modes; auto-regression;
  trace-eval loop (recorded runs → samples → replay); `EvaluatorAgentService` with criteria,
  weights, and a regeneration loop.
- **Source:** `service/EvaluationService.java`, `evalloop/`, `orchestration/evaluator/`,
  `tool/JudgeTool.java`, `JudgeChecker.java`.
- **Benefit:** Biggest strategic lever: repeatable quality regression, foundation for the promotion
  gates (#5), and evidence for determinism claims. quad already has the best raw material —
  `TelemetryStore`, `AgentStepExporter`, and deterministic tests.
- **Effort:** L (weeks). Recommended path: first a run→sample recorder on top of the existing
  persistence, then a keyword gate, then a judge agent implemented as a regular quad agent.
- **Adoption in quad:** new `quad-quarkus/.../evals/` package; recorder behind `AgentEventPublisher`;
  `JdbcJournal`/`TelemetryStore` as the trace source; judge modelled as an `AgentDefinition` with a
  `jsonOutputSchema`.

### 1.3 Semantic cache
- **What:** LRU + TTL, normalized key (query + model), cost = 0 on hits; **only on the no-tools
  path** (never cache tool loops — correctness).
- **Source:** `service/SemanticCacheService.java` (43-97); deliberate in `AiAgentService.java:184-186`.
- **Benefit:** Cost/latency reduction for recurring requests (FAQ-style workflows/channels).
- **Effort:** S. Standalone.
- **Adoption in quad:** cache service in `quad-core` (or a CDI bean) with `ConcurrentHashMap` LRU +
  configurable TTL; hook in `AgentRuntime` before the LLM call (only when no tools are in the path);
  reuse the reference normalization; `quad.cache.*` properties.

### 1.4 Agent versioning (snapshots, diff, rollback)
- **What:** Snapshot of the current definition before every update; revision counter survives
  restarts; flat field diff (old → new); rollback to an earlier revision; lookup by stable `agentId`.
- **Source:** `agent/AgentRegistryService.java:121-242,268-279`.
- **Benefit:** Operational safety: changes are traceable/reversible; accidental misconfiguration can
  be undone; foundation for #5.
- **Effort:** M. Reuses quad's DB persistence (`PersistenceStore`) instead of the file system
  (the reference uses `~/.ai-agent-definitions`; quad should stay on the DB).
- **Adoption in quad:** `PersistenceStore` + `agent_versions` table (snapshot JSON, revision);
  endpoints `GET/POST /api/ui/agents/{id}/versions|diff|rollback`; revision surfaced in `AgentSummary`.

### 1.5 Promotion pipeline DRAFT→TEST→PRODUCTION
- **What:** Stage model per definition with a release gate: SecurityGate (allowedTools/
  allowedSubAgents consistent) + Judge gate (auto-regression pass rate ≥ threshold); dry-run
  `checkGate`; HITL escalation.
- **Source:** `agent/promotion/AgentLifecycleService.java:83-206`, `PromotionController.java`.
- **Benefit:** Disciplined agent rollout; without #2 only the SecurityGate is usable, with #2 the
  full model. quad can bridge with its deterministic/E2E test runner as the gate.
- **Effort:** M (+ dependency on #2 for the judge gate).
- **Adoption in quad:** `quad-quarkus/.../agent/lifecycle/` package, `PromotionState` in DB, gate
  check reusing the existing determinism/E2E tests.

### 1.6 Context compaction
- **What:** Semantic sliding-window compaction (prune the middle, keep last N user messages,
  savings ratio); optionally tiered compaction (NONE/SUMMARY/DIGEST).
- **Source:** `service/ContextManager.java`, `service/CompactionService.java`, `tieredcompaction/`.
- **Benefit:** Longer histories without token explosion; more stable costs (couples with #1/#3).
- **Effort:** M.
- **Adoption in quad:** extend `core/session/memory/SessionMemory.java` (prune strategy + config);
  realize summarising compaction as a hook on top of the existing `AgentRuntime` summarization
  (`AgentRuntime.java:393`) instead of inventing a second mechanism.

### 1.7 Long-term memory tool
- **What:** A tool exposing mem write/read/search/list/delete across sessions plus prompt additions.
- **Source:** `tool/MemoryTool.java` (Redis), `service/AgentMemoryService.java`.
- **Benefit:** Experience/cross-session memory for agents and workflows.
- **Effort:** M.
- **Adoption in quad:** register the tool in `StandardToolProvider`; backend on the
  `JdbcSkillStore` DB schema (instead of Redis — quad runs SQLite/Postgres); config-gated like skills.
- **Note:** Can reuse the HITL/step persistence infrastructure.

### 1.8 Rate-limit filter
- **What:** Token bucket per client IP on chat/eval endpoints, JSON 429.
- **Source:** `config/RateLimitFilter.java`.
- **Benefit:** Protection against LLM cost abuse on public endpoints.
- **Effort:** S (one class + config analogous to `SecurityEnforcementFilter`).
- **Adoption in quad:** `quad-quarkus/.../security/RateLimitFilter.java`, JAX-RS filter
  (`@PreMatching`), `quad.security.rate-limit.*` properties.

### 1.9 Trace-id filter (MDC + header)
- **What:** Per request, a `traceId` into the SLF4J MDC (echoing `X-Trace-Id`) so logs are
  correlateable end-to-end; quad currently has OTel but fragmented log traceability across
  classes/threads.
- **Source:** `config/TraceFilter.java`.
- **Benefit:** Debugging agent/workflow runs across many classes and threads.
- **Effort:** S.
- **Adoption in quad:** JAX-RS filter, read/set `X-Trace-Id`, MDC put/clear in `finally`.

### 1.10 Feedback loop
- **What:** Per-response/per-session user rating, correlated with the definition.
- **Source:** `service/FeedbackService.java`, `FeedbackController.java`.
- **Benefit:** Improvement data for #2/#5 (feedback-driven promotion).
- **Effort:** S–M; scales with #2.
- **Adoption in quad:** `feedback` table in `PersistenceStore`, feedback REST, shown in the run view.

### 1.11 Multi-tenant API-key auth
- **What:** `TenantManager` with keys/`enabled` flag; tenant id in the request context.
- **Source:** `config/ApiKeyFilter.java`, `service/TenantManager.java`.
- **Benefit:** Only relevant for SaaS/multi-tenant operation; quad already carries a `tenantId`
  concept in messaging (topic → tenant).
- **Effort:** M (id cache + filter + isolation).
- **Adoption in quad:** optional — extend `SecurityEnforcementFilter` or add a separate filter; only
  if multi-tenancy is actually planned.

### 1.12 Agent streaming via SSE
- **What:** SSE event stream (llm/tool/done/sub-agent/eval) instead of monitoring polling.
- **Source:** `service/ReActEventService.java`, `LogStreamController.java`, `streamChatV2` (SSE emitter).
- **Benefit:** UX + live observation; quad currently has SSE only for HITL checkpoints.
- **Effort:** M.
- **Adoption in quad:** `AgentEventPublisher` → SSE bridge (analogous to `SSEChannel`), event types
  llm/tool/step/done; use the run id as the channel key for workflow parallelism.

---

## 2. Relative feature variability (per agent definition)

How flexibly each agent feature is definable per agent at the REST/UI/DB level.

| Feature | quad `ApiDtos.AgentDefinition` | ai-agent-service `AgentDefinition` |
|---|---|---|
| Base data | id, name, description, category | + version, tags ✓ |
| **Model** | `model` field is **persisted but not applied** (only via `chatParameters.modelName`, otherwise global `TieredChatModel`) ✗ | `ModelConfig.id` per definition ✓ |
| Sampling | temperature, maxTokens, topP — applied ✓ | temperature, maxTokens ✓ |
| Tools | `tools` = allow-whitelist (standard + MCP, filtered) ✓ | include **and** exclude separated ✓ |
| Tool blocklist (hard) | no separate `blockedTools` ✗ | `SecurityConfig.allowedTools` ✓ |
| MCP server selection | global (`McpManagerService`), no per-def field ✗ | `ToolConfig.mcpServers` per definition ✓ |
| **Skills** | global (config/DB + whitelist union), no per-def field ✗ | `ToolConfig.skills` per definition ✓ |
| System prompt | `systemPrompt` ✓ | `systemPrompt` ✓ |
| **Input template** | `userMessageTemplate` ✓ (reference has none) | — |
| Structured output | `jsonOutput` + `jsonOutputSchema` (JSON schema, **enforced** via `OutputForcer`) ✓✓ | enabled/type/instruction/schema, but **prompt-only** (no response format) |
| Guardrails | `guardrailsInput`/`guardrailsOutput` (named regex guardrails) per def ✓ | — (global flags only) |
| Hooks/plugins | `hooks` (plugin registry) per def ✓ | — |
| Agent type | `agentType` (ua/capability/demo → class) ✓ | — |
| Chat-parameter JSON | `chatParameters` (open) ✓ | ModelConfig scalars |
| **Context window / compaction** | no per-def fields ✗ | `ContextConfig`: maxTokens + compaction ✓ |
| **ReAct iterations** | global only (`QuadConfig`/`setReActMaxIterations`), no def field ✗ | `LimitsConfig.maxReActIterations` ✓ |
| **Cost limit** | absent ✗ | `LimitsConfig.maxCostPerRequestUsd` ✓ |
| **Sub-agents** | not in def (programmatic `withSubAgent` only) ✗ | `subAgents` list with mapping/context policy ✓ |
| Security flags (PII/injection) | global (Gdpr/permission), PII not per def ✗ | per def (`SecurityConfig`) ✓ |
| Timeout | `timeoutSeconds` per def (workflow node execution) ✓ | only as `SubAgentConfig.timeoutSeconds` |
| Metadata/usage | — ✗ | usageCount, avgCostPerRun, createdBy ✓ |
| Stable reference | `id` ✓ | normalized `id`/`agentId` ✓ |

**Takeaway:** quad is **more flexible** where it has its own vocabulary — guardrails/hooks (plugin
system), `jsonOutputSchema` (truly enforced where the reference uses only a prompt instruction),
input template, `agentType`, timeout. quad is **distinctly less variable** on the typical
operational knobs: **model per agent is effectively not wired**, and per-definition selection is
missing for skills, MCP servers, tool **exclusion**, context/compaction, ReAct-iteration limit,
cost limit, sub-agents, blocklists, and usage metrics.

The most obvious **structural** cause: the reference uses an extensible `Feature-Map +
Options-Map` (`AgentConfig.java:42-58`) and a nested `AgentDefinition` record, while quad uses a
flat DTO with constructor accretion — every extension demands another constructor
(`ApiDtos.java:79-143`, as seen with the prefix-caching work). This is the subject of "Structure
lessons #2" below.

---

## 3. Structure lessons (low individual effort, broad effect)

1. **Capability packages over grab-bag packages.** The reference separates `config/`, `routing/`,
   `tieredcompaction/`, `actionguard/`, `evalloop/`, `agent/promotion/`. quad-quarkus `ui/` is a
   grab-bag (`AgentController`, `PersistenceStore`, `SharedState`, …). Break it up on the next
   refactor: `agents/`, `costs/`, `cache/`, `evals/`, `auth/`, `agent/lifecycle/`.
2. **Definition vs. runtime config.** The reference separates `AgentDefinition` (persistent, rich:
   model/tools/structuredOutput/context/security/limits/subAgents/metadata) → mapper →
   `AgentConfig` (feature map + options map, extensible without breaking record signatures,
   `AgentConfig.java:42-58`). quad changes DTOs and migrations on every extension (e.g. the
   prefix-caching work); an extensible options layer would remove that recurring cost. A concrete
   refactor: nested config blocks (`modelConfig`, `tools {includeTools|excludeTools|skills|
   mcpServers}`, `security`, `limits`) with the previously unused `model` field wired through.
3. **Feature flags as properties with no-op fallbacks.** The reference's `@ConditionalOnProperty`
   pattern is already matched by quad's `@IfBuildProperty` + config flags.

## 4. Explicitly NOT adopted

- **Tool approval / HITL:** quad is stronger (async checkpoints with SSE/email/Kafka via
  `HitlService`).
- **Durable sessions / crash recovery:** quad is stronger (`RunStore`/checkpoints/restore).
- **Multi-agent orchestration (`CoordinationService`):** covered by quad's workflow engine.
- **Skills:** quad is already better (file + DB store + whitelist union).
- **Model routing (`ModelRouterService`):** quad has the equivalent (`TieredChatModel`).
- **File-system persistence (`~/.ai-agent-definitions/`):** adopt the principles, not the storage —
  quad's DB persistence is more robust.