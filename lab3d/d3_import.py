from __future__ import annotations

import argparse
import io
import logging
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass, field
from pathlib import Path
from typing import Optional, TYPE_CHECKING
from xml.sax import handler, make_parser

if TYPE_CHECKING:
    import psycopg


LOGGER = logging.getLogger("d3_import")

ROOT_TAG = "people"
PERSON_TAG = "person"
PERSON_START = b"<person"
PERSON_END = b"</person>"
DEFAULT_PREFIX = f"<{ROOT_TAG}>".encode("utf-8")
DEFAULT_SUFFIX = f"</{ROOT_TAG}>".encode("utf-8")

RELATION_TAG_TO_TYPE = {
    "father": "father",
    "mother": "mother",
    "parent": "parent",
    "parents": "parent",
    "spouse": "spouse",
    "spouce": "spouse",
    "wife": "spouse",
    "husband": "spouse",
    "son": "son",
    "daughter": "daughter",
    "child": "child",
    "children": "child",
    "brother": "brother",
    "sister": "sister",
    "sibling": "sibling",
    "siblings": "sibling",
}

FIRST_NAME_TAGS = {"firstname", "first", "given-name", "name-first"}
LAST_NAME_TAGS = {"surname", "family", "family-name", "lastname", "last", "name-last"}
ALIAS_TAGS = {"alias", "name", "full-name"}
ID_ATTR_CANDIDATES = ("id", "ref", "value", "val", "target")
TEXT_BLACKLIST = {"", "UNKNOWN", "NONE", "NULL", "N/A", "-"}


RAW_SCHEMA_SQL = """
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
"""

DEFAULT_TARGET_SCHEMA_SQL = """
create table if not exists person (
    id varchar(255) not null,
    firstname varchar(128) null,
    surname varchar(128) null,
    gender varchar(7) null,

    constraint pk_person primary key (id),

    constraint chk_person_gender
        check (gender in ('male', 'female', 'unknown'))
);

create table if not exists person_alias (
    person_id varchar(255) not null,
    alias varchar(255) not null,

    constraint pk_person_alias
        primary key (person_id, alias),

    constraint fk_person_alias_person
        foreign key (person_id)
        references person(id)
        on delete cascade
);

create table if not exists person_relation (
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
"""

RAW_INDEXES_SQL = """
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
"""

TARGET_TRUNCATE_SQL = """
truncate table person_relation cascade;
truncate table person_alias cascade;
truncate table person cascade;
"""

NORMALIZE_SQL = """
with cleaned_person as (
    select
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
),
grouped_person as (
    select
        id,
        max(firstname) filter (where firstname is not null) as firstname,
        max(surname) filter (where surname is not null) as surname,
        case
            when bool_or(gender = 'male') then 'male'
            when bool_or(gender = 'female') then 'female'
            else 'unknown'
        end as gender
    from cleaned_person
    group by id
)
insert into person (id, firstname, surname, gender)
select
    id,
    firstname,
    surname,
    gender
from grouped_person
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
"""

RESET_SQL = """
drop schema if exists import_raw cascade;
drop table if exists person_relation cascade;
drop table if exists person_alias cascade;
drop table if exists person cascade;
"""


@dataclass(slots=True)
class Segment:
    index: int
    start: int
    end: int


@dataclass(slots=True)
class PersonRecord:
    person_id: Optional[str] = None
    first_name: Optional[str] = None
    last_name: Optional[str] = None
    gender: Optional[str] = None
    aliases: set[str] = field(default_factory=set)
    relations: set[tuple[str, str]] = field(default_factory=set)


def clean_text(value: Optional[str]) -> Optional[str]:
    if value is None:
        return None
    cleaned = " ".join(value.replace("\xa0", " ").split())
    return cleaned or None


def is_person_id(value: Optional[str]) -> bool:
    if not value:
        return False
    value = value.strip()
    if value.upper() in TEXT_BLACKLIST:
        return False
    if len(value) < 2:
        return False
    if value[0].upper() != "P":
        return False
    return value[1:].isdigit()


def split_name(full_name: str) -> tuple[Optional[str], Optional[str]]:
    tokens = clean_text(full_name)
    if not tokens:
        return None, None
    parts = tokens.split(" ", 1)
    if len(parts) == 1:
        return parts[0], None
    return parts[0], parts[1]


def normalize_gender(value: Optional[str]) -> str:
    value = clean_text(value)
    if not value:
        return "unknown"
    upper = value.upper()
    lower = value.lower()
    if upper == "M" or lower == "male":
        return "male"
    if upper == "F" or lower == "female":
        return "female"
    return "unknown"


