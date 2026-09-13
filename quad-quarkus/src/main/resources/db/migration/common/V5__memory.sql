-- Long-term memory persistence (Port 01). SQLite-kompatibel wie V1-V4.
create table if not exists memories (
    id varchar(64) primary key,
    scope varchar(128) not null,
    content text,
    summary text,
    category varchar(32),
    sensitive boolean not null default 0,
    created_at timestamp not null default CURRENT_TIMESTAMP
);
create index if not exists idx_memories_scope on memories (scope);
