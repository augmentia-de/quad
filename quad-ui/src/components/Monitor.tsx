import { useEffect, useState, useCallback } from 'react'
import { fetchAllRuns, fetchMetrics, fetchErrorMetrics } from '../services/api'
import type { ErrorMetrics, TokenMetrics, WorkflowRun } from '../services/types'

export function Monitor() {
  const [metrics, setMetrics] = useState<TokenMetrics>({ prompt: 0, completion: 0, total: 0 })
  const [errorRates, setErrorRates] = useState<ErrorMetrics>({ toolCalls: 0, timeouts: 0, guardrails: 0 })
  const [error, setError] = useState<string | null>(null)
  const [runs, setRuns] = useState<WorkflowRun[]>([])
  const [selectedRun, setSelectedRun] = useState<WorkflowRun | null>(null)
  const [loading, setLoading] = useState(false)

  const refresh = useCallback(async () => {
    setLoading(true)
    try {
      const [tokens, errors, runsData] = await Promise.allSettled([
        fetchMetrics(),
        fetchErrorMetrics(),
        fetchAllRuns(),
      ])
      if (tokens.status === 'fulfilled') {
        setMetrics({
          prompt: tokens.value.prompt || 0,
          completion: tokens.value.completion || 0,
          total: tokens.value.total || 0,
        })
      }
      if (errors.status === 'fulfilled') {
        setErrorRates({
          toolCalls: errors.value.toolCalls || 0,
          timeouts: errors.value.timeouts || 0,
          guardrails: errors.value.guardrails || 0,
        })
      }
      if (runsData.status === 'fulfilled') {
        setRuns(runsData.value)
        setSelectedRun(prev => prev ? runsData.value.find(r => r.runId === prev.runId) ?? prev : prev)
      }
      setError(null)
    } catch (err) {
      setError('Metriken konnten nicht geladen werden: ' + (err instanceof Error ? err.message : String(err)))
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    refresh()
    const interval = setInterval(refresh, 5000)
    return () => clearInterval(interval)
  }, [refresh])

  return (
    <div className="monitor">
      <div className="monitor-header">
        <h2>Monitoring & Observability</h2>
        <button className="reload-btn" onClick={refresh} disabled={loading}>
          {loading ? '⏳' : '🔄'} Reload
        </button>
      </div>

      {error && <p className="monitor-error error-state">{error}</p>}

      <section className="monitoring-section">
        <h3>Workflow Runs</h3>
        {runs.length === 0 ? (
          <p className="empty-state">No runs yet. Run a workflow to monitor it here.</p>
        ) : (
          <table className="runs-table">
            <thead>
              <tr>
                <th>Run</th>
                <th>Workflow</th>
                <th>Status</th>
                <th>Gestartet</th>
                <th>Dauer</th>
                <th>Knoten</th>
              </tr>
            </thead>
            <tbody>
              {runs.map(run => (
                <tr key={run.runId} onClick={() => setSelectedRun(run)} style={{ cursor: 'pointer' }}>
                  <td><code>{run.runId}</code></td>
                  <td>{run.workflowId}</td>
                  <td><span className={`status-badge ${run.status}`}>{run.status.toUpperCase()}</span></td>
                  <td>{new Date(run.startedAt).toLocaleTimeString()}</td>
                  <td>{run.finishedAt ? `${run.durationMs} ms` : '—'}</td>
                  <td>{run.nodeResults?.length ?? 0}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>

      {selectedRun && (
        <section className="monitoring-section">
          <h3>Run-Detail: {selectedRun.runId}</h3>
          <div className="run-node-grid">
            {selectedRun?.nodeResults?.map(nr => (
              <div key={nr.nodeId} className={`run-node-card ${nr.status}`}>
                <strong>{nr.title}</strong>
                <span className="status-badge">{nr.status.toUpperCase()}</span>
                <details>
                  <summary>Details</summary>
                  {nr.input && (
                    <>
                      <h4>Input (SessionState):</h4>
                      <pre className="run-input">{nr.input}</pre>
                    </>
                  )}
                  <h4>Output:</h4>
                  <pre className="run-output">{nr.output}</pre>
                </details>
              </div>
            ))}
          </div>
        </section>
      )}

      <section className="monitoring-section">
        <h3>LLM Token Consumption</h3>
        <div className="metrics-grid">
          <div className="metric-card">
            <h4>Prompt Tokens</h4>
            <p className="metric-value">{metrics.prompt.toLocaleString()}</p>
          </div>
          <div className="metric-card">
            <h4>Completion Tokens</h4>
            <p className="metric-value">{metrics.completion.toLocaleString()}</p>
          </div>
          <div className="metric-card">
            <h4>Total Tokens</h4>
            <p className="metric-value">{metrics.total.toLocaleString()}</p>
          </div>
        </div>
      </section>

      <section className="monitoring-section">
        <h3>Error Analysis</h3>
        <div className="metrics-grid">
          <div className="metric-card">
            <h4>Failed Tool Calls</h4>
            <p className="metric-value">{errorRates.toolCalls}%</p>
          </div>
          <div className="metric-card">
            <h4>Timeout Rate</h4>
            <p className="metric-value">{errorRates.timeouts}%</p>
          </div>
          <div className="metric-card">
            <h4>Guardrail Triggers</h4>
            <p className="metric-value">{errorRates.guardrails}%</p>
          </div>
        </div>
      </section>
    </div>
  )
}