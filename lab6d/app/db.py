from contextlib import contextmanager
from typing import Iterator

from psycopg.rows import dict_row
from psycopg_pool import ConnectionPool

from app.config import settings

pool = ConnectionPool(
    conninfo=settings.database_url,
    min_size=1,
    max_size=10,
    kwargs={"row_factory": dict_row},
    open=False,
)


def open_pool() -> None:
    if pool.closed:
        pool.open()


def close_pool() -> None:
    if not pool.closed:
        pool.close()


@contextmanager
def get_conn() -> Iterator:
    with pool.connection() as conn:
        yield conn
        