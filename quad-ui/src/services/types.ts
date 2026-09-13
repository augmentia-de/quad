// Central type definitions for the QUAD UI API.
// Mirrors the backend JSON contracts (UiAgentResource) 1:1.
// See also: ../schemas/*.json (JSON Schema variants of these types)

// ─── Status ────────────────────────────────────────────────────────────────

/** Response from GET /api/ui/status */
export interface StatusResponse {
  ready: boolean
  model: string
  baseUrl: string
  costLimit: number
  dummyMode: boolean
}

// ─── Tools ────────────────────────────────────────────────────────────────

/** A LangChain4j tool as returned by GET /api/ui/tools */
export interface ToolSpec {
  name: string
  description: string
  /** Argument schema (JSON Schema object) */
  parameters?: Record<string, unknown>
}

// ─── Agents ───────────────────────────────────────────────────────────────

/** Full agent definition (GET /agents, POST /agents, PUT /agents/{id}) */
export interface AgentDefinition {
  id: string
  name: string
  description: string
  category: string
  model: string
  temperature: number
  maxTokens: number
  topP: number
  tools: string[]
  guardrailsInput: string[]
  guardrailsOutput: string[]
  /** Named lifecycle hooks attached per agent (e.g. "hitl"). */
  hooks?: string[]
  agentType?: string
  systemPrompt?: string
  userMessageTemplate?: string
  /** Agent produces structured JSON output. */
  jsonOutput?: boolean
  /** Optional JSON Schema enforced via responseFormat (StructuredOutputConfig.dynamicSchema). */
  jsonOutputSchema?: string
  chatParameters?: string
  /** false = internally generated workflow step agent (hidden in the classic UI). */
  active?: boolean
  /** Individual runtime limit in seconds per agent execution. Undefined = global default. */
  timeoutSeconds?: number
}

/** Request payload to create an agent (id is assigned by the backend) */
export type AgentCreateRequest = Omit<AgentDefinition, 'id'> & { id?: string }

// ─── Execution (Single Task) ──────────────────────────────────────────────

/** Request payload for POST /api/ui/execute */
export interface ExecuteRequest {
  task: string
  /** Optional: Execute task in the context of an agent */
  agentId?: string
  /** Optional: Continue an existing session (chat history is preserved) */
  sessionId?: string
}

/** Response from POST /api/ui/execute */
export interface UiExecutionResult {
  sessionId: string
  success: boolean
  result: string
  toolCount: number
  durationMs: number
  costLimit: number
  model: string
}

// ─── Workflows ────────────────────────────────────────────────────────────

/** Workflow node type (controls the prompt in the backend) */
export type WorkflowNodeType =
  | 'agent'
  | 'messaging-in'
  | 'messaging-out'
  | 'loop'
  | 'conditional'
  | 'nested-workflow'
  | 'async'
  | 'fork'
  | 'join'

/** Workflow node status during execution */
export type WorkflowNodeStatus = 'pending' | 'running' | 'waiting' | 'completed' | 'failed'

/** Position of a draggable node on the canvas */
export interface NodePosition {
  x: number
  y: number
}

/** A node in the workflow graph */
export interface WorkflowNode {
  id: string
  type: WorkflowNodeType
  title: string
  status: WorkflowNodeStatus
  /** Canvas position — set when user drags, fallback to grid placement */
  position?: NodePosition
  createdAt?: string
  /** Optional node-specific configuration (e.g. prompt override) */
  config?: Record<string, unknown>
}

/** A directed edge: source → target */
export interface WorkflowEdge {
  id: string
  source: string
  target: string
  /**
   * Legacy single input mapping (still supported by the backend).
   * Prefer `inputs` for multiple sources per edge.
   */
  input?: EdgeInputMapping
  /**
   * List of input mappings feeding the target node from arbitrary source
   * values (not only the direct predecessor). Each mapping selects a source
   * node/output; for `json` format additionally a subtree path is resolved
   * and its content passed into the successor.
   */
  inputs?: EdgeInputMapping[]
  /** Join behaviour at the target node: wait for all ('all') or first ('any') predecessors. */
  joinPolicy?: 'all' | 'any'
}

