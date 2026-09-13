import { createContext, useContext, useReducer } from 'react'
import type { ReactNode } from 'react'
import type { NodePosition, WorkflowEdge, WorkflowNode, WorkflowNodeStatus, WorkflowNodeType } from '../services/types'

export type AgentType = WorkflowNodeType
export type NodeStatus = WorkflowNodeStatus

const NODE_WIDTH = 180
const NODE_HEIGHT = 80
const NODE_GAP_X = 60
const NODE_GAP_Y = 40
const COLUMN_COUNT = 3

function getNodePosition(index: number): NodePosition {
  const col = index % COLUMN_COUNT
  const row = Math.floor(index / COLUMN_COUNT)
  return {
    x: col * (NODE_WIDTH + NODE_GAP_X) + 40,
    y: row * (NODE_HEIGHT + NODE_GAP_Y) + 20,
  }
}

function typeDefaultConfig(type: AgentType, agentId?: string): Record<string, unknown> {
  if (type === 'loop') return { maxIterations: 5, exitCondition: '' }
  if (type === 'conditional') return { condition: '' }
  if (type === 'nested-workflow') return { workflowId: '', outputMapping: {} }
  if (type === 'async') return {}
  if (type === 'fork') return {}
  if (type === 'join') return { joinPolicy: 'all' }
  return agentId ? { agentId } : {}
}

export interface WorkflowState {
  id: string
  name: string
  nodes: WorkflowNode[]
  edges: WorkflowEdge[]
  createdAt: string
  updatedAt: string
}

export interface WorkflowAction {
  type: 'ADD_NODE' | 'UPDATE_NODE' | 'REMOVE_NODE' | 'ADD_EDGE' | 'UPDATE_EDGE' | 'REMOVE_EDGE' | 'EXECUTE' | 'RESET' | 'SET_NAME' | 'SET_ID' | 'LOAD' | 'SET_STATUS' | 'NEW'
  payload?: unknown
}

const initialState: WorkflowState = {
  id: 'default',
  name: 'Standard Workflow',
  nodes: [],
  edges: [],
  createdAt: new Date().toISOString(),
  updatedAt: new Date().toISOString(),
}

function workflowReducer(state: WorkflowState, action: WorkflowAction): WorkflowState {
  switch (action.type) {
    case 'ADD_NODE': {
      const node = action.payload as WorkflowNode
      return {
        ...state,
        nodes: [...state.nodes, node],
        updatedAt: new Date().toISOString(),
      }
    }
    case 'UPDATE_NODE': {
      const { id, updates } = action.payload as { id: string; updates: Partial<WorkflowNode> }
      return {
        ...state,
        nodes: state.nodes.map(n => n.id === id ? { ...n, ...updates } : n),
        updatedAt: new Date().toISOString(),
      }
    }
    case 'REMOVE_NODE': {
      const nodeId = action.payload as string
      return {
        ...state,
        nodes: state.nodes.filter(n => n.id !== nodeId),
        edges: state.edges.filter(e => e.source !== nodeId && e.target !== nodeId),
        updatedAt: new Date().toISOString(),
      }
    }
    case 'ADD_EDGE': {
      const edge = action.payload as WorkflowEdge
      return {
        ...state,
        edges: [...state.edges, edge],
        updatedAt: new Date().toISOString(),
      }
    }
    case 'UPDATE_EDGE': {
      const { id, updates } = action.payload as { id: string; updates: Partial<WorkflowEdge> }
      return {
        ...state,
        edges: state.edges.map(e => e.id === id ? { ...e, ...updates } : e),
        updatedAt: new Date().toISOString(),
      }
    }
    case 'REMOVE_EDGE': {
      const edgeId = action.payload as string
      return {
        ...state,
        edges: state.edges.filter(e => e.id !== edgeId),
        updatedAt: new Date().toISOString(),
      }
    }
    case 'EXECUTE':
      return {
        ...state,
        nodes: state.nodes.map(n => ({ ...n, status: 'running' })),
        updatedAt: new Date().toISOString(),
      }
    case 'SET_STATUS': {
      const statuses = action.payload as Record<string, WorkflowNodeStatus>
      return {
        ...state,
        nodes: state.nodes.map(n => statuses[n.id] ? { ...n, status: statuses[n.id] } : n),
        updatedAt: new Date().toISOString(),
      }
    }
    case 'SET_NAME':
      return { ...state, name: action.payload as string, updatedAt: new Date().toISOString() }
    case 'SET_ID':
      return { ...state, id: action.payload as string, updatedAt: new Date().toISOString() }
    case 'NEW':
      return {
        ...initialState,
        createdAt: new Date().toISOString(),
        updatedAt: new Date().toISOString(),
      }
    case 'LOAD': {
      const def = action.payload as { id: string; name: string; nodes: WorkflowNode[]; edges: WorkflowEdge[] }
      return {
        ...state,
        id: def.id,
        name: def.name,
        nodes: def.nodes.map((n, idx) => ({
          ...n,
          status: n.status ?? 'pending',
          position: n.position ?? getNodePosition(idx),
        })),
        edges: def.edges,
        updatedAt: new Date().toISOString(),
      }
    }
    case 'RESET':
      return {
        ...state,
        nodes: state.nodes.map(n => ({ ...n, status: 'pending' })),
        updatedAt: new Date().toISOString(),
      }
    default:
      return state
  }
}

