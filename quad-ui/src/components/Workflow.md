# Workflow Builder Guide

## Overview
Workflows in QUAD Studio are sequences of agent nodes that can be executed in parallel or sequence. Each node represents an agent type (Research, Code, Review) that performs specific tasks.

## How to Build a Workflow

### Step 1: Open Workflow Composer
Navigate to the "🔄 Workflows" tab to open the Workflow Composer interface.

### Step 2: Add Agent Nodes
Click on agent buttons in the toolbar to add nodes:

- **🔍 Research Agent** - Web search, document analysis, information gathering
- **💻 Code Agent** - Code generation, execution, testing
- **🔍 Review Agent** - Code review, security analysis, quality checks

Example workflow:
```
[Research Agent] → [Code Agent] → [Review Agent]
```

### Step 3: Configure Nodes (Future Enhancement)
When node properties are available, you can:
- Set agent-specific configurations
- Configure input/output parameters
- Set execution order constraints

### Step 4: Execute Workflow
Click the **Execute** button to run the workflow. The system will:
1. Trigger all nodes with `status: 'pending'`
2. Update node status to `status: 'running'`
3. Execute tasks via the agent
4. Set final status (`'completed'` or `'failed'`)

### Step 5: Save or Reset
- **Reset** - Clear all nodes, start fresh
- **Save** (future) - Persist to backend for reuse

---

## Data Flow

### Client-Side (WorkflowContext)
```typescript
WorkflowState {
  id: string           // Unique identifier ("default" for local, UUID for saved)
  name: string         // Workflow name
  nodes: WorkflowNode[] // Agent nodes
  edges: WorkflowEdge[] // Connections between nodes
  createdAt: string    // ISO timestamp
  updatedAt: string    // ISO timestamp
}

WorkflowNode {
  id: string           // Unique node ID
  type: 'research' | 'code' | 'review'  // Agent type
  title: string        // Display title (e.g., "Research Node")
  status: 'pending' | 'running' | 'completed' | 'failed'
  createdAt: string    // ISO timestamp
}

WorkflowEdge {
  id: string           // Unique edge ID
  source: string       // Source node ID
  target: string       // Target node ID
}
```

### Backend API (UiAgentResource)
```java
POST /api/ui/execute
Request:  { task: string }
Response: {
  sessionId: string,
  success: boolean,
  result: string,
  toolCount: number,
  durationMs: number,
  costLimit: number,
  model: string
}

POST /api/ui/workflows
Request: {
  id?: string,
  name?: string,
  nodes: WorkflowNode[],
  edges: WorkflowEdge[]
}
Response: { id: string }
```

---

## Agent Types & Capabilities

### Research Agent
**Purpose:** Information gathering, analysis
**Default tools:** readFile, writeFile, webSearch, analyze

### Code Agent
**Purpose:** Code generation, execution
**Default tools:** readFile, writeFile, executeBash, analyze

### Review Agent
**Purpose:** Code review, security analysis
**Default tools:** readFile, analyze

---

## Real LLM Usage Configuration

### Environment Variables
The backend uses LangChain4j to connect to OpenAI-compatible APIs:

```bash
# Required for real LLM calls
OPENAI_API_KEY=sk-or-v1-...           # Your API key
OPENAI_BASE_URL=https://openrouter.ai/api/v1
OPENAI_MODEL=deepseek/deepseek-v4-flash

# Optional settings
LLM_TEMPERATURE=0.7
LLM_MAX_RETRIES=3
QUAD_DUMMY_MODE=false                  # Set to true for mock agent (default: false)
QUAD_COST_LIMIT=1.0
```

### How LLM Integration Works
1. `UiConfig` reads environment variables and determines `dummyMode`
2. When `dummyMode = false`, the backend uses `AgentBuilder.withLlmFromEnv()`
3. This creates a LangChain4j `OpenAiChatModel` with API key, base URL, and model
4. Agent executes real LLM calls via the specified endpoint

### Checking LLM Status
Access `GET /api/ui/status` to see:
```json
{
  "ready": true,
  "model": "deepseek/deepseek-v4-flash",
  "costLimit": 1.0,
  "baseUrl": "https://openrouter.ai/api/v1",
  "dummyMode": false
}
```

---

## Execution Flow

```
[UI: Execute Button]
        ↓
[WorkflowComposer.handleExecute()]
        ↓
[executeWorkflow() dispatched (status: pending → running)]
        ↓
[POST /api/ui/workflows  → createWorkflow()  → { id }]
        ↓
[POST /api/ui/workflows/{id}/execute → executeWorkflow(id)]
        ↓
[Backend: UiAgentResource.executeWorkflow() (topologische Sortierung)]
        ↓
[Pro Knoten: LLM-Aufruf, Output → Kontext für Nachfolger]
        ↓
[UI: Node status: running → completed/failed]
        ↓
[Debug tab opens with results]
```

> Die JSON-Schema-Kontrakte für diese Requests/Responses liegen in `quad-ui/schemas/`
> (`execute.schema.json`, `workflow.schema.json`, `agent.schema.json`, `monitor.schema.json`).

---

## API Integration

| Endpoint | Method | Purpose | Response |
|----------|--------|---------|----------|
| `/api/ui/execute` | POST | Execute single task | `UiResponse` |
| `/api/ui/workflows` | POST | Save workflow | `{ id }` |
| `/api/ui/workflows` | GET | List workflows | `Workflow[]` |
| `/api/ui/workflows/{id}` | GET | Get workflow | `Workflow` |
| `/api/ui/workflows/{id}` | DELETE | Delete workflow | 204 |

---

## Development Notes

### Current Implementation Gaps
1. **Edge connections** - Visual connection lines not rendered
2. **Node configuration** - No per-node settings dialog
3. **Save/Load UI** - WorkflowComposer erstellt + führt über das Backend aus; Laden/Speichern bestehender Workflows (GET /workflows) ist noch nicht eingebunden

### Future Enhancements
1. Add "Save Workflow" modal to WorkflowComposer
2. Add "Load" dropdown listing saved workflows
3. Implement sequential/parallel execution of nodes
4. Add node-specific configuration panels
5. Implement workflow versioning