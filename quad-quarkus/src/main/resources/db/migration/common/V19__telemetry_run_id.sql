-- TelemetryStore: run_id-Spalte fuer Run-Events (Stufe 08)
ALTER TABLE session_events ADD COLUMN run_id VARCHAR(64);
CREATE INDEX IF NOT EXISTS idx_sess_events_run ON session_events (run_id);
