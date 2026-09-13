import { useEffect, useState } from 'react'
import { fetchWorkspaceRecent, openWorkspace, setTrust, fetchTrustedWorkspaces, addRoot, removeRoot, createTempSession } from '../services/api'
import type { WorkspaceRecord } from '../services/types'

export function WorkspaceManager() {
  const [recent, setRecent] = useState<WorkspaceRecord[]>([])
  const [trusted, setTrusted] = useState<WorkspaceRecord[]>([])
  const [newPath, setNewPath] = useState('')
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  const load = async () => {
    setLoading(true)
    try {
      const [rec, tru] = await Promise.all([fetchWorkspaceRecent(20), fetchTrustedWorkspaces()])
      setRecent(rec)
      // trusted is array of TrustResponse, extract paths for display
      setTrusted(tru.map(t => ({ path: t.workspace, name: '', trusted: t.trusted, commandTrust: JSON.stringify(t.requested_commands), gitBranch: null, lastAccessedAt: null })) as unknown as WorkspaceRecord[])
    } catch (e: unknown) { setError(e instanceof Error ? e.message : String(e)) }
    finally { setLoading(false) }
  }

  useEffect(() => { load() }, [])

  const handleOpen = async () => {
    if (!newPath.trim()) return
    try { await openWorkspace(newPath.trim()); setNewPath(''); load() } catch (e: unknown) { setError(e instanceof Error ? e.message : String(e)) }
  }

  const handleTrust = async (path: string, trusted: boolean) => {
    try {
      await setTrust(path, trusted)
      load()
    } catch (_e) {}
  }

  return (
    <div className="quad-page">
      <h2>Workspace & Permission</h2>
      <p style={{ color: 'var(--text-dim)', marginBottom: '1rem' }}>Workspace-Verwaltung + Befehls-Permissions</p>

      <div style={{ marginBottom: '1rem', display: 'flex', gap: '0.5rem' }}>
        <input value={newPath} onChange={e => setNewPath(e.target.value)} placeholder="Workspace-Pfad eingeben..." style={{ flex: 1, fontFamily: 'var(--mono)' }} />
        <button onClick={handleOpen}>Öffnen</button>
        <button onClick={load}>Reload</button>
      </div>

      {error && <div className="error-state">{error}</div>}

      {loading ? (
        <div className="loading">Loading...</div>
      ) : recent.length === 0 && trusted.length === 0 ? (
        <div className="empty-state">No workspaces registered.</div>
      ) : (
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '1rem' }}>
          {/* Recent Workspaces */}
          <div>
            <h3>Recently opened</h3>
            {recent.length === 0 ? (
              <div className="empty-state" style={{ padding: '1rem' }}>Leere Liste.</div>
            ) : (
              <table className="data-table">
                <thead><tr><th>Pfad</th><th>Name</th><th>Git Branch</th></tr></thead>
                <tbody>
                  {recent.map(w => (
                    <tr key={w.path}>
                      <td><code>{w.path}</code></td>
                      <td>{w.name || '-'}</td>
                      <td>{w.gitBranch || '-'}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </div>

          {/* Trusted Workspaces */}
          <div>
            <h3>Vertraute Workspaces</h3>
            {trusted.length === 0 ? (
              <div className="empty-state" style={{ padding: '1rem' }}>Keine vertrauten Workspaces.</div>
            ) : (
              <table className="data-table">
                <thead><tr><th>Pfad</th><th>Trust</th><th>Aktion</th></tr></thead>
                <tbody>
                  {trusted.map(w => (
                    <tr key={w.path}>
                      <td><code>{w.path}</code></td>
                      <td><span className={`badge ${w.trusted ? 'success' : 'warning'}`}>{w.trusted ? 'trust' : 'no trust'}</span></td>
                      <td>
                        <button className="danger" onClick={() => handleTrust(w.path, !w.trusted)}>Toggle</button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </div>
        </div>
      )}
    </div>
  )
}