export interface EdgeInputMapping {
  /** ID of the source node (or SessionState field like "__session.findings") */
  sourceNodeId: string
  /** Output format: text (default) or json (for structured outputs) */
  format: 'text' | 'json'
  /**
   * Einzelner Subtree-/State-Pfad in das JSON-Output des Quell-Nodes (nur bei format='json'):
   * JSON-Pointer ("/items/0/name"), Punkt-Notation ("items.0.name") oder Bracket-Notation ("items[0].name").
   * Leer/fehlend = ganzer Output.
   */
  path?: string
/**
 * Multiple subtree/state paths (only for format='json'). Each path expression is resolved
 * and passed to the following node as a separate fragment. Multi-selection via the
 * JSON schema tree. If only `path` is set, it counts as the only path.
 */
  paths?: string[]
}

/** Response from POST /api/ui/workflows/{id}/execute (async start) */
export interface RunAccepted {
  runId: string
  workflowId: string
  /** The start task / initial data the run was started with */
  initialData?: string
}

/** Live result of a single node within a run */
export interface NodeRunResult {
  nodeId: string
  type: string
  title: string
  status: WorkflowNodeStatus
  output: string
  /** What was injected into the node prompt (SessionState mapping) */
  input: string
}

/** Snapshot of a workflow run (GET /api/ui/runs/{runId}) */
export interface WorkflowRun {
  runId: string
  workflowId: string
  status: 'running' | 'completed' | 'failed'
  startedAt: string
  finishedAt: string | null
  durationMs: number
  nodeResults: NodeRunResult[]
  /** The start task / initial data this run was started with */
  initialData?: string
  /** Node from which a resumed run continues (crash-recovery / continue) */
  executingFrom?: string
  /** How often this run was restarted */
  restartCount?: number
}

/** Full workflow definition (GET /workflows/{id}) */
export interface WorkflowDefinition {
  id: string
  name: string
  nodes: WorkflowNode[]
  edges: WorkflowEdge[]
  /** Optional start task used when the workflow has no messaging-in node */
  initialTask?: string
  createdAt: string
  updatedAt: string
}

/** Compact workflow list entry (GET /workflows) */
export interface WorkflowSummary {
  id: string
  name: string
  nodeCount: number
  edgeCount: number
  initialTask?: string
  createdAt: string
  updatedAt: string
}

/** Request payload for POST /api/ui/workflows */
export interface WorkflowCreateRequest {
  id?: string
  name: string
  nodes: WorkflowNode[]
  edges: WorkflowEdge[]
  /** Optional start task used when the workflow has no messaging-in node */
  initialTask?: string
}

/** Result of a single node during workflow execution */
export interface WorkflowNodeResult {
  nodeId: string
  type: WorkflowNodeType
  title: string
  output: string
}

/** Response from POST /api/ui/workflows/{id}/execute */
export interface WorkflowExecutionResult {
  workflowId: string
  success: boolean
  results: WorkflowNodeResult[]
  durationMs: number
}

// ─── Metrics ──────────────────────────────────────────────────────────────

/** Response from GET /api/ui/metrics */
export interface TokenMetrics {
  prompt: number
  completion: number
  total: number
}

/** Response from GET /api/ui/metrics/errors */
export interface ErrorMetrics {
  toolCalls: number
  timeouts: number
  guardrails: number
}

// ─── Audit ────────────────────────────────────────────────────────────────

/** An entry in the audit log (GET /api/ui/audit/logs) */
export interface AuditLogEntry {
  id: string
  timestamp: string
  agent: string
  event: string
  details: string
}

// ─── Runs (Stufe 14, GET /api/ui/runs) ────────────────────────────────────

/** Ein einzelner Schritt einer Run-Sicht (aus session_events STEP/SPAN-Records). */
export interface RunStepView {
  stepId: string
  kind: string
  status: string
  durationMs: number
  eventType: string
  timestamp: string | null
}

/** Run-Sicht (aggregiert aus TelemetryStore.query). */
export interface RunView {
  runId: string
  kind: string
  status: string
  stepIndex: number
  startedAt: string | null
  finishedAt: string | null
  durationMs: number
  steps: RunStepView[]
}

export interface RunsFilter {
  period?: string
  kind?: string
  status?: string
  runId?: string
  sessionId?: string
}

