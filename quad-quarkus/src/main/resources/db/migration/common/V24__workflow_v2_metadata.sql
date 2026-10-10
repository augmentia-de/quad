-- V24: Workflow v2 metadata (Phase 0, non-breaking).
--
-- Adds definition metadata to `workflows` and run-pinning columns to `workflow_state`.
-- Column additions with defaults are portable across SQLite / PostgreSQL / H2 and keep the
-- current behaviour: pre-v2 rows read as schema_version=1 and status='published'.
--
-- The canonical v2 document itself lives in `workflow_documents` (see the dialect-specific
-- V25). The JSON integrity CHECK for SQLite is defined on that new table, because SQLite
-- cannot add a CHECK constraint to an existing table via ALTER TABLE.

ALTER TABLE workflows ADD COLUMN schema_version integer NOT NULL DEFAULT 1;
ALTER TABLE workflows ADD COLUMN definition_version varchar(32);
ALTER TABLE workflows ADD COLUMN status varchar(16) NOT NULL DEFAULT 'published';
ALTER TABLE workflows ADD COLUMN content_hash varchar(128);

ALTER TABLE workflow_state ADD COLUMN definition_version varchar(32);
ALTER TABLE workflow_state ADD COLUMN definition_hash varchar(128);
