-- V13: visibility flag for agents
-- inactive = internally generated workflow step agents (persisted immediately on generation,
-- but do not appear in the classic agent list).
ALTER TABLE agents ADD COLUMN active boolean not null default 1;