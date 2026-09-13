import { useEffect, useState } from 'react'
import { fetchSkillList, upsertSkill, deleteSkill } from '../services/api'
import type { SkillSpec } from '../services/types'

const renderTags = (tags: string[], color: string) => tags.length ? (
  <div style={{ display: 'flex', flexWrap: 'wrap', gap: '0.25rem', marginTop: '0.3rem' }}>
    {tags.map((t, i) => <span key={i} className="tag" style={{ background: color }}>{t}</span>)}
  </div>
) : null

export function SkillStoreManager() {
  const [skills, setSkills] = useState<SkillSpec[]>([])
  const [loading, setLoading] = useState(true)
  const [editMode, setEditMode] = useState(false)
  const [editingName, setEditingName] = useState('')
  const [description, setDescription] = useState('')
  const [allowedInput, setAllowedInput] = useState('')
  const [declaredInput, setDeclaredInput] = useState('')
  const [error, setError] = useState<string | null>(null)

  const load = async () => {
    setLoading(true)
    try { setSkills(await fetchSkillList()) } catch (e: unknown) { setError(e instanceof Error ? e.message : String(e)) }
    finally { setLoading(false) }
  }

  useEffect(() => { load() }, [])

  const openCreate = () => {
    setEditMode(true)
    setEditingName('')
    setDescription('')
    setAllowedInput('')
    setDeclaredInput('')
  }

  const handleSave = async () => {
    if (!editingName.trim()) return
    try {
      await upsertSkill(editingName.trim(), { name: editingName, description: description.trim() || undefined, allowedTools: allowedInput.split(',').map(s => s.trim()).filter(Boolean), declaredTools: declaredInput.split(',').map(s => s.trim()).filter(Boolean) })
      setEditMode(false)
      load()
    } catch (e: unknown) { setError(e instanceof Error ? e.message : String(e)) }
  }

  const handleDelete = async (name: string) => {
    if (!confirm(`Delete skill "${name}"?`)) return
    try { await deleteSkill(name); load() } catch (_e) { /* silent */ }
  }

  return (
    <div className="quad-page">
      <h2>Skill Store</h2>

      <div style={{ marginBottom: '1rem', display: 'flex', gap: '0.5rem' }}>
        <button onClick={openCreate}>New skill</button>
      </div>

      {error && <div className="error-state">{error}</div>}

      {/* Create/Edit form */}
      {editMode && (
        <div className="agent-editor-overlay" style={{ position: 'fixed', top: 0, left: 0, right: 0, bottom: 0, background: 'rgba(0,0,0,0.6)', zIndex: 1000, display: 'flex', justifyContent: 'center', alignItems: 'center' }}>
          <div className="agent-editor modal" style={{ background: 'var(--surface)', padding: '1.5rem', borderRadius: 'var(--radius)', width: '480px' }}>
            <h3>{editingName ? 'Edit skill' : 'Create new skill'}</h3>
            <div style={{ display: 'flex', flexDirection: 'column', gap: '0.75rem' }}>
              <input value={editingName} onChange={e => setEditingName(e.target.value)} placeholder="Skill Name (required)" style={{ fontFamily: 'var(--mono)' }} autoFocus />
              <textarea value={description} onChange={e => setDescription(e.target.value)} placeholder="Description" rows={2} />
              <input value={allowedInput} onChange={e => setAllowedInput(e.target.value)} placeholder="Allowed tools (comma-separated, e.g. readFile,webSearch)" style={{ fontFamily: 'var(--mono)' }} />
              <input value={declaredInput} onChange={e => setDeclaredInput(e.target.value)} placeholder="Declared tools (comma-separated)" style={{ fontFamily: 'var(--mono)' }} />
              <div style={{ display: 'flex', gap: '0.5rem', justifyContent: 'flex-end' }}>
                <button onClick={() => setEditMode(false)}>Abbrechen</button>
                <button onClick={handleSave}>Speichern</button>
              </div>
            </div>
          </div>
        </div>
      )}

      {loading ? (
        <div className="loading">Loading...</div>
      ) : skills.length === 0 ? (
        <div className="empty-state">Keine Skills in der Datenbank.</div>
      ) : (
        <>
          <table className="data-table">
            <thead><tr><th>Name</th><th>Beschreibung</th><th>Erlaubte Tools</th><th>Deklarierte Tools</th><th>Aktionen</th></tr></thead>
            <tbody>
              {skills.map(s => (
                <tr key={s.name}>
                  <td><strong>{s.name}</strong></td>
                  <td>{s.description || '-'}</td>
                  <td>{renderTags(s.allowedTools, 'var(--accent-bg)')}</td>
                  <td>{renderTags(s.declaredTools, 'var(--warn-bg)')}</td>
                  <td>
                    <button onClick={() => { openCreate(); setEditingName(s.name); setDescription(s.description || ''); setAllowedInput((s.allowedTools || []).join(',')); setDeclaredInput((s.declaredTools || []).join(',')); }}>Edit</button>
                    <button className="danger" onClick={() => handleDelete(s.name)}>Delete</button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </>
      )}
    </div>
  )
}
