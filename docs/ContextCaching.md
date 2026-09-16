# quad Context Caching

> Architecture decision record for prefix-based prompt caching across workflow agents.
> Verified against the codebase on 2026-09-14.

## 1. Goal

Reuse a single, **static, byte-identical system prefix** (the shared "Block A") across
all agent nodes of a workflow run so the provider's prefix-cache mechanism serves the
same tokens repeatedly. This reduces latency and token cost for the shared template
content (role definitions, workspace rules, skills) instead of re-sending it on every
node call.

Scope: **workflow path only**. Single agents rely on a stable first user message /
conversation prefix and need no extra mechanism.

## 2. Provider mechanics (verified, theoretical)

- **Anthropic**: explicit `cache_control: {"type":"ephemeral"}` on system/tool blocks,
  explicit breakpoints, TTL 5 min, minimum cacheable size 1024 / 2048 / 4096 tokens.
- **OpenAI / Gemini**: automatic prefix caching, >1024 tokens (Gemini implicit 4096+),
  TTL 5-60 min. Cache key = exact token prefix + API key/org. No `cache_control`
  needed; the cache is keyed by the *identical prefix* of consecutive requests.
- Byte-exactness: static templates must be embedded as an identical prefix; only
  dynamic results are transported. `cache_control` is Anthropic-only.

## 3. Current state in the code (verified)

- `PromptAssembler.buildInitialMessages`
  (`quad-core/.../agent/PromptAssembler.java:70-128`) builds the system text as
  `[per-agent systemPrompt] -> [stateDoc] -> [workspace path] -> [granted dirs] ->
  [tools] -> [skills]`. The divergent part sits **at the top** today, which is the
  opposite of what prefix caching needs.
- `WorkflowExecutionContext`
  (`quad-quarkus/.../workflow/internal/WorkflowExecutionContext.java:22`) already
  carries `AgentSessionState sessionState`, created once per run in
  `WorkflowEngine.java:62` and threaded into every `NodeExecutor`. It is used by
  `InputBuilder`/`RunStateWriter` for findings and memory.
- `AgentNodeExecutor.execute` calls `agent.execute(prompt2)`
  (`AgentNodeExecutor.java:87/102/165`). The one-arg overload binds a **fresh** session
  state via `stateFactory.create(this)` (`Agent.java:284`,
  `SessionStateFactoryProducer.java:26-35`); the ctx session flows into the prompt
  text only, not into the bound agent state.
- Native `toolSpecifications` are appended to the request top-level
  (`AgentRuntime.java:254/262`). Verified on `ChatCompletionRequest`
  (langchain4j-open-ai 1.15.0, field order via `javap`): `model`,
  `messages`, `temperature`, … `tools`, `toolChoice`, … — i.e. the `tools`
  section is serialized **after** `messages`, so the system prefix tokens are
  untouched by tool content on OpenAI. (The earlier assumption that tool specs
  sit before the system message was wrong.)
- `ModelFactory` (`quad-core/.../config/ModelFactory.java:19-22`) registers only
  `OPENAI` and `OPENAI_COMPATIBLE` providers - no Anthropic provider exists, so
  `cache_control` is not usable in this stack today.

## 4. Design decisions ("solutions")

- **CachePrefix only in the workflow.** A new `cachePrefix` field on `Agent`
  (core) is embedded by `PromptAssembler` at the **first position** of the system
  message, only when set (default null = no behavior change). This covers static
  sub-agents automatically because they run through the same `AgentRuntime` /
  `PromptAssembler` path (`SubAgentTool.java:89`).
- **Sub-agents.** Static sub-agents inherit the prefix via the `Agent` field.
  `DynamicSubAgentTool` overrides `initialMessages()` and bypasses
  `PromptAssembler` (`DynamicSubAgentTool.java:217-226`), so it needs explicit
  prefix embedding in its system string.
- **Single agents.** Put the static context as the first user message; no extra
  mechanism needed.
- **Session binding unchanged.** `agent.execute(prompt2)` stays as is. Prefix
  caching is server-side, keyed by the request prefix + API key/org, **not** by the
  HTTP/transport session. The workflow already builds fresh model clients per node
  (`ChannelAgentFactory.java:155`), which does not affect caching.
- **Tool divergence — resolved.** Verified on `ChatCompletionRequest`
  (langchain4j-open-ai 1.15.0): `tools` serialize **after** `messages`; on
  Anthropic `system` and `tools` are separate top-level cache blocks. So tool
  divergence does **not** invalidate the cached system prefix on either provider
  — it only caps the cache span where it occurs. The user-facing target stays
  strictly: all agents sharing a `cachePrefix` expose an **identical** native
  tool list (same name, description, parameter schema, same order) so even the
  tool descriptions stay inside the cacheable prefix.