// ─── HITL ─────────────────────────────────────────────────────────────────

/** A HITL approval request (GET /api/ui/hitl/approvals) */
export interface HitlApproval {
  id: string
  agent: string
  tool: string
  request: string
  since: string
  status: 'pending' | 'approved' | 'rejected'
}

/** Response from POST /api/ui/hitl/approvals/{id}/approve */
export interface ApproveResponse {
  approved: boolean
  id: string
}

/** Response from POST /api/ui/hitl/approvals/{id}/reject */
export interface RejectResponse {
  rejected: boolean
  id: string
}

// ─── Errors ───────────────────────────────────────────────────────────────

/** Unified backend error response */
export interface ApiError {
  error: string
}

// ─── Guardrails ────────────────────────────────────────────────────────────

/** A guardrail entry (GET /api/ui/guardrails) */
export interface GuardrailSpec {
  name: string
  type: 'input' | 'output' | 'unknown'
  active: boolean
}

// ─── Sessions ─────────────────────────────────────────────────────────────

/** A persisted session entry (GET /api/ui/sessions) */
export interface SessionSummary {
  id: string
  createdAt: string
  lastSeen: string
  task: string
  result: string
  memorySummary: string
}

/** A single event within a session */
export interface SessionEvent {
  id: string
  eventType: string
  payload: string
  timestamp: string
}

/** Full session with event history (GET /api/ui/sessions/{id}) */
export interface SessionDetail {
  session: SessionSummary
  events: SessionEvent[]
}

// ─── GDPR ─────────────────────────────────────────────────────────────────

/** Exported session data (GET /api/ui/gdpr/export/{sessionId}) */
export interface GdprExport {
  session: SessionSummary
  events: SessionEvent[]
  state: Record<string, unknown>
}

// ─── MCP ──────────────────────────────────────────────────────────────────

/** Status of a single MCP server */
export interface McpServerStatus {
  name: string
  transport: string
  toolCount: number
  error: string | null
}

/** Overall status of all MCP servers (GET /api/ui/mcp/status) */
export interface McpStatus {
  enabled: boolean
  toolCount: number
  lastSetupAt: string | null
  servers: McpServerStatus[]
}

// ─── Journal / Debug ──────────────────────────────────────────────────────

/** A single trace entry (GET /api/ui/journal) — backend journal JSONL record */
export interface JournalEntry {
  /** Epoch millis or ISO timestamp */
  timestamp: number | string
  /** Session that produced the event */
  sessionId?: string
  /** Event type (e.g. tool/llm/guardrail/error) */
  type?: string
  /** Event payload (string or structured JSON) */
  payload?: unknown
  [key: string]: unknown
}

/** Response from GET /api/ui/journal */
export interface JournalResponse {
  enabled: boolean
  count: number
  events: JournalEntry[]
}

// ─── Model Tiers ──────────────────────────────────────────────────────────

/** Configuration for a model tier */
export interface ModelTierConfig {
  model: string
  baseUrl: string
  apiKeySet: boolean
}

/** Response from GET /api/ui/model/tiers */
export interface ModelTiersResponse {
  enabled: boolean
  defaultTier: 'simple' | 'advanced'
  simple: ModelTierConfig
  advanced: ModelTierConfig
}

// ─── Skill ────────────────────────────────────────────────────────────────

/** A skill entry (GET /api/ui/skills) */
export interface SkillSpec {
  name: string
  description: string
  allowedTools: string[]
  declaredTools: string[]
}

/** Request payload for PUT/POST /api/ui/skills/{name} */
export interface SkillUpsertRequest {
  id?: string
  name: string
  description?: string
  instructions?: string
  allowedTools?: string[]
  declaredTools?: string[]
}

// ─── Memory ───────────────────────────────────────────────────────────────

export type MemoryCategory = 'USER_FACT' | 'PROJECT_FACT' | 'TASK_FACT'

/** A long-term memory entry (GET /api/ui/memory) */
export interface MemoryEntry {
  id: string
  scope: string
  content: string
  summary: string | null
  category: MemoryCategory | null
  sensitive: boolean
  createdAt: string
}

/** Response from POST /api/ui/memory/update */
export interface MemoryUpdateResponse {
  ok: boolean
  id: string
}

