-- Workflow-level prefix caching (Block A, §4/§6): opt-in marker + shared static prefix
-- content. Per-node chain config (usePrefix / prefixSource / clearPrefix) lives in the
-- nodes JSON column — no migration needed there.
ALTER TABLE workflows ADD COLUMN cache_prefix text;
ALTER TABLE workflows ADD COLUMN prefix_caching boolean NOT NULL DEFAULT 0;