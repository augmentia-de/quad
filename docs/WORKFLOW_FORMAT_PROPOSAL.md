# Proposal: Canonical Workflow Storage Format (v2)

Status: **proposal** — Slices 1–5 implemented (v2 schema + validator, v1→v2 upcast + publish, semantic validator + HITL normalization, DB Phase 0, run-event foundation). See §12.
Scope: workflow *definition* and *run state* storage across both reactors —
`quad-quarkus` (authoring, classic engine, DB) and `kafka-platform`
(execution DAG, orchestrator).

Related: `quad-workflow-core/src/main/resources/quad/schema/quad.workflow.v1.schema.json`,
`quad-ui/schemas/workflow.schema.json`, `misc/docs/workflow.md`,
`kafka-platform/agent-common/.../model/DagNode.java`.

---

## 1. Executive summary

Today there are **four parallel representations** of a workflow and **three
persistence locations**, connected by a hand-written, lossy bridge. Nodes are
**untyped** in the stored/exported format, definition **versioning is incomplete**
and run state does not pin the definition it executed.

Recommended target: **one canonical, typed, versioned definition document**
(a `quad.workflow` v2 schema aligned with the CNCF **Open Workflow
Specification**), with the flat `{nodes,edges}` view reduced to a **UI projection**,
and an **event-sourced run state** (append-only events + snapshots + HITL/audit)
that pins `definitionId + version + contentHash`.

**Decisions taken (2026-10-09):**

1. **OWS-strict.** Adopt the CNCF **Open Workflow Specification (v1.0.3)** as the
   canonical envelope/task model, including its own **runtime expression syntax**
   (not JSONata/JQ).
2. **HITL — both mechanisms.** Keep the explicit `hitl` task type *and*
   `requiresHumanApproval` as sugar; a publish-time compiler normalizes both to the
   same runtime state (`WAITING_HITL`). See §5.3.
3. **Overrides split.** Authoring-time overrides live in the definition
   (`nodeAgent.overrides`, included in `contentHash`); run-time overrides live in the
   run state as an `OVERRIDES_APPLIED` event and are pinned in the snapshot. See §5.3.
4. **SQLite hardening.** `TEXT` JSON columns get `CHECK(json_valid(...))` in addition
   to JSON-Schema validation on write. See §5.2.
5. **Agent pinning.** `agentRef` MUST carry a `contentHash`; runs pin the resolved
   agent configuration in the execution snapshot. See §5.2 / §6.

---

## 2. Current state (evidence)

### 2.1 quad-quarkus — authoring & classic engine

| Artefact | Shape | Location |
|---|---|---|
| DB `workflows` | `id, name, nodes TEXT, edges TEXT, initial_task` — nodes/edges are JSON arrays of **untyped maps** | `db/migration/common/V2__agents_workflows_runs.sql`, `V4` |
| UI contract | node `{id,type,title,status?,config?}`, edge `{id,source,target,input?,inputs?,joinPolicy?}`; `type ∈ agent/messaging-in/messaging-out/loop/conditional/nested-workflow/async/fork/join` | `quad-ui/schemas/workflow.schema.json` |
| Programmatic DSL | `WorkflowNode` (AgentStep, LoopNode, ConditionalNode, NestedWorkflowNode), `AgentWorkflowBuilder`, `AgentWorkflowConfig` | `quad-workflow-core/src/main/java/.../workflow/` |
| Transfer format **v1** | envelope `{format:"quad.workflow", schemaVersion:1, mainWorkflowId, workflows[], agents[]}`; node allows `additionalProperties:true`, only `id` required | `quad/schema/quad.workflow.v1.schema.json` |
| Validator | hand-written JSON-Schema subset (`type/properties/required/items/enum/…`) | `QuadWorkflowSchemaValidator.java` |
| Run state | `workflow_state (initial_data, step_results, executing_from, restart_count)`; `sessions`/`session_events` | `V14__workflow_rebuild.sql` |
| Agent definitions | `agents` (model, temperature, max_tokens, tools, guardrails, system_prompt, user_message_template, json_output, json_output_schema, chat_parameters, active, …) | `V2/V3/V13` |

