import { useEffect, useState } from 'react'
import { deleteWorkflow, duplicateWorkflow, fetchWorkflows } from '../services/api'
import type { WorkflowSummary } from '../services/types'

interface WorkflowListProps {
  onSelect: (id: string) => void
  onExecute: (id: string) => void
}

export function WorkflowList({ onSelect, onExecute }: WorkflowListProps) {
  const [workflows, setWorkflows] = useState<WorkflowSummary[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false
    setLoading(true)
    fetchWorkflows()
      .then(data => { if (!cancelled) setWorkflows(data) })
      .catch(err => { if (!cancelled) setError(String(err)) })
      .finally(() => { if (!cancelled) setLoading(false) })
    return () => { cancelled = true }
  }, [])

  const handleExecute = (id: string, e: React.MouseEvent) => {
    e.stopPropagation()
    onExecute(id)
  }

  const handleDelete = async (id: string, e: React.MouseEvent) => {
    e.stopPropagation()
    const workflow = workflows.find(w => w.id === id)
    if (!confirm(`Delete "${workflow?.name ?? id}"?`)) return
    setError(null)
    try {
      await deleteWorkflow(id)
      setWorkflows(prev => prev.filter(w => w.id !== id))
    } catch (err) {
      setError(String(err))
    }
  }

  const handleDuplicate = async (id: string, e: React.MouseEvent) => {
    e.stopPropagation()
    setError(null)
    try {
      const newId = await duplicateWorkflow(id)
      const copy = workflows.find(w => w.id === id)
      if (copy) {
        setWorkflows(prev => [...prev, { ...copy, id: newId, name: `${copy.name} (copy)` }])
      }
    } catch (err) {
      setError(String(err))
    }
  }

  if (loading) return <p className="empty-state">Loading workflows...</p>
  if (error) return <p className="error-state">{error}</p>
  if (workflows.length === 0) return <p className="empty-state">No workflows saved yet</p>

  return (
    <div className="workflow-list">
      <h3>Saved workflows</h3>
      <p className="hint">Click a row to load & edit · ▶ = run directly</p>
      <table>
        <thead>
          <tr><th>Name</th><th>Knoten</th><th>Erstellt</th><th>Aktionen</th></tr>
        </thead>
        <tbody>
          {workflows.map(wf => (
            <tr key={wf.id} onClick={() => onSelect(wf.id)} style={{ cursor: 'pointer' }}>
              <td>{wf.name}</td>
              <td>{wf.nodeCount} Knoten, {wf.edgeCount} Kanten</td>
              <td>{new Date(wf.createdAt).toLocaleString()}</td>
              <td>
                <button className="run" onClick={(e) => handleExecute(wf.id, e)}>▶ Run</button>
                <button onClick={(e) => handleDuplicate(wf.id, e)} title="Workflow duplizieren">⧉ Duplizieren</button>
                <button className="danger" onClick={(e) => handleDelete(wf.id, e)}>🗑 Delete</button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}