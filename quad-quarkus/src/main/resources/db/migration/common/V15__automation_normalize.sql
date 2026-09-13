-- V15: Automation-Normalisierung.
-- Automatisierte Speicherung auf das in AutomationStore.java erwartete
-- (normalisierte) Schema: JSON-BLOB in data + indexierte Laufspalten.
-- V8 legte denormalisierte Spalten an (title, schedule_kind, cron, ...), die vom
-- Store nie beschrieben wurden -> Tabellen waren leer und sind inhaltslos.

-- a) automation_tasks -> (id, enabled, next_run, data)
DROP TABLE IF EXISTS automation_tasks;
CREATE TABLE IF NOT EXISTS automation_tasks (
    id        varchar(64) primary key,
    enabled   integer     not null default 1,
    next_run  timestamp,
    data      text        not null
);
CREATE INDEX IF NOT EXISTS idx_automation_tasks_next ON automation_tasks (enabled, next_run);

-- b) automation_runs -> (run_id, task_id, started_at, data)
DROP TABLE IF EXISTS automation_runs;
CREATE TABLE IF NOT EXISTS automation_runs (
    run_id     varchar(64) primary key,
    task_id    varchar(64) not null,
    started_at timestamp   not null default CURRENT_TIMESTAMP,
    data       text        not null
);
CREATE INDEX IF NOT EXISTS idx_automation_runs_task ON automation_runs (task_id, started_at);
