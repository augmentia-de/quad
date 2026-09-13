-- V17: RunState extended with kind + step_index (shared run model for agent & workflow)
-- Source: MAO_todo Stage 03 (Obs 1). Backward-compatible: old columns remain; new ones are nullable
-- or have defaults, so existing runs still load without the fields (MAO2 §3.1 rollback plan).
ALTER TABLE workflow_state ADD COLUMN kind varchar(32);
ALTER TABLE workflow_state ADD COLUMN step_index integer;

-- Existing runs: set kind/step_index to sensible defaults (instead of NULL),
-- so that after upgrade "WORKFLOW" with step 0 applies consistently.
UPDATE workflow_state SET kind = 'WORKFLOW' WHERE kind IS NULL;
UPDATE workflow_state SET step_index = 0 WHERE step_index IS NULL;