def first_nonempty(*values: Optional[str]) -> Optional[str]:
    for value in values:
        value = clean_text(value)
        if value:
            return value
    return None


def find_next_bytes(path: Path, start_offset: int, needle: bytes, chunk_size: int = 1024 * 1024) -> int:
    with path.open("rb") as fh:
        fh.seek(start_offset)
        absolute = start_offset
        overlap = len(needle) - 1
        buffer = b""
        while True:
            chunk = fh.read(chunk_size)
            if not chunk:
                return -1
            data = buffer + chunk
            pos = data.find(needle)
            if pos != -1:
                return absolute - len(buffer) + pos
            if len(data) >= overlap:
                buffer = data[-overlap:]
            else:
                buffer = data
            absolute += len(chunk)


def find_last_bytes(path: Path, needle: bytes, chunk_size: int = 1024 * 1024) -> int:
    file_size = path.stat().st_size
    overlap = len(needle) - 1
    with path.open("rb") as fh:
        pos = file_size
        buffer = b""
        while pos > 0:
            read_size = min(chunk_size, pos)
            pos -= read_size
            fh.seek(pos)
            chunk = fh.read(read_size)
            data = chunk + buffer
            idx = data.rfind(needle)
            if idx != -1:
                return pos + idx
            if len(data) >= overlap:
                buffer = data[:overlap]
            else:
                buffer = data
    return -1


def split_xml_into_segments(path: Path, threads: int) -> list[Segment]:
    first_person = find_next_bytes(path, 0, PERSON_START)
    if first_person == -1:
        raise ValueError("Не найден ни один <person> в XML.")

    last_person_start = find_last_bytes(path, PERSON_END)
    if last_person_start == -1:
        raise ValueError("Не найден ни один </person> в XML.")

    last_person_end = last_person_start + len(PERSON_END)

    if threads <= 1:
        return [Segment(index=0, start=first_person, end=last_person_end)]

    rough = (last_person_end - first_person) // threads
    segments: list[Segment] = []
    current_start = first_person

    for idx in range(threads):
        if idx == threads - 1:
            segments.append(Segment(index=idx, start=current_start, end=last_person_end))
            break

        boundary = first_person + rough * (idx + 1)
        corrected_end_start = find_next_bytes(path, boundary, PERSON_END)
        if corrected_end_start == -1:
            corrected_end = last_person_end
        else:
            corrected_end = corrected_end_start + len(PERSON_END)

        next_start = find_next_bytes(path, corrected_end, PERSON_START)
        if next_start == -1:
            next_start = last_person_end

        segments.append(Segment(index=idx, start=current_start, end=corrected_end))
        current_start = next_start

        if current_start >= last_person_end:
            break

    return [s for s in segments if s.start < s.end]


class WrappedSegmentStream(io.RawIOBase):
    def __init__(self, path: Path, start: int, end: int, prefix: bytes = DEFAULT_PREFIX, suffix: bytes = DEFAULT_SUFFIX):
        super().__init__()
        self._file = path.open("rb")
        self._file.seek(start)
        self._body_remaining = max(0, end - start)
        self._prefix = memoryview(prefix)
        self._suffix = memoryview(suffix)
        self._prefix_pos = 0
        self._suffix_pos = 0
        self._closed = False

    def readable(self) -> bool:
        return True

    def close(self) -> None:
        if not self._closed:
            self._file.close()
            self._closed = True
        super().close()

    def _read_prefix(self, max_bytes: int) -> bytes:
        if self._prefix_pos >= len(self._prefix):
            return b""
        end = min(len(self._prefix), self._prefix_pos + max_bytes)
        chunk = self._prefix[self._prefix_pos:end].tobytes()
        self._prefix_pos = end
        return chunk

    def _read_suffix(self, max_bytes: int) -> bytes:
        if self._suffix_pos >= len(self._suffix):
            return b""
        end = min(len(self._suffix), self._suffix_pos + max_bytes)
        chunk = self._suffix[self._suffix_pos:end].tobytes()
        self._suffix_pos = end
        return chunk

    def read(self, size: int = -1) -> bytes:
        if self._closed:
            return b""

        if size is None or size < 0:
            chunks = []
            chunks.append(self._read_prefix(len(self._prefix)))
            if self._body_remaining > 0:
                chunks.append(self._file.read(self._body_remaining))
                self._body_remaining = 0
            chunks.append(self._read_suffix(len(self._suffix)))
            return b"".join(chunks)

        remaining = size
        chunks: list[bytes] = []

        if remaining > 0:
            chunk = self._read_prefix(remaining)
            if chunk:
                chunks.append(chunk)
                remaining -= len(chunk)

        if remaining > 0 and self._body_remaining > 0:
            to_read = min(self._body_remaining, remaining)
            chunk = self._file.read(to_read)
            self._body_remaining -= len(chunk)
            if chunk:
                chunks.append(chunk)
                remaining -= len(chunk)

        if remaining > 0:
            chunk = self._read_suffix(remaining)
            if chunk:
                chunks.append(chunk)

        return b"".join(chunks)


