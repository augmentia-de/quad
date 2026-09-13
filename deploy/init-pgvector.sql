-- init-pgvector.sql
-- Is run on first start der PostgreSQL automatically.
-- Creates the pgvector-extension for vector search.

CREATE EXTENSION IF NOT EXISTS vector;

-- Schema for quad-Anwendung (optional, app creates via Flyway/Liquibase)
-- CREATE SCHEMA IF NOT EXISTS quad;

SELECT 'pgvector ' || extversion || ' installed' AS version
FROM pg_extension WHERE extname = 'vector';
