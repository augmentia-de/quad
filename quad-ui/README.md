# QUAD Studio (quad-ui)

React single-page app for the QUAD agent platform. It drives the **Quarkus backend**
(primary data plane) and, through the **Unified API Gateway**, the **Kafka
orchestrator** — so a workflow prototyped here can be converted and executed on the
event-driven platform.

## Stack

- React 19 + TypeScript 6, Vite 8
- `@xyflow/react` (workflow canvas), `mermaid` (diagrams), `98.css` (retro shell)
- Vitest + Testing Library, Oxlint

## Scripts

```bash
npm run dev        # dev server on :5173 (proxies the two backends)
npm run build      # production build into dist/
npm run preview    # serve the built app
npm run test       # vitest run
npm run test:dev   # vitest watch
npm run lint       # oxlint
```

## Backends & dev proxy

Two API planes are proxied in `vite.config.ts`:

| Path | Default target | Backend |
|---|---|---|
| `/api/v1/**` | `http://localhost:8080` | Kafka orchestrator (Unified Gateway) |
| `/api/**` | `http://localhost:8086` | Quarkus (`/api/ui/*`) |

Both targets are overridable via env (no file edit needed):

```bash
VITE_ORCH_PROXY_TARGET=http://localhost:19500 npm run dev   # compose stack host port
VITE_QUAD_PROXY_TARGET=http://localhost:8086  npm run dev
```

`VITE_KAFKA_API_BASE` overrides the gateway path prefix (default `/api/v1`).

## Structure

```
src/
├── App.tsx                 # Win98 shell: side navigation + 16 tabs
├── contexts/WorkflowContext.tsx
├── services/
│   ├── api.ts              # Quarkus plane (/api/ui/*)
│   ├── kafkaApi.ts         # Unified Gateway (/api/v1): convert/run/HITL/metrics
│   ├── export.ts, types.ts, jsonSchema.ts
└── components/             # one component per tab, e.g.
                            # WorkflowComposer, DynamicWorkflow, AgentManager, Runs
```

## quad → Kafka in the UI

- **Dynamic** tab (`DynamicWorkflow`): LLM decomposes a task into a workflow
  (`/api/ui/dynamic/generate`), can be refined in a chat, run in Quarkus, and saved.
- **Workflows** tab (`WorkflowComposer`): the **Execution backend** selector switches
  `quarkus | kafka`. With `kafka` selected, execution calls
  `/api/v1/unified/workflows/from-quad` (convert + persist + start) and then polls the
  orchestrator. The adjacent dropdown lists workflow definitions already persisted in the
  orchestrator.

## Tests

```bash
npm run test
```

`AgentEditor`, `AgentPlayground`, `Dashboard`, `Runs`, `WorkflowComposer`, `JsonSchemaPopup`
and the JSON-schema service are covered.