class RawBatchWriter:
    def __init__(self, conn, batch_size: int):
        self.conn = conn
        self.batch_size = batch_size
        self.person_rows: list[tuple] = []
        self.alias_rows: list[tuple] = []
        self.relation_rows: list[tuple] = []
        self.person_count = 0
        self.alias_count = 0
        self.relation_count = 0

    def insert_person(self, record: PersonRecord, segment_index: int, source_position: int) -> None:
        self.person_rows.append(
            (
                record.person_id,
                record.first_name,
                record.last_name,
                record.gender,
                segment_index,
                source_position,
            )
        )

        for alias in sorted(record.aliases):
            self.alias_rows.append((record.person_id, alias, segment_index, source_position))

        for relation_type, related_person_id in sorted(record.relations):
            self.relation_rows.append(
                (record.person_id, related_person_id, relation_type, segment_index, source_position)
            )

        if self.pending_rows >= self.batch_size:
            self.flush()

    @property
    def pending_rows(self) -> int:
        return len(self.person_rows) + len(self.alias_rows) + len(self.relation_rows)

    def flush(self) -> None:
        with self.conn.cursor() as cur:
            if self.person_rows:
                cur.executemany(
                    """
                    insert into import_raw.raw_person
                        (person_id, firstname, surname, gender, source_segment, source_position)
                    values (%s, %s, %s, %s, %s, %s)
                    """,
                    self.person_rows,
                )
                self.person_count += len(self.person_rows)
                self.person_rows.clear()

            if self.alias_rows:
                cur.executemany(
                    """
                    insert into import_raw.raw_alias
                        (person_id, alias, source_segment, source_position)
                    values (%s, %s, %s, %s)
                    """,
                    self.alias_rows,
                )
                self.alias_count += len(self.alias_rows)
                self.alias_rows.clear()

            if self.relation_rows:
                cur.executemany(
                    """
                    insert into import_raw.raw_relation
                        (person_id, related_person_id, relation_type, source_segment, source_position)
                    values (%s, %s, %s, %s, %s)
                    """,
                    self.relation_rows,
                )
                self.relation_count += len(self.relation_rows)
                self.relation_rows.clear()

        self.conn.commit()


class NullWriter:
    def __init__(self):
        self.person_count = 0
        self.alias_count = 0
        self.relation_count = 0

    def insert_person(self, record: PersonRecord, segment_index: int, source_position: int) -> None:
        self.person_count += 1
        self.alias_count += len(record.aliases)
        self.relation_count += len(record.relations)

    def flush(self) -> None:
        return None


