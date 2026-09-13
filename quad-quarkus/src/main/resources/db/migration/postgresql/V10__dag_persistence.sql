-- V10: DAG persistence (Phase A) — PostgreSQL-kompatibel.

CREATE TABLE IF NOT EXISTS dag_definitions_db (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    description TEXT,
    version TEXT DEFAULT '1.0.0',
    config_json TEXT,
    tags TEXT DEFAULT '[]',
    created_at TEXT,
    updated_at TEXT
);

CREATE TABLE IF NOT EXISTS dag_runs (
    run_id TEXT PRIMARY KEY,
    dag_id TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'running',
    started_at TEXT,
    finished_at TEXT,
    duration_ms INTEGER DEFAULT 0,
    inputs_json TEXT,
    node_states_json TEXT,
    version INTEGER DEFAULT 1,
    CONSTRAINT fk_dag_runs_definition FOREIGN KEY (dag_id) REFERENCES dag_definitions_db(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_dag_runs_dag_id ON dag_runs(dag_id);
CREATE INDEX IF NOT EXISTS idx_dag_runs_status ON dag_runs(status);
CREATE INDEX IF NOT EXISTS idx_dag_runs_started_at ON dag_runs(started_at DESC);

CREATE TABLE IF NOT EXISTS dag_run_events (
    event_id serial PRIMARY KEY,
    run_id TEXT NOT NULL,
    node_id TEXT NOT NULL,
    event_type TEXT NOT NULL,
    status TEXT NOT NULL,
    attempt INTEGER DEFAULT 1,
    started_at TEXT,
    completed_at TEXT,
    output_json TEXT,
    error TEXT,
    otel_span_id TEXT,
    reason TEXT,
    CONSTRAINT fk_dag_run_events_run FOREIGN KEY (run_id) REFERENCES dag_runs(run_id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_dag_run_events_run_id ON dag_run_events(run_id);
CREATE INDEX IF NOT EXISTS idx_dag_run_events_node_id ON dag_run_events(node_id, run_id);
CREATE INDEX IF NOT EXISTS idx_dag_run_events_event_type ON dag_run_events(event_type, run_id);
