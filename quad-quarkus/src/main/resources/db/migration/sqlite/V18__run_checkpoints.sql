-- V18: StepSnapshotStore — Run checkpoints per step (resume-capable step snapshots).
-- Source: MAO_todo Stage 05. Exactly one snapshot per (run_id, step_index); REINSERT (INSERT OR REPLACE).
-- environment_state: JSON string of the environment (AgentSessionState/Outputs) incl. optional memory_summary.
-- SQLite-compatible; size limit of 500 kB is enforced in the Store (limit: 512000 bytes).
CREATE TABLE IF NOT EXISTS run_checkpoints (
    id                 integer primary key autoincrement,
    run_id             varchar(64) not null,
    step_index         integer not null default 0,
    step_status        varchar(32) not null default 'running',
    environment_state  text,          -- JSON of the environment/step snapshot
    memory_summary     text,
    created_at         timestamp not null default CURRENT_TIMESTAMP,
    updated_at         timestamp not null default CURRENT_TIMESTAMP,
    UNIQUE (run_id, step_index)
);
CREATE INDEX IF NOT EXISTS idx_run_checkpoints_run ON run_checkpoints (run_id);

-- Restore existing runs (without checkpoints): offload from workflow_state.step_results,
-- so the StepSnapshotStore can be initialized without data loss.
INSERT OR IGNORE INTO run_checkpoints (run_id, step_index, step_status, environment_state, memory_summary, updated_at)
SELECT s.id,
       0,
       s.status,
       s.step_results,
       NULL,
       s.updated_at
FROM workflow_state s;