class FlexiblePersonHandler(handler.ContentHandler):
    def __init__(self, writer: RawBatchWriter | NullWriter, segment_index: int):
        super().__init__()
        self.writer = writer
        self.segment_index = segment_index
        self.current: Optional[PersonRecord] = None
        self.stack: list[str] = []
        self.attrs_stack: list[dict[str, str]] = []
        self.text_parts: list[str] = []
        self.source_position = 0

    def startElement(self, name: str, attrs):  
        self.stack.append(name)
        self.attrs_stack.append({key: attrs.getValue(key) for key in attrs.getNames()})
        self.text_parts = []

        if name == PERSON_TAG:
            self.current = PersonRecord()
            person_id = first_nonempty(attrs.get("id"), attrs.get("value"))
            if is_person_id(person_id):
                self.current.person_id = person_id

            name_attr = clean_text(attrs.get("name"))
            if name_attr:
                self.current.aliases.add(name_attr)
                first_name, last_name = split_name(name_attr)
                self.current.first_name = first_nonempty(self.current.first_name, first_name)
                self.current.last_name = first_nonempty(self.current.last_name, last_name)

        if self.current is None:
            return

        relation_type = RELATION_TAG_TO_TYPE.get(name)
        if relation_type:
            relation_id = self._extract_id_from_attrs(self.attrs_stack[-1])
            if relation_id:
                self.current.relations.add((relation_type, relation_id))

        if name in ALIAS_TAGS:
            alias_value = first_nonempty(
                self.attrs_stack[-1].get("value"),
                self.attrs_stack[-1].get("val"),
            )
            if alias_value:
                self.current.aliases.add(alias_value)

    def characters(self, content: str):  
        self.text_parts.append(content)

    def endElement(self, name: str): 
        text = clean_text("".join(self.text_parts))
        attrs = self.attrs_stack[-1] if self.attrs_stack else {}

        if self.current is not None:
            if name == "id":
                candidate = first_nonempty(attrs.get("value"), text)
                if is_person_id(candidate):
                    self.current.person_id = candidate

            elif name in FIRST_NAME_TAGS:
                candidate = first_nonempty(attrs.get("value"), text)
                self.current.first_name = first_nonempty(self.current.first_name, candidate)

            elif name in LAST_NAME_TAGS:
                candidate = first_nonempty(attrs.get("value"), text)
                self.current.last_name = first_nonempty(self.current.last_name, candidate)

            elif name == "gender":
                candidate = first_nonempty(attrs.get("value"), text)
                if candidate:
                    self.current.gender = normalize_gender(candidate)

            elif name in ALIAS_TAGS:
                candidate = first_nonempty(attrs.get("value"), text)
                if candidate:
                    self.current.aliases.add(candidate)

            elif name in RELATION_TAG_TO_TYPE:
                relation_type = RELATION_TAG_TO_TYPE[name]
                if text and is_person_id(text):
                    self.current.relations.add((relation_type, text))

            if name == PERSON_TAG:
                if self.current.gender is None:
                    self.current.gender = "unknown"

                if self.current.first_name and self.current.last_name:
                    self.current.aliases.add(f"{self.current.first_name} {self.current.last_name}")

                self.source_position += 1
                self.writer.insert_person(self.current, self.segment_index, self.source_position)
                self.current = None

        self.stack.pop()
        self.attrs_stack.pop()
        self.text_parts = []

    @staticmethod
    def _extract_id_from_attrs(attrs: dict[str, str]) -> Optional[str]:
        for key in ID_ATTR_CANDIDATES:
            value = attrs.get(key)
            if is_person_id(value):
                return value
        return None


def _connect(dsn: str):
    try:
        import psycopg
    except ModuleNotFoundError as exc:
        raise SystemExit(
            ":( не повезло тебе конечно" 
        ) from exc
    return psycopg.connect(dsn)


def execute_sql(conn, sql: str) -> None:
    with conn.cursor() as cur:
        cur.execute(sql)
    conn.commit()


def create_schemas(dsn: str, target_schema_file: Optional[Path]) -> None:
    with _connect(dsn) as conn:
        execute_sql(conn, RAW_SCHEMA_SQL)
        sql = target_schema_file.read_text(encoding="utf-8") if target_schema_file else DEFAULT_TARGET_SCHEMA_SQL
        execute_sql(conn, sql)


def reset_database(dsn: str) -> None:
    with _connect(dsn) as conn:
        execute_sql(conn, RESET_SQL)


def create_raw_indexes(dsn: str) -> None:
    with _connect(dsn) as conn:
        execute_sql(conn, RAW_INDEXES_SQL)


def truncate_target_tables(dsn: str) -> None:
    with _connect(dsn) as conn:
        execute_sql(conn, TARGET_TRUNCATE_SQL)


def normalize_target(dsn: str) -> None:
    truncate_target_tables(dsn)
    with _connect(dsn) as conn:
        execute_sql(conn, NORMALIZE_SQL)


def truncate_raw_schema(dsn: str) -> None:
    with _connect(dsn) as conn:
        execute_sql(
            conn,
            """
            do $$
            begin
                if to_regclass('import_raw.raw_relation') is not null then
                    execute 'truncate table import_raw.raw_relation';
                end if;

                if to_regclass('import_raw.raw_alias') is not null then
                    execute 'truncate table import_raw.raw_alias';
                end if;

                if to_regclass('import_raw.raw_person') is not null then
                    execute 'truncate table import_raw.raw_person';
                end if;
            end
            $$;
            """,
        )


