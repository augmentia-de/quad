import { useEffect, useState, useCallback } from 'react'
import { fetchRuns, execTask } from '../services/api'
import type { RunView, RunsFilter } from '../services/types'

const PERIODS = ['all', 'today', 'week', 'month']
const KINDS = ['all', 'tool', 'model', 'run']
const STATUSES = ['all', 'running', 'completed', 'failed']

const POLL_MS = 5000

export function Runs() {
  const [runs, setRuns] = useState<RunView[]>([])
  const [filters, setFilters] = useState<RunsFilter>({
    period: 'all',
    kind: 'all',
    status: 'all',
    runId: '',
  })
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [live, setLive] = useState(true)
  const [resuming, setResuming] = useState<string | null>(null)

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const data = await fetchRuns(filters)
      setRuns(data)
      setError(null)
    } catch (err) {
      setError(String(err))
    } finally {
      setLoading(false)
    }
  }, [filters])

  useEffect(() => { load() }, [load])

  // Live-Status via Polling (solange aktiv)
  useEffect(() => {
    if (!live) return
    const id = setInterval(() => { fetchRuns(filters).then(setRuns).catch(() => {}) }, POLL_MS)
    return () => clearInterval(id)
  }, [live, filters])

  const handleResume = async (run: RunView) => {
    if (!window.confirm(`Run ${run.runId} fortsetzen?`)) return
    setResuming(run.runId)
    try {
      await execTask('Continue', undefined, run.runId)
      setResuming(null)
    } catch (err) {
      setError('Continue fehlgeschlagen: ' + String(err))
      setResuming(null)
    }
  }

  return (
    <div className="runs-view">
      <div className="runs-header">
        <h2>Runs</h2>
        <div className="runs-actions">
          <label className="inline-check">
            <input type="checkbox" checked={live} onChange={e => setLive(e.target.checked)} />
            Live
          </label>
          <button className="reload-btn" onClick={load} disabled={loading}>
            {loading ? '⏳' : '🔄'} Reload
          </button>
        </div>
      </div>

      {error && <p className="error-state">{error}</p>}

      <div className="runs-controls">
        <label>
          Period:
          <select value={filters.period} onChange={e => setFilters({ ...filters, period: e.target.value })}>
            {PERIODS.map(p => <option key={p} value={p}>{p === 'all' ? 'All periods' : p}</option>)}
          </select>
        </label>

        <label>
          Kind:
          <select value={filters.kind} onChange={e => setFilters({ ...filters, kind: e.target.value })}>
            {KINDS.map(k => <option key={k} value={k}>{k === 'all' ? 'All kinds' : k}</option>)}
          </select>
        </label>

        <label>
          Status:
          <select value={filters.status} onChange={e => setFilters({ ...filters, status: e.target.value })}>
            {STATUSES.map(s => <option key={s} value={s}>{s === 'all' ? 'All status' : s}</option>)}
          </select>
        </label>

        <label>
          Run ID:
          <input
            type="text"
            value={filters.runId ?? ''}
            placeholder="Filter by run/session id"
            onChange={e => setFilters({ ...filters, runId: e.target.value })}
          />
        </label>
      </div>

      {runs.length === 0 ? (
        <p className="empty-state">Keine Runs vorhanden.</p>
      ) : (
        <table className="runs-table">
          <thead>
            <tr>
              <th>Run ID</th>
              <th>Kind</th>
              <th>Status</th>
              <th>Steps</th>
              <th>Started</th>
              <th>Finished</th>
              <th>Duration</th>
              <th>Actions</th>
            </tr>
          </thead>
          <tbody>
            {runs.map(run => (
              <RunRow key={run.runId} run={run} onResume={handleResume} resuming={resuming === run.runId} />
            ))}
          </tbody>
        </table>
      )}
    </div>
  )
}

function RunRow({ run, onResume, resuming }: {
  run: RunView
  onResume: (run: RunView) => void
  resuming: boolean
}) {
  return (
    <>
      <tr>
        <td><code>{run.runId.length > 12 ? run.runId.slice(0, 12) + '…' : run.runId}</code></td>
        <td><span className={`badge runs-kind-${run.kind}`}>{run.kind}</span></td>
        <td><span className={`badge runs-status-${run.status}`}>{run.status}</span></td>
        <td>{run.stepIndex}</td>
        <td>{run.startedAt ? new Date(run.startedAt).toLocaleString() : '—'}</td>
        <td>{run.finishedAt ? new Date(run.finishedAt).toLocaleString() : '—'}</td>
        <td>{run.durationMs} ms</td>
        <td>
          <button
            className="small-btn"
            onClick={() => onResume(run)}
            disabled={resuming}
            title="Run fortsetzen (Continue)"
          >
            {resuming ? '⏳' : '▶️'} Continue
          </button>
        </td>
      </tr>
      {run.steps.length > 0 && (
        <tr className="run-steps-row">
          <td colSpan={8}>
            <details>
              <summary>Steps ({run.steps.length})</summary>
              <table className="run-steps-table">
                <thead>
                  <tr>
                    <th>Step ID</th>
                    <th>Kind</th>
                    <th>Status</th>
                    <th>Duration</th>
                    <th>Time</th>
                  </tr>
                </thead>
                <tbody>
                  {run.steps.map(step => (
                    <tr key={step.stepId + step.timestamp}>
                      <td><code>{step.stepId}</code></td>
                      <td>{step.kind}</td>
                      <td>{step.status}</td>
                      <td>{step.durationMs} ms</td>
                      <td>{step.timestamp ? new Date(step.timestamp).toLocaleString() : '—'}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </details>
          </td>
        </tr>
      )}
    </>
  )
}
