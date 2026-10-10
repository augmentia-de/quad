import { useEffect, useState } from 'react'
import { AgentEditor } from './AgentEditor'
import { AgentPlayground } from './AgentPlayground'
import { createAgent, deleteAgent, duplicateAgent, fetchAgents, fetchSkills, fetchTools, updateAgent } from '../services/api'
import type { AgentDefinition, SkillSpec } from '../services/types'

const modelOptions = ['deepseek/deepseek-v4-flash', 'gpt-4o', 'gpt-4o-mini']

export function AgentManager() {
  const [allAgents, setAllAgents] = useState<AgentDefinition[]>([])
  const [toolOptions, setToolOptions] = useState<string[]>([])
  const [skillOptions, setSkillOptions] = useState<SkillSpec[]>([])
  const [showEditor, setShowEditor] = useState(false)
  const [editingAgent, setEditingAgent] = useState<AgentDefinition | null>(null)
  const [editingIsNew, setEditingIsNew] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [search, setSearch] = useState('')
  const [categoryFilter, setCategoryFilter] = useState('all')
  const [showInactive, setShowInactive] = useState(false)

  useEffect(() => {
    let cancelled = false
    fetchAgents(showInactive)
      .then(data => { if (!cancelled) setAllAgents(data) })
      .catch(err => { if (!cancelled) setError(String(err)) })
    fetchTools()
      .then(tools => { if (!cancelled) setToolOptions(tools.map(t => t.name)) })
      .catch(() => {
        if (!cancelled) setToolOptions(['readFile', 'writeFile', 'webSearch', 'executeBash', 'analyze'])
      })
    fetchSkills()
      .then(skills => { if (!cancelled) setSkillOptions(skills) })
      .catch(() => { if (!cancelled) setSkillOptions([]) })
    return () => { cancelled = true }
  }, [showInactive])

  const filteredAgents = allAgents.filter(a => {
    const matchesSearch = !search
      || a.name.toLowerCase().includes(search.toLowerCase())
      || a.description.toLowerCase().includes(search.toLowerCase())
    const matchesCategory = categoryFilter === 'all' || a.category === categoryFilter
    return matchesSearch && matchesCategory
  })

  const categories = ['all', ...new Set(allAgents.map(a => a.category).filter(Boolean))]

  const createNewAgent = () => {
    setEditingAgent({
      id: '',
      name: 'New Agent',
      description: '',
      category: 'general',
      model: modelOptions[0],
      temperature: 0.7,
      maxTokens: 4096,
      topP: 1.0,
      tools: [],
      guardrailsInput: [],
      guardrailsOutput: [],
      hooks: [],
      agentType: 'ua',
      systemPrompt: 'You are QUAD, a helpful AI assistant. Be concise, accurate and helpful. When you use tools, explain what you did.',
      skills: [],
    })
    setEditingIsNew(true)
    setShowEditor(true)
  }

  const saveAgent = async (updated: AgentDefinition) => {
    try {
      if (editingIsNew) {
        const id = await createAgent(updated)
        setAllAgents(prev => [...prev, { ...updated, id }])
      } else {
        const saved = await updateAgent(updated.id, updated)
        setAllAgents(prev => prev.map(a => a.id === saved.id ? saved : a))
      }
      setError(null)
    } catch (err) {
      setError(String(err))
    }
    setEditingAgent(null)
    setShowEditor(false)
  }

  const handleDelete = async (id: string, closeEditor: boolean) => {
    try {
      await deleteAgent(id)
      setAllAgents(prev => prev.filter(a => a.id !== id))
      setError(null)
      if (closeEditor) {
        setEditingAgent(null)
        setShowEditor(false)
      }
    } catch (err) {
      setError(String(err))
    }
  }

  const handleDuplicate = async (id: string, e: React.MouseEvent) => {
    e.stopPropagation()
    try {
      const newId = await duplicateAgent(id)
      const copy = allAgents.find(a => a.id === id)
      if (copy) {
        setAllAgents(prev => [...prev, { ...copy, id: newId, name: `${copy.name} (copy)` }])
      }
      setError(null)
    } catch (err) {
      setError(String(err))
    }
  }

  return (
    <div className="agent-manager">
      <div className="agent-header">
        <h2>Agent Management</h2>
        <button onClick={createNewAgent}>Create New Agent</button>
      </div>

      {error && <p className="error-state">{error}</p>}

      <div className="agent-filters">
        <input
          type="text"
          value={search}
          onChange={e => setSearch(e.target.value)}
          placeholder="Agent suchen..."
          aria-label="Agent suchen"
        />
        <select
          value={categoryFilter}
          onChange={e => setCategoryFilter(e.target.value)}
          aria-label="Kategorie-Filter"
        >
          {categories.map(c => (
            <option key={c} value={c}>{c === 'all' ? 'Alle Kategorien' : c}</option>
          ))}
        </select>
        <label className="agent-filter-toggle" title="Zeigt intern generierte Workflow-Step-Agenten (active=false) zur Bearbeitung.">
          <input
            type="checkbox"
            checked={showInactive}
            onChange={e => setShowInactive(e.target.checked)}
          />
          {' '}Interne Step-Agenten
        </label>
      </div>

      {showEditor && editingAgent && (
        <AgentEditor
          agent={editingAgent}
          isNew={editingIsNew}
          onSave={saveAgent}
          onCancel={() => { setShowEditor(false); setEditingAgent(null) }}
          onDelete={editingIsNew ? undefined : () => handleDelete(editingAgent.id, true)}
          modelOptions={modelOptions}
          toolOptions={toolOptions}
          skillOptions={skillOptions}
        />
      )}

      {!showEditor && (
        <div className="agent-list">
          <h3>Your Agents</h3>
          {filteredAgents.length === 0 ? (
            <p className="empty-state">
              {allAgents.length === 0
                ? 'Create your first agent to get started'
                : 'Kein Agent passt zum Filter'}
            </p>
          ) : (
            <div className="agents-grid">
              {filteredAgents.map(agent => (
                <div
                  key={agent.id}
                  className="agent-card"
                  onClick={() => { setEditingAgent(agent); setEditingIsNew(false); setShowEditor(true) }}
                >
                  <h4>{agent.name}</h4>
                  <p>{agent.description || 'No description'}</p>
                  <div className="agent-meta">
                    <span className="model">{agent.model}</span>
                    <span className="tools">{agent.tools.length} tools</span>
                    <span className="skills">{(agent.skills ?? []).length} skills</span>
                    {agent.active === false && (
                      <span className="badge badge-internal" title="Intern generierter Workflow-Step-Agent (nicht in der Standardliste)">internal</span>
                    )}
                  </div>
                  <div className="guardrail-badges">
                    {(agent.guardrailsInput ?? []).map(g => (
                      <span key={`in-${g}`} className="badge badge-in" title={`Input-Guardrail: ${g}`}>{g}</span>
                    ))}
                    {(agent.guardrailsOutput ?? []).map(g => (
                      <span key={`out-${g}`} className="badge badge-out" title={`Output-Guardrail: ${g}`}>{g}</span>
                    ))}
                    {(agent.hooks ?? []).map(h => (
                      <span key={`hook-${h}`} className="badge badge-hook" title={`Hook: ${h}`}>{h}</span>
                    ))}
                    {(agent.skills ?? []).map(s => (
                      <span key={`skill-${s}`} className="badge badge-skill" title={`Skill: ${s}`}>{s}</span>
                    ))}
                  </div>
                  <button
                    className="danger"
                    onClick={e => {
                      e.stopPropagation()
                      if (confirm(`Delete "${agent.name}"?`)) handleDelete(agent.id, false)
                    }}
                  >
                    🗑 Delete
                  </button>
                  <button onClick={(e) => handleDuplicate(agent.id, e)} title="Duplicate agent">⧉ Duplicate</button>
                </div>
              ))}
            </div>
          )}
        </div>
      )}

      <AgentPlayground agents={allAgents} />
    </div>
  )
}