def import_segment(
    dsn: str,
    xml_path: Path,
    segment: Segment,
    batch_size: int,
    dry_run: bool = False,
) -> tuple[int, int, int]:
    LOGGER.info("Segment %s: bytes [%s, %s)", segment.index, segment.start, segment.end)

    parser = make_parser()
    parser.setFeature(handler.feature_namespaces, 0)

    if dry_run:
        writer = NullWriter()
        stream = WrappedSegmentStream(xml_path, segment.start, segment.end)
        try:
            parser.setContentHandler(FlexiblePersonHandler(writer, segment.index))
            parser.parse(stream)
            writer.flush()
            LOGGER.info(
                "Segment %s done (dry-run): persons=%s aliases=%s relations=%s",
                segment.index,
                writer.person_count,
                writer.alias_count,
                writer.relation_count,
            )
            return writer.person_count, writer.alias_count, writer.relation_count
        finally:
            stream.close()

    with _connect(dsn) as conn:
        conn.autocommit = False
        writer = RawBatchWriter(conn, batch_size=batch_size)
        stream = WrappedSegmentStream(xml_path, segment.start, segment.end)
        try:
            parser.setContentHandler(FlexiblePersonHandler(writer, segment.index))
            parser.parse(stream)
            writer.flush()
            LOGGER.info(
                "Segment %s done: persons=%s aliases=%s relations=%s",
                segment.index,
                writer.person_count,
                writer.alias_count,
                writer.relation_count,
            )
            return writer.person_count, writer.alias_count, writer.relation_count
        except Exception:
            conn.rollback()
            raise
        finally:
            stream.close()


def run_import(dsn: str, xml_path: Path, threads: int, batch_size: int, dry_run: bool = False) -> None:
    segments = split_xml_into_segments(xml_path, threads)
    LOGGER.info("Prepared %s segments.", len(segments))
    for segment in segments:
        LOGGER.info("Segment %s => [%s, %s)", segment.index, segment.start, segment.end)

    totals = [0, 0, 0]
    with ThreadPoolExecutor(max_workers=threads) as pool:
        futures = [pool.submit(import_segment, dsn, xml_path, segment, batch_size, dry_run) for segment in segments]
        for future in as_completed(futures):
            persons, aliases, relations = future.result()
            totals[0] += persons
            totals[1] += aliases
            totals[2] += relations

    LOGGER.info(
        "Import totals: persons=%s aliases=%s relations=%s",
        totals[0],
        totals[1],
        totals[2],
    )


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Task D3 importer for PostgreSQL.")
    parser.add_argument("--dsn", help="PostgreSQL DSN, e.g. postgresql://postgres:postgres@localhost:5432/postgres")
    parser.add_argument("--xml", type=Path, help="Path to input XML.")
    parser.add_argument("--threads", type=int, default=4, help="Number of worker threads.")
    parser.add_argument("--batch-size", type=int, default=500, help="Batch size for inserts.")
    parser.add_argument("--mode", choices=("all", "schema", "import", "normalize"), default="all")
    parser.add_argument("--reset", action="store_true", help="Drop raw schema and target tables before running.")
    parser.add_argument("--truncate-raw", action="store_true", help="Clean raw tables before import.")
    parser.add_argument("--target-schema-file", type=Path, help="Path to D2 SQL. If omitted, built-in target schema is used.")
    parser.add_argument("--dry-run", action="store_true", help="Parse XML and count extracted rows without writing to DB.")
    parser.add_argument("--log-level", default="INFO", choices=("DEBUG", "INFO", "WARNING", "ERROR"))
    return parser.parse_args()


def validate_args(args: argparse.Namespace) -> None:
    if args.mode in {"all", "import"} and args.xml is None:
        raise SystemExit("--xml is required for mode=all/import")
    if args.mode in {"all", "schema", "import", "normalize"} and not args.dry_run and not args.dsn:
        raise SystemExit("--dsn is required unless --dry-run is used")
    if args.xml is not None and not args.xml.exists():
        raise SystemExit(f"XML file not found: {args.xml}")
    if args.threads < 1:
        raise SystemExit("--threads must be >= 1")
    if args.batch_size < 1:
        raise SystemExit("--batch-size must be >= 1")


def main() -> None:
    args = parse_args()
    logging.basicConfig(
        level=getattr(logging, args.log_level),
        format="%(asctime)s %(levelname)s %(threadName)s %(message)s",
    )
    validate_args(args)

    if args.reset and not args.dry_run:
        LOGGER.info("Resetting database objects...")
        reset_database(args.dsn)

    if args.mode in {"all", "schema"} and not args.dry_run:
        LOGGER.info("Creating raw schema and target schema...")
        create_schemas(args.dsn, args.target_schema_file)

    if args.mode in {"all", "import"}:
        if not args.dry_run and args.truncate_raw:
            LOGGER.info("Truncating raw tables...")
            truncate_raw_schema(args.dsn)
        LOGGER.info("Starting streaming import...")
        run_import(args.dsn, args.xml, args.threads, args.batch_size, args.dry_run)

    if args.mode in {"all", "normalize"} and not args.dry_run:
        LOGGER.info("Creating raw indexes...")
        create_raw_indexes(args.dsn)
        LOGGER.info("Running normalization SQL...")
        normalize_target(args.dsn)
        LOGGER.info("Normalization finished.")


if __name__ == "__main__":
    main()
    
    