import { useEffect, useState } from 'react'
import { fetchHooks } from '../services/api'
import type { AgentDefinition, SkillSpec } from '../services/types'

const INPUT_GUARDRAIL_OPTIONS = [
  { name: 'pii', label: 'PII (E-Mail-Adressen)' },
  { name: 'sql-injection', label: 'SQL-Injection' },
]

const OUTPUT_GUARDRAIL_OPTIONS = [
  { name: 'prompt-injection', label: 'Prompt-Injection' },
]

const HOOK_LABELS: Record<string, string> = {
  hitl: 'HITL (Tool-Freigabe)',
}

const FALLBACK_HOOK_OPTIONS = [
  { name: 'hitl', label: 'HITL (Tool-Freigabe)' },
]

interface AgentEditorProps {
  agent: AgentDefinition
  isNew?: boolean
  onSave: (agent: AgentDefinition) => void
  onCancel: () => void
  onDelete?: () => void
  modelOptions: string[]
  toolOptions: string[]
  skillOptions: SkillSpec[]
  onSkillToggle?: (skillName: string) => void
}

function GuardrailCheckboxes({
  options,
  selected,
  onChange,
}: {
  options: { name: string; label: string }[]
  selected: string[]
  onChange: (names: string[]) => void
}) {
  return (
    <div className="tool-checkbox-group">
      {options.map(o => (
        <label key={o.name}>
          <input
            type="checkbox"
            checked={selected.includes(o.name)}
            onChange={e => {
              const next = e.target.checked
                ? [...selected, o.name]
                : selected.filter(x => x !== o.name)
              onChange(next)
            }}
          />
          {o.label}
        </label>
      ))}
    </div>
  )
}

