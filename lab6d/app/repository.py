from __future__ import annotations

import random
import string
from dataclasses import dataclass
from datetime import datetime, timedelta
from decimal import Decimal
from typing import Any

from psycopg import Connection

from app.errors import ConflictError, NotFoundError, ValidationError
from app.models import (
    AirportItem,
    CheckInResponse,
    CityItem,
    FareConditions,
    RouteOption,
    RouteSearchParams,
    RouteSegment,
)


@dataclass(slots=True)
class ResolvedFlight:
    flight_id: int
    flight_no: str
    route_no: str
    departure_airport: str
    departure_city: str
    arrival_airport: str
    arrival_city: str
    airplane_code: str
    status: str
    scheduled_departure: datetime
    scheduled_arrival: datetime
    free_seats: int


def _normalize_point_value(value: str) -> str:
    return value.strip()


def _generate_book_ref(conn: Connection[Any]) -> str:
    alphabet = string.ascii_uppercase + string.digits
    while True:
        candidate = "".join(random.choice(alphabet) for _ in range(6))
        with conn.cursor() as cur:
            cur.execute("SELECT 1 FROM bookings WHERE book_ref = %s", (candidate,))
            if cur.fetchone() is None:
                return candidate


def _generate_ticket_no(conn: Connection[Any]) -> str:
    while True:
        candidate = "".join(random.choice(string.digits) for _ in range(13))
        with conn.cursor() as cur:
            cur.execute("SELECT 1 FROM tickets WHERE ticket_no = %s", (candidate,))
            if cur.fetchone() is None:
                return candidate


def _resolve_point_cte(kind: str, param_name: str) -> str:
    if kind == "airport":
        return f"""
        SELECT airport_code
        FROM airports
        WHERE upper(airport_code) = upper(%({param_name})s)
        """
    return f"""
    SELECT airport_code
    FROM airports
    WHERE lower(city) = lower(%({param_name})s)
    """


def list_cities(conn: Connection[Any], role: str) -> list[CityItem]:
    if role == "source":
        sql = """
        SELECT DISTINCT a.city AS name
        FROM routes r
        JOIN airports a ON a.airport_code = r.departure_airport
        ORDER BY 1
        """
    elif role == "destination":
        sql = """
        SELECT DISTINCT a.city AS name
        FROM routes r
        JOIN airports a ON a.airport_code = r.arrival_airport
        ORDER BY 1
        """
    else:
        sql = """
        SELECT DISTINCT city AS name
        FROM (
            SELECT a.city
            FROM routes r
            JOIN airports a ON a.airport_code = r.departure_airport
            UNION
            SELECT a.city
            FROM routes r
            JOIN airports a ON a.airport_code = r.arrival_airport
        ) x
        ORDER BY 1
        """
    with conn.cursor() as cur:
        cur.execute(sql)
        return [CityItem(**row) for row in cur.fetchall()]


def list_airports(conn: Connection[Any], role: str) -> list[AirportItem]:
    if role == "source":
        sql = """
        SELECT DISTINCT a.airport_code, a.airport_name, a.city, a.country, a.timezone
        FROM routes r
        JOIN airports a ON a.airport_code = r.departure_airport
        ORDER BY a.city, a.airport_name
        """
    elif role == "destination":
        sql = """
        SELECT DISTINCT a.airport_code, a.airport_name, a.city, a.country, a.timezone
        FROM routes r
        JOIN airports a ON a.airport_code = r.arrival_airport
        ORDER BY a.city, a.airport_name
        """
    else:
        sql = """
        SELECT DISTINCT a.airport_code, a.airport_name, a.city, a.country, a.timezone
        FROM airports a
        JOIN (
            SELECT departure_airport AS airport_code FROM routes
            UNION
            SELECT arrival_airport AS airport_code FROM routes
        ) x ON x.airport_code = a.airport_code
        ORDER BY a.city, a.airport_name
        """
    with conn.cursor() as cur:
        cur.execute(sql)
        return [AirportItem(**row) for row in cur.fetchall()]


def list_airports_in_city(conn: Connection[Any], city: str) -> list[AirportItem]:
    with conn.cursor() as cur:
        cur.execute(
            """
            SELECT airport_code, airport_name, city, country, timezone
            FROM airports
            WHERE lower(city) = lower(%s)
            ORDER BY airport_name
            """,
            (city,),
        )
        rows = cur.fetchall()
    if not rows:
        raise NotFoundError(f"City '{city}' was not found")
    return [AirportItem(**row) for row in rows]


