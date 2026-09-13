// API service for QUAD Studio
// This file exports functions for communicating with the backend API.
// Typkontrakte: ./types.ts

import type {
  AgentCreateRequest,
  AgentDefinition,
  ApiError,
  ApproveResponse,
  AuditEventRow,
  AuditLogEntry,
  AutomationCreateRequest,
  AutomationDetail,
  AutomationListEntry,
  AutomationListResponse,
  AutomationUpdateRequest,
  ErrorMetrics,
  GdprExport,
  GuardrailSpec,
  HitlApproval,
  JournalResponse,
  McpStatus,
  MessagingChannel,
  MessagingStatus,
  MemoryEntry,
  MemoryUpdateResponse,
  ModelTiersResponse,
  PrepareRunResponse,
  ProvenanceResponse,
  RejectResponse,
  RunAccepted,
  RunView,
  RunsFilter,
  ScheduleDefinition,
  ScheduledTask,
  SessionDetail,
  SessionEvent,
  SessionSummary,
  SkillSpec,
  SkillUpsertRequest,
  StatusResponse,
  TokenMetrics,
  ToolSpec,
  TaskRun,
  UiExecutionResult,
  WorkflowCreateRequest,
  WorkflowDefinition,
  WorkflowRun,
  WorkspaceOpenResponse,
  WorkspaceRecord,
  TrustResponse,
  WorkspaceRoot,
  WorkflowSummary,
} from './types'

export type {
  AgentCreateRequest,
  AgentDefinition,
  ApiError,
  ApproveResponse,
  AuditEventRow,
  AuditLogEntry,
  AutomationCreateRequest,
  AutomationDetail,
  AutomationListEntry,
  AutomationListResponse,
  AutomationUpdateRequest,
  ErrorMetrics,
  GdprExport,
  GuardrailSpec,
  HitlApproval,
  JournalResponse,
  McpStatus,
  MessagingChannel,
  MessagingStatus,
  MemoryEntry,
  ModelTiersResponse,
  PrepareRunResponse,
  ProvenanceResponse,
  RejectResponse,
  RunAccepted,
  RunView,
  RunsFilter,
  ScheduledTask,
  ScheduleDefinition,
  SessionDetail,
  SessionEvent,
  SessionSummary,
  SkillSpec,
  SkillUpsertRequest,
  StatusResponse,
  TaskRun,
  TokenMetrics,
  ToolSpec,
  TrustResponse,
  UiExecutionResult,
  WorkflowCreateRequest,
  WorkflowDefinition,
  WorkflowEdge,
  WorkflowRun,
  WorkflowNode,
  WorkflowNodeResult,
  WorkflowNodeStatus,
  WorkflowNodeType,
  WorkflowSummary,
  WorkspaceRecord,
  WorkspaceOpenResponse,
  WorkspaceRoot,
} from './types'

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || '/api/ui'

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`${API_BASE_URL}${path}`, init)
  if (!response.ok) {
    let message = `Request failed: ${response.status} ${path}`
    try {
      const body = (await response.json()) as ApiError
      if (body?.error) message = body.error
    } catch {
      // ignore JSON parse errors
    }
    throw new Error(message)
  }
  if (response.status === 204) return undefined as T
  return response.json() as Promise<T>
}

const json = (method: string, body: unknown): RequestInit => ({
  method,
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify(body),
})

// ─── Status ───────────────────────────────────────────────────────────────

export function fetchAgentStatus(): Promise<StatusResponse> {
  return request<StatusResponse>('/status')
}

// ─── Tools ────────────────────────────────────────────────────────────────

export function fetchTools(): Promise<ToolSpec[]> {
  return request<ToolSpec[]>('/tools')
}

// ─── Agents ───────────────────────────────────────────────────────────────

export function fetchAgents(includeInactive = false): Promise<AgentDefinition[]> {
  return request<AgentDefinition[]>(`/agents${includeInactive ? '?includeInactive=true' : ''}`)
}

export function fetchAgent(id: string): Promise<AgentDefinition> {
  return request<AgentDefinition>(`/agents/${encodeURIComponent(id)}`)
}