- **Workflow flag ("use prefix") + user responsibility.** A workflow opts in with
  `WorkflowDef.prefixCaching = true` (the marker); `WorkflowDef.cachePrefix`
  carries the shared Block-A content. `AgentNodeExecutor.configureAgent` then
  applies the prefix to every agent node and records the node's native tool
  fingerprint; a divergent node fails fast
  (`WorkflowExecutionContext.assertSharedToolFingerprint`). The user stays
  responsible for registering identical tools — the flag makes that contract
  explicit, and `CachePrefixGuard`/`CachePrefixGuardTest` (quad-core) make it
  verifiable instead of being a bare "declaration".

## 5. Open points

- Anthropic provider not registered yet; `cache_control` path is not usable today.
- Concrete byte-layout of Block A (which templates form the shared head) is not yet
  fixed; the layout decision (position 0) is fixed, the content is not.
- The workflow flag (§4) and the agent-scoped chain (§6) are implemented, including
  the workflow-level persistence and UI hooks (§7). Block-A content layout is still
  a user decision (per workflow/node).

## 6. Agent-scoped prefix chain (implemented)

The prefix is now **per agent node**, controlled by a `usePrefix` flag on each
node. The run context carries a mutable **current prefix** that advances as
nodes execute.

### Node semantics (agent nodes only)

- `usePrefix` (bool, default false): if true, the node's agent gets the run's
  current prefix at position 0 of its system message. If false (or unset), the
  node receives no prefix and the current prefix survives unchanged — a node can
  skip the prefix while a later node still receives it. When the workflow-level
  `prefixCaching` marker is true and no per-node `usePrefix` is set, the marker
  serves as the default (backward-compatible §4 behavior).
- `prefixSource` (optional, honored only with `usePrefix=true`): loads a new
  prefix and makes it the run's current prefix for all following nodes:
  - **inline JSON element** (bare string used as-is; any other JSON tree element
    serialized to text);
  - **URL** — `{"url": "https://..."}` or a bare `http(s)://` string,
    fetched at runtime (size-capped);
  - **file** — `{"file": "path"}` or a bare `file://` string; absolute paths
    are read as-is, relative paths are resolved against the session workspace
    (size-capped);
  - **step reference** — `{"step": "<nodeId>"}` or `{"self": true}` resolves to
    the referenced node's output (requires it to have executed already per DAG
    order; otherwise the source resolves to null = no prefix).
- `clearPrefix` (bool, default false): explicit deactivation later in the
  workflow — attaches nothing and clears the run's current prefix, so all
  following nodes have no prefix.
- `cachePrefix` (optional static text): a node may carry its own static prefix
  that overrides the chain prefix for that node.

### Resolution order

When a node with `usePrefix=true` does not carry `prefixSource`, the resolved
prefix falls back to node-local `cachePrefix` and then to the run's current
chain prefix. When a node carries `prefixSource`, the source is resolved first;
on null/blank the chain is unchanged and the node gets no prefix for its own
request.

### Propagation (sub-agents, §6B)

An agent with an active prefix passes it to its sub-agent tools:

- **Static sub-agents** (`SubAgentTool`): inherit via the parent `Agent` field
  when the sub-agent has no explicit prefix of its own.
- **Dynamic sub-agents** (`DynamicSubAgentTool`): inherit when
  `inheritCachePrefix` is true (default); opt out with
  `setInheritCachePrefix(false)`.

Both propagate automatically when `Agent.setCachePrefix` is called.

### Caching honesty

Only byte-identical, repeated prefix text is served by the provider cache.
Static sources (inline JSON, file content) cache across runs. URL/step-loaded
prefixes are per-run content: no cross-run cache hit, but they still produce a
stable context block at position 0 and cache inside a single agent's repeated
LLM calls (multi-turn ReAct).

Composable: `static prefix + "\n\n" + dynamic section` keeps the static head
cacheable while the dynamic part travels as context.

### Guard (per prefix group)

Nodes whose agents carry the same prefix text must expose identical native tools
(`CachePrefixGuard.toolFingerprint`). Nodes with a different or no prefix form
independent groups. A node's sub-agent tool sets may differ (they only inherit
the prefix text). The guard is recorded per prefix string in the run context.

### Ordering

