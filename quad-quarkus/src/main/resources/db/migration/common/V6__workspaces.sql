-- Workspace registry + per-session project bindings (Port 02). SQLite-kompatibel.
create table if not exists workspaces (
    path varchar(512) primary key,
    name varchar(256),
    trusted boolean not null default 0,
    command_trust text,
    git_branch varchar(128),
    last_accessed_at timestamp not null default CURRENT_TIMESTAMP,
    created_at timestamp not null default CURRENT_TIMESTAMP
);
create table if not exists project_bindings (
    session_id varchar(64) not null,
    kind varchar(32) not null,
    name varchar(256),
    primary key (session_id, kind)
);
