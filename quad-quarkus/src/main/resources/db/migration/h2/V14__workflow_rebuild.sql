-- V14: Workflow-Rebuild (semantisches Rename + workflow_state + DAG-Entfernung)
-- H2-kompatibel. WHERE NOT EXISTS statt INSERT OR IGNORE / ON CONFLICT DO NOTHING.

-- a) agents: json_input → json_output
ALTER TABLE agents RENAME COLUMN json_input TO json_output;
ALTER TABLE agents ADD COLUMN json_output_schema text;

-- b) neue Workflow-State-Tabelle
CREATE TABLE IF NOT EXISTS workflow_state (
    id             varchar(64) primary key,
    workflow_id    varchar(64) not null,
    status         varchar(32) not null default 'running',
    initial_data   text,
    step_results   text,
    executing_from varchar(64),
    restart_count  int not null default 0,
    started_at     timestamp not null default CURRENT_TIMESTAMP,
    finished_at    timestamp,
    updated_at     timestamp not null default CURRENT_TIMESTAMP,
    duration_ms    bigint not null default 0
);
CREATE INDEX IF NOT EXISTS idx_workflow_state_workflow ON workflow_state (workflow_id);
CREATE INDEX IF NOT EXISTS idx_workflow_state_status    ON workflow_state (status);

-- b1) Bestands-Runs 1:1 nach workflow_state migrieren
INSERT INTO workflow_state
    (id, workflow_id, status, initial_data, step_results, started_at, finished_at, duration_ms, updated_at)
SELECT id, workflow_id, status, initial_data, node_results, started_at, finished_at, duration_ms,
       COALESCE(finished_at, started_at)
FROM runs r
WHERE NOT EXISTS (SELECT 1 FROM workflow_state ws WHERE ws.id = r.id);
DROP TABLE IF EXISTS runs;

-- c) DAG-Tabellen entfernen
DROP TABLE IF EXISTS dag_node_overrides;
DROP TABLE IF EXISTS dag_over_all_state;
DROP TABLE IF EXISTS dag_run_events;
DROP TABLE IF EXISTS dag_runs;
DROP TABLE IF EXISTS dag_definitions_db;