export async function createAgent(agent: AgentCreateRequest): Promise<string> {
  const flat = {
    name: agent.name,
    description: agent.description,
    category: agent.category,
    model: agent.model,
    temperature: agent.temperature,
    maxTokens: agent.maxTokens,
    topP: agent.topP,
    tools: agent.tools ?? [],
    guardrailsInput: agent.guardrailsInput ?? [],
    guardrailsOutput: agent.guardrailsOutput ?? [],
    hooks: agent.hooks ?? [],
    agentType: agent.agentType ?? 'ua',
    systemPrompt: agent.systemPrompt,
    userMessageTemplate: agent.userMessageTemplate,
    jsonOutput: agent.jsonOutput ?? false,
    jsonOutputSchema: agent.jsonOutputSchema ?? '',
    chatParameters: agent.chatParameters,
  }
  const data = await request<{ id: string }>('/agents', json('POST', flat))
  return data.id
}

export function updateAgent(id: string, agent: AgentCreateRequest): Promise<AgentDefinition> {
  const flat = {
    name: agent.name,
    description: agent.description,
    category: agent.category,
    model: agent.model,
    temperature: agent.temperature,
    maxTokens: agent.maxTokens,
    topP: agent.topP,
    tools: agent.tools ?? [],
    guardrailsInput: agent.guardrailsInput ?? [],
    guardrailsOutput: agent.guardrailsOutput ?? [],
    hooks: agent.hooks ?? [],
    agentType: agent.agentType ?? 'ua',
    systemPrompt: agent.systemPrompt,
    userMessageTemplate: agent.userMessageTemplate,
    jsonOutput: agent.jsonOutput ?? false,
    jsonOutputSchema: agent.jsonOutputSchema ?? '',
    chatParameters: agent.chatParameters,
  }
  return request<AgentDefinition>(`/agents/${encodeURIComponent(id)}`, json('PUT', flat))
}

export async function duplicateAgent(id: string): Promise<string> {
  const data = await request<{ id: string }>(`/agents/${encodeURIComponent(id)}/duplicate`, { method: 'POST' })
  return data.id
}

export function deleteAgent(id: string): Promise<void> {
  return request<void>(`/agents/${encodeURIComponent(id)}`, { method: 'DELETE' })
}

// ─── Execution (Einzeltask) ───────────────────────────────────────────────

export function execTask(task: string, agentId?: string, sessionId?: string): Promise<UiExecutionResult> {
  const body: { task: string; agentId?: string; sessionId?: string } = { task }
  if (agentId) body.agentId = agentId
  if (sessionId) body.sessionId = sessionId
  return request<UiExecutionResult>('/execute', json('POST', body))
}

export { execTask as executeTask }

// ─── Workflows ────────────────────────────────────────────────────────────

export function fetchWorkflows(): Promise<WorkflowSummary[]> {
  return request<WorkflowSummary[]>('/workflows')
}

export function fetchWorkflow(id: string): Promise<WorkflowDefinition> {
  return request<WorkflowDefinition>(`/workflows/${encodeURIComponent(id)}`)
}

export async function createWorkflow(workflow: WorkflowCreateRequest): Promise<string> {
  const data = await request<{ id: string }>('/workflows', json('POST', workflow))
  return data.id
}

export function updateWorkflow(id: string, workflow: WorkflowCreateRequest): Promise<WorkflowDefinition> {
  return request<WorkflowDefinition>(`/workflows/${encodeURIComponent(id)}`, json('PUT', workflow))
}

export function deleteWorkflow(id: string): Promise<void> {
  return request<void>(`/workflows/${encodeURIComponent(id)}`, { method: 'DELETE' })
}

export async function duplicateWorkflow(id: string): Promise<string> {
  const data = await request<{ id: string }>(`/workflows/${encodeURIComponent(id)}/duplicate`, { method: 'POST' })
  return data.id
}

export function executeWorkflow(id: string, initialData?: string): Promise<RunAccepted> {
  const body = { ...(initialData ? { initialData } : {}) }
  return request<RunAccepted>(`/workflows/${encodeURIComponent(id)}/execute`, json('POST', body))
}

// ─── Dynamic Workflows ─────────────────────────────────────────────────────

export interface DynamicGenerateRequest {
  task: string
  name?: string
}

export interface DynamicGenerateResponse {
  workflow: WorkflowDefinition
}

export interface DynamicRunRequest {
  workflow: WorkflowCreateRequest
  initialData?: string
}

export interface DynamicRunResponse {
  runId: string
  workflowId: string
  initialData?: string
}

