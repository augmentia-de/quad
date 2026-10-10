-- V25 (PostgreSQL): canonical v2 workflow documents.
--
-- The typed v2 definition is stored as JSONB, which enforces syntactic validity at the
-- database level in addition to the application-side JSON-Schema validation.

CREATE TABLE IF NOT EXISTS workflow_documents (
    id varchar(64) PRIMARY KEY,
    name varchar(256) NOT NULL,
    definition_version varchar(32) NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'draft',
    schema_version integer NOT NULL DEFAULT 2,
    content_hash varchar(128) NOT NULL,
    document jsonb NOT NULL,
    created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_workflow_documents_name ON workflow_documents (name);
CREATE INDEX IF NOT EXISTS idx_workflow_documents_status ON workflow_documents (status);
