-- Initial tables for session persistence (H2-compatible)
-- Used by SessionStore + SessionResource

create table if not exists sessions (
    id varchar(64) primary key,
    created_at timestamp not null default CURRENT_TIMESTAMP,
    last_seen timestamp not null default CURRENT_TIMESTAMP,
    task varchar(2048),
    result varchar(8192),
    memory_summary varchar(8192),
    state_json text
);

create table if not exists session_events (
    id int auto_increment primary key,
    session_id varchar(64) not null,
    event_type varchar(64),
    payload text,
    ts timestamp not null default CURRENT_TIMESTAMP
);

create index if not exists idx_sess_events on session_events (session_id, ts);