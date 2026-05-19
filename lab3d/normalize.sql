insert into person (id, firstname, surname, gender)
select distinct
    trim(person_id) as id,
    nullif(trim(firstname), '') as firstname,
    nullif(trim(surname), '') as surname,
    case
        when lower(trim(coalesce(gender, 'unknown'))) in ('male', 'female', 'unknown')
            then lower(trim(coalesce(gender, 'unknown')))
        when upper(trim(coalesce(gender, ''))) = 'M'
            then 'male'
        when upper(trim(coalesce(gender, ''))) = 'F'
            then 'female'
        else 'unknown'
    end as gender
from import_raw.raw_person
where person_id is not null
  and trim(person_id) <> ''
on conflict (id) do update
set firstname = coalesce(excluded.firstname, person.firstname),
    surname = coalesce(excluded.surname, person.surname),
    gender = case
        when person.gender = 'unknown' and excluded.gender <> 'unknown' then excluded.gender
        else person.gender
    end;

insert into person_alias (person_id, alias)
select distinct
    trim(a.person_id) as person_id,
    trim(a.alias) as alias
from import_raw.raw_alias a
join person p on p.id = trim(a.person_id)
where a.person_id is not null
  and trim(a.person_id) <> ''
  and a.alias is not null
  and trim(a.alias) <> ''
on conflict do nothing;

insert into person_relation (person_id, related_person_id, relation_type)
select distinct
    trim(r.person_id) as person_id,
    trim(r.related_person_id) as related_person_id,
    lower(trim(r.relation_type)) as relation_type
from import_raw.raw_relation r
join person p1 on p1.id = trim(r.person_id)
join person p2 on p2.id = trim(r.related_person_id)
where r.person_id is not null
  and trim(r.person_id) <> ''
  and r.related_person_id is not null
  and trim(r.related_person_id) <> ''
  and trim(r.person_id) <> trim(r.related_person_id)
  and lower(trim(r.relation_type)) in (
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
on conflict do nothing;

