# QUAD Studio UI Specification

> **Status note:** This document describes the target "QUAD Studio" vision. The
> current `quad-ui` implements a subset — see [Open Points](#16-open-points).

## 1. Product Vision & Goals

**QUAD Studio** is an enterprise frontend for observing and driving LLM agent
workflows. It pairs the power of three cooperating React agents with an
intuitive visual interface.

---

## 2. User Roles

| Role | Permissions | Description |
|---|---|---|
| **Admin** | Full access to all agents, workflows, security settings | Enterprise administration |
| **Agent Manager** | Create / edit agents, define workflows | Process experts |
| **Developer** | Debugging, tool configuration, code view | Technical users |
| **Reviewer** | HITL decisions, audit-trail review | Quality assurance |
| **End User** | Execute workflows, view results | Standard users |

---

## 3. Main Navigation & Layout

```
┌──────────────────────────────────────────────────────────────────┐
│ QUAD Studio                            [User] [Settings]         │
├──────────────────────────────────────────────────────────────────┤
│ Dashboard │ Agents │ Workflows │ Security │ Monitor │ Audit      │
└──────────────────────────────────────────────────────────────────┘
│                                                                  │
│                Main content area (React Switch)                  │
│                                                                  │
└──────────────────────────────────────────────────────────────────┘
│ Status bar: Session | Tenant | LLM | Cost: $0.03 | Tokens: 1.2K │
└──────────────────────────────────────────────────────────────────┘
```

---

## 4. Dashboard

### 4.1 Action Center
- **Current tasks:** running agent executions with progress bars
- **Quick actions:** "Start new workflow", "Create agent", "Security scan"

### 4.2 LLM Monitoring
- **Real-time token consumption:** prompt vs. completion
- **Cost visualization:** chart with budget alerts
- **Response time:** historical averages

### 4.3 Agent Overview
- **Three React agent statuses:**
  - ResearchAgent: active (searching for information…)
  - CodeAgent: waiting (on ReviewAgent)
  - ReviewAgent: reviewing current input
- **Workflow visual:** arrows with status indicators

---

## 5. Agent Management

### 5.1 Agent Editor
- **General settings:**
  - Name, description, category
  - LLM model (dropdown: gpt-4o, gpt-4o-mini, …)
  - Temperature, max tokens, top-P
- **Tool configuration:**
  - Available tools (checkboxes): readFile, writeFile, webSearch, …
  - Custom tool integration (API endpoint, OpenAPI spec)
  - Tool group assignments ("read-only-tools", "write-tools")
- **Guardrails:**
  - Input guardrails (list): PII detection, language filter, forbidden keywords
  - Output guardrails (list): toxic content, fact check, schema validation

---

## 6. Workflow Composer (Drag & Drop)

### 6.1 Canvas Design
```
[Start] → [ResearchAgent] → [ReviewAgent] → [CodeAgent] → [ReviewAgent] → [End]
               │                    │                     │
               ↓                    ↓                     ↓
         [Tool: webSearch]   [Tool: analyze]      [Tool: generateCode]
```

### 6.2 Agent Nodes
- **Default agent:** rectangle with name, status, icon
- **Click to expand:**
  - open configuration (same as agent editor above)
  - show tool links
  - guardrail status

### 6.3 Connection Arrows
- **Types:**
  - `→` (default data flow)
  - `⇢` (parallel execution)
  - `⟲` (feedback loop)

---

## 7. Security Center

### 7.1 Guardrail Overview
- **Status tiles:**
  - PII protection: active (blocked: 3 emails)
  - Language filter: active
  - Rate limit: 85% utilized
- **Live log:**
  - blocked requests with reason
  - neutralized content

### 7.2 HITL Management
- **Pending approvals:**

  | ID | Agent | Tool | Request | Since | Actions |
  |----|-------|------|---------|-------|---------|
  | 001 | CodeAgent | executeBash | `rm -rf /` | 2 min | Approve / Reject |

### 7.3 Audit Trail
- **Filter:** date range, agent, event type
- **Export:** JSON, CSV, PDF

---

## 8. Monitoring & Observability

### 8.1 Real-time Monitoring
- **Chart:** LLM token consumption over time
- **Metrics:** agent execution time, error rate, average response time

### 8.2 Error Analysis
- **Statistics:**
  - failed tool calls (5%)
  - timeout rate (2%)
  - guardrail triggers (1%)
- **Detailed export:** error logs per failure

---

## 9. UI Components

### 9.1 AgentCard
```tsx
<AgentCard
  name="ResearchAgent"
  status="active"
  tool="webSearch"
  cost={0.01}
  latency={1200}
  onCancel={handleCancel}
>
  <AgentHeader name={name} status={status} />
  <AgentTools tools={tools} />
  <AgentMetrics cost={cost} latency={latency} />
</AgentCard>
```

### 9.2 WorkflowCanvas
```tsx
<WorkflowCanvas
  agents={agents}
  connections={connections}
  onNodeSelect={handleSelect}
  onConnect={handleConnect}
>
  <AgentNode id="research" type="research" position="200,100" />
  <AgentNode id="review" type="review" position="400,100" />
  <AgentNode id="code" type="code" position="600,100" />
  <Connection from="research" to="review" />
  <Connection from="review" to="code" />
</WorkflowCanvas>
```

---

## 10. Responsive Design

### 10.1 Desktop (1920×1080)
- Full three-column layout
- Real-time charts

### 10.2 Tablet
- Compact cards instead of tables
- role-based UI adaptation

### 10.3 Mobile
- list-based navigation
- key metrics displayed prominently

---

## 11. Technical Specifications

### 11.1 Current stack (quad-ui)
- **React 19.x** with TypeScript 6
- **React Router v6** for navigation
- **@xyflow/react v12** for workflow canvas / graph rendering
- **mermaid** for diagram rendering
- **Vite 8** build toolchain
- **vitest 4** + React Testing Library for tests
- **oxlint** for linting

### 11.2 State Management
- **React Context** for global agent instances
- (No dedicated state library in use yet)

### 11.3 Styling
- Vanilla CSS (no Tailwind / UI library yet)
- Dark / light theme: system preference (planned)

### 11.4 Future additions (target vision)
- **TanStack Query** for data-fetching & caching
- **Recharts** for monitoring charts
- **Zustand** or Redux Toolkit for lightweight state management
- **Tailwind CSS** or Material UI for design system

---

## 12. API Binding

### 12.1 REST Endpoints (Quarkus)
```
# Agent management
GET    /api/ui/agents
GET    /api/ui/agents/{id}
POST   /api/ui/agents
PUT    /api/ui/agents/{id}
DELETE /api/ui/agents/{id}

# Workflow management & execution
GET    /api/ui/workflows
GET    /api/ui/workflows/{id}
GET    /api/ui/workflows/{id}/runs
POST   /api/ui/workflows
POST   /api/ui/workflows/{id}/execute

# Interactive chat / dynamic workflows
POST   /api/ui/dynamic/chat/start       {"task":"...", "name":"..."}
POST   /api/ui/dynamic/chat             {"sessionId":"...", "request":"..."}
GET    /api/ui/dynamic/chat/sessions
POST   /api/ui/dynamic/run

# HITL approvals
GET    /api/ui/hitl/approvals
POST   /api/ui/hitl/approvals/{id}/approve
POST   /api/ui/hitl/approvals/{id}/reject

# Skills, guardrails, MCP
GET    /api/ui/skills
GET    /api/ui/guardrails
GET    /api/ui/mcp/status

# Observability
GET    /api/ui/metrics
GET    /api/ui/metrics/errors
GET    /api/ui/model/tiers
GET    /api/ui/sessions
GET    /api/ui/journal

# GDPR
POST   /api/ui/gdpr/export/{sessionId}
DELETE /api/ui/gdpr/export/{sessionId}
```

---

## 13. Security

- **Authentication:** OAuth2 / OIDC (Keycloak)
- **Authorization:** role-based access control (`ToolGuard`, `PermissionGuardHook`)
- **CSRF protection:** token-based
- **XSS prevention:** automatic escaping
- **Rate limiting:** API-side

---

## 14. Deployment

### 14.1 Development Environment
```bash
npm install
npm run dev      # Vite dev server
npm run build    # Production build
npm run test     # Vitest
```

### 14.2 Production Deployment
- **Docker:** multi-stage build (`quad-ui/docker/frontend.Dockerfile`)
- **Kubernetes / Cloud Run:** via `deploy/06-frontend-react.yml` and Helm chart

---

## 15. Appendix A: Color Palette & Design System

### Primary Colors
- **Primary:** #2563EB (blue)
- **Secondary:** #10B981 (green)
- **Warning:** #F59E0B (yellow)
- **Error:** #EF4444 (red)
- **Background:** #F9FAFB (light grey)

### Status Indicators
- **Active:** #10B981 (green)
- **Waiting:** #F59E0B (yellow)
- **Inactive:** #6B7280 (grey)
- **Error:** #EF4444 (red)

---

## 16. Open Points

The current `quad-ui` implements a subset of this vision:

1. **Implemented:** agent list / detail views, chat UI, workflow canvas (`@xyflow/react`), mermaid diagram rendering, skill / guardrail lists, basic error states.
2. **Not yet implemented:** dashboard with real-time metrics, security center (PII / guardrail live logs), full HITL approval UI, audit-trail export, dark/light theme toggle.
3. **Missing libraries:** TanStack Query, Recharts, Zustand, Tailwind — listed as target dependencies, not yet in `package.json`.
4. **JSX → TSX migration:** code examples above show `.jsx`; the project uses `.tsx` exclusively.
5. **API paths corrected:** the old spec referenced non-existent paths (`/api/agents`, `/api/security/…`); the table above reflects the actual `quad-quarkus` surface.
6. **Three React agent scenario** (Appendix C) describes a design exercise; actual agent orchestration is server-side through the workflow engine.
7. **Testing strategy:** the project uses **vitest** + React Testing Library (not Jest / Cypress); coverage targets should be defined in `vite.config.ts`.