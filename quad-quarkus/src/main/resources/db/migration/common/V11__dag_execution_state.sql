-- V11: Phase B — executionId column + over_all_state table

ALTER TABLE dag_runs ADD COLUMN execution_id TEXT;
CREATE INDEX IF NOT EXISTS idx_dag_runs_execution_id ON dag_runs(execution_id);

CREATE TABLE IF NOT EXISTS dag_over_all_state (
    execution_id TEXT PRIMARY KEY,
    dag_id TEXT,
    state_json TEXT NOT NULL,
    version INTEGER DEFAULT 1,
    updated_at TEXT NOT NULL
);
