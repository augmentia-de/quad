-- DB-based agent-skill assignments (Port 06).
-- Skills are always "active" (available). Only skills assigned to an agent
-- via this table are actually used by that agent (analog to agent_tools).
-- SQLite-kompatibel wie V1-V4.
create table if not exists agent_skills (
    agent_id varchar(64) not null,
    skill_name varchar(256) not null,
    created_at timestamp not null default CURRENT_TIMESTAMP,
    primary key (agent_id, skill_name)
);

create index if not exists idx_agent_skills_agent on agent_skills (agent_id);
create index if not exists idx_agent_skills_skill on agent_skills (skill_name);