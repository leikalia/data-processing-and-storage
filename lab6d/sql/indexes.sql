CREATE INDEX IF NOT EXISTS idx_flights_scheduled_departure
    ON flights (scheduled_departure);

CREATE INDEX IF NOT EXISTS idx_flights_status_scheduled_departure
    ON flights (status, scheduled_departure);

CREATE INDEX IF NOT EXISTS idx_segments_flight_class
    ON segments (flight_id, fare_conditions);

CREATE INDEX IF NOT EXISTS idx_segments_ticket
    ON segments (ticket_no);

CREATE INDEX IF NOT EXISTS idx_boarding_passes_flight
    ON boarding_passes (flight_id);

CREATE INDEX IF NOT EXISTS idx_routes_arrival_validity
    ON routes (arrival_airport, lower(validity));

CREATE INDEX IF NOT EXISTS idx_routes_departure_arrival_validity
    ON routes (departure_airport, arrival_airport, lower(validity));

CREATE INDEX IF NOT EXISTS idx_seats_airplane_class
    ON seats (airplane_code, fare_conditions);
