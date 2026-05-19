create table person (
    id varchar(255) not null,
    firstname varchar(128) null,
    surname varchar(128) null,
    gender varchar(7) null,

    constraint pk_person primary key (id),

    constraint chk_person_gender
        check (gender in ('male', 'female', 'unknown'))
);

create table person_alias (
    person_id varchar(255) not null,
    alias varchar(255) not null,

    constraint pk_person_alias
        primary key (person_id, alias),

    constraint fk_person_alias_person
        foreign key (person_id)
        references person(id)
        on delete cascade
);

create table person_relation (
    person_id varchar(255) not null,
    related_person_id varchar(255) not null,
    relation_type varchar(8) not null,

    constraint pk_person_relation
        primary key (person_id, related_person_id, relation_type),

    constraint fk_person_relation_person
        foreign key (person_id)
        references person(id)
        on delete cascade,

    constraint fk_person_relation_related_person
        foreign key (related_person_id)
        references person(id)
        on delete cascade,

    constraint chk_person_relation_not_self
        check (person_id <> related_person_id),

    constraint chk_person_relation_type
        check (
            relation_type in (
                'father',
                'mother',
                'parent',
                'spouse',
                'son',
                'daughter',
                'child',
                'brother',
                'sister',
                'sibling'
            )
        )
);

