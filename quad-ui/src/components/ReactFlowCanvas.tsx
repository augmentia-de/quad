import { useCallback, useEffect, useMemo, useRef } from 'react'
import {
  ReactFlow,
  Handle,
  Position,
  BaseEdge,
  getStraightPath,
  useNodesState,
  useEdgesState,
  type Node,
  type Edge,
  type NodeChange,
  type EdgeChange,
  type Connection,
} from '@xyflow/react'
import '@xyflow/react/dist/style.css'
import type { AgentDefinition, NodePosition, WorkflowEdge, WorkflowNode } from '../services/types'

const NODE_W = 180, NODE_H = 80

type NodeData = WorkflowNode & { agentName: string }

function defaultPosition(index: number): NodePosition {
  return { x: (index % 3) * (NODE_W + 60) + 40, y: Math.floor(index / 3) * (NODE_H + 40) + 20 }
}

function WorkflowNodeComponent({ data }: { data: NodeData }) {
  return (
    <div className={`wf-node ${data.type} ${data.status ?? 'pending'}`} data-node-id={data.id}>
      <Handle type="target" position={Position.Left} className="wf-handle wf-handle-target" />
      <span className="wf-node-title">{data.title}</span>
      <span className="wf-node-agent">{data.agentName || ''}</span>
      <span className="wf-node-status">{(data.status ?? 'pending').toUpperCase()}</span>
      <Handle type="source" position={Position.Right} className="wf-handle wf-handle-source" />
    </div>
  )
}

function CondEdge({ data, sourceX, sourceY, targetX, targetY }: any) {
  const [edgePath] = getStraightPath({ sourceX, sourceY, targetX, targetY })
  const isThen = data?.kind === 'then'
  return (
    <BaseEdge
      path={edgePath}
      style={{
        stroke: isThen ? 'var(--accent, #75c29d)' : 'var(--warn, #d5a24d)',
        strokeWidth: 1.5,
        strokeDasharray: isThen ? '4,3' : '8,3',
        opacity: 0.7,
      }}
    />
  )
}

function LoopbackEdge({ sourceX, sourceY, targetX, targetY }: any) {
  const [edgePath] = getStraightPath({ sourceX, sourceY, targetX, targetY })
  return (
    <BaseEdge
      path={edgePath}
      style={{
        stroke: 'var(--accent-border, rgba(117,194,157,0.42))',
        strokeWidth: 1.5,
        strokeDasharray: '6,4',
        opacity: 0.5,
      }}
    />
  )
}

const nodeTypes = { workflowNode: WorkflowNodeComponent }
const edgeTypes = { condEdge: CondEdge, loopbackEdge: LoopbackEdge }

function computeSyntheticEdges(nodes: WorkflowNode[], loopBodySinks: Array<{ loopId: string; sinkId: string }>): Edge[] {
  const edges: Edge[] = []
  for (const n of nodes) {
    if (n.type === 'conditional') {
      if (n.config?.trueTargetId) {
        edges.push({
          id: `cond-then-${n.id}`,
          source: n.id,
          target: n.config.trueTargetId as string,
          type: 'condEdge',
          data: { kind: 'then' },
        })
      }
      if (n.config?.falseTargetId) {
        edges.push({
          id: `cond-else-${n.id}`,
          source: n.id,
          target: n.config.falseTargetId as string,
          type: 'condEdge',
          data: { kind: 'else' },
        })
      }
    }
  }
  for (const { loopId, sinkId } of loopBodySinks) {
    edges.push({
      id: `loopback-${loopId}-${sinkId}`,
      source: sinkId,
      target: loopId,
      type: 'loopbackEdge',
      data: {},
    })
  }
  return edges
}

function getLoopBodySinks(nodes: WorkflowNode[], edges: WorkflowEdge[]): Array<{ loopId: string; sinkId: string }> {
  const sinks: Array<{ loopId: string; sinkId: string }> = []
  for (const node of nodes) {
    if (node.type !== 'loop') continue
    const downstream = new Set<string>()
    const queue = [node.id]
    while (queue.length > 0) {
      const cur = queue.shift()!
      for (const e of edges) {
        if (e.source === cur && !downstream.has(e.target)) {
          downstream.add(e.target)
          queue.push(e.target)
        }
      }
    }
    if (downstream.size === 0) continue
    const bodyStart = (node.config?.loopTargetId as string) ?? node.id
    let bodyNodes = downstream
    if (bodyStart !== node.id) {
      const fromStart = new Set<string>()
      const q = [bodyStart]
      while (q.length > 0) {
        const cur = q.shift()!
        for (const e of edges) {
          if (e.source === cur && downstream.has(e.target) && !fromStart.has(e.target)) {
            fromStart.add(e.target)
            q.push(e.target)
          }
        }
      }
      bodyNodes = fromStart
    }
    for (const b of bodyNodes) {
      const hasOutgoingWithin = edges.some(e => e.source === b && bodyNodes.has(e.target))
      if (!hasOutgoingWithin) {
        sinks.push({ loopId: node.id, sinkId: b })
      }
    }
  }
  return sinks
}

interface ReactFlowCanvasProps {
  nodes: WorkflowNode[]
  edges: WorkflowEdge[]
  agents: AgentDefinition[]
  selectedNodeId: string | null
  onSelectNode: (id: string | null) => void
  onAddEdge: (edge: WorkflowEdge) => void
  onRemoveEdge: (id: string) => void
  onUpdateNode: (id: string, updates: Partial<WorkflowNode>) => void
}

