-- Automation / scheduled tasks (Port 04). SQLite-kompatibel wie V1-V4.
create table if not exists automation_tasks (
    id varchar(64) primary key,
    title varchar(256) not null,
    instructions text not null,
    schedule_kind varchar(16) not null,
    cron varchar(128),
    fire_at timestamp,
    timezone varchar(64) default 'local',
    enabled boolean not null default 1,
    agent varchar(64),
    model varchar(128),
    workspace varchar(512),
    permissions text,
    run_count int not null default 0,
    last_run_at timestamp,
    last_status varchar(16),
    seen_runs_at timestamp,
    created_at timestamp not null default CURRENT_TIMESTAMP,
    updated_at timestamp not null default CURRENT_TIMESTAMP
);
create table if not exists automation_runs (
    id varchar(64) primary key,
    task_id varchar(64) not null,
    trigger varchar(16) not null,
    status varchar(16) not null,
    result_text text,
    error text,
    session_id varchar(64),
    started_at timestamp not null default CURRENT_TIMESTAMP,
    finished_at timestamp
);
create index if not exists idx_automation_runs_task on automation_runs (task_id, started_at);
