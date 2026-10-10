# Quickstart: Agenten anlegen in 5 Schritten

Fünf aufeinander aufbauende Beispiele zeigen, wie man einen Agenten anlegt — vom
Einzeiler bis zum zusammengesetzten System. Sie liegen in
`de.augmentia.quad.examples.quickstart` und nutzen ausschließlich die aktuelle API
(`Agents`, `AgentBuilder`, `AgentConfig`).

| Schritt | Datei | Neu |
|---|---|---|
| 1 | `Step1_MinimalAgent` | Einstieg: `Agents.simple().run(...)` — keine eigene Klasse, keine Config |
| 2 | `Step2_AgentWithTool` | Eigene `Agent`-Klasse + `@Tool`; `AgentBuilder.create(...)` |
| 3 | `Step3_ConfiguredAgent` | `AgentConfig` (Prompt, Sampling, Limits) + Tools als Objekte, ohne Subclassing |
| 4 | `Step4_StructuredOutput` | Typsichere Ausgabe über einen `record` + `executeStructured(...)` |
| 5 | `Step5_SubAgents` | Komposition: Orchestrator delegiert per `withSubAgent(...)` |

Jede Klasse hat eine `main`-Methode und einen Kopfkommentar „Neu gegenüber Schritt N-1".
Alle benötigen `OPENAI_API_KEY` (optional `OPENAI_MODEL`) aus der Umgebung.

## Ausführen

Per IDE: die jeweilige `main`-Methode starten.

Per Maven (Beispiel Schritt 1):

```bash
mvn -pl quad-examples -am compile
mvn -pl quad-examples exec:java \
  -Dexec.mainClass=de.augmentia.quad.examples.quickstart.Step1_MinimalAgent
```

## Abgrenzung

`quad-examples` enthält darüber hinaus eine gewachsene Sammlung zu einzelnen Features
(`features/`), Workflows/Orchestrierung (`orchestrator/`, `TwoStageWorkflow*`),
Capability/MCP (`Capability*`) und Diagnose-Probes (`PrefixProbeExample`). Zum
Kennlernen der Agent-Anlage sind die `quickstart/Step1..5` gedacht; die übrigen
Beispiele bleiben als Referenz für Spezialthemen bestehen.