export function AgentEditor({ agent, isNew = false, onSave, onCancel, onDelete, modelOptions, toolOptions, skillOptions }: AgentEditorProps) {
  const [editedAgent, setEditedAgent] = useState(agent)
  const [hookOptions, setHookOptions] = useState(FALLBACK_HOOK_OPTIONS)

  useEffect(() => {
    let cancelled = false
    fetchHooks()
      .then(specs =>
        specs.map(s => ({
          name: s.name,
          label: HOOK_LABELS[s.name] ?? s.name.charAt(0).toUpperCase() + s.name.slice(1),
        }))
      )
      .then(options => { if (!cancelled && options.length > 0) setHookOptions(options) })
      .catch(() => undefined)
    return () => { cancelled = true }
  }, [])

  const handleSave = () => {
    onSave({ ...editedAgent, category: 'general', agentType: 'ua' })
  }

  const handleDelete = () => {
    if (onDelete && confirm('Delete this agent?')) {
      onDelete()
    }
  }

  return (
    <div className="agent-editor-overlay">
      <div className="agent-editor modal">
        <div className="agent-editor-header">
          <h3>{isNew ? 'Create Agent' : 'Edit Agent'}</h3>
          <button className="agent-editor-close" onClick={onCancel} aria-label="Cancel">✕</button>
        </div>
        
        <div className="form-group">
          <label>Name</label>
          <input 
            type="text" 
            value={editedAgent.name} 
            onChange={e => setEditedAgent({ ...editedAgent, name: e.target.value })}
          />
        </div>

        <div className="form-group">
          <label>Description</label>
          <textarea
            value={editedAgent.description}
            onChange={e => setEditedAgent({ ...editedAgent, description: e.target.value })}
          />
        </div>

        <div className="form-group">
          <label>System Prompt</label>
          <textarea
            value={editedAgent.systemPrompt || ''}
            onChange={e => setEditedAgent({ ...editedAgent, systemPrompt: e.target.value })}
            placeholder="Describe how the agent should behave"
            rows={3}
          />
          {!editedAgent.systemPrompt?.trim() && (
            <small style={{display: 'block', marginTop: '4px', color: '#c96'}}>
              System prompt is required
            </small>
          )}
        </div>

        <div className="form-group">
          <label>Model</label>
          <select 
            value={editedAgent.model} 
            onChange={e => setEditedAgent({ ...editedAgent, model: e.target.value })}
          >
            {modelOptions.map(m => <option key={m} value={m}>{m}</option>)}
          </select>
        </div>

        <div className="form-group">
          <label>Temperature</label>
          <input 
            type="number" 
            step="0.1" 
            min="0" 
            max="1"
            value={editedAgent.temperature}
            onChange={e => setEditedAgent({ ...editedAgent, temperature: parseFloat(e.target.value) })}
          />
        </div>

        <div className="form-group">
          <label>Max Tokens</label>
          <input 
            type="number"
            min="1"
            value={editedAgent.maxTokens}
            onChange={e => setEditedAgent({ ...editedAgent, maxTokens: parseInt(e.target.value) || 4096 })}
          />
        </div>

        <div className="form-group">
          <label>Timeout (Sekunden, optional)</label>
          <input 
            type="number"
            min="1"
            aria-label="Timeout (Sekunden, optional)"
            placeholder="600 (globaler Default)"
            value={editedAgent.timeoutSeconds ?? ''}
            onChange={e => {
              const v = e.target.value
              setEditedAgent({ ...editedAgent, timeoutSeconds: v === '' ? undefined : parseInt(v) || undefined })
            }}
          />
          <small style={{display: 'block', marginTop: '4px', color: '#888'}}>
            Max runtime of an agent execution. Empty = global default is used.
          </small>
        </div>

        <div className="form-group">
          <label>Top P</label>
          <input 
            type="number"
            step="0.1"
            min="0"
            max="1"
            value={editedAgent.topP}
            onChange={e => setEditedAgent({ ...editedAgent, topP: parseFloat(e.target.value) })}
          />
        </div>

        <div className="form-group">
          <label>Tools</label>
          <div className="tool-checkbox-group">
            {toolOptions.map(t => (
              <label key={t}>
                <input
                  type="checkbox"
                  checked={editedAgent.tools.includes(t)}
                  onChange={e => {
                    const newTools = e.target.checked
                      ? [...editedAgent.tools, t]
                      : editedAgent.tools.filter(x => x !== t)
                    setEditedAgent({ ...editedAgent, tools: newTools })
                  }}
                />
                {t}
              </label>
            ))}
          </div>
        </div>

        <div className="form-group">
          <label>Skills (JIT-loaded via load_skill tool)</label>
          <div className="tool-checkbox-group">
            {skillOptions.length === 0 ? (
              <small style={{ color: '#888' }}>No skills available. Create skills in the Skill Store first.</small>
            ) : (
              skillOptions.map(s => (
                <label key={s.name}>
                  <input
                    type="checkbox"
                    checked={(editedAgent.skills ?? []).includes(s.name)}
                    onChange={e => {
                      const newSkills = e.target.checked
                        ? [...(editedAgent.skills ?? []), s.name]
                        : (editedAgent.skills ?? []).filter(x => x !== s.name)
                      setEditedAgent({ ...editedAgent, skills: newSkills })
                    }}
                  />
                  <span style={{ display: 'inline-flex', alignItems: 'center', gap: '0.25rem' }}>
                    {s.name}
                    <span className="badge" style={{ background: s.source === 'db' ? 'var(--accent-bg)' : 'var(--surface-2)', fontSize: '0.7rem' }}>{s.source}</span>
                  </span>
                </label>
              ))
            )}
          </div>
          <small style={{display: 'block', marginTop: '4px', color: '#888'}}>
            Skills are loaded Just-in-Time via the load_skill tool when the agent needs them. Max 3 active at startup.
          </small>
        </div>

        <div className="form-group">
          <label>Eingabe-Guardrails</label>
          <GuardrailCheckboxes
            options={INPUT_GUARDRAIL_OPTIONS}
            selected={editedAgent.guardrailsInput ?? []}
            onChange={names => setEditedAgent({ ...editedAgent, guardrailsInput: names })}
          />
        </div>

        <div className="form-group">
          <label>Ausgabe-Guardrails</label>
          <GuardrailCheckboxes
            options={OUTPUT_GUARDRAIL_OPTIONS}
            selected={editedAgent.guardrailsOutput ?? []}
            onChange={names => setEditedAgent({ ...editedAgent, guardrailsOutput: names })}
          />
        </div>

        <div className="form-group">
          <label>Hooks</label>
          <GuardrailCheckboxes
            options={hookOptions}
            selected={editedAgent.hooks ?? []}
            onChange={names => setEditedAgent({ ...editedAgent, hooks: names })}
          />
          <small style={{display: 'block', marginTop: '4px', color: '#888'}}>
            Lifecycle hooks attached per agent (e.g. HITL approval before tool execution).
          </small>
        </div>

        <div className="form-group">
          <label>
            <input
              type="checkbox"
              checked={editedAgent.jsonOutput || false}
              onChange={e => setEditedAgent({ ...editedAgent, jsonOutput: e.target.checked || undefined })}
            />
            JSON Output Mode
          </label>
          <small style={{display: 'block', marginTop: '4px', color: '#888'}}>
            Agent produces structured JSON output. With a schema below it is enforced as responseFormat.
          </small>
        </div>

        {editedAgent.jsonOutput && (
          <div className="form-group">
            <label>Output Schema (JSON Schema, optional)</label>
            <textarea
              value={editedAgent.jsonOutputSchema || ''}
              onChange={e => setEditedAgent({ ...editedAgent, jsonOutputSchema: e.target.value || undefined })}
              placeholder={'{\n  "type": "object",\n  "properties": {\n    "summary": { "type": "string" }\n  },\n  "required": ["summary"]\n}'}
              rows={6}
              style={{fontFamily: 'monospace', fontSize: '13px'}}
            />
            <small style={{display: 'block', marginTop: '4px', color: '#888'}}>
              JSON Schema, enforced by the model (Structured Output). Empty = JSON output requested, no schema.
            </small>
          </div>
        )}

        {editedAgent.jsonOutput && (
          <div className="form-group">
            <label>User Message Template</label>
            <textarea
              value={editedAgent.userMessageTemplate || ''}
              onChange={e => setEditedAgent({ ...editedAgent, userMessageTemplate: e.target.value || undefined })}
              placeholder="Template with {{field}} placeholders from JSON input"
              rows={4}
            />
            <small style={{display: 'block', marginTop: '4px', color: '#888'}}>
              Optional: renders the user message when prompt input is structured JSON
            </small>
          </div>
        )}

        <div className="form-group">
          <label>Chat Parameters (JSON, optional)</label>
          <textarea
            value={editedAgent.chatParameters || ''}
            onChange={e => setEditedAgent({ ...editedAgent, chatParameters: e.target.value || undefined })}
            placeholder='{"temperature":0.5,"maxOutputTokens":2048,"topP":0.9}'
            rows={3}
            style={{fontFamily: 'monospace', fontSize: '13px'}}
          />
          <small style={{display: 'block', marginTop: '4px', color: '#888'}}>
            Override per-request: temperature, maxOutputTokens, topP, topK, modelName
          </small>
        </div>

        <div className="form-actions">
          <button onClick={onCancel}>Cancel</button>
          {onDelete && (
            <button className="danger" onClick={handleDelete}>Delete</button>
          )}
          <button onClick={handleSave} disabled={!editedAgent.systemPrompt?.trim()}>Save</button>
        </div>
      </div>
    </div>
  )
}