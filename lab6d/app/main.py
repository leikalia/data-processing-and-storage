from contextlib import asynccontextmanager
import logging
from uuid import uuid4

import psycopg
from psycopg_pool import PoolTimeout
import uvicorn
from fastapi import FastAPI, Query, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from starlette.exceptions import HTTPException as StarletteHTTPException

from app import repository
from app.config import settings
from app.db import close_pool, get_conn, open_pool
from app.errors import AppError
from app.models import (
    AirportListResponse,
    AirportScheduleResponse,
    BookingResponse,
    CheckInRequest,
    CheckInResponse,
    CityAirportsResponse,
    CityListResponse,
    CreateBookingRequest,
    RouteSearchParams,
    RouteSearchResponse,
)

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s %(name)s: %(message)s",
)
logger = logging.getLogger(__name__)


def error_response(
    status_code: int,
    detail,
    *,
    error_type: str | None = None,
    request_id: str | None = None,
    extra: dict | None = None,
) -> JSONResponse:
    payload = {"detail": detail}
    if error_type is not None:
        payload["error_type"] = error_type
    if request_id is not None:
        payload["request_id"] = request_id
    if extra:
        payload.update(extra)
    return JSONResponse(status_code=status_code, content=payload)


@asynccontextmanager
async def lifespan(_: FastAPI):
    open_pool()
    try:
        yield
    finally:
        close_pool()


app = FastAPI(
    title="Flights Demo REST API",
    version="1.0.0",
    description="Implementation of tasks D5-D6 over the Postgres Pro Flights demo database.",
    lifespan=lifespan,
)


@app.exception_handler(AppError)
async def handle_app_error(_: Request, exc: AppError):
    return error_response(
        exc.status_code,
        exc.message,
        error_type=exc.__class__.__name__,
        extra=exc.extra,
    )


@app.exception_handler(RequestValidationError)
async def handle_request_validation_error(_: Request, exc: RequestValidationError):
    return error_response(
        422,
        exc.errors(),
        error_type="RequestValidationError",
    )


@app.exception_handler(StarletteHTTPException)
async def handle_http_exception(_: Request, exc: StarletteHTTPException):
    return error_response(
        exc.status_code,
        exc.detail,
        error_type="HTTPException",
    )


@app.exception_handler(PoolTimeout)
async def handle_pool_timeout(_: Request, exc: PoolTimeout):
    request_id = str(uuid4())
    logger.exception("Pool timeout [%s]: %s", request_id, exc)
    return error_response(
        503,
        "Database connection pool timeout",
        error_type="PoolTimeout",
        request_id=request_id,
    )


@app.exception_handler(psycopg.OperationalError)
async def handle_operational_error(_: Request, exc: psycopg.OperationalError):
    request_id = str(uuid4())
    logger.exception("Operational error [%s]: %s", request_id, exc)
    return error_response(
        503,
        "Database is temporarily unavailable",
        error_type=exc.__class__.__name__,
        request_id=request_id,
    )


@app.exception_handler(psycopg.InterfaceError)
async def handle_interface_error(_: Request, exc: psycopg.InterfaceError):
    request_id = str(uuid4())
    logger.exception("Interface error [%s]: %s", request_id, exc)
    return error_response(
        503,
        "Database interface is unavailable",
        error_type=exc.__class__.__name__,
        request_id=request_id,
    )


@app.exception_handler(psycopg.IntegrityError)
async def handle_integrity_error(_: Request, exc: psycopg.IntegrityError):
    request_id = str(uuid4())
    logger.exception("Integrity error [%s]: %s", request_id, exc)
    return error_response(
        409,
        "Database integrity conflict",
        error_type=exc.__class__.__name__,
        request_id=request_id,
    )


@app.exception_handler(psycopg.DataError)
async def handle_data_error(_: Request, exc: psycopg.DataError):
    request_id = str(uuid4())
    logger.exception("Data error [%s]: %s", request_id, exc)
    return error_response(
        400,
        "Invalid data for database operation",
        error_type=exc.__class__.__name__,
        request_id=request_id,
    )


