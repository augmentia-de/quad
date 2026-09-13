import { useEffect, useState, useCallback } from 'react'
import { fetchSessions, fetchSession, gdprExport, gdprDelete } from '../services/api'
import type { SessionSummary, SessionDetail } from '../services/types'

export function Sessions() {
  const [sessions, setSessions] = useState<SessionSummary[]>([])
  const [selected, setSelected] = useState<SessionDetail | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [gdprBusy, setGdprBusy] = useState(false)

  const refresh = useCallback(async () => {
    setLoading(true)
    try {
      const data = await fetchSessions()
      setSessions(data)
      setError(null)
    } catch (err) {
      setError(String(err))
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => { refresh() }, [refresh])

  const loadDetail = async (id: string) => {
    try {
      const detail = await fetchSession(id)
      setSelected(detail)
    } catch (err) {
      setError('Session laden fehlgeschlagen: ' + String(err))
    }
  }

  const handleGdprExport = async (sessionId: string) => {
    setGdprBusy(true)
    try {
      const data = await gdprExport(sessionId)
      const blob = new Blob([JSON.stringify(data, null, 2)], { type: 'application/json' })
      const url = URL.createObjectURL(blob)
      const a = document.createElement('a')
      a.href = url
      a.download = `gdpr-export-${sessionId}.json`
      a.click()
      URL.revokeObjectURL(url)
    } catch (err) {
      setError('GDPR Export fehlgeschlagen: ' + String(err))
    } finally {
      setGdprBusy(false)
    }
  }

  const handleGdprDelete = async (sessionId: string) => {
    if (!window.confirm(`Delete session ${sessionId}? This cannot be undone.`)) return
    setGdprBusy(true)
    try {
      await gdprDelete(sessionId)
      setSelected(null)
      await refresh()
    } catch (err) {
      setError('GDPR Delete fehlgeschlagen: ' + String(err))
    } finally {
      setGdprBusy(false)
    }
  }

  return (
    <div className="sessions-view">
      <div className="sessions-header">
        <h2>Sessions</h2>
        <button className="reload-btn" onClick={refresh} disabled={loading}>
          {loading ? '⏳' : '🔄'} Reload
        </button>
      </div>

      {error && <p className="error-state">{error}</p>}

      <section className="sessions-list-section">
        {sessions.length === 0 ? (
          <p className="empty-state">Keine Sessions vorhanden.</p>
        ) : (
          <table className="sessions-table">
            <thead>
              <tr>
                <th>ID</th>
                <th>Erstellt</th>
                <th>Last activity</th>
                <th>Task</th>
                <th>Actions</th>
              </tr>
            </thead>
            <tbody>
              {sessions.map(s => (
                <tr
                  key={s.id}
                  className={selected?.session.id === s.id ? 'selected' : ''}
                  onClick={() => loadDetail(s.id)}
                  style={{ cursor: 'pointer' }}
                >
                  <td><code>{s.id.length > 12 ? s.id.slice(0, 12) + '…' : s.id}</code></td>
                  <td>{new Date(s.createdAt).toLocaleString()}</td>
                  <td>{new Date(s.lastSeen).toLocaleString()}</td>
                  <td>{s.task ? (s.task.length > 60 ? s.task.slice(0, 60) + '…' : s.task) : '—'}</td>
                  <td>
                    <button
                      className="small-btn"
                      onClick={e => { e.stopPropagation(); handleGdprExport(s.id) }}
                      disabled={gdprBusy}
                      title="GDPR Export"
                    >
                      📦 Export
                    </button>
                    <button
                      className="small-btn reject"
                      onClick={e => { e.stopPropagation(); handleGdprDelete(s.id) }}
                      disabled={gdprBusy}
                      title="GDPR Delete"
                    >
                      🗑️ Delete
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>

      {selected && (
        <section className="session-detail-section">
          <h3>Session Detail: <code>{selected.session.id}</code></h3>

          <div className="session-meta">
            <div className="meta-field">
              <strong>Task:</strong>
              <pre>{selected.session.task || '—'}</pre>
            </div>
            <div className="meta-field">
              <strong>Result:</strong>
              <pre>{selected.session.result || '—'}</pre>
            </div>
            <div className="meta-field">
              <strong>Memory Summary:</strong>
              <pre>{selected.session.memorySummary || '—'}</pre>
            </div>
          </div>

          <h4>Events ({selected.events.length})</h4>
          {selected.events.length === 0 ? (
            <p className="empty-state">No events for this session.</p>
          ) : (
            <div className="session-events">
              {selected.events.map(ev => (
                <div key={ev.id} className="session-event-card">
                  <span className="event-type">{ev.eventType}</span>
                  <span className="event-time">{new Date(ev.timestamp).toLocaleTimeString()}</span>
                  <details>
                    <summary>Payload</summary>
                    <pre>{ev.payload}</pre>
                  </details>
                </div>
              ))}
            </div>
          )}
        </section>
      )}
    </div>
  )
}
