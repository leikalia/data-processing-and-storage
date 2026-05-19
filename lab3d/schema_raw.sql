create schema if not exists import_raw;

create table if not exists import_raw.raw_person (
    person_id       varchar(255),
    firstname       varchar(128),
    surname         varchar(128),
    gender          varchar(32),
    source_segment  integer not null,
    source_position bigint  not null
);

create table if not exists import_raw.raw_alias (
    person_id       varchar(255),
    alias           varchar(255),
    source_segment  integer not null,
    source_position bigint  not null
);

create table if not exists import_raw.raw_relation (
    person_id          varchar(255),
    related_person_id  varchar(255),
    relation_type      varchar(32),
    source_segment     integer not null,
    source_position    bigint  not null
);

create index if not exists ix_raw_person_id
    on import_raw.raw_person(person_id);

create index if not exists ix_raw_alias_person_id
    on import_raw.raw_alias(person_id);

create index if not exists ix_raw_relation_person_id
    on import_raw.raw_relation(person_id);

create index if not exists ix_raw_relation_related_person_id
    on import_raw.raw_relation(related_person_id);

create index if not exists ix_raw_relation_type
    on import_raw.raw_relation(relation_type);
    