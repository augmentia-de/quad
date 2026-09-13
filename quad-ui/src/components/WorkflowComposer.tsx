import { useEffect, useRef, useState } from 'react'
import { useWorkflow } from '../contexts/WorkflowContext'
import { createWorkflow, executeWorkflow, fetchAgents, fetchMessagingChannels, fetchRun, fetchWorkflow, fetchWorkflows, updateWorkflow } from '../services/api'
import { WorkflowList } from './WorkflowList'
import { JsonSchemaPopup } from './JsonSchemaPopup'
import { buildSchemaTree } from '../services/jsonSchema'
import type { SchemaTreeNode } from '../services/jsonSchema'
import type { AgentDefinition, EdgeInputMapping, MessagingChannel, WorkflowNodeType, WorkflowRun, WorkflowSummary } from '../services/types'
import { ReactFlowCanvas } from './ReactFlowCanvas'
import './WorkflowComposer.css'

interface WorkflowComposerProps {
  onTabChange?: (tab: string) => void
}

/** Forward reachability: all nodes reachable from a source via outgoing edges */
function getDownstreamNodeIds(sourceId: string, edges: Array<{ source: string; target: string }>): Set<string> {
  const result = new Set<string>()
  const queue = [sourceId]
  while (queue.length > 0) {
    const cur = queue.shift()!
    for (const edge of edges) {
      if (edge.source === cur && !result.has(edge.target)) {
        result.add(edge.target)
        queue.push(edge.target)
      }
    }
  }
  return result
}

/** Get all agent/other non-control-flow nodes that are downstream of a given source node */
function getAvailableTargets(sourceId: string, nodes: Array<{ id: string; title: string; type: string }>, edges: Array<{ source: string; target: string }>): Array<{ id: string; title: string }> {
  const downstream = getDownstreamNodeIds(sourceId, edges)
  const targets: Array<{ id: string; title: string }> = []
  for (const n of nodes) {
    if (downstream.has(n.id)) {
      targets.push({ id: n.id, title: n.title })
    }
  }
  return targets
}

const SESSION_FIELDS = [
  { value: '__session.findings', label: 'Findings (SessionState)' },
  { value: '__session.currentProject', label: 'currentProject' },
  { value: '__session.tenantId', label: 'tenantId' },
  { value: '__session.cwd', label: 'CWD' },
]

/** Spezialquelle = Start-/InitialTask des Workflows (Workflow-State-Objekt). */
const INITIAL_DATA_FIELD = { value: '__initialData', label: 'Initial Data (Start-Task)' }

interface SourceOption {
  value: string
  label: string
}

interface PopupSourceNode {
  id: string
  title: string
  type: string
  /** JSON-Schema des Agent-Outputs (node.config.jsonOutputSchema), optional. */
  schema?: string
}

interface EdgeInputsPopupProps {
  nodes: PopupSourceNode[]
  targetTitle: string
  initialInputs: EdgeInputMapping[]
  onSave: (inputs: EdgeInputMapping[]) => void
  onClose: () => void
}

/** Rekursive, aufklappbare Baum-Navigation zur visuellen Auswahl eines Subtree-Pfads im JSON-Schema. */
function SchemaPathPicker({ schemaRaw, value, onSelect }: {
  schemaRaw: string
  value: string
  onSelect: (dotPath: string) => void
}) {
  const tree = buildSchemaTree(schemaRaw)
  const [open, setOpen] = useState<Record<string, boolean>>(() => {
    const initial: Record<string, boolean> = {}
    for (const n of tree) initial[n.dotPath] = true
    return initial
  })

  if (tree.length === 0) {
    return (
      <span className="field-hint">
        Kein JSON-Schema angegeben — Pfad manuell eingeben.
      </span>
    )
  }

  const renderNode = (node: SchemaTreeNode) => {
    const hasChildren = node.children.length > 0
    const isOpen = !!open[node.dotPath]
    const isSelected = value === node.dotPath
    return (
      <li key={node.dotPath}>
        <div className={`schema-tree-row ${isSelected ? 'selected' : ''}`}>
          {hasChildren ? (
            <button
              type="button"
              className="schema-tree-toggle"
              aria-label={isOpen ? `Collapse ${node.key}` : `Expand ${node.key}`}
              onClick={() => setOpen(prev => ({ ...prev, [node.dotPath]: !prev[node.dotPath] }))}
            >
              {isOpen ? '▾' : '▸'}
            </button>
          ) : (
            <span className="schema-tree-toggle placeholder" />
          )}
          <button
            type="button"
            className="schema-tree-label"
            onClick={() => onSelect(node.dotPath)}
            title={node.dotPath}
          >
            <span className="schema-tree-key">{node.key}</span>
            {node.type && <span className="schema-tree-type">{node.type}</span>}
          </button>
        </div>
        {hasChildren && isOpen && (
          <ul className="schema-tree-children">
            {node.children.map(renderNode)}
          </ul>
        )}
      </li>
    )
  }

  return (
    <ul className="schema-tree">
      {tree.map(renderNode)}
    </ul>
  )
}

/**
 * Modal for editing the input sources of an edge. Allows a list of
 * arbitrary predecessor outputs: for 'text' only one source, for 'json' additionally
 * a subtree path from the source agent's JSON schema, selectable visually (tree)
 * or manually, whose content is passed to the following node as JSON.
 */
