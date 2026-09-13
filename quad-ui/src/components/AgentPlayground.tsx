import { useState } from 'react'
import { execTask } from '../services/api'
import type { AgentDefinition, UiExecutionResult } from '../services/types'

interface AgentPlaygroundProps {
  agents: AgentDefinition[]
}

interface ChatMessage {
  role: 'user' | 'agent'
  text: string
}

/** Test agents directly: enter a task, run it, inspect the result */
export function AgentPlayground({ agents }: AgentPlaygroundProps) {
  const [agentId, setAgentId] = useState('')
  const [task, setTask] = useState('')
  const [messages, setMessages] = useState<ChatMessage[]>([])
  const [lastMeta, setLastMeta] = useState<Pick<UiExecutionResult, 'sessionId' | 'toolCount' | 'durationMs' | 'model'> | null>(null)
  const [chatHistory, setChatHistory] = useState(false)
  const [running, setRunning] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const sessionId = lastMeta?.sessionId ?? ''

  const handleRun = async () => {
    if (!task.trim() || running) return
    setRunning(true)
    setError(null)
    const request = task.trim()
    try {
      const res = await execTask(request, agentId || undefined, chatHistory ? sessionId : undefined)
      setMessages(prev => [
        ...(chatHistory ? prev : []),
        { role: 'user', text: request },
        { role: 'agent', text: res.result },
      ])
      setLastMeta({ sessionId: res.sessionId, toolCount: res.toolCount, durationMs: res.durationMs, model: res.model })
    } catch (err) {
      setError(String(err))
    } finally {
      setRunning(false)
    }
  }

  return (
    <div className="agent-playground">
      <h3>Agent Playground</h3>
      <div className="playground-row">
        <select value={agentId} onChange={e => setAgentId(e.target.value)} aria-label="Agent for test">
          <option value="">— No agent binding —</option>
          {agents.map(a => (
            <option key={a.id} value={a.id}>{a.name} ({a.model})</option>
          ))}
        </select>
        <input
          type="text"
          value={task}
          onChange={e => setTask(e.target.value)}
          placeholder="Enter task and test..."
          onKeyDown={e => { if (e.key === 'Enter') handleRun() }}
        />
        <button onClick={handleRun} disabled={running || !task.trim()}>
          {running ? 'Running...' : '▶ Run'}
        </button>
      </div>

      <label className="playground-chat-history">
        <input
          type="checkbox"
          checked={chatHistory}
          onChange={e => setChatHistory(e.target.checked)}
        />
        Carry chat history (next request continues the session)
      </label>
      {chatHistory && sessionId && (
        <small className="playground-session-hint">
          Fortlaufende Session: <code>{sessionId}</code>
        </small>
      )}

      {error && <p className="error-state">{error}</p>}

      {messages.length === 0 ? (
        !error && <p className="empty-state">Gib oben eine Aufgabe ein und teste den Agent.</p>
      ) : (
        <div className="playground-result">
          {lastMeta && (
            <div className="playground-meta">
              <span><strong>Session:</strong> {lastMeta.sessionId}</span>
              <span><strong>Tools:</strong> {lastMeta.toolCount}</span>
              <span><strong>Dauer:</strong> {lastMeta.durationMs} ms</span>
              <span><strong>Modell:</strong> {lastMeta.model}</span>
            </div>
          )}
          <div className="playground-chat">
            {messages.map((m, i) => (
              <div key={i} className={`chat-message ${m.role}`}>
                <strong className="chat-role">{m.role === 'user' ? 'Request' : 'Response'}</strong>
                <pre>{m.text}</pre>
              </div>
            ))}
          </div>
        </div>
      )}
    </div>
  )
}