export function generateDynamicWorkflow(req: DynamicGenerateRequest): Promise<DynamicGenerateResponse> {
  return request<DynamicGenerateResponse>('/dynamic/generate', json('POST', req))
}

export function runDynamicWorkflow(req: DynamicRunRequest): Promise<DynamicRunResponse> {
  return request<DynamicRunResponse>('/dynamic/run', json('POST', req))
}

export async function saveDynamicWorkflow(req: WorkflowCreateRequest): Promise<string> {
  const data = await request<{ id: string }>('/dynamic/save', json('POST', req))
  return data.id
}

export interface DynamicCompleteRequest {
  runId: string
  nodeId: string
  output?: string
}

export function completeDynamicWorkflow(req: DynamicCompleteRequest): Promise<{ ok: boolean }> {
  return request<{ ok: boolean }>('/dynamic/complete', json('POST', req))
}

export interface DynamicChatStartRequest {
  task: string
  name?: string
}

export interface DynamicChatResponse {
  sessionId: string
  workflow: WorkflowDefinition
  reply?: string
}

export function startDynamicChat(req: DynamicChatStartRequest): Promise<DynamicChatResponse> {
  return request<DynamicChatResponse>('/dynamic/chat/start', json('POST', req))
}

export function chatDynamicWorkflow(req: { sessionId: string; request: string }): Promise<DynamicChatResponse> {
  return request<DynamicChatResponse>('/dynamic/chat', json('POST', req))
}

// ─── Workflow Runs ─────────────────────────────────────────────────────────

export function fetchWorkflowRuns(id: string): Promise<WorkflowRun[]> {
  return request<WorkflowRun[]>(`/workflows/${encodeURIComponent(id)}/runs`)
}

export function fetchAllRuns(): Promise<WorkflowRun[]> {
  return request<WorkflowRun[]>('/workflow-runs')
}

export function fetchRun(runId: string): Promise<WorkflowRun> {
  return request<WorkflowRun>(`/runs/${encodeURIComponent(runId)}`)
}

/** POST /api/ui/runs/{runId}/continue — resumes a failed run from its last completed step */
export function continueRun(runId: string): Promise<{ runId: string; status: string }> {
  return request(`/runs/${encodeURIComponent(runId)}/continue`, { method: 'POST' })
}

/** POST /api/ui/runs/{runId}/restart — re-runs the workflow from scratch */
export function restartRun(runId: string): Promise<{ runId: string; status: string; restartCount: number }> {
  return request(`/runs/${encodeURIComponent(runId)}/restart`, { method: 'POST' })
}

// ─── Metrics ──────────────────────────────────────────────────────────────

export function fetchMetrics(): Promise<TokenMetrics> {
  return request<TokenMetrics>('/metrics')
}

export function fetchErrorMetrics(): Promise<ErrorMetrics> {
  return request<ErrorMetrics>('/metrics/errors')
}

// ─── Audit ────────────────────────────────────────────────────────────────

export function fetchAuditLogs(filters?: {
  period?: string
  agent?: string
  eventType?: string
}): Promise<AuditLogEntry[]> {
  const params = new URLSearchParams()
  if (filters?.period && filters.period !== 'all') params.append('period', filters.period)
  if (filters?.agent && filters.agent !== 'all') params.append('agent', filters.agent)
  if (filters?.eventType && filters.eventType !== 'all') params.append('eventType', filters.eventType)
  return request<AuditLogEntry[]>(`/audit/logs?${params}`)
}

// ─── Runs (Stufe 14) ────────────────────────────────────────────────────

/** Fetch aggregated run views (GET /api/ui/runs). Filter-Parameter optional. */
export function fetchRuns(filters?: RunsFilter): Promise<RunView[]> {
  const params = new URLSearchParams()
  if (filters?.period && filters.period !== 'all') params.append('period', filters.period)
  if (filters?.kind && filters.kind !== 'all') params.append('kind', filters.kind)
  if (filters?.status && filters.status !== 'all') params.append('status', filters.status)
  if (filters?.runId && filters.runId !== 'all') params.append('runId', filters.runId)
  if (filters?.sessionId && filters.sessionId !== 'all') params.append('sessionId', filters.sessionId)
  const qs = params.toString()
  return request<RunView[]>(`/runs${qs ? `?${qs}` : ''}`)
}

// ─── HITL ─────────────────────────────────────────────────────────────────

export function fetchHitlApprovals(): Promise<HitlApproval[]> {
  return request<HitlApproval[]>('/hitl/approvals')
}

