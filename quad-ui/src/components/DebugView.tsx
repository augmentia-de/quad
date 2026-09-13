import { useEffect, useState, useCallback } from 'react'
import { fetchJournal, clearJournal } from '../services/api'
import type { JournalEntry, JournalResponse } from '../services/types'

export function DebugView() {
  const [journal, setJournal] = useState<JournalResponse | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [filter, setFilter] = useState('')
  const [clearing, setClearing] = useState(false)

  const refresh = useCallback(async () => {
    setLoading(true)
    try {
      const data = await fetchJournal(200)
      setJournal(data)
      setError(null)
    } catch (err) {
      setError(String(err))
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    refresh()
    const interval = setInterval(refresh, 5000)
    return () => clearInterval(interval)
  }, [refresh])

  const handleClear = async () => {
    if (!window.confirm('Delete all journal entries?')) return
    setClearing(true)
    try {
      await clearJournal()
      setJournal({ enabled: false, count: 0, events: [] })
    } catch (err) {
      setError('Failed to clear journal: ' + String(err))
    } finally {
      setClearing(false)
    }
  }

  const payloadText = (entry: JournalEntry): string => {
    const p = entry.payload
    if (p == null) return ''
    if (typeof p === 'string') return p
    try {
      return JSON.stringify(p, null, 2)
    } catch {
      return String(p)
    }
  }

  const filtered = journal?.events.filter(e =>
    !filter ||
    String(e.type ?? '').toLowerCase().includes(filter.toLowerCase()) ||
    String(e.sessionId ?? '').toLowerCase().includes(filter.toLowerCase()) ||
    payloadText(e).toLowerCase().includes(filter.toLowerCase())
  ) ?? []

  const eventType = (entry: JournalEntry): string => String(entry.type ?? entry.eventType ?? '')

  const eventTypeColor = (type: string) => {
    if (!type) return 'default'
    if (type.includes('error') || type.includes('fail')) return 'error'
    if (type.includes('tool')) return 'tool'
    if (type.includes('llm') || type.includes('chat')) return 'llm'
    if (type.includes('guardrail')) return 'guardrail'
    return 'default'
  }

  return (
    <div className="debug-view">
      <div className="debug-header">
        <h2>Debug Console — Journal Trace</h2>
        <div className="debug-actions">
          <input
            type="text"
            value={filter}
            onChange={e => setFilter(e.target.value)}
            placeholder="Filtern nach Typ, Agent oder Inhalt..."
            className="debug-filter"
          />
          <button className="reload-btn" onClick={refresh} disabled={loading}>
            {loading ? '⏳' : '🔄'} Reload
          </button>
          <button className="small-btn reject" onClick={handleClear} disabled={clearing || !journal?.events.length}>
            🗑️ Clear
          </button>
        </div>
      </div>

      {error && <p className="error-state">{error}</p>}

      {journal && !journal.enabled && (
        <p className="warning-state">Journal is disabled. Enable it in the backend configuration.</p>
      )}

      <div className="journal-stats">
        <span>Total entries: {journal?.count ?? 0}</span>
        <span>Showing: {filtered.length}</span>
      </div>

      <div className="journal-entries">
        {filtered.length === 0 ? (
          <p className="empty-state">
            {journal?.events.length === 0
? 'No journal entries yet. Run an agent action to see trace data.'
               : 'No entries match the filter.'}
          </p>
        ) : (
          filtered.map((entry, i) => (
            <div key={i} className={`journal-entry ${eventTypeColor(eventType(entry))}`}>
              <div className="journal-entry-header">
                <span className="journal-timestamp">
                  {entry.timestamp ? new Date(entry.timestamp).toLocaleTimeString() : '—'}
                </span>
                <span className="journal-type">{eventType(entry)}</span>
                {entry.sessionId && <span className="journal-agent">session {entry.sessionId}</span>}
              </div>
              {payloadText(entry) && (
                <pre className="journal-detail">{payloadText(entry)}</pre>
              )}
            </div>
          ))
        )}
      </div>
    </div>
  )
}
