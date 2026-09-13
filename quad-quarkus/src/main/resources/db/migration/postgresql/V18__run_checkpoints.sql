-- V18: StepSnapshotStore — Run checkpoints per step (resume-capable step snapshots).
-- PostgreSQL-compatible; ON CONFLICT DO NOTHING instead of INSERT OR IGNORE.
CREATE TABLE IF NOT EXISTS run_checkpoints (
    id                 serial primary key,
    run_id             varchar(64) not null,
    step_index         integer not null default 0,
    step_status        varchar(32) not null default 'running',
    environment_state  text,
    memory_summary     text,
    created_at         timestamp not null default CURRENT_TIMESTAMP,
    updated_at         timestamp not null default CURRENT_TIMESTAMP,
    UNIQUE (run_id, step_index)
);
CREATE INDEX IF NOT EXISTS idx_run_checkpoints_run ON run_checkpoints (run_id);

INSERT INTO run_checkpoints (run_id, step_index, step_status, environment_state, memory_summary, updated_at)
SELECT s.id,
       0,
       s.status,
       s.step_results,
       NULL,
       s.updated_at
FROM workflow_state s
ON CONFLICT DO NOTHING;