export function approveHitl(id: string): Promise<ApproveResponse> {
  return request<ApproveResponse>(`/hitl/approvals/${encodeURIComponent(id)}/approve`, { method: 'POST' })
}

export function rejectHitl(id: string): Promise<RejectResponse> {
  return request<RejectResponse>(`/hitl/approvals/${encodeURIComponent(id)}/reject`, { method: 'POST' })
}

// ─── Guardrails ───────────────────────────────────────────────────────────

export function fetchGuardrails(): Promise<GuardrailSpec[]> {
  return request<GuardrailSpec[]>('/guardrails')
}

// ─── Hooks ────────────────────────────────────────────────────────────────

export function fetchHooks(): Promise<GuardrailSpec[]> {
  return request<GuardrailSpec[]>('/hooks')
}

// ─── Skills ───────────────────────────────────────────────────────────────

export function fetchSkills(): Promise<SkillSpec[]> {
  return request<SkillSpec[]>('/skills')
}

// ─── Model Tiers ──────────────────────────────────────────────────────────

export function fetchModelTiers(): Promise<ModelTiersResponse> {
  return request<ModelTiersResponse>('/model/tiers')
}

// ─── Sessions ─────────────────────────────────────────────────────────────

export function fetchSessions(): Promise<SessionSummary[]> {
  return request<SessionSummary[]>('/sessions')
}

export function fetchSession(id: string): Promise<SessionDetail> {
  return request<SessionDetail>(`/sessions/${encodeURIComponent(id)}`)
}

export function fetchSessionEvents(id: string): Promise<{ sessionId: string; events: SessionEvent[] }> {
  return request(`/sessions/${encodeURIComponent(id)}/events`)
}

// ─── GDPR ─────────────────────────────────────────────────────────────────

export function gdprExport(sessionId: string): Promise<GdprExport> {
  return request<GdprExport>(`/gdpr/export/${encodeURIComponent(sessionId)}`)
}

export function gdprDelete(sessionId: string): Promise<void> {
  return request<void>(`/gdpr/delete/${encodeURIComponent(sessionId)}`, { method: 'DELETE' })
}

// ─── MCP ──────────────────────────────────────────────────────────────────

export function fetchMcpStatus(): Promise<McpStatus> {
  return request<McpStatus>('/mcp/status')
}

export function reinitMcp(): Promise<McpStatus> {
  return request<McpStatus>('/mcp/reinit', { method: 'POST' })
}

// ─── Journal / Debug ──────────────────────────────────────────────────────

export function fetchJournal(limit = 100): Promise<JournalResponse> {
  return request<JournalResponse>(`/journal?limit=${limit}`)
}

export function clearJournal(): Promise<void> {
  return request<void>('/journal', { method: 'DELETE' })
}

// ─── Messaging ────────────────────────────────────────────────────────────

export function fetchMessagingStatus(): Promise<MessagingStatus> {
  return request<MessagingStatus>('/messaging/status')
}

export function fetchMessagingChannels(): Promise<MessagingChannel[]> {
  return request<MessagingChannel[]>('/messaging/channels')
}

// ─── Skills (Port 06 – extended CRUD) ──────────────────────────────────────

/** Full skill management including upsert and delete */
export function fetchSkillList(): Promise<SkillSpec[]> {
  return request<SkillSpec[]>('/skills')
}

export function upsertSkill(name: string, payload: SkillUpsertRequest): Promise<void> {
  return request<void>(`/skills/${encodeURIComponent(name)}`, json('PUT', payload))
}

export function deleteSkill(name: string): Promise<void> {
  return request<void>(`/skills/${encodeURIComponent(name)}`, { method: 'DELETE' })
}

// ─── Memory Persistence (Port 01) ──────────────────────────────────────────

export function fetchMemories(scope?: string): Promise<MemoryEntry[]> {
  const params = scope ? `?scope=${encodeURIComponent(scope)}` : ''
  return request<MemoryEntry[]>(`/memory${params}`)
}

export function getMemory(id: string): Promise<MemoryEntry> {
  return request<MemoryEntry>(`/memory/${encodeURIComponent(id)}`)
}

export function updateMemory(id: string, content: string): Promise<MemoryUpdateResponse> {
  return request<MemoryUpdateResponse>(`/memory/${encodeURIComponent(id)}`, json('PUT', { content }))
}

