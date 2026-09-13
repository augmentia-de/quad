-- V12: Node overrides (wf3_enh §3.1)
-- SQLite-compatible.

CREATE TABLE IF NOT EXISTS dag_node_overrides (
    dag_id TEXT NOT NULL,
    node_id TEXT NOT NULL,
    prompt_template TEXT,
    config_overrides TEXT,
    reason TEXT,
    created_at TEXT,
    created_by TEXT,
    updated_at TEXT,
    PRIMARY KEY (dag_id, node_id),
    CONSTRAINT fk_dag_node_overrides_def FOREIGN KEY (dag_id)
        REFERENCES dag_definitions_db(id) ON DELETE CASCADE
);