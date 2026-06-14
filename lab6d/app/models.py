from datetime import date, datetime, time
from decimal import Decimal
from typing import Literal

from pydantic import BaseModel, Field, field_validator


Role = Literal["source", "destination", "both"]
PointKind = Literal["airport", "city"]
FareConditions = Literal["Economy", "Comfort", "Business"]


class CityItem(BaseModel):
    name: str


class CityListResponse(BaseModel):
    role: Role
    items: list[CityItem]


class AirportItem(BaseModel):
    airport_code: str
    airport_name: str
    city: str
    country: str
    timezone: str


class AirportListResponse(BaseModel):
    role: Role
    items: list[AirportItem]


class CityAirportsResponse(BaseModel):
    city: str
    items: list[AirportItem]


class InboundScheduleItem(BaseModel):
    flight_no: str
    days_of_week: list[int]
    time_local: time
    origin_airport: str
    origin_city: str


class OutboundScheduleItem(BaseModel):
    flight_no: str
    days_of_week: list[int]
    time_local: time
    destination_airport: str
    destination_city: str


class AirportScheduleResponse(BaseModel):
    airport_code: str
    items: list[InboundScheduleItem | OutboundScheduleItem]


class RouteSegment(BaseModel):
    flight_id: int
    flight_no: str
    departure_airport: str
    departure_city: str
    arrival_airport: str
    arrival_city: str
    scheduled_departure: datetime
    scheduled_arrival: datetime
    free_seats_in_class: int


class RouteOption(BaseModel):
    connections: int
    flight_ids: list[int]
    flight_nos: list[str]
    origin_airport: str
    origin_city: str
    destination_airport: str
    destination_city: str
    first_departure: datetime
    final_arrival: datetime
    route_free_seats: int
    segments: list[RouteSegment]


class RouteSearchResponse(BaseModel):
    query: dict
    items: list[RouteOption]


class RouteSearchParams(BaseModel):
    origin_kind: PointKind
    origin: str
    destination_kind: PointKind
    destination: str
    departure_date: date
    booking_class: FareConditions
    max_connections: str = Field(default="0")
    limit: int = Field(default=50, ge=1, le=200)

    @field_validator("max_connections")
    @classmethod
    def validate_max_connections(cls, value: str) -> str:
        allowed = {"0", "1", "2", "3", "unbound"}
        if value not in allowed:
            raise ValueError("max_connections must be one of 0, 1, 2, 3, unbound")
        return value


class CreateBookingRequest(BaseModel):
    flight_ids: list[int] = Field(min_length=1, max_length=6)
    fare_conditions: FareConditions
    passenger_id: str = Field(min_length=3, max_length=64)
    passenger_name: str = Field(min_length=3, max_length=128)


class BookingResponse(BaseModel):
    book_ref: str
    ticket_no: str
    passenger_id: str
    passenger_name: str
    fare_conditions: FareConditions
    total_amount: Decimal
    flight_ids: list[int]


class CheckInRequest(BaseModel):
    ticket_no: str = Field(min_length=13, max_length=13)
    flight_id: int
    preferred_seat: str | None = Field(default=None, max_length=4)


class CheckInResponse(BaseModel):
    ticket_no: str
    flight_id: int
    seat_no: str
    boarding_no: int
    boarding_time: datetime
    