export function forgetMemory(id: string): Promise<void> {
  return request<void>(`/memory/${encodeURIComponent(id)}`, { method: 'DELETE' })
}

export function clearMemories(): Promise<void> {
  return request<void>('/memory', { method: 'DELETE' })
}

// ─── Permission / Workspace (Port 02) ──────────────────────────────────────

export function fetchWorkspaceRecent(limit = 20): Promise<WorkspaceRecord[]> {
  return request<WorkspaceRecord[]>(`/workspace/recent?limit=${limit}`)
}

export function openWorkspace(path: string, create = false): Promise<WorkspaceOpenResponse> {
  return request<WorkspaceOpenResponse>('/workspace/open', json('POST', { path, create }))
}

export function fetchCommandTrust(path: string): Promise<TrustResponse> {
  return request<TrustResponse>(`/workspace/command-trust/${encodeURIComponent(path)}`)
}

export function setTrust(path: string, trusted: boolean, commands?: string[]): Promise<TrustResponse> {
  return request<TrustResponse>('/workspace/trust', json('POST', { path, trusted, requestedCommands: commands }))
}

export function fetchTrustedWorkspaces(): Promise<TrustResponse[]> {
  return request<TrustResponse[]>('/workspace/trusted')
}

export function createTempSession(sessionId: string, git = false): Promise<WorkspaceOpenResponse> {
  return request<WorkspaceOpenResponse>(`/workspace/temp/${encodeURIComponent(sessionId)}`, json('POST', { git }))
}

export function saveAsProject(sessionId: string, target: string): Promise<WorkspaceOpenResponse> {
  return request<WorkspaceOpenResponse>(`/workspace/save-as/${encodeURIComponent(sessionId)}`, json('POST', { path: target }))
}

export function fetchRoots(sessionId: string): Promise<WorkspaceRoot[]> {
  return request<WorkspaceRoot[]>(`/workspace/roots/${encodeURIComponent(sessionId)}`)
}

export function addRoot(sessionId: string, path: string, writable = true): Promise<{ ok: boolean; roots: WorkspaceRoot[] }> {
  return request(`/workspace/roots/${encodeURIComponent(sessionId)}`, json('POST', { path, writable }))
}

export function removeRoot(sessionId: string, path: string): Promise<{ ok: boolean; roots: WorkspaceRoot[] }> {
  return request(`/workspace/roots/${encodeURIComponent(sessionId)}/${encodeURIComponent(path)}`, { method: 'DELETE' })
}

export function fetchProjectMenu(sessionId: string, kind: string): Promise<Record<string, unknown>> {
  return request(`/workspace/project/${encodeURIComponent(sessionId)}/${encodeURIComponent(kind)}`)
}

export function setBinding(sessionId: string, kind: string, name: string): Promise<{ ok: boolean }> {
  return request(`/workspace/project/${encodeURIComponent(sessionId)}/${encodeURIComponent(kind)}`, json('POST', { name }))
}

export function fetchPermissionAll(): Promise<Record<string, unknown>> {
  return request<Record<string, unknown>>('/permission')
}

export function fetchPermissionMode(sessionId: string): Promise<{ sessionId: string; mode: string }> {
  return request(`/permission/${encodeURIComponent(sessionId)}`)
}

export function setPermissionMode(sessionId: string, mode: string): Promise<{ sessionId: string; mode: string }> {
  return request(`/permission/${encodeURIComponent(sessionId)}/${mode}`, json('PUT', {}))
}

// ─── Structured Audit (Port 03) ────────────────────────────────────────────

/** Fetch structured DB audit events (GET /api/audit) */
export function fetchAuditEvents(limit = 200, sessionId?: string, eventType?: string): Promise<AuditEventRow[]> {
  const params = new URLSearchParams()
  params.set('limit', String(Math.min(limit, 1000)))
  if (sessionId) params.set('session', sessionId)
  if (eventType) params.set('eventType', eventType)
  return request<AuditEventRow[]>(`/audit?${params.toString()}`)
}

/** Fetch recent in-memory audit events (GET /api/audit/recent) */
export function fetchRecentAuditEvents(): Promise<AuditEventRow[]> {
  return request<AuditEventRow[]>('/audit/recent')
}

// ─── SSE Streaming (Port 01) ────────────────────────────────────────────────

