# QUAD UI – API-Definitionen

Dieser Ordner enthält die **JSON-Schema-Kontrakte** für alle Daten, die zwischen QUAD UI
und dem QUAD-Backend ausgetauscht werden. Die TypeScript-Typen in
`src/services/types.ts` sind die 1:1-Übersetzung dieser Schemas.

## Schemas

| Datei | Inhalt | Backend-Endpoints |
|-------|--------|-------------------|
| `agent.schema.json` | Agent-Definition (Request/Response) | `/api/ui/agents` (GET/POST/PUT/DELETE) |
| `workflow.schema.json` | Workflow-Definition inkl. Nodes/Edges | `/api/ui/workflows` (GET/POST) |
| `execute.schema.json` | Task- und Workflow-Ausführung | `/api/ui/execute`, `/api/ui/workflows/{id}/execute` |
| `monitor.schema.json` | Metrics, Audit, HITL | `/api/ui/metrics`, `/audit/logs`, `/hitl/approvals` |

## Validierung (Beispiel)

```bash
npx ajv-cli validate -s schemas/workflow.schema.json -d beispiele/workflow.json
```

## Workflow-Datenfluss

```
[Node n1: research] ──edge e1──▶ [Node n2: review]
        │                                  ▲
        └── Output wird als Kontext ───────┘
             ("Input from upstream node")
```

- Die Ausführungsreihenfolge wird durch die **gerichteteten Kanten** bestimmt
  (topologische Sortierung im Backend).
- Jeder Knoten liefert einen LLM-Output (`output` in `WorkflowNodeResult`).
- Outputs vorgelagerter Knoten werden dem Prompt nachfolgender Knoten als
  `Input from upstream node: <output>` hinzugefügt.
- Knoten ohne Vorgänger werden zuerst ausgeführt.

## Beispiel: Mini-Workflow

```json
{
  "name": "Mini Research Workflow",
  "nodes": [
    { "id": "n1", "type": "research", "title": "What is 2+2?", "status": "pending" },
    { "id": "n2", "type": "review", "title": "Verify the previous answer", "status": "pending" }
  ],
  "edges": [
    { "id": "e1", "source": "n1", "target": "n2" }
  ]
}
```

POST auf `/api/ui/workflows` → `{ "id": "workflow-..." }` → anschließend
`POST /api/ui/workflows/{id}/execute`.
