-- V16: Add an autoincrement `index` column to every table.
-- Purpose: a non-PK, monotonically increasing row counter for later use.
-- The tables keep their existing primary keys; `index` is NOT a PK.
--
-- Notes:
--  * `index` is a reserved word in SQLite, so the column is quoted as "index".
--  * SQLite only auto-increments the rowid (INTEGER PRIMARY KEY) column, so a
--    plain non-PK "index" is filled by an AFTER INSERT trigger that assigns the
--    next sequence value (MAX("index")+1) to the freshly inserted row.
--  * Existing rows (inserted before this migration) keep "index" = NULL.

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

CREATE TRIGGER IF NOT EXISTS trg_sessions_index
AFTER INSERT ON sessions
BEGIN
    UPDATE sessions SET "index" = (SELECT COALESCE(MAX("index"), 0) + 1 FROM sessions)
    WHERE rowid = NEW.rowid AND "index" IS NULL;
END;

CREATE TRIGGER IF NOT EXISTS trg_session_events_index
AFTER INSERT ON session_events
BEGIN
    UPDATE session_events SET "index" = (SELECT COALESCE(MAX("index"), 0) + 1 FROM session_events)
    WHERE rowid = NEW.rowid AND "index" IS NULL;
END;

CREATE TRIGGER IF NOT EXISTS trg_agents_index
AFTER INSERT ON agents
BEGIN
    UPDATE agents SET "index" = (SELECT COALESCE(MAX("index"), 0) + 1 FROM agents)
    WHERE rowid = NEW.rowid AND "index" IS NULL;
END;

CREATE TRIGGER IF NOT EXISTS trg_workflows_index
AFTER INSERT ON workflows
BEGIN
    UPDATE workflows SET "index" = (SELECT COALESCE(MAX("index"), 0) + 1 FROM workflows)
    WHERE rowid = NEW.rowid AND "index" IS NULL;
END;

CREATE TRIGGER IF NOT EXISTS trg_memories_index
AFTER INSERT ON memories
BEGIN
    UPDATE memories SET "index" = (SELECT COALESCE(MAX("index"), 0) + 1 FROM memories)
    WHERE rowid = NEW.rowid AND "index" IS NULL;
END;

CREATE TRIGGER IF NOT EXISTS trg_workspaces_index
AFTER INSERT ON workspaces
BEGIN
    UPDATE workspaces SET "index" = (SELECT COALESCE(MAX("index"), 0) + 1 FROM workspaces)
    WHERE rowid = NEW.rowid AND "index" IS NULL;
END;

CREATE TRIGGER IF NOT EXISTS trg_project_bindings_index
AFTER INSERT ON project_bindings
BEGIN
    UPDATE project_bindings SET "index" = (SELECT COALESCE(MAX("index"), 0) + 1 FROM project_bindings)
    WHERE rowid = NEW.rowid AND "index" IS NULL;
END;

CREATE TRIGGER IF NOT EXISTS trg_audit_events_index
AFTER INSERT ON audit_events
BEGIN
    UPDATE audit_events SET "index" = (SELECT COALESCE(MAX("index"), 0) + 1 FROM audit_events)
    WHERE rowid = NEW.rowid AND "index" IS NULL;
END;

CREATE TRIGGER IF NOT EXISTS trg_automation_tasks_index
AFTER INSERT ON automation_tasks
BEGIN
    UPDATE automation_tasks SET "index" = (SELECT COALESCE(MAX("index"), 0) + 1 FROM automation_tasks)
    WHERE rowid = NEW.rowid AND "index" IS NULL;
END;

CREATE TRIGGER IF NOT EXISTS trg_automation_runs_index
AFTER INSERT ON automation_runs
BEGIN
    UPDATE automation_runs SET "index" = (SELECT COALESCE(MAX("index"), 0) + 1 FROM automation_runs)
    WHERE rowid = NEW.rowid AND "index" IS NULL;
END;

CREATE TRIGGER IF NOT EXISTS trg_skills_index
AFTER INSERT ON skills
BEGIN
    UPDATE skills SET "index" = (SELECT COALESCE(MAX("index"), 0) + 1 FROM skills)
    WHERE rowid = NEW.rowid AND "index" IS NULL;
END;

CREATE TRIGGER IF NOT EXISTS trg_workflow_state_index
AFTER INSERT ON workflow_state
BEGIN
    UPDATE workflow_state SET "index" = (SELECT COALESCE(MAX("index"), 0) + 1 FROM workflow_state)
    WHERE rowid = NEW.rowid AND "index" IS NULL;
END;