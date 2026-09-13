import { useEffect, useState, useCallback } from 'react'
import { fetchAgents, fetchAuditLogs } from '../services/api'
import { exportAuditLogs } from '../services/export'
import type { AgentDefinition, AuditLogEntry } from '../services/types'

export function Audit() {
  const [entries, setEntries] = useState<AuditLogEntry[]>([])
  const [agents, setAgents] = useState<AgentDefinition[]>([])
  const [filters, setFilters] = useState({
    period: 'all',
    agent: 'all',
    eventType: 'all',
  })
  const [loading, setLoading] = useState(false)

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const data = await fetchAuditLogs(filters)
      setEntries(data)
    } catch {
      setEntries([])
    } finally {
      setLoading(false)
    }
  }, [filters])

  useEffect(() => { load() }, [load])

  useEffect(() => {
    let cancelled = false
    fetchAgents()
      .then(data => { if (!cancelled) setAgents(data) })
      .catch(() => {})
    return () => { cancelled = true }
  }, [])

  const agentOptions = ['all', ...new Set(agents.map(a => a.id))]

  const handleExport = (format: 'json' | 'csv') => {
    exportAuditLogs(entries, format, 'audit-export')
  }

  return (
    <div className="audit">
      <div className="audit-header">
        <h2>Audit Trail</h2>
        <button className="reload-btn" onClick={load} disabled={loading}>
          {loading ? '⏳' : '🔄'} Reload
        </button>
      </div>

      <div className="audit-controls">
        <label>
          Period:
          <select value={filters.period} onChange={e => setFilters({...filters, period: e.target.value})}>
            <option value="all">All periods</option>
            <option value="today">Today</option>
            <option value="week">Last 7 days</option>
            <option value="month">Last month</option>
          </select>
        </label>

        <label>
          Agent:
          <select value={filters.agent} onChange={e => setFilters({...filters, agent: e.target.value})}>
            {agentOptions.map(a => (
              <option key={a} value={a}>{a === 'all' ? 'All agents' : a}</option>
            ))}
          </select>
        </label>

        <label>
          Event type:
          <select value={filters.eventType} onChange={e => setFilters({...filters, eventType: e.target.value})}>
            <option value="all">All events</option>
            <option value="tool">Tool Execution</option>
            <option value="guardrail">Guardrail</option>
            <option value="error">Error</option>
          </select>
        </label>

        <button className="export-btn" onClick={() => handleExport('json')}>Export JSON</button>
        <button className="export-btn" onClick={() => handleExport('csv')}>Export CSV</button>
      </div>

      {entries.length === 0 ? (
        <p className="empty-state">No audit entries.</p>
      ) : (
        <table className="audit-table">
          <thead>
            <tr>
              <th>ID</th>
              <th>Time</th>
              <th>Agent</th>
              <th>Event</th>
              <th>Details</th>
            </tr>
          </thead>
          <tbody>
            {entries.map(entry => (
              <tr key={entry.id}>
                <td>{entry.id}</td>
                <td>{entry.timestamp}</td>
                <td>{entry.agent}</td>
                <td>{entry.event}</td>
                <td><code>{entry.details}</code></td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  )
}