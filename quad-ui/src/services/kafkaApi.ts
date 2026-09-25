// ─── Unified Kafka API Layer ──────────────────────────────────────────────
// Calls the Unified API Gateway which routes to either Quarkus or Kafka backend.

const KAFKA_API_BASE = import.meta.env.VITE_KAFKA_API_BASE || '/api/v1'

async function kafkaRequest<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`${KAFKA_API_BASE}${path}`, init)
  if (!response.ok) {
    let message = `Kafka API request failed: ${response.status} ${path}`
    try {
      const body = (await response.json()) as Record<string, unknown>
      if (typeof body?.error === 'string') message = body.error
    } catch { /* ignore */ }
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

// ── Types for Kafka backend ──────────────────────────────────────────────

export interface KafkaDagNode {
  stepId: string
  nodeType: 'STEP' | 'DECISION' | 'LOOP' | 'FORK' | 'JOIN' | 'END'
  agentType?: string
  dependsOn?: string[]
  guardCondition?: string
  branches?: Array<{ branchId: string; condition: string; target: string }>
  joinType?: string
  requiresHumanApproval?: boolean
  timeoutMs?: number
  maxIterations?: number
  exitCondition?: string
}

export interface KafkaWorkflowDefinition {
  id: string
  name: string
  version: number
  nodes: KafkaDagNode[]
  isActive: boolean
  createdAt?: string
}

export interface KafkaStartWorkflowResponse {
  executionId: string
  duplicate: boolean
}

export interface KafkaCheckpointResponse {
  stepId: string
  agentType: string
  status: string
  iteration: number
  startedAt: string
  completedAt?: string
  outputPayload?: Record<string, unknown>
}

export interface KafkaWorkflowStatus {
  executionId: string
  workflowName: string
  status: string
  startedAt: string
  finishedAt?: string
  checkpoints: KafkaCheckpointResponse[]
}

export interface KafkaHitlTicket {
  ticketId: string
  workflowId: string
  stepId: string
  reason: string
  status: string
  assignedRole: string
  createdAt: string
}

export interface KafkaAgent {
  id: string
  name: string
  category: string
  modelKey: string
  temperature: number
  maxTokens: number
  timeoutSeconds: number
  maxRetryAttempts: number
  version: number
}

// ── Kafka Workflow Definitions ───────────────────────────────────────────

export function fetchKafkaWorkflows(): Promise<KafkaWorkflowDefinition[]> {
  return kafkaRequest<KafkaWorkflowDefinition[]>('/workflow-definitions')
}

export function fetchKafkaAgents(): Promise<KafkaAgent[]> {
  return kafkaRequest<KafkaAgent[]>('/agents')
}

export async function createKafkaWorkflow(name: string, nodes: KafkaDagNode[]): Promise<{ id: string; name: string; version: number }> {
  return kafkaRequest(`/workflow-definitions`, json('POST', { name, nodes }))
}

/**
 * Convert a quad-ui flat workflow into Kafka format, persist it into the shared
 * DB and start it. POST /api/v1/unified/workflows/from-quad
 *
 * Returns the persisted definition plus the running execution.
 */
export interface ConvertAndRunResult {
  converted: boolean
  nodeCount: number
  originalName: string
  kafkaId: string
  name: string
  version: number
  executionId: string
  duplicate: boolean
}

export async function convertQuadToKafka(
  quadName: string,
  quadNodes: Array<{ id: string; type: string; agentType?: string }>,
  quadEdges: Array<{ source: string; target: string; condition?: string }>,
  task?: string
): Promise<ConvertAndRunResult> {
  return kafkaRequest('/unified/workflows/from-quad', json('POST', {
    name: quadName,
    nodes: quadNodes.map(n => ({
      id: n.id,
      type: n.type,
      agentType: n.agentType,
    })),
    edges: quadEdges,
    task: task ?? 'Run workflow',
  })) as Promise<ConvertAndRunResult>
}

// ── Kafka Workflow Runs ──────────────────────────────────────────────────

export async function startKafkaWorkflow(
  workflowName: string,
  version: number,
  inputParams: Record<string, unknown>,
  idempotencyKey: string
): Promise<KafkaStartWorkflowResponse> {
  return kafkaRequest('/workflows/start', json('POST', {
    workflowName, version, inputParams, idempotencyKey,
  })) as Promise<KafkaStartWorkflowResponse>
}

export function getKafkaWorkflowStatus(executionId: string): Promise<KafkaWorkflowStatus> {
  return kafkaRequest<KafkaWorkflowStatus>(`/workflows/${encodeURIComponent(executionId)}`)
}

export function getKafkaCheckpoints(executionId: string): Promise<KafkaCheckpointResponse[]> {
  return kafkaRequest<KafkaCheckpointResponse[]>(`/workflows/${encodeURIComponent(executionId)}/checkpoints`)
}

export function cancelKafkaWorkflow(executionId: string): Promise<void> {
  return kafkaRequest<void>(`/workflows/${encodeURIComponent(executionId)}/cancel`, { method: 'POST' })
}

// ── Kafka HITL ───────────────────────────────────────────────────────────

export function fetchKafkaHitlTickets(): Promise<KafkaHitlTicket[]> {
  return kafkaRequest<KafkaHitlTicket[]>('/hitl/tickets')
}

export function approveKafkaHitl(ticketId: string, decidedBy: string): Promise<void> {
  return kafkaRequest(`/hitl/tickets/${encodeURIComponent(ticketId)}/approve`,
    json('POST', { action: 'APPROVE', decidedBy })
  )
}

export function rejectKafkaHitl(ticketId: string, decidedBy: string): Promise<void> {
  return kafkaRequest(`/hitl/tickets/${encodeURIComponent(ticketId)}/reject`,
    json('POST', { action: 'REJECT', decidedBy })
  )
}

// ── Kafka Agent Configs ──────────────────────────────────────────────────

export async function convertAgentForKafka(
  agentName: string,
  agentModel: string,
  tools: string[]
): Promise<{ converted: boolean; agentId: string; modelKey: string; allowedTools: string[] }> {
  return kafkaRequest('/unified/agents/convert', json('POST', {
    name: agentName,
    modelId: agentModel,
    toolDefinitions: tools.map(name => ({ name })),
  }),
  ) as Promise<{ converted: boolean; agentId: string; modelKey: string; allowedTools: string[] }>
}

// ── Metrics ──────────────────────────────────────────────────────────────

export interface MergedMetricsResponse {
  kafkaOrchestrator: { source: string; available: boolean }
  quadQuarkus: { source: string; available: boolean }
  merged: { status: string; timestamp: number }
}

export function fetchMergedMetrics(): Promise<MergedMetricsResponse> {
  return kafkaRequest<MergedMetricsResponse>('/unified/metrics')
}
