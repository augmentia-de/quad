import { useEffect, useState } from 'react'
import { fetchAutomations, createAutomation, updateAutomation, deleteAutomation, markSeen, getAutomation, prepareManualRun, finalizeManualRun } from '../services/api'
import type { ScheduledTask, TaskRun, ScheduleDefinition } from '../services/types'

function statusBadge(status: string) {
  const cls = status === 'ok' ? 'success' : status === 'running' ? 'running' : status === 'error' ? 'failed' : ''
  return cls ? <span className={`status-badge ${cls}`}>{status.toUpperCase()}</span> : status
}

function timeFromEpoch(sec: number | null): string {
  if (sec == null) return '-'
  return new Date(sec * 1000).toISOString().replace('T', ' ').slice(0, 19)
}

export function Automation() {
  const [tasks, setTasks] = useState<ScheduledTask[]>([])
  const [detailTask, setDetailTask] = useState<ScheduledTask | null>(null)
  const [runs, setRuns] = useState<TaskRun[]>([])
  const [loading, setLoading] = useState(true)
  const [showCreate, setShowCreate] = useState(false)
  const [title, setTitle] = useState('')
  const [instructions, setInstructions] = useState('')
  const [cronExpr, setCronExpr] = useState('')
  const [fireAt, setFireAt] = useState('')
  const [error, setError] = useState<string | null>(null)

  const load = async () => {
    setLoading(true)
    try {
      const res = await fetchAutomations()
      setTasks(res.tasks as unknown as ScheduledTask[])
    } catch (e: unknown) { setError(e instanceof Error ? e.message : String(e)) }
    finally { setLoading(false) }
  }

  useEffect(() => { load() }, [])

  const showDetail = async (task: ScheduledTask) => {
    setDetailTask(task)
    try { const d = await getAutomation(task.id); setRuns(d.runs) } catch (_e) { setRuns([]) }
  }

  const hideDetail = () => { setDetailTask(null); setRuns([]) }

  const handleCreate = async () => {
    if (!title.trim() || !instructions.trim()) return
    try {
      await createAutomation({ title: title.trim(), instructions: instructions.trim(), cron: cronExpr.trim() || undefined, fire_at: fireAt.trim() || undefined })
      setShowCreate(false)
      setTitle(''); setInstructions(''); setCronExpr(''); setFireAt('')
      load()
    } catch (e: unknown) { setError(e instanceof Error ? e.message : String(e)) }
  }

  const handleDelete = async (id: string) => {
    if (!confirm(`Delete automation "${id}"?`)) return
    try { await deleteAutomation(id); load(); hideDetail() } catch (_e) {}
  }

  const handleMarkSeen = async (id: string) => {
    try { await markSeen(id); load() } catch (_e) {}
  }

  const runTask = async (task: ScheduledTask) => {
    try {
      const pr = await prepareManualRun(task.id)
      alert(`Run prepared. Run-ID: ${pr.run_id}\nRun it first in the Playground, then confirm via finalize.`)
    } catch (_e) {}
  }

  return (
    <div className="quad-page">
      <h2>Automation Scheduler</h2>
      <p style={{ color: 'var(--text-dim)', marginBottom: '1rem' }}>CRON-controlled and one-off tasks</p>

      <div style={{ marginBottom: '1rem', display: 'flex', gap: '0.5rem' }}>
        <button onClick={() => setShowCreate(!showCreate)}>New automation</button>
        <button onClick={load}>Reload</button>
      </div>

      {error && <div className="error-state">{error}</div>}

      {/* Create form */}
      {showCreate && (
        <div style={{ background: 'var(--surface)', padding: '1rem', borderRadius: 'var(--radius)', marginBottom: '1rem' }}>
          <h3>New automation</h3>
          <div style={{ display: 'flex', flexDirection: 'column', gap: '0.5rem' }}>
            <input value={title} onChange={e => setTitle(e.target.value)} placeholder="Titel *" style={{ fontFamily: 'var(--mono)' }} />
            <textarea value={instructions} onChange={e => setInstructions(e.target.value)} placeholder="Anweisungen *" rows={3} />
            <input value={cronExpr} onChange={e => setCronExpr(e.target.value)} placeholder="Cron-Ausdruck (z.B. '10 19 * * *')" style={{ fontFamily: 'var(--mono)' }} />
            <small style={{ color: 'var(--text-dim)' }}>Or for a one-off run: enter an ISO datetime in "fire_at".</small>
            <input value={fireAt} onChange={e => setFireAt(e.target.value)} placeholder="fire_at ISO datetime" style={{ fontFamily: 'var(--mono)' }} />
            <div style={{ display: 'flex', gap: '0.5rem' }}>
              <button onClick={() => setShowCreate(false)}>Abbrechen</button>
              <button onClick={handleCreate}>Erstellen</button>
            </div>
          </div>
        </div>
      )}

      {loading ? (
        <div className="loading">Loading...</div>
      ) : tasks.length === 0 ? (
        <div className="empty-state">Keine Automations vorhanden.</div>
      ) : (
        <>
          <table className="data-table">
            <thead><tr><th>Name</th><th>Schedule</th><th>Status</th><th>Runs</th><th>Next</th><th>Actions</th></tr></thead>
            <tbody>
              {tasks.map(t => (
                <tr key={t.id} style={{ cursor: 'pointer' }} onClick={() => showDetail(t)}>
                  <td>{t.title}</td>
                  <td>{t.schedule_raw.kind === 'once' ? t.schedule_raw.fire_at : t.schedule_raw.cron}</td>
                  <td>{t.enabled ? <span className="badge success">active</span> : <span className="badge warning">inactive</span>}</td>
                  <td>{t.run_count}</td>
                  <td>{timeFromEpoch(t.next_run)}</td>
                  <td onClick={e => e.stopPropagation()}>
                    <button onClick={() => handleDelete(t.id)} className="danger">Delete</button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>

          {/* Detail panel */}
          {detailTask && (
            <div style={{ background: 'var(--surface)', padding: '1rem', borderRadius: 'var(--radius)', marginTop: '1rem' }}>
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '0.5rem' }}>
                <h3>Details: {detailTask.title}</h3>
                <div style={{ display: 'flex', gap: '0.5rem' }}>
                  <button onClick={() => runTask(detailTask)}>Run now</button>
                  <button onClick={() => handleMarkSeen(detailTask.id)}>Seen markieren</button>
                  <button className="danger" onClick={() => handleDelete(detailTask.id)}>Delete</button>
                  <button onClick={hideDetail}>Close</button>
                </div>
              </div>

              <p style={{ fontSize: '0.9rem' }}>{detailTask.instructions}</p>

              {runs.length > 0 && (
                <table className="data-table" style={{ marginTop: '0.75rem' }}>
                  <thead><tr><th>Run-ID</th><th>Trigger</th><th>Status</th><th>Gestartet</th><th>Fehler</th></tr></thead>
                  <tbody>
                    {runs.slice(0, 20).map(r => (
                      <tr key={r.run_id}>
                        <td><code>{r.run_id}</code></td>
                        <td>{r.trigger}</td>
                        <td>{statusBadge(r.status)}</td>
                        <td style={{ whiteSpace: 'nowrap' }}>{timeFromEpoch(r.started_at)}</td>
                        <td><code style={{ color: 'var(--danger)' }}>{r.error?.slice(0, 80) || '-'}</code></td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
            </div>
          )}
        </>
      )}
    </div>
  )
}
