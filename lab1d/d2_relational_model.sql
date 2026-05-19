-- D2. Relational model for schema X2

CREATE TABLE person (
    id VARCHAR(255) NOT NULL,
    firstname VARCHAR(255) NULL,
    surname VARCHAR(255) NULL,
    gender VARCHAR(20) NULL,

    CONSTRAINT pk_person PRIMARY KEY (id),

    CONSTRAINT chk_person_gender
        CHECK (gender IN ('male', 'female', 'unknown'))
);

CREATE TABLE person_alias (
    person_id VARCHAR(255) NOT NULL,
    alias VARCHAR(255) NOT NULL,

    CONSTRAINT pk_person_alias PRIMARY KEY (person_id, alias),

    CONSTRAINT fk_person_alias_person
        FOREIGN KEY (person_id)
        REFERENCES person(id)
        ON DELETE CASCADE
);

CREATE TABLE person_relation (
    person_id VARCHAR(255) NOT NULL,
    related_person_id VARCHAR(255) NOT NULL,
    relation_type VARCHAR(20) NOT NULL,

    CONSTRAINT pk_person_relation
        PRIMARY KEY (person_id, related_person_id, relation_type),

    CONSTRAINT fk_person_relation_person
        FOREIGN KEY (person_id)
        REFERENCES person(id)
        ON DELETE CASCADE,

    CONSTRAINT fk_person_relation_related_person
        FOREIGN KEY (related_person_id)
        REFERENCES person(id)
        ON DELETE CASCADE,

    CONSTRAINT chk_person_relation_not_self
        CHECK (person_id <> related_person_id),

    CONSTRAINT chk_person_relation_type
        CHECK (
            relation_type IN (
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