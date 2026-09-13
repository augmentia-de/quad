-- V14: Workflow rebuild (semantic rename + workflow_state + DAG removal)
-- SQLite-compatible. See docs/Rebuild.md.

-- a) agents: json_input → json_output (semantics: agent produces structured JSON)
--    + JSON-Schema field that holds the schema when json_output is active
ALTER TABLE agents RENAME COLUMN json_input TO json_output;
ALTER TABLE agents ADD COLUMN json_output_schema text;

-- b) new workflow-state table: step results + restart/continue.
--    Replaces the runs table; current status is queryable from the DB at any time.
CREATE TABLE IF NOT EXISTS workflow_state (
    id             varchar(64) primary key,      -- = run/execution id
    workflow_id    varchar(64) not null,
    status         varchar(32) not null default 'running',  -- currently queryable status
    initial_data   text,
    step_results   text,                         -- JSON array [{nodeId,type,title,status,input,output}]
    executing_from varchar(64),                 -- node from which continue resumes (otherwise NULL)
    restart_count  int not null default 0,
    started_at     timestamp not null default CURRENT_TIMESTAMP,
    finished_at    timestamp,
    updated_at     timestamp not null default CURRENT_TIMESTAMP,
    duration_ms    bigint not null default 0
);
CREATE INDEX IF NOT EXISTS idx_workflow_state_workflow ON workflow_state (workflow_id);
CREATE INDEX IF NOT EXISTS idx_workflow_state_status    ON workflow_state (status);

-- b1) Migrate existing runs 1:1 into workflow_state, remove old runs table
INSERT OR IGNORE INTO workflow_state
    (id, workflow_id, status, initial_data, step_results, started_at, finished_at, duration_ms, updated_at)
SELECT id, workflow_id, status, initial_data, node_results, started_at, finished_at, duration_ms,
       COALESCE(finished_at, started_at)
FROM runs;
DROP TABLE IF EXISTS runs;

-- c) Remove DAG tables (V10-V12; system is replaced by the classic engine)
DROP TABLE IF EXISTS dag_node_overrides;
DROP TABLE IF EXISTS dag_over_all_state;
DROP TABLE IF EXISTS dag_run_events;
DROP TABLE IF EXISTS dag_runs;
DROP TABLE IF EXISTS dag_definitions_db;