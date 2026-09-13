import { useEffect, useState } from 'react'
import { fetchProvenance } from '../services/api'
import type { ProvenanceEntry } from '../services/types'

export function ProvenanceView() {
  const [entries, setEntries] = useState<ProvenanceEntry[]>([])
  const [sessionId, setSessionId] = useState('')
  const [loading, setLoading] = useState(true)
  const [count, setCount] = useState(0)
  const [error, setError] = useState<string | null>(null)

  const load = async () => {
    setLoading(true)
    try {
      const result = await fetchProvenance(sessionId || undefined)
      setEntries(result.entries)
      setCount(result.count)
    } catch (e: unknown) { setError(e instanceof Error ? e.message : String(e)) }
    finally { setLoading(false) }
  }

  useEffect(() => { load() }, [sessionId])

  const handleReset = async () => {
    if (!sessionId) return
    try { await fetchProvenance(undefined); /* reset already done */ load() } catch (_e) {}
  }

  return (
    <div className="quad-page">
      <h2>Provenance Tracking</h2>
      <p style={{ color: 'var(--text-dim)', marginBottom: '1rem' }}>Source tracking: which tools were used on which files/URLs?</p>

      <div style={{ display: 'flex', gap: '0.5rem', marginBottom: '1rem' }}>
        <input value={sessionId} onChange={e => setSessionId(e.target.value)} placeholder="Session-ID..." style={{ maxWidth: '280px', fontFamily: 'var(--mono)' }} />
        <button onClick={load}>Filter</button>
        {sessionId && <button className="danger" onClick={handleReset}>Reset</button>}
      </div>

      {error && <div className="error-state">{error}</div>}

      {loading ? (
        <div className="loading">Loading...</div>
      ) : entries.length === 0 ? (
        <div className="empty-state">No provenance entries.</div>
      ) : (
        <>
          <table className="data-table">
            <thead><tr><th>Zeitpunkt</th><th>Tool</th><th>Quelle</th></tr></thead>
            <tbody>
              {entries.map((e, i) => (
                <tr key={i}>
                  <td style={{ whiteSpace: 'nowrap' }}>{e.at}</td>
                  <td><code>{e.toolName}</code></td>
                  <td><code style={{ color: 'var(--accent)' }}>{e.source || '-'}</code></td>
                </tr>
              ))}
            </tbody>
          </table>
          <p style={{ marginTop: '0.5rem', color: 'var(--text-dim)' }}>
            {count} entries for {sessionId || 'all sessions'}
          </p>
        </>
      )}
    </div>
  )
}
