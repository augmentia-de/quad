import { useEffect, useState } from 'react'
import { approveHitl, fetchAuditLogs, fetchGuardrails, fetchHitlApprovals, rejectHitl } from '../services/api'
import { exportAuditLogs } from '../services/export'
import type { AuditLogEntry, GuardrailSpec, HitlApproval } from '../services/types'

export function SecurityCenter() {
  const [guardrails, setGuardrails] = useState<GuardrailSpec[]>([])
  const [guardrailError, setGuardrailError] = useState<string | null>(null)

  const [pendingApprovals, setPendingApprovals] = useState<HitlApproval[]>([])
  const [loading, setLoading] = useState(true)

  const [hitlFilters, setHitlFilters] = useState({
    period: 'all',
    agent: 'all',
    eventType: 'all',
  })
  const [auditEntries, setAuditEntries] = useState<AuditLogEntry[]>([])

  useEffect(() => {
    fetchGuardrails()
      .then(data => setGuardrails(data))
      .catch(err => setGuardrailError(String(err)))
  }, [])

  useEffect(() => {
    const loadApprovals = async () => {
      try {
        const data = await fetchHitlApprovals()
        setPendingApprovals(data)
      } catch {
        setPendingApprovals([])
      } finally {
        setLoading(false)
      }
    }
    loadApprovals()
  }, [])

  useEffect(() => {
    let cancelled = false
    fetchAuditLogs(hitlFilters)
      .then(data => { if (!cancelled) setAuditEntries(data) })
      .catch(() => { if (!cancelled) setAuditEntries([]) })
    return () => { cancelled = true }
  }, [hitlFilters])

  const handleFilterChange = (key: string, value: string) => {
    setHitlFilters(prev => ({ ...prev, [key]: value }))
  }

  const handleExport = (format: 'json' | 'csv') => {
    exportAuditLogs(auditEntries, format, 'security-export')
  }

  const handleApprove = async (id: string) => {
    const previous = pendingApprovals.find(a => a.id === id)
    setPendingApprovals(prev =>
      prev.map(a => a.id === id ? { ...a, status: 'approved' } : a)
    )
    try {
      await approveHitl(id)
    } catch (error) {
      console.error('Approval fehlgeschlagen:', error)
      setPendingApprovals(prev =>
        prev.map(a => a.id === id ? { ...a, status: previous?.status ?? 'pending' } : a)
      )
    }
  }

  const handleReject = async (id: string) => {
    const previous = pendingApprovals.find(a => a.id === id)
    setPendingApprovals(prev =>
      prev.map(a => a.id === id ? { ...a, status: 'rejected' } : a)
    )
    try {
      await rejectHitl(id)
    } catch (error) {
      console.error('Rejection fehlgeschlagen:', error)
      setPendingApprovals(prev =>
        prev.map(a => a.id === id ? { ...a, status: previous?.status ?? 'pending' } : a)
      )
    }
  }

  return (
    <div className="security-center">
      <h2>Security Center</h2>

      <section className="guardrails-section">
        <h3>Guardrail Overview</h3>
        {guardrailError ? (
          <p className="error-state">{guardrailError}</p>
        ) : guardrails.length === 0 ? (
          <p className="empty-state">Keine Guardrails konfiguriert. Weise Agents Guardrails zu.</p>
        ) : (
          <div className="status-grid">
            {guardrails.map(gr => (
              <div key={gr.name} className={`status-card ${gr.active ? 'active' : 'inactive'}`}>
                <h4>{gr.name}</h4>
                <span className={`status-indicator ${gr.active ? 'green' : 'yellow'}`}>
                  {gr.active ? '✅' : '⚠️'}
                </span>
                <p>Type: {gr.type}</p>
                <p>{gr.active ? 'Aktiviert' : 'Deaktiviert'}</p>
              </div>
            ))}
          </div>
        )}
      </section>

      <section className="hitl-section">
        <h3>HITL Management</h3>
        {loading ? (
          <p>Loading approvals...</p>
        ) : (
          <table className="hitl-table">
            <thead>
              <tr>
                <th>ID</th>
                <th>Agent</th>
                <th>Tool</th>
                <th>Request</th>
                <th>Since</th>
                <th>Status</th>
                <th>Actions</th>
              </tr>
            </thead>
            <tbody>
              {pendingApprovals
                .filter(a => a.status === 'pending')
                .map(a => (
                  <tr key={a.id}>
                    <td>{a.id}</td>
                    <td>{a.agent}</td>
                    <td>{a.tool}</td>
                    <td><code>{a.request}</code></td>
                    <td>{a.since}</td>
                    <td><span className="status-badge pending">PENDING</span></td>
                    <td>
                      <button className="approve" onClick={() => handleApprove(a.id)}>Approve</button>
                      <button className="reject" onClick={() => handleReject(a.id)}>Reject</button>
                    </td>
                  </tr>
                ))
              }
              {pendingApprovals.every(a => a.status !== 'pending') && (
                <tr>
                  <td colSpan={7}>No pending approvals</td>
                </tr>
              )}
            </tbody>
          </table>
        )}
      </section>

      <section className="audit-section">
        <h3>Audit Log</h3>
        <div className="audit-controls">
          <select value={hitlFilters.period} onChange={e => handleFilterChange('period', e.target.value)}>
            <option value="all">All Time Periods</option>
            <option value="today">Today</option>
            <option value="week">Last 7 days</option>
          </select>
          <select value={hitlFilters.agent} onChange={e => handleFilterChange('agent', e.target.value)}>
            <option value="all">All Agents</option>
          </select>
          <select value={hitlFilters.eventType} onChange={e => handleFilterChange('eventType', e.target.value)}>
            <option value="all">All Events</option>
            <option value="tool">Tool Execution</option>
            <option value="guardrail">Guardrail</option>
            <option value="error">Error</option>
          </select>
        </div>
        <button className="export-btn" onClick={() => handleExport('json')}>Export JSON</button>
        <button className="export-btn" onClick={() => handleExport('csv')}>Export CSV</button>
      </section>
    </div>
  )
}