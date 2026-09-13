-- Structured DB audit (Port 03). H2-kompatibel.
create table if not exists audit_events (
    id int auto_increment primary key,
    ts timestamp not null default CURRENT_TIMESTAMP,
    event_type varchar(64),
    user_id varchar(128),
    roles text,
    session_id varchar(64),
    tool_name varchar(128),
    tool_args text,
    result text,
    is_error boolean not null default 0,
    duration_ms bigint,
    correlation_id varchar(128),
    token_id varchar(128)
);
create index if not exists idx_audit_ts on audit_events (ts);
create index if not exists idx_audit_session on audit_events (session_id);
create index if not exists idx_audit_type on audit_events (event_type);