**Drift:** `V14` drops all `dag_*` tables, yet `misc/docs/workflow.md` (§2.2) still
describes them as the active infrastructure.

### 2.2 kafka-platform — execution

| Artefact | Shape | Location |
|---|---|---|
| Flat Quarkus format | `QuarkusWorkflow {name, nodes[], edges[]}` | `bridge/QuarkusWorkflow.java` |
| Typed DAG | `WorkflowDefinition {id,name,version,isActive,nodes[]}`; `DagNode {stepId, nodeType(STEP/DECISION/LOOP/FORK/JOIN/END), agentType, dependsOn, branches, defaultTarget, joinType, joinBranches, joinN, maxIterations, exitCondition, timeoutMs, bodySteps, requiresHumanApproval}` | `common/model/DagNode.java`, `WorkflowDefinition.java` |
| Bridge (flat → DAG) | derives `dependsOn`/branch routing from edges, back-edges → LOOP, synthetic END | `bridge/WorkflowDefinitionBridge.java` |
| Semantics validator | `WorkflowDefinitionValidator` rules R1–R7 (acyclic except LOOP, single END, fork/join pairing, …) | `common/routing/WorkflowDefinitionValidator.java` |
| DB | `workflow_definitions (name, version, dag_structure JSONB, is_active, unique(name,version))`; `workflow_executions (input_params/final_output/error_details JSONB, idempotency_key)`; `workflow_checkpoints` | `agent-orchestrator/src/main/resources/db/migration/V1__init.sql` |

---

## 3. Problems

1. **Two sources of truth + lossy bridge.** Flat `{nodes,edges}` ⇄ typed DAG;
   `WorkflowDefinitionBridge` and `AgentDefinitionBridge` document their own
   losses (skills, hooks, permissions, MCP/sandbox).
2. **Untyped nodes.** v1 `additionalProperties:true`; quad stores raw maps. No
   enforced contract for node config.
3. **Incomplete definition versioning.** The v1 envelope has `schemaVersion`, the
   DB row does not; quad runs pin only `workflow_id`. Kafka is better
   (`name+version`, `is_active`) but a run still does not pin a content hash.
4. **Type-name divergence** (`conditional` vs `decision`, `nested-workflow` vs
   `subflow`) is mapped by convention, not schema.
5. **Ad-hoc data flow:** `{{nodeId.output}}`, `input`/`inputs`, `joinPolicy`,
   `fanInMerge` — no shared expression/mapping standard.
6. **Duplicate validation:** `QuadWorkflowSchemaValidator` **and**
   `WorkflowDefinitionValidator`.
7. **Storage details:** quad `TEXT` (portable, no DB constraint) vs kafka `JSONB`
   (better) — inconsistent guarantees.
8. **Agent/memory/permissions** split between node config and the `agents` table
   without a canonical reference model.

---

## 4. Best practices (researched, 2026)

- **Open Workflow Specification (OWS, CNCF, formerly Serverless Workflow), v1.0.3** —
  vendor-neutral YAML/JSON DSL with a published **JSON Schema** and a **Java SDK**;
  runtimes include Apache KIE SonataFlow, Synapse, Lemline, Zigflow.
  Source: `serverlessworkflow.io` / `open-workflow-specification`.
- **Agentic extension is in progress:** OWS issue **#1184** proposes an optional
  `agent` task (model, instruction, tools, output key, **memory**, provider). Not final.
- **Open Agent Specification (Agent Spec)** — declarative, framework-portable model
  for agents and agentic workflows (arXiv 2510.04173). Emerging, not a formal standard.
- **Quarkus Flow** — OWS-compliant Quarkus engine with **LangChain4j / agentic AI
  workflows**; matches this stack.
- **BPMN / DMN / CMMN** (Camunda/Flowable): strong for business processes and
  decision tables (**DMN**), but heavyweight for agent-native flows.