/**
 * SSE stream for progressive agent execution responses.
 * Connects to the existing /api/checkpoints/stream/ui endpoint.
 * The backend streams checkpoint events (tool calls, HITL, completion)
 * as Server-Sent Events.
 */
export interface SseCheckpointEvent {
  id?: string
  event: string
  data: Record<string, unknown>
  timestamp?: string
}

/**
 * Subscribe to the SSE checkpoint stream.
 * The backend already supports this endpoint — no API change needed.
 *
 * @param onEvent callback for each checkpoint event
 * @param onError optional error handler
 */
export function streamCheckpoints(
  onEvent: (evt: SseCheckpointEvent) => void,
  onError?: (err: Event) => void
): () => void {
  const source = new EventSource('/api/checkpoints/stream/ui')
  const handler = (e: MessageEvent) => {
    try {
      const data = JSON.parse(e.data) as Record<string, unknown>
      onEvent({
        event: e.type,
        data,
        timestamp: new Date().toISOString()
      })
    } catch {
      // Ignore parse errors
    }
  }
  source.addEventListener('checkpoint', handler as EventListener)
  source.addEventListener('complete', handler as EventListener)
  source.addEventListener('error', handler as EventListener)
  if (onError) source.onerror = onError
  return () => source.close()
}

/**
 * Stream agent execution progress.
 * Falls back to synchronous execTask if SSE is not available.
 */
export function streamAgentExecution(
  task: string,
  agentId: string | undefined,
  sessionId: string | undefined,
  onChunk: (chunk: string) => void,
  onDone: (result: string) => void,
  onError: (err: Error) => void
): () => void {
  let done = false
  const stop = streamCheckpoints(
    (evt) => {
      if (done) return
      if (evt.event === 'complete' || evt.event === 'checkpoint') {
        const data = evt.data
        if (typeof data.result === 'string') {
          onChunk(data.result)
        }
        if (data.status === 'completed') {
          done = true
          onDone(typeof data.result === 'string' ? data.result : JSON.stringify(data))
        }
      }
    },
    (err) => {
      if (done) return
      done = true
      // SSE not available — fall back to synchronous
      execTask(task, agentId, sessionId)
        .then((r) => { onDone(typeof r === 'string' ? r : JSON.stringify(r)) })
        .catch(onError)
    }
  )
  return stop
}

// ─── Automation / Scheduler (Port 04) ──────────────────────────────────────

export function fetchAutomations(): Promise<AutomationListResponse> {
  return request<AutomationListResponse>('/automation')
}

export function getAutomation(taskId: string): Promise<AutomationDetail> {
  return request<AutomationDetail>(`/automation/${encodeURIComponent(taskId)}`)
}

export function createAutomation(payload: AutomationCreateRequest): Promise<{ ok: boolean; task: ScheduledTask }> {
  return request('/automation', json('POST', payload))
}

export function updateAutomation(taskId: string, changes: AutomationUpdateRequest): Promise<{ ok: boolean; task: ScheduledTask }> {
  return request(`/automation/${encodeURIComponent(taskId)}`, json('PUT', changes))
}

export function deleteAutomation(taskId: string): Promise<{ ok: boolean; id: string }> {
  return request(`/automation/${encodeURIComponent(taskId)}`, { method: 'DELETE' })
}

export function markSeen(taskId: string): Promise<{ ok: boolean }> {
  return request(`/automation/${encodeURIComponent(taskId)}/mark-seen`, json('POST', {}))
}

export function prepareManualRun(taskId: string): Promise<PrepareRunResponse> {
  return request(`/automation/${encodeURIComponent(taskId)}/prepare-run`, json('POST', {}))
}

export function finalizeManualRun(taskId: string, runId: string): Promise<{ ok: boolean; run: TaskRun }> {
  return request(`/automation/${encodeURIComponent(taskId)}/finalize-run/${encodeURIComponent(runId)}`, json('POST', {}))
}

// ─── Provenance Tracking (Port 05) ─────────────────────────────────────────

export function fetchProvenance(sessionId?: string): Promise<ProvenanceResponse> {
  const params = sessionId ? `?session=${encodeURIComponent(sessionId)}` : ''
  return request<ProvenanceResponse>(`/provenance${params}`)
}

export function resetProvenance(sessionId: string): Promise<{ ok: boolean; sessionId: string }> {
  const params = sessionId ? `?session=${encodeURIComponent(sessionId)}` : ''
  return request(`/provenance/reset${params}`, json('POST', {}))
}