def inbound_schedule(conn: Connection[Any], airport_code: str) -> list[dict]:
    with conn.cursor() as cur:
        cur.execute(
            """
            SELECT
                r.route_no AS flight_no,
                r.days_of_week,
                (
                    (
                        ((timestamp '2000-01-03' + r.scheduled_time) AT TIME ZONE dep.timezone) + r.duration
                    ) AT TIME ZONE arr.timezone
                )::time AS time_local,
                dep.airport_code AS origin_airport,
                dep.city AS origin_city
            FROM routes r
            JOIN airports dep ON dep.airport_code = r.departure_airport
            JOIN airports arr ON arr.airport_code = r.arrival_airport
            WHERE upper(r.arrival_airport) = upper(%s)
            ORDER BY time_local, flight_no
            """,
            (airport_code,),
        )
        rows = cur.fetchall()
    if not rows:
        raise NotFoundError(
            f"Airport '{airport_code}' was not found or has no inbound schedule"
        )
    return rows


def outbound_schedule(conn: Connection[Any], airport_code: str) -> list[dict]:
    with conn.cursor() as cur:
        cur.execute(
            """
            SELECT
                r.route_no AS flight_no,
                r.days_of_week,
                r.scheduled_time AS time_local,
                arr.airport_code AS destination_airport,
                arr.city AS destination_city
            FROM routes r
            JOIN airports arr ON arr.airport_code = r.arrival_airport
            WHERE upper(r.departure_airport) = upper(%s)
            ORDER BY time_local, flight_no
            """,
            (airport_code,),
        )
        rows = cur.fetchall()
    if not rows:
        raise NotFoundError(
            f"Airport '{airport_code}' was not found or has no outbound schedule"
        )
    return rows


def _resolve_connection_limit(value: str) -> tuple[int, int]:
    if value == "unbound":
        return 99, 6
    connections = int(value)
    return connections, connections + 1