A `prefixSource` step reference requires the referenced node to have executed
already (DAG order); otherwise it resolves to null = no prefix. Relative file
paths are resolved against the session workspace (not the filesystem root).

## 7. UI and persistence (implemented)

The workflow editor (`quad-ui`) exposes the node-level prefix configuration and
a workflow-level default input area, fully backed by persistence and the REST
layer.

### Workflow-level fields (workflow start / general settings)

A **"Shared Prefix Caching (Block A)"** panel sits above the `initialTask`
field in `WorkflowComposer`. It contains a toggle `prefixCaching` 
(`WorkflowDef.prefixCaching`, workflow-level default marker) and, when enabled,
a `cachePrefix` textarea (`WorkflowDef.cachePrefix` — Block-A static content).

### Node-level fields (node property panel)

When a node of type `agent` is selected, the properties panel adds a
**"Prefix (Block A)"** section:

| Field | UI | Maps to |
|---|---|---|
| `usePrefix` | select (Workflow default / On / Off) | `node.config.usePrefix` |
| `clearPrefix` | checkbox | `node.config.clearPrefix` |
| `cachePrefix` | textarea | `node.config.cachePrefix` — node-local override |
| `prefixSource` | type selector + value field | `node.config.prefixSource` |

The `prefixSource` type selector mirrors `PrefixSourceResolver`:
`inline` (stored as raw string), `url`/`file`/`step` (stored as 
`{"url"|"file"|"step": value}`), `self` (`{"self": true}`, no value field).
All node fields live inside the node's `config` map, which is already
serialized to JSON in the `nodes` column — no per-node migration.

### Persistence / REST round-trip

- Migration `V22__workflow_cache_prefix.sql` adds `cache_prefix` (text, nullable)
  and `prefix_caching` (boolean, default false) to the `workflows` table
  (shared `db/migration/common`).
- `PersistenceStore.upsertWorkflow` / `loadAllWorkflows` persist and restore
  both columns; `SharedState.putWorkflow` writes through to the DB.
- `ApiDtos.WorkflowCreateRequest` gained `prefixCaching` / `cachePrefix`;
  `AgentController` create/update/list/get and the duplicate endpoint carry
  both fields, as does `DynamicWorkflowResource.save`.
- `WorkflowTransferService` exports/imports both fields; the workflow schema
  (`quad.workflow.v1.schema.json`) allows the new properties, so
  prefix-bearing exports re-import cleanly.
- The UI sends the workflow-level fields in the create/update payload; the run
  engine reads them back from the persisted workflow definition, so a run
  started from the composer applies the prefix chain automatically.

## 8. History

- 2026-09-14: prefix-caching feasibility discussion; verified the session flow and
  the `PromptAssembler` layout; decided to scope the prefix to the workflow path and
  to defer the tool-spec concern.
- 2026-09-14 (2nd): verified the OpenAI request serialization order (`messages`
  before `tools`, via `javap` on `ChatCompletionRequest`), corrected the earlier
  claim, and replaced "ignore" with an enforced guard
  (`CachePrefixGuard` + `CachePrefixGuardTest`).
- 2026-09-15: implemented the workflow flag ("use prefix") model — opt-in via
  `WorkflowDef.prefixCaching`, guard wired into
  `AgentNodeExecutor.configureAgent` with fail-fast divergence detection on the
  run context; user stays responsible for identical tool registration.
- 2026-09-15 (2nd): added §6 concept — agent-scoped prefix
  chain (`usePrefix` per node, `prefixSource` JSON-element/URL/file/step,
  `clearPrefix`, sub-agent inheritance).
- 2026-09-15 (3rd): implemented §6 — per-node prefix chain with mutable run-level
  current prefix, `PrefixSourceResolver` (inline/URL/file/step), per-prefix-group
  guard in `WorkflowExecutionContext`, `DynamicSubAgentTool.inheritCachePrefix`
  flag and automatic propagation in `Agent.setCachePrefix` (static + dynamic
  sub-agents). UI plan added to §7.
- 2026-09-15 (4th): implemented the workflow-level persistence, REST and UI —
  migration V22 (`cache_prefix`, `prefix_caching`), `PersistenceStore`,
  `WorkflowCreateRequest`, `AgentController` create/update/list/get/duplicate,
  `DynamicWorkflowResource.save`, `WorkflowTransferService` export/import, schema
  allowlist, and the `WorkflowComposer` panels (workflow-level Block-A toggle +
  textarea, per-node prefix section with `usePrefix`/`clearPrefix`/`cachePrefix`/
  `prefixSource` type selector). §7 marked implemented.