export function ReactFlowCanvas({
  nodes: ctxNodes,
  edges: ctxEdges,
  agents,
  selectedNodeId,
  onSelectNode,
  onAddEdge,
  onRemoveEdge,
  onUpdateNode,
}: ReactFlowCanvasProps) {
  const loopBodySinks = useMemo(() => getLoopBodySinks(ctxNodes, ctxEdges), [ctxNodes, ctxEdges])
  const prevStructureRef = useRef('')

  const initialRfNodes: Node<NodeData>[] = useMemo(() =>
    ctxNodes.map((n, i) => ({
      id: n.id,
      type: 'workflowNode',
      position: n.position ?? defaultPosition(i),
      data: {
        ...n,
        agentName: n.config?.agentId
          ? (agents.find(a => a.id === n.config!.agentId)?.name ?? String(n.config.agentId))
          : '',
      },
      selected: n.id === selectedNodeId,
    })),
  // eslint-disable-next-line react-hooks/exhaustive-deps
  [])

  const initialRfEdges: Edge[] = useMemo(() => {
    const real: Edge[] = ctxEdges.map(e => ({
      id: e.id,
      source: e.source,
      target: e.target,
      type: 'default',
      data: { edge: e },
    }))
    return [...real, ...computeSyntheticEdges(ctxNodes, loopBodySinks)]
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const [rfNodes, setRfNodes, onNodesChangeRaw] = useNodesState(initialRfNodes)
  const [rfEdges, setRfEdges, onEdgesChangeRaw] = useEdgesState(initialRfEdges)

  const currentPositionsRef = useRef<Record<string, NodePosition>>({})

  useEffect(() => {
    const structureKey = JSON.stringify(ctxNodes.map(n => ({ id: n.id, type: n.type, title: n.title, config: n.config })))
      + '|' + ctxEdges.length + '|' + ctxNodes.length
    if (structureKey === prevStructureRef.current) return
    prevStructureRef.current = structureKey

    setRfNodes(ctxNodes.map((n, i) => ({
      id: n.id,
      type: 'workflowNode',
      position: currentPositionsRef.current[n.id] ?? n.position ?? defaultPosition(i),
      data: {
        ...n,
        agentName: n.config?.agentId
          ? (agents.find(a => a.id === n.config!.agentId)?.name ?? String(n.config.agentId))
          : '',
      },
      selected: n.id === selectedNodeId,
    })))

    setRfEdges([
      ...ctxEdges.map(e => ({
        id: e.id,
        source: e.source,
        target: e.target,
        type: 'default' as const,
        data: { edge: e },
      })),
      ...computeSyntheticEdges(ctxNodes, loopBodySinks),
    ])
  }, [ctxNodes, ctxEdges, loopBodySinks, agents, selectedNodeId, setRfNodes, setRfEdges])

  const handleNodesChange = useCallback((changes: NodeChange[]) => {
    onNodesChangeRaw(changes)
    for (const c of changes) {
      if (c.type === 'position' && c.dragging) {
        const node = ctxNodes.find(n => n.id === c.id)
        if (node && c.position) {
          currentPositionsRef.current[c.id] = { x: Math.round(c.position.x), y: Math.round(c.position.y) }
        }
      }
    }
  }, [onNodesChangeRaw, ctxNodes])

  const handleEdgesChange = useCallback((changes: EdgeChange[]) => {
    const realIds = new Set(ctxEdges.map(e => e.id))
    const filtered = changes.filter(c => realIds.has(c.id))
    onEdgesChangeRaw(filtered)
    for (const c of changes) {
      if (c.type === 'remove' && realIds.has(c.id)) {
        onRemoveEdge(c.id)
      }
    }
  }, [ctxEdges, onEdgesChangeRaw, onRemoveEdge])

  const handleConnect = useCallback((conn: Connection) => {
    if (!conn.source || !conn.target) return
    const edge: WorkflowEdge = {
      id: `edge-${Date.now()}-${Math.random().toString(36).slice(2, 11)}`,
      source: conn.source,
      target: conn.target,
      input: { sourceNodeId: conn.source, format: 'text' },
    }
    onAddEdge(edge)
  }, [onAddEdge])

  const handleNodeDragStop = useCallback((_event: any, node: Node) => {
    onUpdateNode(node.id, { position: { x: Math.round(node.position.x), y: Math.round(node.position.y) } })
  }, [onUpdateNode])

  const handleNodeClick = useCallback((_event: any, node: Node) => {
    onSelectNode(node.id)
  }, [onSelectNode])

  const handlePaneClick = useCallback(() => {
    onSelectNode(null)
  }, [onSelectNode])

  return (
    <div className="wf-canvas-container" data-testid="workflow-editor">
      <ReactFlow
        nodes={rfNodes}
        edges={rfEdges}
        onNodesChange={handleNodesChange}
        onEdgesChange={handleEdgesChange}
        onConnect={handleConnect}
        onNodeDragStop={handleNodeDragStop}
        onNodeClick={handleNodeClick}
        onPaneClick={handlePaneClick}
        nodeTypes={nodeTypes}
        edgeTypes={edgeTypes}
        fitView
        selectNodesOnDrag={false}
        deleteKeyCode="Delete"
        multiSelectionKeyCode={null}
      />
    </div>
  )
}