def search_routes(conn: Connection[Any], params: RouteSearchParams) -> list[RouteOption]:
    max_connections, max_legs = _resolve_connection_limit(params.max_connections)

    sql = f"""
    WITH RECURSIVE
    origin_airports AS (
        {_resolve_point_cte(params.origin_kind, 'origin_value')}
    ),
    destination_airports AS (
        {_resolve_point_cte(params.destination_kind, 'destination_value')}
    ),
    seat_capacity AS (
        SELECT airplane_code, COUNT(*)::int AS total_seats
        FROM seats
        WHERE fare_conditions = %(booking_class)s
        GROUP BY airplane_code
    ),
    booked_seats AS (
        SELECT flight_id, COUNT(*)::int AS booked_seats
        FROM segments
        WHERE fare_conditions = %(booking_class)s
        GROUP BY flight_id
    ),
    eligible_flights AS (
        SELECT
            t.flight_id,
            t.route_no AS flight_no,
            t.route_no,
            t.departure_airport,
            dep.city AS departure_city,
            t.arrival_airport,
            arr.city AS arrival_city,
            t.airplane_code,
            t.status,
            t.scheduled_departure,
            t.scheduled_arrival,
            cap.total_seats,
            COALESCE(bs.booked_seats, 0)::int AS booked_seats,
            (cap.total_seats - COALESCE(bs.booked_seats, 0))::int AS free_seats
        FROM timetable t
        JOIN airports dep ON dep.airport_code = t.departure_airport
        JOIN airports arr ON arr.airport_code = t.arrival_airport
        JOIN seat_capacity cap ON cap.airplane_code = t.airplane_code
        LEFT JOIN booked_seats bs ON bs.flight_id = t.flight_id
        WHERE t.status NOT IN ('Cancelled', 'Departed', 'Arrived')
          AND t.scheduled_departure::date = %(departure_date)s
          AND cap.total_seats > COALESCE(bs.booked_seats, 0)
    ),
    search_paths AS (
        SELECT
            ARRAY[e.flight_id] AS flight_ids,
            ARRAY[e.flight_no] AS flight_nos,
            ARRAY[e.departure_airport::text, e.arrival_airport::text]::text[] AS airport_path,
            jsonb_build_array(
                jsonb_build_object(
                    'flight_id', e.flight_id,
                    'flight_no', e.flight_no,
                    'departure_airport', e.departure_airport,
                    'departure_city', e.departure_city,
                    'arrival_airport', e.arrival_airport,
                    'arrival_city', e.arrival_city,
                    'scheduled_departure', e.scheduled_departure,
                    'scheduled_arrival', e.scheduled_arrival,
                    'free_seats_in_class', e.free_seats
                )
            ) AS segments,
            e.departure_airport AS origin_airport,
            e.departure_city AS origin_city,
            e.arrival_airport AS current_airport,
            e.arrival_city AS current_city,
            e.scheduled_departure AS first_departure,
            e.scheduled_arrival AS last_arrival,
            0::int AS connections,
            1::int AS legs,
            e.free_seats AS route_free_seats
        FROM eligible_flights e
        JOIN origin_airports oa ON oa.airport_code = e.departure_airport

        UNION ALL

        SELECT
            sp.flight_ids || e.flight_id,
            sp.flight_nos || e.flight_no,
            sp.airport_path || e.arrival_airport::text,
            sp.segments || jsonb_build_array(
                jsonb_build_object(
                    'flight_id', e.flight_id,
                    'flight_no', e.flight_no,
                    'departure_airport', e.departure_airport,
                    'departure_city', e.departure_city,
                    'arrival_airport', e.arrival_airport,
                    'arrival_city', e.arrival_city,
                    'scheduled_departure', e.scheduled_departure,
                    'scheduled_arrival', e.scheduled_arrival,
                    'free_seats_in_class', e.free_seats
                )
            ),
            sp.origin_airport,
            sp.origin_city,
            e.arrival_airport,
            e.arrival_city,
            sp.first_departure,
            e.scheduled_arrival,
            sp.connections + 1,
            sp.legs + 1,
            LEAST(sp.route_free_seats, e.free_seats)
        FROM search_paths sp
        JOIN eligible_flights e
          ON e.departure_airport = sp.current_airport
         AND e.scheduled_departure >= sp.last_arrival + interval '40 minutes'
         AND e.scheduled_departure <= sp.last_arrival + interval '12 hours'
         AND NOT e.arrival_airport::text = ANY(sp.airport_path)
        WHERE sp.legs < %(max_legs)s
    )
    SELECT
        flight_ids,
        flight_nos,
        origin_airport,
        origin_city,
        current_airport AS destination_airport,
        current_city AS destination_city,
        first_departure,
        last_arrival AS final_arrival,
        connections,
        route_free_seats,
        segments
    FROM search_paths sp
    JOIN destination_airports da ON da.airport_code = sp.current_airport
    WHERE sp.connections <= %(max_connections)s
    ORDER BY connections, first_departure, final_arrival
    LIMIT %(limit)s
    """

    query_params = {
        "origin_value": _normalize_point_value(params.origin),
        "destination_value": _normalize_point_value(params.destination),
        "booking_class": params.booking_class,
        "departure_date": params.departure_date,
        "max_legs": max_legs,
        "max_connections": max_connections,
        "limit": params.limit,
    }

    with conn.cursor() as cur:
        cur.execute(sql, query_params)
        rows = cur.fetchall()

    if not rows:
        with conn.cursor() as cur:
            cur.execute(
                f"SELECT COUNT(*) AS cnt FROM ({_resolve_point_cte(params.origin_kind, 'value')}) x",
                {"value": params.origin},
            )
            origin_count = cur.fetchone()["cnt"]

            cur.execute(
                f"SELECT COUNT(*) AS cnt FROM ({_resolve_point_cte(params.destination_kind, 'value')}) x",
                {"value": params.destination},
            )
            destination_count = cur.fetchone()["cnt"]

        if origin_count == 0:
            raise NotFoundError(f"Origin '{params.origin}' was not found")
        if destination_count == 0:
            raise NotFoundError(f"Destination '{params.destination}' was not found")
        return []

    items: list[RouteOption] = []
    for row in rows:
        segments = [RouteSegment(**segment) for segment in row["segments"]]
        items.append(
            RouteOption(
                connections=row["connections"],
                flight_ids=row["flight_ids"],
                flight_nos=row["flight_nos"],
                origin_airport=row["origin_airport"],
                origin_city=row["origin_city"],
                destination_airport=row["destination_airport"],
                destination_city=row["destination_city"],
                first_departure=row["first_departure"],
                final_arrival=row["final_arrival"],
                route_free_seats=row["route_free_seats"],
                segments=segments,
            )
        )
    return items