- **Durable execution** (Temporal, Restate, Inngest, Step Functions, Argo): separate
  **definition** from **durable, event-sourced run history**; deterministic replay,
  checkpointing, HITL gates, audit.
- **Schema evolution:** semantic versioning, **immutable published versions**,
  backward-compatible additions, schema registry.
- **Agent state:** definition ≠ run state ≠ memory; explicit tool/permission
  declaration; model+version per step; cost/token/approval audit.

> Caveats: OWS agent extension is a proposal; Open Agent Spec is a technical report.
> Sources are 2026 web research plus direct code/DB/schema reads in this repo.

---

## 5. Target architecture

Three separated layers.

### 5.1 Definition — one typed, versioned document
- **One** canonical format (JSON/YAML) with a published JSON Schema as source of
  truth. Flat `{nodes,edges}` becomes a **UI projection** only.
- Envelope and task model **follow OWS 1.0.3**; agent tasks use the OWS agent profile
  (or Open Agent Spec) plus a `quad`-namespaced extension, rather than ad-hoc `config` maps.
- **Strict typing:** discriminated `$defs/node` per `type`
  (`agent|decision|loop|fork|join|hitl|subworkflow|end`); `additionalProperties:false`.
- **One** expression/mapping standard: the **OWS runtime expression syntax**
  (`${ … }`, `$context`, `$input`, `$workflow`, …). *No* JSONata/JQ side-channel — OWS is
  not JSONata. Data flow is shaped via task `output`/`export` and read back through the
  context (e.g. `${ $context.extract.output }`).

### 5.2 Persistence — immutable, versioned, pinned
- Definition: `id/name/version`, **published versions immutable** (new version instead
  of update), `schema_version`, `content_hash`. Store as **JSONB** (Postgres); SQLite
  keeps `TEXT` with `CHECK(json_valid(document))` (JSON1, SQLite ≥ 3.38) **plus**
  JSON-Schema validation on write — `json_valid` checks syntax only, not the schema.
- Run pins `definition_version` **and `content_hash`** — reproducible.
- Agent definitions as referenced, versioned resources (or inline via the top-level
  `agents[]`), with explicit tools/permissions/model.
- **`agentRef` is content-addressed:** the publish compiler resolves `ref` → resolved
  agent config and fills the required `contentHash`; a run additionally pins the
  **resolved agent configuration** in its execution snapshot, so historical runs stay
  deterministic even if an external registry changes (§6).

### 5.3 State — event sourcing + checkpoints + HITL
- **Append-only run events** (`STARTED`/`COMPLETED`/`FAILED`/`WAITING_HITL`/`APPROVED`/
  `REJECTED`/`EXPIRED`/`ESCALATED`/`OVERRIDES_APPLIED`/…) as truth, plus snapshots for
  performance (the kafka `dag_runs`/`dag_run_events` pattern).
- **HITL — both mechanisms, one state machine.** The explicit `hitl` task and
  `requiresHumanApproval: true` on an agent task are normalized at publish time to the
  same runtime state (`WAITING_HITL`). Precedence is deterministic: an explicit `hitl`
  node wins; a `hitl` node and the flag never produce two gates in one step.
- **Overrides are state, not definition.** Run-time overrides (model/temperature/prompt
  for a single run) are recorded as an `OVERRIDES_APPLIED` event and pinned in the run
  snapshot; authoring-time overrides stay in the immutable definition (§6).
- **Checkpoint after every step** → resume/replay; approval persisted with
  approver/time/reason; audit includes model/tool/cost.
- **Migration:** `schemaVersion` + explicit upcasters; old runs never break.

---

