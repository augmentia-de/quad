import { useCallback, useEffect, useMemo, useState } from 'react'
import {
  continueRun,
  fetchAllRuns,
  fetchWorkflows,
  restartRun,
} from '../services/api'
import type { WorkflowRun, WorkflowSummary } from '../services/types'

const STATUS_TABS = [
  { id: 'all', label: 'All runs' },
  { id: 'running', label: 'Laufend' },
  { id: 'failed', label: 'Fehlerhaft' },
  { id: 'completed', label: 'Komplett' },
] as const

type TabId = (typeof STATUS_TABS)[number]['id']

function timeAgo(iso: string | null): string {
  if (!iso) return '—'
  try {
    const then = new Date(iso).getTime()
    const delta = Date.now() - then
    const s = Math.floor(delta / 1000)
    if (s < 60) return `${s}s`
    const m = Math.floor(s / 60)
    if (m < 60) return `${m}m`
    const h = Math.floor(m / 60)
    if (h < 24) return `${h}h`
    return `${Math.floor(h / 24)}d`
  } catch {
    return iso
  }
}

export function WorkflowProduction() {
  const [tab, setTab] = useState<TabId>('all')
  const [runs, setRuns] = useState<WorkflowRun[]>([])
  const [workflowNames, setWorkflowNames] = useState<Record<string, string>>({})
  const [selectedRunId, setSelectedRunId] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)
  const [busyAction, setBusyAction] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)

  const selectedRun = useMemo(
    () => runs.find(r => r.runId === selectedRunId) ?? null,
    [runs, selectedRunId],
  )

  const filtered = useMemo(
    () => (tab === 'all' ? runs : runs.filter(r => r.status === tab)),
    [runs, tab],
  )

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const [runsRes, wfRes] = await Promise.allSettled([fetchAllRuns(), fetchWorkflows()])
      if (runsRes.status === 'fulfilled') setRuns(runsRes.value)
      if (wfRes.status === 'fulfilled') {
        const map: Record<string, string> = {}
        wfRes.value.forEach((w: WorkflowSummary) => { map[w.id] = w.name })
        setWorkflowNames(map)
      }
      setError(null)
    } catch (err) {
      setError('Runs konnten nicht geladen werden: ' + (err instanceof Error ? err.message : String(err)))
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    load()
  }, [load])

  // Live-refresh while runs are running
  useEffect(() => {
    if (!runs.some(r => r.status === 'running')) return
    const interval = setInterval(load, 4000)
    return () => clearInterval(interval)
  }, [runs, load])

  const handleAction = async (fn: () => Promise<unknown>, actionKey: string) => {
    setBusyAction(actionKey)
    setError(null)
    try {
      await fn()
      await load()
    } catch (err) {
      setError(`Action fehlgeschlagen: ${err instanceof Error ? err.message : String(err)}`)
    } finally {
      setBusyAction(null)
    }
  }

  const statusClass = (s: string) => `status-badge ${s}`

  return (
    <div className="production">
      <div className="workflow-production">
        <h2>Workflow Production</h2>
        <p className="field-hint">
          All runs from the persistent run store (workflow_state). Failed runs can be resumed
          from the last completed step (Continue) or fully restarted (Restart). Live refresh
          while runs are in progress.
        </p>

        <div className="tabs" role="tablist">
          {STATUS_TABS.map(t => (
            <button
              key={t.id}
              role="tab"
              aria-selected={tab === t.id}
              className={tab === t.id ? 'active' : ''}
              onClick={() => setTab(t.id)}
            >
              {t.label}
            </button>
          ))}
        </div>

        {error && <p className="error-state">{error}</p>}
        {loading && <p className="field-hint">Lade Runs...</p>}

        <section className="production-section">
          <table className="runs-table">
            <thead>
              <tr>
                <th>Workflow</th>
                <th>Status</th>
                <th>Gestartet</th>
                <th>Dauer</th>
                <th>Aktionen</th>
              </tr>
            </thead>
            <tbody>
              {filtered.length === 0 ? (
                <tr><td colSpan={5} className="empty-state">No runs yet.</td></tr>
              ) : filtered.map((run) => (
                <tr
                  key={run.runId}
                  onClick={() => setSelectedRunId(run.runId)}
                  className={selectedRunId === run.runId ? 'selected-row' : ''}
                  style={{ cursor: 'pointer' }}
                >
                  <td>
                    <strong>{workflowNames[run.workflowId] ?? run.workflowId}</strong>
                    <div>
                      <code>{run.runId}</code>
                      {typeof run.restartCount === 'number' && run.restartCount > 0 && (
                        <span className="source-tag">restart #{run.restartCount}</span>
                      )}
                    </div>
                  </td>
                  <td><span className={statusClass(run.status)}>{run.status.toUpperCase()}</span></td>
                  <td>{timeAgo(run.startedAt)}</td>
                  <td>{run.durationMs ? `${run.durationMs} ms` : '—'}</td>
                  <td onClick={(e) => e.stopPropagation()}>
                    {run.status === 'failed' && (
                      <button type="button" className="small" disabled={!!busyAction}
                        onClick={() => handleAction(() => continueRun(run.runId), `continue-${run.runId}`)}>
                        ▶ Continue
                      </button>
                    )}
                    {run.status !== 'running' && (
                      <>
                        {' '}
                        <button type="button" className="small" disabled={!!busyAction}
                          onClick={() => handleAction(() => restartRun(run.runId), `restart-${run.runId}`)}>
                          ↻ Restart
                        </button>
                      </>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </section>

        {selectedRun && (
          <section className="production-section">
            <h3>Details: {selectedRun.runId}</h3>
            <div className="production-details">
              <div className="run-meta">
                <div><strong>ID:</strong> <code>{selectedRun.runId}</code></div>
                <div><strong>Workflow:</strong> {workflowNames[selectedRun.workflowId] ?? selectedRun.workflowId}</div>
                <div><strong>Status:</strong> <span className={statusClass(selectedRun.status)}>{selectedRun.status.toUpperCase()}</span></div>
                <div><strong>Gestartet:</strong> {selectedRun.startedAt ?? '—'}</div>
                <div><strong>Fertig:</strong> {selectedRun.finishedAt ?? '—'}</div>
                <div><strong>Dauer:</strong> {selectedRun.durationMs} ms</div>
                {selectedRun.executingFrom && <div><strong>Fortgesetzt ab:</strong> {selectedRun.executingFrom}</div>}
                {typeof selectedRun.restartCount === 'number' && selectedRun.restartCount > 0 && (
                  <div><strong>Restarts:</strong> {selectedRun.restartCount}</div>
                )}
              </div>

              <div className="action-bar">
                {selectedRun.status === 'failed' && (
                  <button type="button" disabled={!!busyAction}
                    onClick={() => handleAction(() => continueRun(selectedRun.runId), `continue-${selectedRun.runId}`)}>
                    ▶ Continue (letzter Schritt)
                  </button>
                )}
                {selectedRun.status !== 'running' && (
                  <button type="button" disabled={!!busyAction}
                    onClick={() => handleAction(() => restartRun(selectedRun.runId), `restart-${selectedRun.runId}`)}>
                    ↻ Restart
                  </button>
                )}
              </div>
            </div>

            {selectedRun.nodeResults.length > 0 && (
              <div className="production-events">
                <h4>Node-Ergebnisse</h4>
                <ul className="event-list">
                  {selectedRun.nodeResults.map(nr => (
                    <li key={nr.nodeId}>
                      <span className={statusClass(nr.status)}>{nr.status.toUpperCase()}</span>{' '}
                      {nr.title || nr.nodeId} — {nr.output || '–'}
                    </li>
                  ))}
                </ul>
              </div>
            )}
          </section>
        )}
      </div>
    </div>
  )
}