def _load_flights_for_booking(
    conn: Connection[Any],
    flight_ids: list[int],
    fare_conditions: FareConditions,
    lock: bool = False,
) -> list[ResolvedFlight]:
    lock_clause = "FOR UPDATE OF f" if lock else ""
    sql = f"""
    WITH seat_capacity AS (
        SELECT airplane_code, COUNT(*)::int AS total_seats
        FROM seats
        WHERE fare_conditions = %(fare_conditions)s
        GROUP BY airplane_code
    ),
    booked_seats AS (
        SELECT flight_id, COUNT(*)::int AS booked_seats
        FROM segments
        WHERE fare_conditions = %(fare_conditions)s
        GROUP BY flight_id
    )
    SELECT
        f.flight_id,
        t.route_no AS flight_no,
        t.route_no,
        t.departure_airport,
        dep.city AS departure_city,
        t.arrival_airport,
        arr.city AS arrival_city,
        t.airplane_code,
        t.status,
        t.scheduled_departure,
        t.scheduled_arrival,
        (cap.total_seats - COALESCE(bs.booked_seats, 0))::int AS free_seats
    FROM flights f
    JOIN timetable t ON t.flight_id = f.flight_id
    JOIN airports dep ON dep.airport_code = t.departure_airport
    JOIN airports arr ON arr.airport_code = t.arrival_airport
    JOIN seat_capacity cap ON cap.airplane_code = t.airplane_code
    LEFT JOIN booked_seats bs ON bs.flight_id = f.flight_id
    WHERE f.flight_id = ANY(%(flight_ids)s)
      AND t.status NOT IN ('Cancelled', 'Departed', 'Arrived')
    ORDER BY t.scheduled_departure
    {lock_clause}
    """
    with conn.cursor() as cur:
        cur.execute(
            sql,
            {"flight_ids": flight_ids, "fare_conditions": fare_conditions},
        )
        rows = cur.fetchall()

    if len(rows) != len(set(flight_ids)):
        raise NotFoundError("One or more selected flights were not found")

    resolved = [ResolvedFlight(**row) for row in rows]
    for item in resolved:
        if item.free_seats <= 0:
            raise ConflictError(
                f"No seats left in class {fare_conditions} for flight {item.flight_id}"
            )
    return resolved


def _validate_booking_path(flights: list[ResolvedFlight]) -> None:
    for prev, cur in zip(flights, flights[1:]):
        if prev.arrival_airport != cur.departure_airport:
            raise ValidationError("Selected flights do not form a continuous route")
        layover = cur.scheduled_departure - prev.scheduled_arrival
        if layover < timedelta(minutes=40):
            raise ValidationError("Connection time is too short")
        if layover > timedelta(hours=12):
            raise ValidationError("Connection time is too long for a single searched route")


def _suggest_segment_price(
    conn: Connection[Any],
    route_no: str,
    fare_conditions: FareConditions,
) -> Decimal:
    with conn.cursor() as cur:
        cur.execute(
            """
            WITH route_prices AS (
                SELECT percentile_disc(0.5) WITHIN GROUP (ORDER BY s.price) AS price
                FROM segments s
                JOIN timetable t ON t.flight_id = s.flight_id
                WHERE t.route_no = %s
                  AND s.fare_conditions = %s
            ),
            class_prices AS (
                SELECT percentile_disc(0.5) WITHIN GROUP (ORDER BY s.price) AS price
                FROM segments s
                WHERE s.fare_conditions = %s
            )
            SELECT COALESCE(
                (SELECT price FROM route_prices),
                (SELECT price FROM class_prices),
                0
            ) AS price
            """,
            (route_no, fare_conditions, fare_conditions),
        )
        row = cur.fetchone()
    return Decimal(row["price"])