// ─── Permission / Workspace ───────────────────────────────────────────────

export type PermissionMode = 'PLAN' | 'INTERACTIVE' | 'AUTO'

/** A workspace registry entry (GET /api/workspace/recent) */
export interface WorkspaceRecord {
  path: string
  name: string
  trusted: boolean
  commandTrust: string | null
  gitBranch: string | null
  lastAccessedAt: string | null
}

/** Response from POST /api/workspace/open */
export interface WorkspaceOpenResponse {
  ok: boolean
  path: string
  git_branch: string | null
  command_trust: Record<string, unknown>
}

/** Response for trust management */
export interface TrustResponse {
  ok: boolean
  workspace: string
  requested_commands: string[]
  trusted: boolean
  required: boolean
  exists: boolean
}

/** A root directory of a session */
export interface WorkspaceRoot {
  path: string
  writable: boolean
  label: string
  primary: boolean
  exists: boolean
}

// ─── Audit Events (structured DB audit, Port 03) ─────────────────────────

/** An audit event row from the structured audit table (GET /api/audit) */
export interface AuditEventRow {
  eventType: string
  userId: string | null
  roles: string[]
  sessionId: string | null
  toolName: string | null
  toolArgs: string | null
  result: string | null
  isError: boolean
  durationMs: number
  correlationId: string | null
  tokenId: string | null
  ts: string
}

// ─── Automation / Scheduler ───────────────────────────────────────────────

/** Schedule definition for a scheduled task */
export interface ScheduleDefinition {
  kind: string    // 'cron' | 'once'
  cron: string | null
  fire_at: string | null
  timezone: string
}

/** A persisted scheduled task (GET /api/automation) */
export interface ScheduledTask {
  id: string
  title: string
  instructions: string
  schedule: string              // human-readable schedule like "Every day at ~7:10 PM"
  schedule_raw: ScheduleDefinition
  workspace: string | null
  agent: string
  enabled: boolean
  next_run: number | null       // epoch seconds
  last_run: number | null
  last_status: string | null
  run_count: number
  notify_on_completion: boolean
  seen_runs_at: number
  always_allowed: Array<{ entry: string; tool: string; target?: string }>
}

/** An automation task creation request */
export interface AutomationCreateRequest {
  title: string
  instructions: string
  cron?: string
  fire_at?: string
  timezone?: string
  agent?: string
  model?: string
  permissions?: unknown
}

/** An automation task update request */
export interface AutomationUpdateRequest {
  enabled?: boolean
  instructions?: string
  title?: string
  cron?: string
  revoke?: string
}

/** A run of an automation task */
export interface TaskRun {
  task_id: string
  run_id: string
  started_at: number
  finished_at: number | null
  status: string                // 'running' | 'ok' | 'error' | 'skipped'
  result_text: string | null
  artifacts: string[]
  error: string | null
  trigger: string               // 'schedule' | 'manual' | 'catchup'
  session_id: string
}

/** Full automation response with task + runs */
export interface AutomationDetail {
  task: ScheduledTask
  runs: TaskRun[]
}

/** List wrapper for automations (with unseen-run counts) */
export interface AutomationListEntry extends ScheduledTask {
  unseen_runs: number
  unseen_failed: boolean
}

/** Response from prepare manual run */
export interface PrepareRunResponse {
  ok: boolean
  run_id: string
  session_id: string
  workspace: string
  agent: string
  prompt: string
}

/** Response list wrapper */
export interface AutomationListResponse {
  tasks: AutomationListEntry[]
}

// ─── Provenance ───────────────────────────────────────────────────────────

/** A provenance trace entry (tool → source file/url) */
export interface ProvenanceEntry {
  toolName: string
  source: string | null
  at: string
}

/** Response from GET /api/provenance?session=... */
export interface ProvenanceResponse {
  entries: ProvenanceEntry[]
  count: number
}

// ─── Messaging ────────────────────────────────────────────────────────────

/** Response from GET /api/ui/messaging/status */
export interface MessagingStatus {
  inbound: Record<string, string>
  outbound: Record<string, string>
}

/** A configured messaging channel (GET /api/ui/messaging/channels) */
export interface MessagingChannel {
  name: string
  transport: string
  topic: string
  agentId: string
  tenantId: string
}
