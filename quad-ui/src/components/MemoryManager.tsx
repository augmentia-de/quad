import { useEffect, useState } from 'react'
import { fetchMemories, forgetMemory, clearMemories, updateMemory } from '../services/api'
import type { MemoryCategory, MemoryEntry } from '../services/types'

const CATEGORY_LABELS: Record<string, string> = { USER_FACT: 'User Fact', PROJECT_FACT: 'Project Fact', TASK_FACT: 'Task Fact' }

export function MemoryManager() {
  const [memories, setMemories] = useState<MemoryEntry[]>([])
  const [loading, setLoading] = useState(true)
  const [scope, setScope] = useState('')
  const [editingId, setEditingId] = useState<string | null>(null)
  const [editContent, setEditContent] = useState('')
  const [error, setError] = useState<string | null>(null)

  const load = async () => {
    setLoading(true)
    try {
      const result = scope ? await fetchMemories(scope) : await fetchMemories()
      setMemories(result)
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => { load() }, [scope])

  const handleForget = async (id: string) => {
    try { await forgetMemory(id); load() } catch (_e) { /* silent */ }
  }

  const handleSave = async (id: string) => {
    try { await updateMemory(id, editContent); setEditingId(null); load() } catch (_e) { /* silent */ }
  }

  const handleClear = async () => {
    if (!confirm('All memories delete?')) return
    try { await clearMemories(); load() } catch (_e) { /* silent */ }
  }

  return (
    <div className="quad-page">
      <h2>Memory persistence</h2>

      <div style={{ display: 'flex', gap: '0.5rem', marginBottom: '1rem', alignItems: 'center' }}>
        <input value={scope} onChange={e => setScope(e.target.value)} placeholder="Scope filter..." style={{ maxWidth: '240px', fontFamily: 'var(--mono)' }} />
        <button onClick={load}>Reload</button>
        <button className="danger" onClick={handleClear}>Delete all</button>
      </div>

      {error && <div className="error-state">{error}</div>}

      {loading ? (
        <div className="loading">Loading...</div>
      ) : memories.length === 0 ? (
        <div className="empty-state">Keine Memories vorhanden.</div>
      ) : (
        <>
          <table className="data-table">
            <thead><tr>
              <th>ID</th><th>Scope</th><th>Kategorie</th><th>Inhalt</th><th>Erstellt</th><th>Aktionen</th>
            </tr></thead>
            <tbody>
              {memories.map(m => (
                <tr key={m.id}>
                  <td><code>{m.id.slice(0, 8)}</code></td>
                  <td>{m.scope}</td>
                  <td><span className={`badge ${m.category?.toLowerCase() || ''}`}>{CATEGORY_LABELS[m.category || ''] || '-'}</span></td>
                  <td>
                    {editingId === m.id ? (
                      <div style={{ display: 'flex', gap: '0.3rem' }}>
                        <input value={editContent} onChange={e => setEditContent(e.target.value)} style={{ flex: 1, fontFamily: 'var(--mono)', fontSize: '0.85rem' }} />
                        <button onClick={() => handleSave(m.id)}>✓</button>
                        <button onClick={() => setEditingId(null)}>✕</button>
                      </div>
                    ) : (
                      <span style={{ maxWidth: '300px', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', cursor: 'pointer' }}
                            onClick={() => { setEditingId(m.id); setEditContent(m.content) }}>{m.content}</span>
                    )}
                  </td>
                  <td>{m.createdAt}</td>
                  <td><button className="danger" onClick={() => handleForget(m.id)}>Forget</button></td>
                </tr>
              ))}
            </tbody>
          </table>
          <p style={{ marginTop: '0.5rem', color: 'var(--text-dim)', fontSize: '0.9rem' }}>
            {memories.length} Memory(s) — sensitive: {memories.filter(m => m.sensitive).length}
          </p>
        </>
      )}
    </div>
  )
}
