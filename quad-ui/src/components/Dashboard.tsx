import { useEffect, useState } from 'react'
import { execTask, fetchAgentStatus, fetchAgents, fetchMcpStatus } from '../services/api'
import type { AgentDefinition, McpStatus, StatusResponse, UiExecutionResult } from '../services/types'

interface DashboardProps {
  onNavigate?: (tab: string) => void
}

const fallbackStatus: StatusResponse = {
  ready: false,
  model: 'gpt-4o-mini',
  baseUrl: 'unavailable',
  costLimit: 1.0,
  dummyMode: true,
}

export function Dashboard({ onNavigate }: DashboardProps) {
  const [status, setStatus] = useState<StatusResponse | null>(null)
  const [loading, setLoading] = useState(true)
  const [taskInput, setTaskInput] = useState('')
  const [taskResult, setTaskResult] = useState<UiExecutionResult | null>(null)
  const [taskRunning, setTaskRunning] = useState(false)
  const [taskError, setTaskError] = useState<string | null>(null)
  const [agents, setAgents] = useState<AgentDefinition[]>([])
  const [selectedAgent, setSelectedAgent] = useState<string>('')
  const [mcpStatus, setMcpStatus] = useState<McpStatus | null>(null)

  useEffect(() => {
    const loadAll = async () => {
      try {
        const data = await fetchAgentStatus()
        setStatus(data)
      } catch {
        setStatus(fallbackStatus)
      }
      try {
        const agentsData = await fetchAgents()
        setAgents(agentsData)
      } catch {
        // ignore
      }
      try {
        const mcpData = await fetchMcpStatus()
        setMcpStatus(mcpData)
      } catch {
        // ignore
      }
      setLoading(false)
    }
    loadAll()
  }, [])

  const handleExecute = async () => {
    if (!taskInput.trim() || taskRunning) return
    setTaskRunning(true)
    setTaskError(null)
    setTaskResult(null)
    try {
      const result = await execTask(taskInput.trim(), selectedAgent || undefined)
      setTaskResult(result)
    } catch (err) {
      setTaskError(String(err))
    } finally {
      setTaskRunning(false)
    }
  }

  const quickActions = [
    { label: 'New Workflow', action: () => onNavigate?.('workflows') },
    { label: 'Create Agent', action: () => onNavigate?.('agents') },
    { label: 'Security Scan', action: () => onNavigate?.('security') },
    { label: 'Sessions', action: () => onNavigate?.('sessions') },
  ]

  return (
    <div className="dashboard">
      <h1>QUAD Studio Dashboard</h1>

      <section className="actions-section">
        <h2>Quick Actions</h2>
        <div className="quick-actions">
          {quickActions.map(action => (
            <button key={action.label} onClick={action.action}>{action.label}</button>
          ))}
        </div>
      </section>

      <section className="task-execution-section">
        <h3>Quick Execution</h3>
        <div className="task-execution-form">
          <div className="task-execution-row">
            <input
              type="text"
              value={taskInput}
              onChange={e => setTaskInput(e.target.value)}
              placeholder="Enter task..."
              onKeyDown={e => { if (e.key === 'Enter') handleExecute() }}
            />
            <button onClick={handleExecute} disabled={taskRunning || !taskInput.trim()}>
              {taskRunning ? 'Running...' : 'Execute'}
            </button>
          </div>
          {agents.length > 0 && (
            <div className="agent-selector">
              <label htmlFor="agent-select">Agent:</label>
              <select
                id="agent-select"
                value={selectedAgent}
                onChange={e => setSelectedAgent(e.target.value)}
              >
                <option value="">(Standard)</option>
                {agents.map(a => (
                  <option key={a.id} value={a.id}>{a.name}</option>
                ))}
              </select>
            </div>
          )}
        </div>
        {taskError && <p className="error-state">{taskError}</p>}
        {taskResult && (
          <div className="task-result">
            <div className="task-result-header">
              <span className={`status-badge ${taskResult.success ? 'completed' : 'failed'}`}>
                {taskResult.success ? '✓ Erfolgreich' : '✗ Fehlgeschlagen'}
              </span>
              <span className="task-meta">
                {taskResult.toolCount} Tools · {taskResult.durationMs} ms · {taskResult.model}
              </span>
            </div>
            <pre>{taskResult.result}</pre>
          </div>
        )}
      </section>

      {loading ? (
        <div className="loading">Loading...</div>
      ) : (
        <div className="status-grid">
          <div className="agent-card">
            <h3>QUAD Backend</h3>
            <p><strong>Ready:</strong> {status?.ready ? 'Yes' : 'No'}</p>
            <p><strong>Model:</strong> {status?.model}</p>
            <p><strong>Base URL:</strong> {status?.baseUrl}</p>
            <p><strong>Cost Limit:</strong> ${status?.costLimit}</p>
            <p><strong>Mode:</strong> {status?.dummyMode ? 'Dummy' : 'Live'}</p>
          </div>

          {mcpStatus && (
            <div className="agent-card">
              <h3>MCP Status</h3>
              <p><strong>Enabled:</strong> {mcpStatus.enabled ? 'Yes' : 'No'}</p>
              <p><strong>Tools:</strong> {mcpStatus.toolCount}</p>
              {mcpStatus.lastSetupAt && (
                <p><strong>Last Setup:</strong> {new Date(mcpStatus.lastSetupAt).toLocaleString()}</p>
              )}
              {mcpStatus.servers.length > 0 && (
                <div className="mcp-servers">
                  <strong>Servers:</strong>
                  {mcpStatus.servers.map(s => (
                    <div key={s.name} className="mcp-server-entry">
                      <span>{s.name}</span>
                      <span className="status-badge">{s.transport}</span>
                      <span>{s.toolCount} tools</span>
                      {s.error && <span className="error-state">{s.error}</span>}
                    </div>
                  ))}
                </div>
              )}
            </div>
          )}
        </div>
      )}
    </div>
  )
}