## 6. v2 schema draft

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "https://quad.augmentia.de/schema/workflow/quad.workflow.v2.schema.json",
  "title": "QUAD Workflow Document v2",
  "description": "Canonical typed workflow definition shared by quad-quarkus and kafka-platform. Control flow is explicit on nodes; the flat {nodes,edges} UI view is a projection. Cross-references (dependsOn, branch targets, body) are checked by the semantic validator, not by JSON Schema.",
  "type": "object",
  "additionalProperties": false,
  "required": ["format", "schemaVersion", "mainWorkflow"],
  "properties": {
    "format": { "const": "quad.workflow" },
    "schemaVersion": { "const": 2 },
    "exportedAt": { "type": "string", "format": "date-time" },
    "contentHash": { "type": ["string", "null"] },
    "mainWorkflow": { "$ref": "#/$defs/workflow" },
    "subWorkflows": { "type": "array", "items": { "$ref": "#/$defs/workflow" } },
    "agents": { "type": "array", "items": { "$ref": "#/$defs/agent" } }
  },
  "$defs": {
    "workflow": {
      "type": "object",
      "additionalProperties": false,
      "required": ["id", "name", "version", "status", "nodes"],
      "properties": {
        "id": { "type": "string", "minLength": 1 },
        "name": { "type": "string", "minLength": 1 },
        "version": { "type": "string", "pattern": "^[0-9]+\\.[0-9]+\\.[0-9]+$" },
        "status": { "enum": ["draft", "published", "archived"] },
        "initialTask": { "type": ["string", "null"] },
        "cachePrefix": { "type": ["string", "null"] },
        "prefixCaching": { "type": ["boolean", "null"] },
        "inputs": { "type": ["object", "null"] },
        "createdAt": { "type": ["string", "null"], "format": "date-time" },
        "updatedAt": { "type": ["string", "null"], "format": "date-time" },
        "nodes": { "type": "array", "minItems": 1, "items": { "$ref": "#/$defs/node" } }
      }
    },
    "node": {
      "oneOf": [
        { "$ref": "#/$defs/nodeAgent" },
        { "$ref": "#/$defs/nodeDecision" },
        { "$ref": "#/$defs/nodeLoop" },
        { "$ref": "#/$defs/nodeFork" },
        { "$ref": "#/$defs/nodeJoin" },
        { "$ref": "#/$defs/nodeHitl" },
        { "$ref": "#/$defs/nodeSubWorkflow" },
        { "$ref": "#/$defs/nodeEnd" }
      ]
    },
    "nodeAgent": {
      "type": "object",
      "additionalProperties": false,
      "required": ["id", "type", "agent"],
      "properties": {
        "id": { "type": "string", "minLength": 1 },
        "type": { "const": "agent" },
        "title": { "type": "string" },
        "description": { "type": ["string", "null"] },
        "dependsOn": { "type": "array", "items": { "type": "string" } },
        "timeoutMs": { "type": ["integer", "null"], "minimum": 0 },
        "requiresHumanApproval": { "type": ["boolean", "null"] },
        "agent": { "$ref": "#/$defs/agentRef" },
        "overrides": {
          "type": "object",
          "additionalProperties": false,
          "description": "Authoring-time overrides of the referenced agent; part of the immutable definition and included in contentHash. Run-time overrides belong to the run state, not here.",
          "properties": {
            "model": { "type": "string" },
            "temperature": { "type": "number", "minimum": 0, "maximum": 2 },
            "systemPrompt": { "type": "string" }
          }
        },
        "input": { "type": ["object", "null"] },
        "output": { "type": ["object", "null"] }
      }
    },
    "nodeDecision": {
      "type": "object",
      "additionalProperties": false,
      "required": ["id", "type", "branches"],
      "properties": {
        "id": { "type": "string", "minLength": 1 },
        "type": { "const": "decision" },
        "title": { "type": "string" },
        "dependsOn": { "type": "array", "items": { "type": "string" } },
        "branches": {
          "type": "array",
          "minItems": 1,
          "items": {
            "type": "object",
            "additionalProperties": false,
            "required": ["target"],
            "properties": {
              "branchId": { "type": "string" },
              "condition": { "type": ["string", "null"] },
              "target": { "type": "string", "minLength": 1 }
            }
          }
        },
        "defaultTarget": { "type": ["string", "null"] }
      }
    },
    "nodeLoop": {
      "type": "object",
      "additionalProperties": false,
      "required": ["id", "type", "maxIterations", "exitCondition"],
      "properties": {
        "id": { "type": "string", "minLength": 1 },
        "type": { "const": "loop" },
        "title": { "type": "string" },
        "dependsOn": { "type": "array", "items": { "type": "string" } },
        "maxIterations": { "type": "integer", "minimum": 1 },
        "exitCondition": { "type": "string", "minLength": 1 },
        "bodySteps": { "type": "array", "items": { "type": "string" } }
      }
    },
    "nodeFork": {
      "type": "object",
      "additionalProperties": false,
      "required": ["id", "type", "branches"],
      "properties": {
        "id": { "type": "string", "minLength": 1 },
        "type": { "const": "fork" },
        "title": { "type": "string" },
        "dependsOn": { "type": "array", "items": { "type": "string" } },
        "branches": {
          "type": "array",
          "minItems": 1,
          "items": {
            "type": "object",
            "additionalProperties": false,
            "required": ["branchId", "firstStep"],
            "properties": {
              "branchId": { "type": "string" },
              "firstStep": { "type": "string", "minLength": 1 }
            }
          }
        }
      }
    },
    "nodeJoin": {
      "type": "object",
      "additionalProperties": false,
      "required": ["id", "type", "joinType"],
      "properties": {
        "id": { "type": "string", "minLength": 1 },
        "type": { "const": "join" },
        "title": { "type": "string" },
        "dependsOn": { "type": "array", "items": { "type": "string" } },
        "joinType": { "enum": ["all", "any", "n_of_m"] },
        "joinBranches": { "type": "array", "items": { "type": "string" } },
        "joinN": { "type": ["integer", "null"], "minimum": 1 }
      }
    },
    "nodeHitl": {
      "type": "object",
      "additionalProperties": false,
      "required": ["id", "type", "assignee"],
      "properties": {
        "id": { "type": "string", "minLength": 1 },
        "type": { "const": "hitl" },
        "title": { "type": "string" },
        "dependsOn": { "type": "array", "items": { "type": "string" } },
        "assignee": { "type": "string", "minLength": 1 },
        "timeout": { "type": ["string", "null"] },
        "escalationTarget": { "type": ["string", "null"] }
      }
    },
    "nodeSubWorkflow": {
      "type": "object",
      "additionalProperties": false,
      "required": ["id", "type", "workflowRef"],
      "properties": {
        "id": { "type": "string", "minLength": 1 },
        "type": { "const": "subworkflow" },
        "title": { "type": "string" },
        "dependsOn": { "type": "array", "items": { "type": "string" } },
        "workflowRef": { "type": "string", "minLength": 1 },
        "input": { "type": ["object", "null"] }
      }
    },
    "nodeEnd": {
      "type": "object",
      "additionalProperties": false,
      "required": ["id", "type"],
      "properties": {
        "id": { "type": "string", "minLength": 1 },
        "type": { "const": "end" },
        "title": { "type": "string" },
        "dependsOn": { "type": "array", "items": { "type": "string" } },
        "output": { "type": ["object", "null"] }
      }
    },
    "agentRef": {
      "type": "object",
      "additionalProperties": false,
      "required": ["ref", "contentHash"],
      "properties": {
        "ref": { "type": "string", "minLength": 1, "description": "Immutable reference, e.g. 'agents/extractor@1.3.0' or a content address. Inline agents are declared in the top-level agents[] and referenced by id." },
        "contentHash": { "type": "string", "minLength": 1, "description": "Hash of the resolved agent definition. Filled by the publish compiler; mandatory for published workflows and pinned in every run." }
      }
    },
    "agent": {
      "type": "object",
      "additionalProperties": false,
      "required": ["id", "name"],
      "properties": {
        "id": { "type": "string", "minLength": 1 },
        "name": { "type": "string" },
        "version": { "type": "string" },
        "description": { "type": ["string", "null"] },
        "category": { "type": ["string", "null"] },
        "model": { "type": ["string", "null"] },
        "temperature": { "type": "number" },
        "maxTokens": { "type": "integer", "minimum": 0 },
        "topP": { "type": "number" },
        "tools": { "type": "array", "items": { "type": "string" } },
        "permissions": { "type": ["object", "null"] },
        "guardrailsInput": { "type": "array", "items": { "type": "string" } },
        "guardrailsOutput": { "type": "array", "items": { "type": "string" } },
        "hooks": { "type": "array", "items": { "type": "string" } },
        "timeoutSeconds": { "type": ["integer", "null"], "minimum": 0 },
        "systemPrompt": { "type": ["string", "null"] },
        "userMessageTemplate": { "type": ["string", "null"] },
        "jsonOutput": { "type": "boolean" },
        "jsonOutputSchema": { "type": ["string", "null"] },
        "chatParameters": { "type": ["object", "null"] },
        "memory": { "type": ["object", "null"] },
        "active": { "type": "boolean" }
      }
    }
  }
}
```

**Strictness principle:** every node variant sets `additionalProperties:false` and a
`const` `type`. Cross-references (`dependsOn`, branch `target`/`firstStep`, `bodySteps`,
`workflowRef`) **and** the HITL normalization (`hitl` node vs. `requiresHumanApproval`
sugar) cannot be validated by JSON Schema and are enforced by the single semantic
validator (the R1–R7 rules, promoted to the canonical layer). `nodeAgent.overrides` is
authoring-time and included in `contentHash`; `agentRef.contentHash` is required.

---

## 7. Mapping: old → new

| Old | New (v2) |
|---|---|
| quad `workflows.nodes`/`edges` (untyped maps) | `mainWorkflow.nodes` typed; control flow inline; edges only a UI projection |
| UI `type: conditional` | `type: decision` |
| UI `type: nested-workflow` / kafka `subflow` | `type: subworkflow` |
| UI `type: loop` | `type: loop` (+ `maxIterations`, `exitCondition`, optional `bodySteps`) |
| UI `fork`/`join` | `fork` (branches) / `join` (`joinType`, `joinBranches`, `joinN`) |
| kafka `DagNode.dependsOn` | node `dependsOn` |
| kafka `DagNode.defaultTarget` | `decision.defaultTarget` |
| kafka `DagNode.requiresHumanApproval` | `requiresHumanApproval` **or** explicit `hitl` node; both normalized to `WAITING_HITL` |
| kafka `DagNode.agentType` / node `config.agentId` | `agent.ref` + required `agent.contentHash` (inline agents in top-level `agents[]`) |
| `input`/`inputs`/`joinPolicy` mapping | node `input` + OWS runtime expression |
| `{{nodeId.output}}` template | OWS runtime expression, e.g. `${ $context.nodeId.output }` |
| run-time overrides (`dag_node_overrides`, V12) | run event `OVERRIDES_APPLIED` + snapshot; authoring overrides in `nodeAgent.overrides` |
| quad `TEXT` JSON columns | `CHECK(json_valid(...))` (JSON1) + JSON-Schema validation on write |
| `workflows.name`, `initial_task` | `name`, `version`, `status`, `initialTask` |
| quad run `workflow_id` only | run pins `definitionId` + `version` + `contentHash` |
| v1 envelope `workflows[]` + `mainWorkflowId` | `mainWorkflow` + `subWorkflows[]` |
| `agents` table columns | `$defs/agent` (top-level `agents[]`) referenced by `agentRef.ref` + `contentHash` |
| dropped `dag_*` snapshot tables | event log + snapshot (kafka `dag_runs` pattern) |
| `agents.*` split across node config | canonical `agent` resource referenced by `agentRef` |

---

## 8. Migration plan (staged)

1. **Phase 0 — non-breaking groundwork.** Add `schema_version`, `version`, `status`,
   `content_hash` columns to `workflows` (defaults keep current behavior); add
   `CHECK(json_valid(...))` on the JSON `TEXT` columns. Runs record the pinned
   version/hash.
2. **Phase 1 — publish v2 (OWS-based).** Ship `quad.workflow.v2.schema.json`
   (OWS 1.0.3 structural profile + `quad` agent extension) + the JSON-Schema validator;
   importer accepts v1 and **upcasts v1 → v2** (`additionalProperties` map → typed nodes;
   edges → control flow / UI projection); the publish compiler resolves `agentRef` and
   fills its required `contentHash`.
3. **Phase 2 — unify semantics.** Replace the flat→DAG bridge with schema-based mapping;
   align type names (`conditional`→`decision`, `nested-workflow`→`subworkflow`);
   implement deterministic HITL normalization (`hitl` node vs. `requiresHumanApproval`);
   keep the UI projection generated from the canonical document.
4. **Phase 3 — event-sourced state.** Adopt the kafka event-log + snapshot pattern in
   quad; model HITL/approvals and run-time overrides (`OVERRIDES_APPLIED`) as events;
   pin the resolved agent config per run; audit model/tool/cost per step.
5. **Phase 4 — cleanup.** One semantic validator; fix the `misc/docs/workflow.md` drift
   (dropped `dag_*`); delete the duplicated/legacy path.

---

## 9. Strategic option

Because the stack is **Quarkus + LangChain4j**, evaluate **Quarkus Flow**
(OWS-compliant, agentic via LangChain4j) as reference or runtime instead of maintaining
a bespoke DSL long-term. The OWS normative schema + Java SDK provide interoperability;
the agent task extension (OWS #1184) is still evolving, so pin a specific profile.

---

## 10. Risks / remaining open questions

Resolved (see §1): OWS-strict baseline + expression syntax, HITL dual mechanism with
deterministic normalization, overrides split (authoring vs. run-time), SQLite
`json_valid` hardening, `agentRef` content-hash.

Remaining:
- **OWS agent-task profile (#1184)** is still evolving; pin a specific profile/version and
  keep it behind the `quad` extension + `agentRef` so a future change is local.
- **Open Agent Spec** (arXiv 2510.04173) is not a formal standard; treat as input only.
- **HITL normalization** must be deterministic and covered by tests (explicit `hitl` wins;
  no double gate).
- **Upcasters** (v1 ⇄ v2) must be deterministic, with round-trip tests.
- **Cross-reference validation** must stay in one place; otherwise the current double
  validation reappears.
- **BPMN/DMN interop** (for business analysts) can be added later as an *import/export
  projection* of v2, not as the internal model.
- **`agentRef` resolution** requires a registry with immutable tags/content addresses;
  define the failure mode — publish must fail closed when a ref cannot be resolved.

---

## 11. References

- Open Workflow Specification (CNCF), v1.0.3 — `serverlessworkflow.io`, `open-workflow-specification` GitHub.
- OWS agent characteristics extension — issue #1184.
- Open Agent Specification (Agent Spec) — arXiv 2510.04173.
- Quarkus Flow — OWS-compliant Quarkus engine (LangChain4j / agentic).
- Durable execution comparisons (Temporal, Restate, Inngest, AWS Step Functions, Argo), 2026.
- Schema evolution best practices (schema registry / event versioning), 2026.
- Repo evidence: see file references in §2.

---

## 12. Implementation progress

### Slice 1 — v2 schema + validator (done, 2026-10-09)

- **v2 schema resource** shipped:
  `quad-workflow-core/src/main/resources/quad/schema/quad.workflow.v2.schema.json`
  (identical to §6; validated as JSON).
- **Validator generalized** (`QuadWorkflowSchemaValidator`):
  - `$ref` resolution is now **root-relative** — a document is validated against exactly one
    schema generation (v1 or v2); previously refs were hard-wired to the v1 schema.
  - Added **`oneOf`** support (required for the discriminated v2 node variants) with
    deterministic error selection (fewest-errors branch, lowest index on ties).
  - New constants/entry points: `SCHEMA_V1_RESOURCE`, `SCHEMA_V2_RESOURCE`,
    `loadSchema(String)`, `validateV2(...)`, `SUPPORTED_SCHEMA_VERSION_V2`,
    `isSchemaVersionSupportedV2(...)`.
  - `validate(...)` (v1) is unchanged → backward compatible.
- **Tests added**: `QuadWorkflowV2SchemaValidatorTest` (schema well-formedness, valid doc,
  wrong version, unknown node type, missing `agent`, missing `contentHash`, additional
  property, `overrides` allow/deny, determinism, v1 regression).

Verification:
- `mvn -o -pl quad-workflow-core -am test` →
  **quad-core 491 tests, quad-workflow-core 65 tests, 0 failures.**
- `mvn -o -pl quad-quarkus -am test -Dtest=WorkflowCrudIntegrationTest` →
  **13 tests, 0 failures** (boots the app, runs Flyway V24/V25 on SQLite).

### Slice 2 — v1 → v2 upcast + publish compiler (done)

- `QuadWorkflowUpcaster` — structural v1 → v2 conversion: main/sub split, typed node
  mapping (`conditional→decision`, `nested-workflow/subflow→subworkflow`, …), `dependsOn`
  from incoming edges, branch/fork structure from outgoing edges, agent refs with a
  placeholder hash. Fails closed (`QuadWorkflowConversionException`) on unrepresentable
  nodes (decision without edges, subworkflow without `workflowId`).
- `QuadWorkflowPublishCompiler` — resolves every `agentRef` via an `AgentResolver`,
  overwrites `contentHash`, sets `status: published`, computes the envelope `contentHash`
  (canonical, key-sorted) and validates against v2; fails closed
  (`QuadWorkflowPublishException`) on unresolved refs / schema violations.
- Tests: `QuadWorkflowUpcasterTest`.

### Slice 3 — semantic validator + HITL normalization (done)

- `QuadWorkflowSemanticValidator` — graph rules JSON Schema cannot express: unique ids,
  resolvable references, exactly one END, acyclic `dependsOn`, `requiresHumanApproval`
  only on agent nodes, subworkflow `workflowRef` existence.
- `QuadWorkflowHitlNormalizer` — one state machine for both HITL mechanisms; derives the
  effective approval gates (`HITL_NODE` vs `REQUIRES_HUMAN_APPROVAL`).
- Tests: `QuadWorkflowSemanticValidatorTest`.

### Slice 4 — DB Phase 0 (done, validated on SQLite)

- `common/V24__workflow_v2_metadata.sql`: adds `schema_version`, `definition_version`,
  `status`, `content_hash` to `workflows`; `definition_version`, `definition_hash` to
  `workflow_state` (portable; defaults preserve current behaviour — pre-v2 rows read as
  `schema_version=1`, `status='published'`).
- `sqlite|postgresql|h2/V25__workflow_documents_v2.sql`: canonical `workflow_documents`
  table. JSON integrity at DB level per dialect: SQLite `CHECK(json_valid(document))`,
  PostgreSQL `jsonb`, H2 `json`.
- Note: PostgreSQL/H2 variants were not executed here (tests run SQLite only); they are
  structurally equivalent.

### Slice 5 — run-event foundation (done)

- `run/event/`: `WorkflowRunEvent`, `WorkflowRunEventType` (incl. `WAITING_HITL`,
  `APPROVED`/`REJECTED`/`EXPIRED`/`ESCALATED`, `OVERRIDES_APPLIED`),
  `WorkflowRunEventStore`, `InMemoryWorkflowRunEventStore`, `WorkflowRunProjection`
  (deterministic fold). **Not yet wired** into the engine.
- Tests: `WorkflowRunEventStoreTest`.

### Remaining

1. **Wire the run-event log into the engine**: `RunStateWriter` appends events; the
   snapshot becomes a projection; each run pins `definition_version`/`content_hash`.
2. **Wire `QuadWorkflowUpcaster`/`PublishCompiler` into the import/export service** and
   generate the flat UI projection from the canonical v2 document.
3. **Delegate the kafka `WorkflowDefinitionValidator` (R1–R7) to
   `QuadWorkflowSemanticValidator`** (single semantic validator).
4. **Round-trip tests** (v1 → v2 → validate) and OWS profile pinning.

Open points from §10 still apply (OWS profile pin, `agentRef` registry failure mode).