interface WorkflowContextValue {
  state: WorkflowState
  dispatch: React.Dispatch<WorkflowAction>
  addNode: (type: AgentType, agentId?: string, agentName?: string, afterId?: string) => void
  updateNode: (id: string, updates: Partial<WorkflowNode>) => void
  updateNodeStatus: (id: string, status: NodeStatus) => void
  setNodeStatuses: (statuses: Record<string, NodeStatus>) => void
  removeNode: (id: string) => void
  updateEdge: (id: string, updates: Partial<WorkflowEdge>) => void
  removeEdge: (id: string) => void
  setName: (name: string) => void
  setId: (id: string) => void
  newWorkflow: () => void
  loadWorkflow: (id: string, name: string, nodes: WorkflowNode[], edges: WorkflowEdge[]) => void
  executeWorkflow: () => void
  resetWorkflow: () => void
}

const WorkflowContext = createContext<WorkflowContextValue | null>(null)

export function WorkflowProvider({ children }: { children: ReactNode }) {
  const [state, dispatch] = useReducer(workflowReducer, initialState)

  const addNode = (type: AgentType, agentId?: string, agentName?: string, afterId?: string) => {
    const title = agentName
      ? `${agentName}`
      : type.split('-').map(p => p.charAt(0).toUpperCase() + p.slice(1)).join(' ') + ' Node'
    const anchor = afterId ? state.nodes.find(n => n.id === afterId) : undefined
    const newNode: WorkflowNode = {
      id: `node-${Date.now()}-${Math.random().toString(36).slice(2, 11)}`,
      type,
      title,
      status: 'pending',
      createdAt: new Date().toISOString(),
      // Place next to the anchor so middle-inserted steps land visibly in the flow.
      position: anchor?.position ? { x: anchor.position.x + 120, y: anchor.position.y + 40 } : undefined,
      config: typeDefaultConfig(type, agentId),
    }
    dispatch({ type: 'ADD_NODE', payload: newNode })

    // messaging-in is a start node (root) - do not auto-connect
    if (type === 'messaging-in') return

    // Anchor selected: insert directly into the flow.
    if (anchor) {
      // Conditional routes its branches via config (trueTargetId/falseTargetId), not edges —
      // the step must be assigned as then/else target in the node editor. Place it next to the
      // conditional without wiring.
      if (anchor.type === 'conditional') return

      // Middle insert: wire c between anchor a and all of a's real successors (a-b -> a-c-b).
      const inlineEdge: WorkflowEdge = {
        id: `edge-${Date.now()}-${Math.random().toString(36).slice(2, 11)}`,
        source: anchor.id,
        target: newNode.id,
        input: { sourceNodeId: anchor.id, format: 'text' },
      }
      dispatch({ type: 'ADD_EDGE', payload: inlineEdge })
      for (const e of state.edges.filter(ed => ed.source === anchor.id)) {
        dispatch({
          type: 'UPDATE_EDGE',
          payload: {
            id: e.id,
            updates: {
              source: newNode.id,
              input: e.input?.sourceNodeId === anchor.id
                ? { ...e.input, sourceNodeId: newNode.id }
                : e.input,
            },
          },
        })
      }
      return
    }

    if (state.nodes.length > 0) {
      const last = state.nodes[state.nodes.length - 1]
      const edge: WorkflowEdge = {
        id: `edge-${Date.now()}-${Math.random().toString(36).slice(2, 11)}`,
        source: last.id,
        target: newNode.id,
        input: { sourceNodeId: last.id, format: 'text' },
      }
      dispatch({ type: 'ADD_EDGE', payload: edge })
    }
  }

  const updateNode = (id: string, updates: Partial<WorkflowNode>) => {
    dispatch({ type: 'UPDATE_NODE', payload: { id, updates } })
  }

  const removeNode = (id: string) => {
    dispatch({ type: 'REMOVE_NODE', payload: id })
  }

  const updateNodeStatus = (id: string, status: NodeStatus) => {
    dispatch({ type: 'UPDATE_NODE', payload: { id, updates: { status } } })
  }

  const setNodeStatuses = (statuses: Record<string, NodeStatus>) => {
    dispatch({ type: 'SET_STATUS', payload: statuses })
  }

  const updateEdge = (id: string, updates: Partial<WorkflowEdge>) => {
    dispatch({ type: 'UPDATE_EDGE', payload: { id, updates } })
  }

  const removeEdge = (id: string) => {
    dispatch({ type: 'REMOVE_EDGE', payload: id })
  }

  const setName = (name: string) => {
    dispatch({ type: 'SET_NAME', payload: name })
  }

  const setId = (id: string) => {
    dispatch({ type: 'SET_ID', payload: id })
  }

  const newWorkflow = () => {
    dispatch({ type: 'NEW' })
  }

  const loadWorkflow = (id: string, name: string, nodes: WorkflowNode[], edges: WorkflowEdge[]) => {
    dispatch({ type: 'LOAD', payload: { id, name, nodes, edges } })
  }

  const executeWorkflow = () => {
    dispatch({ type: 'EXECUTE' })
  }

  const resetWorkflow = () => {
    dispatch({ type: 'RESET' })
  }

  return (
    <WorkflowContext.Provider value={{
      state,
      dispatch,
      addNode,
      updateNode,
      removeNode,
      updateNodeStatus,
      setNodeStatuses,
      updateEdge,
      removeEdge,
      setName,
      setId,
      newWorkflow,
      loadWorkflow,
      executeWorkflow,
      resetWorkflow,
    }}>
      {children}
    </WorkflowContext.Provider>
  )
}

export function useWorkflow() {
  const context = useContext(WorkflowContext)
  if (!context) {
    throw new Error('useWorkflow must be used within WorkflowProvider')
  }
  return context
}