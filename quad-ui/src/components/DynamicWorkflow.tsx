import { useState } from 'react'
import {
  chatDynamicWorkflow,
  completeDynamicWorkflow,
  generateDynamicWorkflow,
  runDynamicWorkflow,
  saveDynamicWorkflow,
  startDynamicChat,
} from '../services/api'
import type { WorkflowDefinition, WorkflowEdge } from '../services/types'
import { MermaidDiagram } from './MermaidDiagram'

function workflowToMermaid(wf: WorkflowDefinition): string {
  const lines = ['flowchart LR']
  for (const n of wf.nodes) {
    const label = (n.title || n.id).replace(/["\\]/g, '')
    lines.push(`  ${n.id}["${label}"]`)
  }
  for (const e of wf.edges) {
    const label = e.input?.format === 'json' ? 'JSON' : 'text'
    lines.push(`  ${e.source} -- ${label} --> ${e.target}`)
  }
  return lines.join('\n')
}

/** All nodes transitively feeding into a target (used to pick a predecessor input source). */
function availableSources(nodeId: string, wf: WorkflowDefinition): string[] {
  const result: string[] = []
  const visited = new Set<string>()
  const queue = [nodeId]
  while (queue.length > 0) {
    const cur = queue.shift()!
    for (const e of wf.edges) {
      if (e.target === cur && !visited.has(e.source)) {
        visited.add(e.source)
        result.unshift(e.source)
        queue.push(e.source)
      }
    }
  }
  return result
}

/** Subtree: all nodes downstream of (and including) a source, topologically ordered. */
function subtreeOf(sourceId: string, wf: WorkflowDefinition): string[] {
  const result: string[] = []
  const visited = new Set<string>()
  const queue = [sourceId]
  while (queue.length > 0) {
    const cur = queue.shift()!
    if (visited.has(cur)) continue
    visited.add(cur)
    for (const e of wf.edges) {
      if (e.source === cur && !visited.has(e.target)) queue.push(e.target)
    }
  }
  for (const n of wf.nodes) if (visited.has(n.id)) result.push(n.id)
  return result
}

/** True when a node is transitively downstream of (or equal to) the given source. */
function isInSubtree(nodeId: string, subtree: string[]): boolean {
  return subtree.includes(nodeId)
}

export function DynamicWorkflow() {
  const [task, setTask] = useState('')
  const [name, setName] = useState('')
  const [loading, setLoading] = useState(false)
  const [generated, setGenerated] = useState<WorkflowDefinition | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [runId, setRunId] = useState<string | null>(null)
  const [savedId, setSavedId] = useState<string | null>(null)
  const [running, setRunning] = useState(false)

  const [compRunId, setCompRunId] = useState('')
  const [compNodeId, setCompNodeId] = useState('')
  const [compOutput, setCompOutput] = useState('')
  const [compSent, setCompSent] = useState(false)

  const [chatId, setChatId] = useState<string | null>(null)
  const [chatRefine, setChatRefine] = useState('')
  const [chatLoading, setChatLoading] = useState(false)
  const [chatHistory, setChatHistory] = useState<{ role: string; text: string }[]>([])
  const [subtreeRoot, setSubtreeRoot] = useState('')

  const handleGenerate = async () => {
    if (!task.trim()) return
    setError(null)
    setGenerated(null)
    setRunId(null)
    setSavedId(null)
    setLoading(true)
    try {
      const res = await generateDynamicWorkflow({ task: task.trim(), name: name.trim() || undefined })
      setGenerated(res.workflow)
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setLoading(false)
    }
  }

  const handleRun = async () => {
    if (!generated) return
    setError(null)
    setRunId(null)
    setRunning(true)
    try {
      const res = await runDynamicWorkflow({
        workflow: { name: generated.name, nodes: generated.nodes, edges: generated.edges, initialTask: generated.initialTask },
        initialData: task,
      })
      setRunId(res.runId)
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setRunning(false)
    }
  }

  const handleSave = async () => {
    if (!generated) return
    setError(null)
    setSavedId(null)
    try {
      const id = await saveDynamicWorkflow({
        name: name.trim() || generated.name,
        nodes: generated.nodes,
        edges: generated.edges,
        initialTask: generated.initialTask,
      })
      setSavedId(id)
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    }
  }

  const handleComplete = async () => {
    if (!compRunId.trim() || !compNodeId.trim()) return
    setError(null)
    setCompSent(false)
    try {
      await completeDynamicWorkflow({
        runId: compRunId.trim(),
        nodeId: compNodeId.trim(),
        output: compOutput,
      })
      setCompSent(true)
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    }
  }

  const handleChatStart = async () => {
    if (!task.trim()) return
    setError(null)
    setGenerated(null)
    setRunId(null)
    setSavedId(null)
    setChatLoading(true)
    try {
      const res = await startDynamicChat({ task: task.trim(), name: name.trim() || undefined })
      setChatId(res.sessionId)
      setGenerated(res.workflow)
      setChatHistory([{ role: 'user', text: task.trim() }])
      if (res.reply) setChatHistory((h) => [...h, { role: 'assistant', text: res.reply! }])
      setChatRefine('')
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setChatLoading(false)
    }
  }

  const handleChatRefine = async () => {
    if (!chatId || !chatRefine.trim()) return
    setError(null)
    setChatLoading(true)
    const userText = chatRefine.trim()
    setChatRefine('')
    setChatHistory((h) => [...h, { role: 'user', text: userText }])
    try {
      const res = await chatDynamicWorkflow({ sessionId: chatId, request: userText })
      setGenerated(res.workflow)
      if (res.reply) setChatHistory((h) => [...h, { role: 'assistant', text: res.reply! }])
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
      setChatHistory((h) => h.filter((m) => m.text !== userText))
    } finally {
      setChatLoading(false)
    }
  }

  // ─── Editing helpers (JSON format between agents, subtree selection, multi-source) ──

  const updateEdgeInput = (edgeId: string, patch: Partial<WorkflowEdge>) => {
    setGenerated((g) => {
      if (!g) return g
      return {
        ...g,
        edges: g.edges.map((e) => (e.id === edgeId ? { ...e, ...patch } : e)),
      }
    })
  }

  const updateEdgeSource = (edgeId: string, input: WorkflowEdge['input']) => {
    setGenerated((g) => {
      if (!g) return g
      return {
        ...g,
        edges: g.edges.map((e) => (e.id === edgeId ? { ...e, input } : e)),
      }
    })
  }

  const nodeTitle = (id: string) => generated?.nodes.find((n) => n.id === id)?.title ?? id

  // Applied when a subtree root is selected: dim nodes outside the selected subtree.
  const subtree = generated && subtreeRoot ? subtreeOf(subtreeRoot, generated) : null

  return (
    <div className="dynamic-view">
      <div className="dynamic-header">
        <h2>Dynamic Workflow</h2>
        <p className="dynamic-subtitle">
          Describe a task and an LLM will decompose it into an executable workflow.
        </p>
      </div>

      <section className="dynamic-section">
        <div className="form-group">
          <label htmlFor="wf-name">Workflow name (optional)</label>
          <input
            id="wf-name"
            value={name}
            onChange={(e) => setName(e.target.value)}
            placeholder="My generated workflow"
          />
        </div>
        <div className="form-group">
          <label htmlFor="wf-task">Task description</label>
          <textarea
            id="wf-task"
            style={{ minHeight: 90 }}
            value={task}
            onChange={(e) => setTask(e.target.value)}
            placeholder="e.g. Research the market for electric scooters, summarize findings, and draft a 1-page product brief."
          />
        </div>
        <div className="dynamic-actions">
          <button
            className="dynamic-primary"
            onClick={handleGenerate}
            disabled={loading || !task.trim()}
          >
            {loading ? 'Generating…' : 'Generate Workflow'}
          </button>
          <button
            className="dynamic-primary"
            onClick={handleChatStart}
            disabled={chatLoading || !task.trim()}
          >
            {chatLoading ? 'Starting…' : 'Start Chat Session'}
          </button>
        </div>
      </section>

      {chatId && (
        <section className="dynamic-section">
          <h3>Chat session <code>{chatId}</code></h3>
          <div className="dynamic-chat">
            {chatHistory.map((m, i) => (
              <div key={i} className={`chat-bubble ${m.role === 'assistant' ? 'assistant' : 'user'}`}>
                <span className="chat-role">{m.role}</span>
                <pre>{m.text}</pre>
              </div>
            ))}
          </div>
          <div className="task-execution-row">
            <input
              value={chatRefine}
              onChange={(e) => setChatRefine(e.target.value)}
              onKeyDown={(e) => e.key === 'Enter' && handleChatRefine()}
              placeholder="Refine the workflow, e.g. 'add a step to validate results'"
            />
            <button onClick={handleChatRefine} disabled={chatLoading || !chatRefine.trim()}>
              {chatLoading ? 'Refining…' : 'Send'}
            </button>
          </div>
        </section>
      )}

      {error && (
        <div className="error-state">{error}</div>
      )}

      {generated && (
        <>
          <MermaidDiagram
            title={`Generated: ${name || generated.name} (${generated.nodes.length} steps)`}
            definition={workflowToMermaid(generated)}
          />

          <section className="dynamic-section">
            <h3>Data flow between agents (edit before run/save)</h3>
            <p className="dynamic-hint">
              Select a subtree root to focus on its downstream agents. For each edge you can choose
              which predecessor input flows in ({'multi-source'}) and whether it is passed as{' '}
              <code>text</code> or structured <code>JSON</code>.
            </p>
            <div className="runs-controls">
              <label>Subtree root</label>
              <select
                value={subtreeRoot}
                onChange={(e) => setSubtreeRoot(e.target.value)}
              >
                <option value="">— all nodes —</option>
                {generated.nodes.map((n) => (
                  <option key={n.id} value={n.id}>{n.title || n.id}</option>
                ))}
              </select>
            </div>
            {subtree && (
              <p className="dynamic-hint">
                Subtree of <code>{nodeTitle(subtreeRoot)}</code> ({subtree.length} nodes):
                {subtree.map((id) => nodeTitle(id)).join(' → ')}
              </p>
            )}
            {generated.edges.length === 0 ? (
              <p className="empty-state">No edges between agents yet.</p>
            ) : (
              <table className="dynamic-table">
                <thead>
                  <tr>
                    <th>From</th>
                    <th>To</th>
                    <th>Input Source</th>
                    <th>Format</th>
                  </tr>
                </thead>
                <tbody>
                  {generated.edges.map((e) => {
                    const inSubtree = !subtree || (isInSubtree(e.source, subtree) && isInSubtree(e.target, subtree))
                    return (
                      <tr key={e.id} className={inSubtree ? '' : 'dimmed'}>
                        <td>{nodeTitle(e.source)}</td>
                        <td>{nodeTitle(e.target)}</td>
                        <td>
                          <select
                            value={e.input?.sourceNodeId ?? e.source}
                            onChange={(ev) => updateEdgeSource(e.id, {
                              sourceNodeId: ev.target.value,
                              format: e.input?.format ?? 'text',
                            })}
                          >
                            {availableSources(e.target, generated).map((src) => (
                              <option key={src} value={src}>{nodeTitle(src)}</option>
                            ))}
                          </select>
                        </td>
                        <td>
                          <select
                            value={e.input?.format ?? 'text'}
                            onChange={(ev) => updateEdgeInput(e.id, {
                              input: { sourceNodeId: e.input?.sourceNodeId ?? e.source, format: ev.target.value as 'text' | 'json' },
                            })}
                          >
                            <option value="text">Text</option>
                            <option value="json">JSON</option>
                          </select>
                        </td>
                      </tr>
                    )
                  })}
                </tbody>
              </table>
            )}
          </section>

          <div className="dynamic-actions">
            <button
              className="dynamic-primary"
              onClick={handleRun}
              disabled={running}
            >
              {running ? 'Running…' : '▶ Run'}
            </button>
            <button onClick={handleSave}>
              Save as new Workflow
            </button>
            <button
              onClick={() => {
                setGenerated(null)
                setRunId(null)
                setSavedId(null)
                setChatId(null)
                setChatHistory([])
              }}
            >
              Clear
            </button>
          </div>

          {runId && (
            <div className="success-state">
              Workflow started. Run ID: <code>{runId}</code>. Track progress in the <b>Runs</b> tab.
            </div>
          )}

          {savedId && (
            <div className="success-state">
              Saved as a new editable workflow with ID <code>{savedId}</code>. Open the <b>Workflows</b> tab to edit it.
            </div>
          )}
        </>
      )}

      <section className="dynamic-section">
        <h3>Deferred completion (async nodes)</h3>
        <p className="dynamic-hint">
          Deliver the result of an <code>async</code> node that was handed off to an external
          system / human approver, so the run resumes and downstream (incl. the aggregator) continues.
        </p>
        <div className="dynamic-inline-grid">
          <input
            value={compRunId}
            onChange={(e) => setCompRunId(e.target.value)}
            placeholder="runId (from Run)"
          />
          <input
            value={compNodeId}
            onChange={(e) => setCompNodeId(e.target.value)}
            placeholder="nodeId (async node)"
          />
          <input
            value={compOutput}
            onChange={(e) => setCompOutput(e.target.value)}
            placeholder="output (result)"
          />
        </div>
        <div className="dynamic-actions">
          <button
            className="dynamic-primary"
            onClick={handleComplete}
            disabled={!compRunId.trim() || !compNodeId.trim()}
          >
            Send complete
          </button>
        </div>
        {compSent && (
          <div className="success-state">
            Deferred completion delivered for <code>{compNodeId}</code>.
          </div>
        )}
      </section>
    </div>
  )
}