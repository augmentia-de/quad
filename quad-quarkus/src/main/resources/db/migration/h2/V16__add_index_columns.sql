-- V16: Add an `index` column to every table (H2-kompatibel).
-- Purpose: a non-PK, monotonically increasing row counter for later use.
-- The tables keep their existing primary keys; `index` is NOT a PK.
-- H2 has native ROW_NUMBER() for queries — no triggers needed.

ALTER TABLE sessions          ADD COLUMN "index" INTEGER;
ALTER TABLE session_events    ADD COLUMN "index" INTEGER;
ALTER TABLE agents            ADD COLUMN "index" INTEGER;
ALTER TABLE workflows         ADD COLUMN "index" INTEGER;
ALTER TABLE memories          ADD COLUMN "index" INTEGER;
ALTER TABLE workspaces        ADD COLUMN "index" INTEGER;
ALTER TABLE project_bindings  ADD COLUMN "index" INTEGER;
ALTER TABLE audit_events      ADD COLUMN "index" INTEGER;
ALTER TABLE automation_tasks  ADD COLUMN "index" INTEGER;
ALTER TABLE automation_runs   ADD COLUMN "index" INTEGER;
ALTER TABLE skills            ADD COLUMN "index" INTEGER;
ALTER TABLE workflow_state    ADD COLUMN "index" INTEGER;
