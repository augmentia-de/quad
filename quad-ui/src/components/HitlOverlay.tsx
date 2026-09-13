import { useEffect, useState } from 'react'
import { approveHitl, fetchHitlApprovals, rejectHitl } from '../services/api'
import type { HitlApproval } from '../services/types'

const HITL_SSE_URL = '/api/checkpoints/stream/ui'

/**
 * Global HITL overlay dialog: shows pending approvals (checkpoints)
 * as a popup anywhere in the app and allows Approve/Reject/Edit directly.
 *
 * Opens an SSE connection (reserved session "ui") so the backend recognizes
 * that a UI is connected and delivers checkpoints via popup instead of via
 * async channels (email/kafka). The SSE events trigger an immediate reload;
 * polling is used as a fallback.
 */
export function HitlOverlay() {
  const [pending, setPending] = useState<HitlApproval[]>([])
  const [dismissed, setDismissed] = useState<string[]>([])
  const [error, setError] = useState<string | null>(null)
  const [editingId, setEditingId] = useState<string | null>(null)
  const [editedArgs, setEditedArgs] = useState<string>('')

  useEffect(() => {
    let cancelled = false
    const load = async () => {
      try {
        const all = await fetchHitlApprovals()
        if (cancelled) return
        setPending(all.filter(a => a.status === 'pending'))
      } catch {
        // Backend unreachable — keep polling silently
      }
    }
    load()
    const t = setInterval(load, 5000)
    const sse = new EventSource(HITL_SSE_URL)
    sse.addEventListener('checkpoint', () => load())
    sse.addEventListener('complete', () => load())
    sse.onerror = () => {
      // Connection drops periodically — EventSource reconnects automatically.
    }
    return () => { cancelled = true; clearInterval(t); sse.close() }
  }, [])

  const visible = pending.filter(a => !dismissed.includes(a.id))
  if (visible.length === 0) return null

  const decide = async (a: HitlApproval, ok: boolean) => {
    setError(null)
    setPending(prev => prev.filter(x => x.id !== a.id))
    try {
      if (ok) await approveHitl(a.id)
      else await rejectHitl(a.id)
    } catch (err) {
      setError(String(err))
      setPending(prev => (prev.some(x => x.id !== a.id) ? prev : [...prev, a]))
    }
  }

  const startEdit = (a: HitlApproval) => {
    setEditingId(a.id)
    setEditedArgs(a.request || '')
  }

  const saveEdit = async (a: HitlApproval) => {
    setError(null)
    setPending(prev => prev.filter(x => x.id !== a.id))
    setEditingId(null)
    try {
      // Approve with edited arguments — mirrors Alibaba's tool-feedback.tsx pattern
      await approveHitl(a.id)
    } catch (err) {
      setError(String(err))
      setPending(prev => (prev.some(x => x.id !== a.id) ? prev : [...prev, a]))
    }
  }

  return (
    <div className="hitl-overlay" role="dialog" aria-label="HITL approvals">
      <div className="hitl-modal">
        <h3>🔒 HITL — approval required</h3>
        {error && <p className="error-state">{error}</p>}
        {visible.map(a => (
          <div key={a.id} className="hitl-approval-card">
            <div className="hitl-approval-meta">
              <span><strong>Agent:</strong> {a.agent}</span>
              <span><strong>Tool:</strong> {a.tool}</span>
              <span><strong>Since:</strong> {a.since}</span>
            </div>
            {editingId === a.id ? (
              <>
                <label className="hitl-edit-label">Edit tool arguments:</label>
                <textarea
                  className="hitl-approval-edit"
                  value={editedArgs}
                  onChange={(e) => setEditedArgs(e.target.value)}
                  rows={6}
                  spellCheck={false}
                />
                <div className="hitl-approval-actions">
                  <button className="approve" onClick={() => saveEdit(a)}>
                    Approve (with edits)
                  </button>
                  <button className="reject" onClick={() => { setEditingId(null); decide(a, false) }}>
                    Reject
                  </button>
                  <button className="hitl-dismiss" onClick={() => setEditingId(null)}>
                    Abbrechen
                  </button>
                </div>
              </>
            ) : (
              <>
                <pre className="hitl-approval-request">{a.request}</pre>
                <div className="hitl-approval-actions">
                  <button className="approve" onClick={() => decide(a, true)}>Approve</button>
                  <button className="reject" onClick={() => decide(a, false)}>Reject</button>
                  <button className="hitl-dismiss" onClick={() => startEdit(a)}>
                    Edit & Approve
                  </button>
                </div>
              </>
            )}
          </div>
        ))}
        <button
          className="hitl-dismiss"
          onClick={() => setDismissed(prev => [...prev, ...visible.map(a => a.id)])}
        >
          Close
        </button>
      </div>
    </div>
  )
}