@app.exception_handler(TimeoutError)
async def handle_timeout_error(_: Request, exc: TimeoutError):
    request_id = str(uuid4())
    logger.exception("Timeout [%s]: %s", request_id, exc)
    return error_response(
        504,
        "Operation timed out",
        error_type=exc.__class__.__name__,
        request_id=request_id,
    )


@app.exception_handler(psycopg.DatabaseError)
async def handle_database_error(_: Request, exc: psycopg.DatabaseError):
    request_id = str(uuid4())
    logger.exception("Database error [%s]: %s", request_id, exc)
    return error_response(
        500,
        "Internal database error",
        error_type=exc.__class__.__name__,
        request_id=request_id,
    )


@app.exception_handler(Exception)
async def handle_unexpected_error(_: Request, exc: Exception):
    request_id = str(uuid4())
    logger.exception("Unhandled exception [%s]: %s", request_id, exc)
    return error_response(
        500,
        "Internal server error",
        error_type=exc.__class__.__name__,
        request_id=request_id,
    )


@app.get(f"{settings.api_prefix}/health")
def health() -> dict:
    return {"status": "ok"}


@app.get(f"{settings.api_prefix}/cities", response_model=CityListResponse)
def get_cities(role: str = Query(default="both")):
    with get_conn() as conn:
        items = repository.list_cities(conn, role)
    return CityListResponse(role=role, items=items)


@app.get(f"{settings.api_prefix}/airports", response_model=AirportListResponse)
def get_airports(role: str = Query(default="both")):
    with get_conn() as conn:
        items = repository.list_airports(conn, role)
    return AirportListResponse(role=role, items=items)


@app.get(
    f"{settings.api_prefix}/cities/{{city}}/airports",
    response_model=CityAirportsResponse,
)
def get_airports_in_city(city: str):
    with get_conn() as conn:
        items = repository.list_airports_in_city(conn, city)
    return CityAirportsResponse(city=city, items=items)


@app.get(
    f"{settings.api_prefix}/airports/{{airport_code}}/inbound-schedule",
    response_model=AirportScheduleResponse,
)
def get_inbound_schedule(airport_code: str):
    with get_conn() as conn:
        items = repository.inbound_schedule(conn, airport_code)
    return AirportScheduleResponse(airport_code=airport_code.upper(), items=items)


@app.get(
    f"{settings.api_prefix}/airports/{{airport_code}}/outbound-schedule",
    response_model=AirportScheduleResponse,
)
def get_outbound_schedule(airport_code: str):
    with get_conn() as conn:
        items = repository.outbound_schedule(conn, airport_code)
    return AirportScheduleResponse(airport_code=airport_code.upper(), items=items)


@app.get(f"{settings.api_prefix}/routes", response_model=RouteSearchResponse)
def get_routes(
    origin_kind: str,
    origin: str,
    destination_kind: str,
    destination: str,
    departure_date: str,
    booking_class: str,
    max_connections: str = "0",
    limit: int = 50,
):
    params = RouteSearchParams(
        origin_kind=origin_kind,
        origin=origin,
        destination_kind=destination_kind,
        destination=destination,
        departure_date=departure_date,
        booking_class=booking_class,
        max_connections=max_connections,
        limit=limit,
    )
    with get_conn() as conn:
        items = repository.search_routes(conn, params)
    return RouteSearchResponse(query=params.model_dump(), items=items)


@app.post(
    f"{settings.api_prefix}/bookings",
    response_model=BookingResponse,
    status_code=201,
)
def post_booking(request: CreateBookingRequest):
    with get_conn() as conn:
        result = repository.create_booking(
            conn=conn,
            flight_ids=request.flight_ids,
            fare_conditions=request.fare_conditions,
            passenger_id=request.passenger_id,
            passenger_name=request.passenger_name,
        )
    return BookingResponse(**result)


@app.post(
    f"{settings.api_prefix}/check-ins",
    response_model=CheckInResponse,
    status_code=201,
)
def post_check_in(request: CheckInRequest):
    with get_conn() as conn:
        result = repository.check_in(
            conn=conn,
            ticket_no=request.ticket_no,
            flight_id=request.flight_id,
            preferred_seat=request.preferred_seat,
        )
    return result


if __name__ == "__main__":
    uvicorn.run(
        "app.main:app",
        host=settings.host,
        port=settings.port,
        reload=settings.debug,
    )
    