-- DB-based skillstore (Port 06). SQLite-kompatibel wie V1-V4.
create table if not exists skills (
    id varchar(64) primary key,
    name varchar(256) not null unique,
    description text,
    instructions text,
    allowed_tools text,
    declared_tools text,
    metadata text,
    created_at timestamp not null default CURRENT_TIMESTAMP
);