function EdgeInputsPopup({ nodes, targetTitle, initialInputs, onSave, onClose }: EdgeInputsPopupProps) {
  const sourceOptions: SourceOption[] = [
    ...nodes.map(n => ({ value: n.id, label: n.title || n.id })),
    { value: INITIAL_DATA_FIELD.value, label: INITIAL_DATA_FIELD.label },
    ...SESSION_FIELDS.map(f => ({ value: f.value, label: f.label })),
  ]
  const [inputs, setInputs] = useState<EdgeInputMapping[]>(
    initialInputs.length > 0 ? initialInputs.map(i => ({ ...i, format: i.format ?? 'text' })) : []
  )
  const [draftSource, setDraftSource] = useState(sourceOptions[0]?.value ?? '')
  const [draftFormat, setDraftFormat] = useState<'text' | 'json'>('text')
  const [draftPath, setDraftPath] = useState('')
  const [draftPathManual, setDraftPathManual] = useState(false)
  const [openPicker, setOpenPicker] = useState<Record<string, boolean>>({})

  const schemaOf = (nodeId: string) => nodes.find(n => n.id === nodeId)?.schema ?? ''

  /** Pfadliste eines JSON-Inputs; migriert das einzelne `path`-Feld nach `paths`. */
  const pathsOf = (input: EdgeInputMapping): string[] => {
    if (input.paths && input.paths.length > 0) return input.paths
    if (input.path) return [input.path]
    return ['']
  }

  /** Writes back the path list (and maintains `path` as a shorthand for a single path). */
  const setPaths = (idx: number, paths: string[]) => {
    const filtered = paths.filter(p => p.trim() !== '')
    updateInput(idx, {
      paths: filtered.length > 0 ? filtered : undefined,
      path: filtered.length === 1 ? filtered[0] : undefined,
    })
  }

  const updatePath = (idx: number, pIdx: number, value: string) => {
    const paths = [...pathsOf(inputs[idx])]
    paths[pIdx] = value
    setPaths(idx, paths)
  }

  const addPath = (idx: number) => {
    setPaths(idx, [...pathsOf(inputs[idx]), ''])
  }

  const removePath = (idx: number, pIdx: number) => {
    setPaths(idx, pathsOf(inputs[idx]).filter((_, k) => k !== pIdx))
  }

  const pickPath = (idx: number, pIdx: number, dotPath: string) => {
    updatePath(idx, pIdx, dotPath)
    setOpenPicker({})
  }

  const togglePicker = (idx: number, pIdx: number) => {
    const key = `${idx}:${pIdx}`
    setOpenPicker(prev => ({ ...prev, [key]: !prev[key] }))
  }

  const isPickerOpen = (idx: number, pIdx: number) => !!openPicker[`${idx}:${pIdx}`]

  const addInput = () => {
    const src = draftSource
    if (!src) return
    const existing = inputs.find(i => i.sourceNodeId === src)
    if (existing) {
      if (existing.format === draftFormat) return
      setInputs(inputs.map(i =>
        i.sourceNodeId === src
          ? {
              ...i,
              format: draftFormat,
              path: draftFormat === 'json' ? (i.path ?? undefined) : undefined,
              paths: draftFormat === 'json' ? (i.paths ?? undefined) : undefined,
            }
          : i
      ))
      return
    }
    const newInput: EdgeInputMapping = { sourceNodeId: src, format: draftFormat }
    if (draftFormat === 'json' && draftPath.trim()) newInput.path = draftPath.trim()
    setInputs([...inputs, newInput])
    setDraftPath('')
  }

  const updateInput = (idx: number, patch: Partial<EdgeInputMapping>) => {
    setInputs(inputs.map((i, k) => (k === idx ? { ...i, ...patch } : i)))
  }

  const removeInput = (idx: number) => {
    setInputs(inputs.filter((_, k) => k !== idx))
  }

  const labelOf = (value: string) => sourceOptions.find(o => o.value === value)?.label ?? value

  return (
    <div className="agent-editor-overlay" role="dialog" aria-label={`Input sources for ${targetTitle}`}>
      <div className="agent-editor modal edge-inputs-popup">
        <h3>Input Sources — {targetTitle}</h3>
        <p className="schema-popup-hint">
          Choose arbitrary predecessor outputs as input. For <strong>JSON</strong>, the content
          of one or more subtree paths (via the tree view or as <code>items[0].name</code>)
          can be passed to the following node. Multiple sources are allowed.
        </p>

        <div className="edge-inputs-list">
          {inputs.length === 0 && <p className="empty-state">No sources added yet.</p>}
          {inputs.map((input, idx) => {
            const isNode = nodes.some(n => n.id === input.sourceNodeId)
            const isJson = (input.format ?? 'text') === 'json'
            return (
              <div className="edge-input-row" key={`${input.sourceNodeId}-${idx}`}>
                <div className="edge-input-row-head">
                  <span className="edge-input-name">{labelOf(input.sourceNodeId)}</span>
                  <select
                    value={input.format ?? 'text'}
                    onChange={e => updateInput(idx, {
                      format: e.target.value as 'text' | 'json',
                      path: e.target.value === 'json' ? input.path : undefined,
                    })}
                    aria-label={`Format for ${labelOf(input.sourceNodeId)}`}
                  >
                    <option value="text">Text</option>
                    <option value="json">JSON</option>
                  </select>
                  <button type="button" className="danger" onClick={() => removeInput(idx)}>✕</button>
                </div>
                {isJson && isNode && (
                  <div className="edge-input-path-block">
                    {pathsOf(input).map((pathVal, pIdx) => (
                      <div className="edge-input-path-item" key={`${idx}-${pIdx}`}>
                        <div className="edge-input-path-head">
                          <input
                            className="edge-input-path"
                            value={pathVal}
                            placeholder="Subtree path e.g. items[0].name"
                            onChange={e => updatePath(idx, pIdx, e.target.value)}
                            aria-label={`Subtree path ${pIdx + 1} for ${labelOf(input.sourceNodeId)}`}
                          />
                          {schemaOf(input.sourceNodeId) && (
                            <button
                              type="button"
                              className="schema-tree-open"
                              onClick={() => togglePicker(idx, pIdx)}
                              aria-label={`Toggle tree path selection for path ${pIdx + 1}`}
                            >
                              {isPickerOpen(idx, pIdx) ? 'Close' : 'Tree'}
                            </button>
                          )}
                          <button
                            type="button"
                            className="danger path-remove"
                            onClick={() => removePath(idx, pIdx)}
                            aria-label={`Remove path ${pIdx + 1}`}
                          >
                            ✕
                          </button>
                        </div>
                        {isPickerOpen(idx, pIdx) && schemaOf(input.sourceNodeId) && (
                          <SchemaPathPicker
                            schemaRaw={schemaOf(input.sourceNodeId)}
                            value={pathVal}
                            onSelect={dotPath => pickPath(idx, pIdx, dotPath)}
                          />
                        )}
                      </div>
                    ))}
                    <button type="button" className="path-add" onClick={() => addPath(idx)}>
                      + Add Path
                    </button>
                  </div>
                )}
                {isJson && !isNode && (
                  <span className="field-hint">JSON path only available for node sources.</span>
                )}
              </div>
            )
          })}
        </div>

        <div className="edge-input-add">
          <select
            value={draftSource}
            onChange={e => setDraftSource(e.target.value)}
            aria-label="New source"
          >
            {sourceOptions.map(o => <option key={o.value} value={o.value}>{o.label}</option>)}
          </select>
          <select
            value={draftFormat}
            onChange={e => setDraftFormat(e.target.value as 'text' | 'json')}
            aria-label="New source format"
          >
            <option value="text">Text</option>
            <option value="json">JSON</option>
          </select>
          {draftFormat === 'json' && (
            <div className="edge-input-path-block">
              <div className="edge-input-path-head">
                <input
                  className="edge-input-path"
                  value={draftPath}
                  placeholder="Subtree path e.g. items[0].name"
                  onChange={e => setDraftPath(e.target.value)}
                  aria-label="New source subtree path"
                />
                {schemaOf(draftSource) && (
                  <button
                    type="button"
                    className="schema-tree-open"
                    onClick={() => setDraftPathManual(v => !v)}
                    aria-label="Toggle tree path selection"
                  >
                    Tree
                  </button>
                )}
              </div>
              {draftPathManual && schemaOf(draftSource) && (
                <SchemaPathPicker
                  schemaRaw={schemaOf(draftSource)}
                  value={draftPath}
                  onSelect={dotPath => setDraftPath(dotPath)}
                />
              )}
            </div>
          )}
          <button type="button" onClick={addInput}>Add Source</button>
        </div>

        <div className="schema-popup-actions">
          <button type="button" onClick={() => onSave(inputs)}>Save</button>
          <button type="button" className="danger" onClick={onClose}>Cancel</button>
        </div>
      </div>
    </div>
  )
}