def create_booking(
    conn: Connection[Any],
    flight_ids: list[int],
    fare_conditions: FareConditions,
    passenger_id: str,
    passenger_name: str,
) -> dict:
    with conn.transaction():
        flights = _load_flights_for_booking(
            conn=conn,
            flight_ids=flight_ids,
            fare_conditions=fare_conditions,
            lock=True,
        )
        _validate_booking_path(flights)

        book_ref = _generate_book_ref(conn)
        ticket_no = _generate_ticket_no(conn)

        total_amount = Decimal("0.00")
        segment_prices: list[tuple[int, Decimal]] = []
        for flight in flights:
            price = _suggest_segment_price(conn, flight.route_no, fare_conditions)
            segment_prices.append((flight.flight_id, price))
            total_amount += price

        with conn.cursor() as cur:
            cur.execute(
                """
                INSERT INTO bookings (book_ref, book_date, total_amount)
                VALUES (%s, bookings.now(), %s)
                """,
                (book_ref, total_amount),
            )
            cur.execute(
                """
                INSERT INTO tickets (ticket_no, book_ref, passenger_id, passenger_name, outbound)
                VALUES (%s, %s, %s, %s, TRUE)
                """,
                (ticket_no, book_ref, passenger_id, passenger_name),
            )
            for segment_flight_id, price in segment_prices:
                cur.execute(
                    """
                    INSERT INTO segments (ticket_no, flight_id, fare_conditions, price)
                    VALUES (%s, %s, %s, %s)
                    """,
                    (ticket_no, segment_flight_id, fare_conditions, price),
                )

    return {
        "book_ref": book_ref,
        "ticket_no": ticket_no,
        "passenger_id": passenger_id,
        "passenger_name": passenger_name,
        "fare_conditions": fare_conditions,
        "total_amount": total_amount,
        "flight_ids": [item.flight_id for item in flights],
    }


def check_in(
    conn: Connection[Any],
    ticket_no: str,
    flight_id: int,
    preferred_seat: str | None = None,
) -> CheckInResponse:
    with conn.transaction():
        with conn.cursor() as cur:
            cur.execute(
                """
                SELECT
                    s.ticket_no,
                    s.flight_id,
                    s.fare_conditions,
                    tt.airplane_code,
                    tt.scheduled_departure,
                    tt.status,
                    bookings.now() AS demo_now
                FROM segments s
                JOIN flights f ON f.flight_id = s.flight_id
                JOIN timetable tt ON tt.flight_id = s.flight_id
                WHERE s.ticket_no = %s
                  AND s.flight_id = %s
                FOR UPDATE OF f
                """,
                (ticket_no, flight_id),
            )
            row = cur.fetchone()

        if row is None:
            raise NotFoundError("Ticket or flight was not found")

        if row["status"] in {"Cancelled", "Departed", "Arrived"}:
            raise ValidationError("Check-in is not available for this flight status")

        if row["demo_now"] < row["scheduled_departure"] - timedelta(hours=24):
            raise ValidationError("Check-in is not open yet")

        if row["demo_now"] >= row["scheduled_departure"]:
            raise ValidationError("Check-in window is already closed")

        with conn.cursor() as cur:
            cur.execute(
                """
                SELECT 1
                FROM boarding_passes
                WHERE ticket_no = %s
                  AND flight_id = %s
                """,
                (ticket_no, flight_id),
            )
            if cur.fetchone() is not None:
                raise ConflictError("Passenger is already checked in for this flight")

        with conn.cursor() as cur:
            cur.execute(
                """
                SELECT seat_no
                FROM seats
                WHERE airplane_code = %s
                  AND fare_conditions = %s
                  AND seat_no NOT IN (
                      SELECT seat_no
                      FROM boarding_passes
                      WHERE flight_id = %s
                  )
                ORDER BY seat_no
                """,
                (row["airplane_code"], row["fare_conditions"], flight_id),
            )
            free_rows = cur.fetchall()

        free_seats = [seat["seat_no"] for seat in free_rows]
        if not free_seats:
            raise ConflictError("No free seats left for check-in")

        if preferred_seat is not None:
            if preferred_seat not in free_seats:
                raise ConflictError("Preferred seat is not available")
            seat_no = preferred_seat
        else:
            seat_no = free_seats[0]

        with conn.cursor() as cur:
            cur.execute(
                """
                SELECT COALESCE(MAX(boarding_no), 0) + 1 AS next_no
                FROM boarding_passes
                WHERE flight_id = %s
                """,
                (flight_id,),
            )
            boarding_no = cur.fetchone()["next_no"]

            cur.execute(
                """
                INSERT INTO boarding_passes (ticket_no, flight_id, seat_no, boarding_no, boarding_time)
                VALUES (%s, %s, %s, %s, bookings.now())
                RETURNING ticket_no, flight_id, seat_no, boarding_no, boarding_time
                """,
                (ticket_no, flight_id, seat_no, boarding_no),
            )
            inserted = cur.fetchone()

    return CheckInResponse(**inserted)
