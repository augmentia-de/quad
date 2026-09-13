-- Persistence for agent definitions, workflows and workflow runs (SQLite-compatible)

create table if not exists agents (
    id varchar(64) primary key,
    name varchar(256) not null,
    description text,
    category varchar(64),
    model varchar(128),
    temperature double not null default 0.7,
    max_tokens int not null default 4096,
    top_p double not null default 1.0,
    tools text,
    guardrails_input text,
    guardrails_output text,
    agent_type varchar(32) not null default 'ua',
    created_at timestamp not null default CURRENT_TIMESTAMP,
    updated_at timestamp not null default CURRENT_TIMESTAMP
);

create table if not exists workflows (
    id varchar(64) primary key,
    name varchar(256) not null,
    nodes text not null,
    edges text not null,
    created_at timestamp not null default CURRENT_TIMESTAMP,
    updated_at timestamp not null default CURRENT_TIMESTAMP
);

create table if not exists runs (
    id varchar(64) primary key,
    workflow_id varchar(64) not null,
    status varchar(32) not null default 'running',
    started_at timestamp not null default CURRENT_TIMESTAMP,
    finished_at timestamp,
    duration_ms bigint not null default 0,
    node_results text,
    initial_data text
);

create index if not exists idx_runs_workflow on runs (workflow_id);