export function WorkflowComposer({ onTabChange }: WorkflowComposerProps) {
  const {
    state,
    dispatch,
    addNode,
    updateNode,
    removeNode,
    updateEdge,
    removeEdge,
    setNodeStatuses,
    setName,
    setId,
    newWorkflow,
    loadWorkflow,
    executeWorkflow: markRunning,
    resetWorkflow,
  } = useWorkflow()

  const isSaved = state.id !== 'default'
  const [agents, setAgents] = useState<AgentDefinition[]>([])
  const [messagingChannels, setMessagingChannels] = useState<MessagingChannel[]>([])
  const [workflows, setWorkflows] = useState<WorkflowSummary[]>([])
  const [selectedNodeId, setSelectedNodeId] = useState<string | null>(null)
  const [initialTask, setInitialTask] = useState('')
  const [insertAgentId, setInsertAgentId] = useState<string>('')
  const [running, setRunning] = useState(false)
  const [saving, setSaving] = useState(false)
  const [saveMessage, setSaveMessage] = useState<string | null>(null)
  const [lastRun, setLastRun] = useState<WorkflowRun | null>(null)
  const [runError, setRunError] = useState<string | null>(null)
  const [schemaEditorOpen, setSchemaEditorOpen] = useState(false)
  const [editEdgeInputId, setEditEdgeInputId] = useState<string | null>(null)
  const startTaskRef = useRef<HTMLTextAreaElement>(null)

  const hasMessagingIn = state.nodes.some(n => n.type === 'messaging-in')

  useEffect(() => {
    let cancelled = false
    const loadData = () => {
      fetchAgents()
        .then(data => { if (!cancelled) setAgents(data) })
        .catch(() => undefined)
      fetchMessagingChannels()
        .then(data => { if (!cancelled) setMessagingChannels(data) })
        .catch(() => undefined)
      fetchWorkflows()
        .then(data => { if (!cancelled) setWorkflows(data) })
        .catch(() => undefined)
    }
    loadData()
    return () => { cancelled = true }
  }, [])

  // Auto-grow the start-task textarea with its content; collapses back to one line when empty
  const autogrowStartTask = (el: HTMLTextAreaElement) => {
    el.style.height = 'auto'
    el.style.height = `${el.scrollHeight}px`
  }

  useEffect(() => {
    if (startTaskRef.current) autogrowStartTask(startTaskRef.current)
  }, [initialTask])

  const selectedNode = state.nodes.find(n => n.id === selectedNodeId) ?? null

  // Downstream helpers for selector options
  const downstreamTargets = (nodeId: string) => getAvailableTargets(nodeId, state.nodes, state.edges)

  /** Creates a messaging-in node pre-filled from a channel configuration */
  const addMessagingInFromChannel = (channel: MessagingChannel) => {
    const id = `node-${Date.now()}-${Math.random().toString(36).slice(2, 11)}`
    const newNode = {
      id,
      type: 'messaging-in' as const,
      title: `In: ${channel.topic}`,
      status: 'pending' as const,
      createdAt: new Date().toISOString(),
      config: {
        topic: channel.topic,
        tenantId: channel.tenantId || 'default',
        agentId: channel.agentId,
        timeoutMs: 30000,
      },
    }
    // Direct dispatch - messaging-in is a start node, no auto-edge
    dispatch({ type: 'ADD_NODE', payload: newNode })
  }

  const refreshAgents = () => {
    fetchAgents()
      .then(data => setAgents(data))
      .catch(() => undefined)
  }

  /** Creates the workflow or updates it — returns the workflow id */
  const saveWorkflow = async (): Promise<string> => {
    const payload = { name: state.name, nodes: state.nodes, edges: state.edges, initialTask }
    if (isSaved) {
      await updateWorkflow(state.id, payload)
      return state.id
    }
    const id = await createWorkflow(payload)
    setId(id)
    return id
  }

  const handleSave = async () => {
    if (state.nodes.length === 0) {
      setSaveMessage('Nothing to save -- add nodes first')
      return
    }
    setSaving(true)
    setSaveMessage(null)
    try {
      const id = await saveWorkflow()
      setSaveMessage(id === state.id ? `Workflow updated: ${id}` : `Workflow saved: ${id}`)
    } catch (err) {
      setSaveMessage('Save failed: ' + String(err))
    } finally {
      setSaving(false)
    }
  }

  /** Runs a workflow run and polls until completion */
  const runWorkflowById = async (workflowId: string) => {
    setRunError(null)
    setLastRun(null)
    markRunning()
    const { runId } = await executeWorkflow(workflowId, initialTask)
    let finished = false
    while (!finished) {
      await new Promise(resolve => setTimeout(resolve, 1000))
      try {
        const run = await fetchRun(runId)
        setLastRun(run)
        const statuses: Record<string, 'pending' | 'running' | 'waiting' | 'completed' | 'failed'> = {}
        for (const nr of run.nodeResults) {
          statuses[nr.nodeId] = nr.status
        }
        setNodeStatuses(statuses)
        if (run.status === 'completed' || run.status === 'failed') {
          finished = true
          if (run.status === 'failed') setRunError(`Run ${run.runId} failed`)
        }
      } catch {
        finished = true
        setRunError('Could not load run status')
      }
    }
  }

  const handleExecute = async () => {
    if (state.nodes.length === 0 || running) return
    setRunning(true)
    try {
      const id = await saveWorkflow()
      await runWorkflowById(id)
    } catch (error) {
      state.nodes.forEach(node => updateNode(node.id, { status: 'failed' }))
      setRunError(String(error))
    } finally {
      setRunning(false)
    }
  }

  const handleLoadWorkflow = (id: string) => {
    fetchWorkflow(id)
      .then(wf => {
        loadWorkflow(wf.id, wf.name, wf.nodes, wf.edges)
        setInitialTask(wf.initialTask ?? '')
        setSelectedNodeId(null)
        setLastRun(null)
        setSaveMessage(null)
      })
      .catch(() => undefined)
  }

  /** Loads a saved workflow and runs it directly */
  const handleRunWorkflow = async (id: string) => {
    if (running) return
    setRunning(true)
    try {
      const wf = await fetchWorkflow(id)
      loadWorkflow(wf.id, wf.name, wf.nodes, wf.edges)
      setInitialTask(wf.initialTask ?? '')
      setSelectedNodeId(null)
      await runWorkflowById(id)
    } catch (err) {
      setRunError(String(err))
    } finally {
      setRunning(false)
    }
  }

  const handleNewWorkflow = () => {
    newWorkflow()
    setInitialTask('')
    setSelectedNodeId(null)
    setLastRun(null)
    setSaveMessage(null)
    setRunError(null)
  }

  return (
    <div className="workflow-composer" data-testid="workflow-composer">
      <h2>Workflow Composer</h2>

      <div className="workflow-name-row">
        <label>Name:</label>
        <input
          type="text"
          value={state.name}
          onChange={e => setName(e.target.value)}
          aria-label="Workflow-Name"
        />
        <span className="workflow-save-state">
          {isSaved ? `Saved: ${state.id}` : 'Not saved'}
        </span>
      </div>

      <div className="toolbar" role="toolbar">
        <button type="button" onClick={handleNewWorkflow}>+ New</button>
        <span className="toolbar-separator" />
        <select
          value={insertAgentId}
          onChange={e => setInsertAgentId(e.target.value)}
          aria-label="Select agent to insert"
        >
          <option value="">-- select agent --</option>
          {agents.map(a => (
            <option key={a.id} value={a.id}>{a.name} ({a.category})</option>
          ))}
        </select>
        <button
          type="button"
          onClick={() => {
            if (!insertAgentId) return
            const agent = agents.find(a => a.id === insertAgentId)
            addNode('agent', insertAgentId, agent?.name ?? insertAgentId, selectedNodeId ?? undefined)
          }}
          disabled={!insertAgentId}
        >
          + Insert
        </button>
        <span className="toolbar-separator" />
        <button type="button" className="messaging" onClick={() => addNode('messaging-in')}>📥 Messaging In</button>
        <button type="button" className="messaging" onClick={() => addNode('messaging-out', undefined, undefined, selectedNodeId ?? undefined)}>📤 Messaging Out</button>
        <span className="toolbar-separator" />
        <button type="button" className="control-flow" onClick={() => addNode('loop', undefined, undefined, selectedNodeId ?? undefined)}>⟳ Loop</button>
        <button type="button" className="control-flow" onClick={() => addNode('conditional', undefined, undefined, selectedNodeId ?? undefined)}>⤳ Conditional</button>
        <select
          className="more-nodes"
          aria-label="Add more nodes"
          value=""
          onChange={e => {
            const t = e.target.value
            if (t) { addNode(t as WorkflowNodeType, undefined, undefined, selectedNodeId ?? undefined); e.target.value = '' }
          }}
        >
          <option value="">+ Weitere Knoten…</option>
          <option value="nested-workflow">⧉ Nested Workflow</option>
          <option value="async">⚡ Async</option>
          <option value="fork">⎀ Fork</option>
          <option value="join">⧉ Join</option>
        </select>
        <span className="toolbar-separator" />
        <button type="button" onClick={resetWorkflow} disabled={state.nodes.length === 0}>Reset</button>
        <button type="button" onClick={handleSave} disabled={state.nodes.length === 0 || saving}>
          {saving ? 'Saving...' : isSaved ? 'Save (Update)' : 'Save'}
        </button>
        <button type="button" onClick={handleExecute} disabled={state.nodes.length === 0 || running}>
          {running ? 'Running...' : 'Execute'}
        </button>
      </div>

      {saveMessage && <p className="save-message">{saveMessage}</p>}

      <div className="start-task-row">
        <label htmlFor="start-task-input">Start / Initial Task:</label>
        {hasMessagingIn ? (
          <p className="start-task-hint">
            Workflow starts from a messaging-in node — the start task is ignored on execution.
          </p>
        ) : (
          <textarea
            id="start-task-input"
            ref={startTaskRef}
            rows={1}
            className="start-task-input"
            value={initialTask}
            onChange={e => {
              setInitialTask(e.target.value)
              autogrowStartTask(e.target)
            }}
            placeholder="e.g. Analyze customer complaints and propose a solution"
            aria-label="Start / Initial Task"
          />
        )}
      </div>

      {messagingChannels.length > 0 && (
        <div className="messaging-channels-bar">
          <span className="messaging-channels-label">Messaging Channels:</span>
          {messagingChannels.map((ch, i) => (
            <button
              key={i}
              type="button"
              className="messaging channel-btn"
              onClick={() => addMessagingInFromChannel(ch)}
              title={`Add messaging-in node for ${ch.topic} (${ch.transport})`}
            >
              + {ch.transport}:{ch.topic}
            </button>
          ))}
        </div>
      )}

      {running && <div className="workflow-progress" role="progressbar"><p>Workflow running -- loading status...</p></div>}
      {runError && <p className="error-state">{runError}</p>}

      <div className="workflow-editor" role="region" aria-label="Workflow Editor">
        {state.nodes.length === 0 ? (
          <div className="empty-state">
            <p>Add agents using the toolbar to start building your workflow</p>
          </div>
        ) : (
          <ReactFlowCanvas
            nodes={state.nodes}
            edges={state.edges}
            agents={agents}
            selectedNodeId={selectedNodeId}
            onSelectNode={setSelectedNodeId}
            onAddEdge={(edge) => dispatch({ type: 'ADD_EDGE', payload: edge })}
            onRemoveEdge={(id) => dispatch({ type: 'REMOVE_EDGE', payload: id })}
            onUpdateNode={updateNode}
          />
        )}
      </div>

      <div className="workflow-config-grid">
        {selectedNode && (
          <section className="node-editor panel">
            <h3>Node: {selectedNode.title}</h3>
            <div className="form-group">
              <label>Title</label>
              <input
                type="text"
                value={selectedNode.title}
                onChange={e => updateNode(selectedNode.id, { title: e.target.value })}
              />
            </div>

            <div className="form-group">
              <label>Timeout (ms, 0 = kein Limit)</label>
              <input
                type="number"
                min="0"
                value={(selectedNode.config?.timeoutMs as number) ?? 0}
                onChange={e => updateNode(selectedNode.id, {
                  config: { ...selectedNode.config, timeoutMs: parseInt(e.target.value) || 0 },
                })}
              />
              <p className="field-hint">
                <code>0</code> = deaktiviert (unbegrenzt warten). Bei Überschreitung wird ein
                Timeout-Marker in den Node-Output geschrieben — ein nachfolgender Conditional kann
                auf <code>contains:timeout</code> routen.
              </p>
            </div>

            {selectedNode.type === 'agent' && (
              <>
                <div className="form-group">
                  <label>Bound Agent</label>
                  <div className="agent-select-row">
                    <select
                      value={(selectedNode.config?.agentId as string) ?? ''}
                      onChange={e => updateNode(selectedNode.id, {
                        config: { ...selectedNode.config, agentId: e.target.value || undefined },
                      })}
                    >
                      <option value="">-- no agent bound --</option>
                      {agents.map(a => (
                        <option key={a.id} value={a.id}>{a.name} ({a.category})</option>
                      ))}
                    </select>
                    <button type="button" className="small" onClick={refreshAgents} title="Refresh agent list">
                      ↻
                    </button>
                  </div>
                </div>
                <div className="form-group">
                  <label>
                    <input
                      type="checkbox"
                      checked={(selectedNode.config?.jsonOutput as boolean) ?? false}
                      onChange={e => updateNode(selectedNode.id, {
                        config: { ...selectedNode.config, jsonOutput: e.target.checked },
                      })}
                    />
                    {' '}JSON Output (produces structured JSON response)
                  </label>
                </div>
                {(selectedNode.config?.jsonOutput as boolean) && (
                  <div className="form-group">
                    <button
                      type="button"
                      className="small"
                      onClick={() => setSchemaEditorOpen(true)}
                      aria-label="JSON Output Schema bearbeiten"
                    >
                      ✎ JSON-Output-Schema{(selectedNode.config?.jsonOutputSchema as string) ? ' (active)' : ' (off)'}
                    </button>
                    <p className="field-hint">
                      Valid JSON Schema wird als Structured Output (responseFormat) durchgesetzt.
                    </p>
                  </div>
                )}
                <div className="form-group">
                  <label>System Prompt</label>
                  <textarea
                    value={(selectedNode.config?.systemPrompt as string) ?? ''}
                    onChange={e => updateNode(selectedNode.id, {
                      config: { ...selectedNode.config, systemPrompt: e.target.value || undefined },
                    })}
                    placeholder="Role and instructions for the LLM (optional)"
                    rows={3}
                  />
                </div>
                {(selectedNode.config?.jsonOutput as boolean) && (
                  <div className="form-group">
                    <label>User Message Template</label>
                    <textarea
                      value={(selectedNode.config?.userMessageTemplate as string) ?? ''}
                      onChange={e => updateNode(selectedNode.id, {
                        config: { ...selectedNode.config, userMessageTemplate: e.target.value || undefined },
                      })}
                      placeholder="Template with {{nodeId.output}}, {{initialPrompt}}, ..."
                      rows={4}
                    />
                  </div>
                )}
              </>
            )}

            {selectedNode.type === 'loop' && (
              <>
                <div className="form-group">
                  <label>Max Iterations</label>
                  <input
                    type="number"
                    min="1"
                    value={(selectedNode.config?.maxIterations as number) ?? 5}
                    onChange={e => updateNode(selectedNode.id, {
                      config: { ...selectedNode.config, maxIterations: parseInt(e.target.value) || 1 },
                    })}
                  />
                </div>
                <div className="form-group">
                  <label>Exit Condition</label>
                  <input
                    type="text"
                    value={(selectedNode.config?.exitCondition as string) ?? ''}
                    onChange={e => updateNode(selectedNode.id, {
                      config: { ...selectedNode.config, exitCondition: e.target.value },
                    })}
                    placeholder="e.g. contains:fertig | iter>=3 | matches:score=\\d+"
                  />
                  <p className="field-hint">
                    Leave empty to always run maxIterations. Supported: always, contains:text,
                    notcontains:text, equals:text, matches:regex, iter==N, iter!=N, iter&lt;N, iter&gt;=N…
                  </p>
                </div>

                {/* NEW: Loop Anchor selector — default = entire downstream segment */}
                <div className="form-group">
                  <label>Loop Start (first body node)</label>
                  <select
                    value={(selectedNode.config?.loopTargetId as string) ?? ''}
                    onChange={e => updateNode(selectedNode.id, {
                      config: { ...selectedNode.config, loopTargetId: e.target.value || undefined },
                    })}
                    aria-label="Loop start (first body node)"
                  >
                    <option value="">-- all downstream agents --</option>
                    {downstreamTargets(selectedNode.id).map(t => (
                      <option key={t.id} value={t.id}>{t.title}</option>
                    ))}
                  </select>
                  <p className="field-hint">
                    Default (-- all downstream agents --) repeats EVERY agent below this loop node in the
                    same iteration, all the way until the flow links back to the loop node. Optionally pick
                    a start anchor to repeat only the segment from that node onward.
                  </p>
                </div>
              </>
            )}

            {selectedNode.type === 'conditional' && (
              <>
                <div className="form-group">
                  <label>Condition</label>
                  <input
                    type="text"
                    value={(selectedNode.config?.condition as string) ?? ''}
                    onChange={e => updateNode(selectedNode.id, {
                      config: { ...selectedNode.config, condition: e.target.value },
                    })}
                    placeholder="e.g. contains:error"
                  />
                </div>
                <p className="field-hint">
                  Evaluated against the combined input of this node. Use the selectors below to route
                  to specific agent segments instead of skipping everything.
                </p>

                {/* NEW: True Target selector */}
                <div className="form-group">
                  <label>Then →</label>
                  <select
                    value={(selectedNode.config?.trueTargetId as string) ?? ''}
                    onChange={e => updateNode(selectedNode.id, {
                      config: { ...selectedNode.config, trueTargetId: e.target.value || undefined },
                    })}
                    aria-label="Then target"
                  >
                    <option value="">-- next step in sequence --</option>
                    {downstreamTargets(selectedNode.id).map(t => (
                      <option key={t.id} value={t.id}>{t.title}</option>
                    ))}
                  </select>
                </div>

                {/* NEW: False Target selector */}
                <div className="form-group">
                  <label>Else →</label>
                  <select
                    value={(selectedNode.config?.falseTargetId as string) ?? ''}
                    onChange={e => updateNode(selectedNode.id, {
                      config: { ...selectedNode.config, falseTargetId: e.target.value || undefined },
                    })}
                    aria-label="Else target"
                  >
                    <option value="">-- skip downstream --</option>
                    {downstreamTargets(selectedNode.id).map(t => (
                      <option key={t.id} value={t.id}>{t.title}</option>
                    ))}
                  </select>
                </div>
              </>
            )}

            {selectedNode.type === 'nested-workflow' && (
              <>
                <div className="form-group">
                  <label>Target Workflow</label>
                  <select
                    value={(selectedNode.config?.workflowId as string) ?? ''}
                    onChange={e => updateNode(selectedNode.id, {
                      config: { ...selectedNode.config, workflowId: e.target.value },
                    })}
                  >
                    <option value="">-- choose workflow --</option>
                    {workflows.map(w => (
                      <option key={w.id} value={w.id}>{w.name} ({w.id})</option>
                    ))}
                  </select>
                </div>
                <div className="form-group">
                  <label>Output Mapping (subWorkflowNodeId → parentKey)</label>
                  {Object.entries((selectedNode.config?.outputMapping as Record<string, string>) ?? {}).map(([subKey, parentKey]) => (
                    <div className="mapping-row" key={subKey}>
                      <input
                        type="text"
                        value={subKey}
                        aria-label="Sub workflow node id"
                        onChange={e => {
                          const next: Record<string, string> = { ...(selectedNode.config?.outputMapping as Record<string, string> ?? {}) }
                          const value = next[subKey]
                          delete next[subKey]
                          next[e.target.value] = value
                          updateNode(selectedNode.id, { config: { ...selectedNode.config, outputMapping: next } })
                        }}
                      />
                      <span className="mapping-arrow">→</span>
                      <input
                        type="text"
                        value={parentKey ?? ''}
                        aria-label="Parent scope key"
                        onChange={e => updateNode(selectedNode.id, {
                          config: {
                            ...selectedNode.config,
                            outputMapping: { ...(selectedNode.config?.outputMapping as Record<string, string> ?? {}), [subKey]: e.target.value },
                          },
                        })}
                      />
                      <button
                        type="button"
                        className="danger small"
                        onClick={() => {
                          const next: Record<string, string> = { ...(selectedNode.config?.outputMapping as Record<string, string> ?? {}) }
                          delete next[subKey]
                          updateNode(selectedNode.id, { config: { ...selectedNode.config, outputMapping: next } })
                        }}
                      >
                        ✕
                      </button>
                    </div>
                  ))}
                  <button
                    type="button"
                    className="small"
                    onClick={() => updateNode(selectedNode.id, {
                      config: {
                        ...selectedNode.config,
                        outputMapping: {
                          ...(selectedNode.config?.outputMapping as Record<string, string> ?? {}),
                          [`node-${Date.now()}`]: '',
                        },
                      },
                    })}
                  >
                    + Add mapping
                  </button>
                </div>
              </>
            )}

            {selectedNode.type === 'async' && (
              <>
                <p className="help-text">
                  Runs the following node (or <code>refNodeId</code>) asynchronously and
                  waits for the result up to the value set in the "Timeout" field (deferred completion).
                  When the timeout expires (<code>0</code> = no limit), a timeout marker is written
                  into the output — a following conditional can then route on, e.g.,
                  <code> contains:timeout</code>.
                </p>
                <p className="help-text">
                  <strong>External async:</strong> submit the order via <code>messaging-out</code>,
                  the external system returns the result via{' '}
                  <code>POST /api/ui/dynamic/complete {"{runId, nodeId, output}"}</code>.
                  Async nodes run in parallel (virtual threads); a <code>join</code> aggregates
                  multiple async branches.
                </p>
              </>
            )}

            {selectedNode.type === 'fork' && (
              <p className="help-text">
                Parallel branch marker: the incoming and outgoing paths run in parallel via the scheduler.
              </p>
            )}

            {selectedNode.type === 'join' && (
              <>
                <div className="form-group">
                  <label>Join Policy</label>
                  <select
                    value={(selectedNode.config?.joinPolicy as string) ?? 'all'}
                    onChange={e => updateNode(selectedNode.id, {
                      config: { ...selectedNode.config, joinPolicy: e.target.value },
                    })}
                  >
                    <option value="all">all</option>
                    <option value="any">any</option>
                  </select>
                </div>
                <div className="form-group">
                  <label>Result IDs (Komma-getrennt)</label>
                  <input
                    type="text"
                    value={((selectedNode.config?.resultIds as string[]) ?? []).join(',')}
                    onChange={e => updateNode(selectedNode.id, {
                      config: {
                        ...selectedNode.config,
                        resultIds: e.target.value.split(',').map(s => s.trim()).filter(Boolean),
                      },
                    })}
                    placeholder="node-A,node-B,node-C"
                  />
                </div>
                <p className="help-text">
                  <code>all</code> = wait for all incoming branches (including async branches),
                  <code> any</code> = continue as soon as one is done. With the timeout field above,
                  the join fires as soon as all expected result IDs are present <b>or</b> the timeout
                  expires (<code>0</code> = no limit); missing results are marked with
                  <code> timeout</code>. A following aggregator agent gets the
                  results as input, or a conditional routes on <code>contains:timeout</code>.
                </p>
              </>
            )}

            {selectedNode.type === 'messaging-in' && (
              <>
                {messagingChannels.length > 0 && !(selectedNode.config?.topic) && (
                  <div className="form-group">
                    <label>Select Channel</label>
                    <select
                      onChange={e => {
                        const ch = messagingChannels[parseInt(e.target.value)]
                        if (ch) {
                          updateNode(selectedNode.id, {
                            config: {
                              ...selectedNode.config,
                              topic: ch.topic,
                              tenantId: ch.tenantId || 'default',
                              agentId: ch.agentId,
                              timeoutMs: (selectedNode.config?.timeoutMs as number) || 30000,
                            },
                            title: `In: ${ch.topic}`,
                          })
                        }
                      }}
                    >
                      <option value="">-- choose channel --</option>
                      {messagingChannels.map((ch, i) => (
                        <option key={i} value={i}>{ch.transport}: {ch.topic} (agent: {ch.agentId})</option>
                      ))}
                    </select>
                  </div>
                )}
                <div className="form-group">
                  <label>Topic</label>
                  <input
                    type="text"
                    value={(selectedNode.config?.topic as string) ?? ''}
                    onChange={e => updateNode(selectedNode.id, {
                      config: { ...selectedNode.config, topic: e.target.value },
                    })}
                    placeholder="e.g. support-commands"
                  />
                </div>
                <div className="form-group">
                  <label>Tenant ID</label>
                  <input
                    type="text"
                    value={(selectedNode.config?.tenantId as string) ?? 'default'}
                    onChange={e => updateNode(selectedNode.id, {
                      config: { ...selectedNode.config, tenantId: e.target.value || 'default' },
                    })}
                  />
                </div>
              </>
            )}

            {selectedNode.type === 'messaging-out' && (
              <>
                <div className="form-group">
                  <label>Channel</label>
                  <select
                    value={(selectedNode.config?.channel as string) ?? 'kafka'}
                    onChange={e => updateNode(selectedNode.id, {
                      config: { ...selectedNode.config, channel: e.target.value },
                    })}
                  >
                    <option value="kafka">Kafka</option>
                    <option value="amqp">AMQP (RabbitMQ)</option>
                    <option value="email">Email</option>
                  </select>
                </div>
                <div className="form-group">
                  <label>Topic / Queue</label>
                  <input
                    type="text"
                    value={(selectedNode.config?.topic as string) ?? ''}
                    onChange={e => updateNode(selectedNode.id, {
                      config: { ...selectedNode.config, topic: e.target.value },
                    })}
                    placeholder="e.g. quad-results"
                  />
                </div>
              </>
            )}

            <button
              type="button"
              className="danger"
              onClick={() => { removeNode(selectedNode.id); setSelectedNodeId(null) }}
            >
              Remove Node
            </button>
          </section>
        )}

        <section className="edge-editor panel">
          <h3>Data Flow Between Steps</h3>
          {state.edges.length === 0 ? (
            <p className="empty-state">No edges yet. New nodes are auto-connected.</p>
          ) : (
            <table>
              <thead>
                <tr><th>From</th><th>To</th><th>Input Sources</th><th>Join</th><th></th></tr>
              </thead>
              <tbody>
                {state.edges.map(edge => {
                  const tgt = state.nodes.find(n => n.id === edge.target)
                  const srcTitle = (id: string) =>
                    state.nodes.find(n => n.id === id)?.title
                    ?? (id === INITIAL_DATA_FIELD.value ? INITIAL_DATA_FIELD.label : id)
                  const inputs: EdgeInputMapping[] = edge.inputs ?? (edge.input ? [edge.input] : [{
                    sourceNodeId: edge.source, format: 'text',
                  }])
                  const isCustom = !!edge.inputs || !!edge.input
                  const preview = inputs.length === 0
                    ? '—'
                    : inputs.map(src => srcTitle(src.sourceNodeId))
                        .map((t, i) => (i > 0 ? ' + ' : '') + t)
                        .join('')
                  return (
                    <tr key={edge.id}>
                      <td>{state.nodes.find(n => n.id === edge.source)?.title ?? edge.source}</td>
                      <td>{tgt?.title ?? edge.target}</td>
                      <td>
                        <button
                          type="button"
                          className="source-button"
                          onClick={() => setEditEdgeInputId(edge.id)}
                          title={preview}
                          aria-label={`Edit input sources for ${tgt?.title ?? ''}`}
                        >
                          edit ({isCustom ? 'custom' : 'default'})
                          {isCustom && <span className="source-count">{inputs.length}</span>}
                        </button>
                      </td>
                      <td>
                        <select
                          value={edge.joinPolicy ?? 'all'}
                          onChange={e => updateEdge(edge.id, { joinPolicy: e.target.value as 'all' | 'any' })}
                          aria-label={`Join policy for ${tgt?.title ?? ''}`}
                        >
                          <option value="all">all</option>
                          <option value="any">any</option>
                        </select>
                      </td>
                      <td>
                        <button type="button" className="danger" onClick={() => removeEdge(edge.id)}>✕</button>
                      </td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
          )}
        </section>
      </div>

      {lastRun && (
        <section className="run-result panel">
          <h3>Last Run: {lastRun.runId}</h3>
          <p>
            Status: <strong>{lastRun.status}</strong> · Duration: {lastRun.durationMs} ms
            {' · '}{lastRun.startedAt}
          </p>
          {lastRun.initialData && (
            <div className="run-start-task">
              <span className="run-start-task-label">Start task:</span>{' '}
              <span className="run-start-task-value">{lastRun.initialData}</span>
            </div>
          )}
          {lastRun.status === 'completed' && lastRun.nodeResults.length > 0 && (
            <div className="run-summary">
              <h4>Workflow Result</h4>
              <pre className="run-output">{lastRun.nodeResults[lastRun.nodeResults.length - 1].output}</pre>
            </div>
          )}
          {onTabChange && (
            <button type="button" onClick={() => onTabChange('monitor')}>View in Monitor</button>
          )}
          {lastRun.nodeResults.map(nr => (
            <details key={nr.nodeId} open={nr.status === 'failed'}>
              <summary className={`node-summary ${nr.status}`}>
                [{nr.status.toUpperCase()}] {nr.title}
              </summary>
              {nr.input && (
                <>
                  <h4>Injected Input (SessionState):</h4>
                  <pre className="run-input">{nr.input}</pre>
                </>
              )}
              <h4>Output:</h4>
              <pre className="run-output">{nr.output}</pre>
            </details>
          ))}
        </section>
      )}

      <WorkflowList onSelect={handleLoadWorkflow} onExecute={handleRunWorkflow} />

      {schemaEditorOpen && selectedNode?.type === 'agent' && (
        <JsonSchemaPopup
          initialSchema={(selectedNode.config?.jsonOutputSchema as string) ?? ''}
          title={`JSON Output Schema — ${selectedNode.title}`}
          onSave={schema => updateNode(selectedNode.id, {
            config: { ...selectedNode.config, jsonOutputSchema: schema || undefined },
          })}
          onClose={() => setSchemaEditorOpen(false)}
        />
      )}

      {editEdgeInputId && (() => {
        const edge = state.edges.find(e => e.id === editEdgeInputId)
        if (!edge) return null
        const tgt = state.nodes.find(n => n.id === edge.target)
        const initialInputs: EdgeInputMapping[] = edge.inputs ?? (edge.input ? [edge.input] : [{
          sourceNodeId: edge.source, format: 'text',
        }])
        const nodes = state.nodes.map(n => ({
          id: n.id, title: n.title, type: n.type,
          schema: (n.config?.jsonOutputSchema as string) ?? '',
        }))
        return (
          <EdgeInputsPopup
            nodes={nodes}
            targetTitle={tgt?.title ?? edge.target}
            initialInputs={initialInputs}
            onSave={inputs => {
              updateEdge(edge.id, {
                inputs: inputs.length > 0 ? inputs : undefined,
                input: inputs.length === 1 ? inputs[0] : undefined,
              })
              setEditEdgeInputId(null)
            }}
            onClose={() => setEditEdgeInputId(null)}
          />
        )
      })()}
    </div>
  )
}