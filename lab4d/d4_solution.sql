-- D4 solution
-- Restore historical prices per flight and build pricing rules for upcoming flights.

set client_min_messages = warning;
set search_path = bookings, public;

drop schema if exists lab4d cascade;
create schema lab4d;

-- 1) Capacity by airplane model and travel class.
create materialized view lab4d.flight_class_capacity as
select
    s.airplane_code::text as airplane_code,
    s.fare_conditions::text as fare_conditions,
    count(*)::int as class_capacity
from bookings.seats s
group by
    s.airplane_code,
    s.fare_conditions;

create index ix_lab4d_flight_class_capacity
    on lab4d.flight_class_capacity (airplane_code, fare_conditions);

-- 2) Historical prices restored from past booked segments.
-- We take only flights scheduled before bookings.now() and exclude cancelled flights.
create materialized view lab4d.historical_flight_prices as
with historical_segments as (
    select
        tt.flight_id,
        tt.route_no::text as route_no,
        tt.departure_airport::text as departure_airport,
        tt.arrival_airport::text as arrival_airport,
        tt.airplane_code::text as airplane_code,
        tt.scheduled_departure,
        seg.fare_conditions::text as fare_conditions,
        seg.price::numeric(10,2) as price,
        b.book_date
    from bookings.segments seg
    join bookings.tickets t
      on t.ticket_no = seg.ticket_no
    join bookings.bookings b
      on b.book_ref = t.book_ref
    join bookings.timetable tt
      on tt.flight_id = seg.flight_id
    where tt.scheduled_departure < bookings.now()
      and tt.status <> 'Cancelled'
)
select
    hs.flight_id,
    hs.route_no,
    hs.departure_airport,
    hs.arrival_airport,
    hs.airplane_code,
    hs.scheduled_departure,
    hs.fare_conditions,
    count(*)::int as sold_tickets,
    min(hs.book_date) as first_booking_at,
    max(hs.book_date) as last_booking_at,
    min(hs.price)::numeric(10,2) as min_price,
    round(avg(hs.price), 2)::numeric(10,2) as avg_price,
    percentile_cont(0.5) within group (order by hs.price)::numeric(10,2) as median_price,
    max(hs.price)::numeric(10,2) as max_price,
    fcc.class_capacity,
    round(count(*)::numeric / nullif(fcc.class_capacity, 0), 4) as sold_to_capacity_ratio
from historical_segments hs
join lab4d.flight_class_capacity fcc
  on fcc.airplane_code = hs.airplane_code
 and fcc.fare_conditions = hs.fare_conditions
group by
    hs.flight_id,
    hs.route_no,
    hs.departure_airport,
    hs.arrival_airport,
    hs.airplane_code,
    hs.scheduled_departure,
    hs.fare_conditions,
    fcc.class_capacity;

create index ix_lab4d_historical_flight_prices_flight
    on lab4d.historical_flight_prices (flight_id);

create index ix_lab4d_historical_flight_prices_rule
    on lab4d.historical_flight_prices (route_no, departure_airport, arrival_airport, airplane_code, fare_conditions);

-- 3) Pricing rule table.
-- Priority 1: route + airplane + class
-- Priority 2: airport pair + class
-- Priority 3: global class fallback
drop table if exists lab4d.pricing_rules;

create table lab4d.pricing_rules as
with route_rules as (
    select
        1::int as rule_priority,
        'route_class'::text as rule_type,
        h.route_no::text as route_no,
        h.departure_airport::text as departure_airport,
        h.arrival_airport::text as arrival_airport,
        h.airplane_code::text as airplane_code,
        h.fare_conditions::text as fare_conditions,
        count(*)::int as observed_flights,
        sum(h.sold_tickets)::int as observed_tickets,
        min(h.min_price)::numeric(10,2) as min_observed_price,
        percentile_cont(0.10) within group (order by h.median_price)::numeric(10,2) as min_recommended_price,
        percentile_cont(0.50) within group (order by h.median_price)::numeric(10,2) as recommended_price,
        percentile_cont(0.90) within group (order by h.median_price)::numeric(10,2) as max_recommended_price
    from lab4d.historical_flight_prices h
    group by
        h.route_no,
        h.departure_airport,
        h.arrival_airport,
        h.airplane_code,
        h.fare_conditions
    having count(*) >= 3
),
airport_pair_rules as (
    select
        2::int as rule_priority,
        'airport_pair_class'::text as rule_type,
        null::text as route_no,
        h.departure_airport::text as departure_airport,
        h.arrival_airport::text as arrival_airport,
        null::text as airplane_code,
        h.fare_conditions::text as fare_conditions,
        count(distinct h.flight_id)::int as observed_flights,
        sum(h.sold_tickets)::int as observed_tickets,
        min(h.min_price)::numeric(10,2) as min_observed_price,
        percentile_cont(0.10) within group (order by h.median_price)::numeric(10,2) as min_recommended_price,
        percentile_cont(0.50) within group (order by h.median_price)::numeric(10,2) as recommended_price,
        percentile_cont(0.90) within group (order by h.median_price)::numeric(10,2) as max_recommended_price
    from lab4d.historical_flight_prices h
    group by
        h.departure_airport,
        h.arrival_airport,
        h.fare_conditions
    having count(distinct h.flight_id) >= 3
),
global_class_rules as (
    select
        3::int as rule_priority,
        'class_global'::text as rule_type,
        null::text as route_no,
        null::text as departure_airport,
        null::text as arrival_airport,
        null::text as airplane_code,
        h.fare_conditions::text as fare_conditions,
        count(distinct h.flight_id)::int as observed_flights,
        sum(h.sold_tickets)::int as observed_tickets,
        min(h.min_price)::numeric(10,2) as min_observed_price,
        percentile_cont(0.10) within group (order by h.median_price)::numeric(10,2) as min_recommended_price,
        percentile_cont(0.50) within group (order by h.median_price)::numeric(10,2) as recommended_price,
        percentile_cont(0.90) within group (order by h.median_price)::numeric(10,2) as max_recommended_price
    from lab4d.historical_flight_prices h
    group by
        h.fare_conditions
)
select * from route_rules
union all
select * from airport_pair_rules
union all
select * from global_class_rules;

create index ix_lab4d_pricing_rules_lookup
    on lab4d.pricing_rules (rule_priority, fare_conditions, route_no, departure_airport, arrival_airport, airplane_code);

-- 4) Price matrix for all upcoming flights.
-- For every future flight and every class available on its airplane,
-- choose the best matching rule by priority.
create materialized view lab4d.upcoming_flight_prices as
with future_flights as (
    select
        tt.flight_id,
        tt.route_no::text as route_no,
        tt.departure_airport::text as departure_airport,
        tt.arrival_airport::text as arrival_airport,
        tt.airplane_code::text as airplane_code,
        tt.scheduled_departure,
        tt.status
    from bookings.timetable tt
    where tt.scheduled_departure >= bookings.now()
      and tt.status <> 'Cancelled'
),
future_classes as (
    select
        ff.flight_id,
        ff.route_no,
        ff.departure_airport,
        ff.arrival_airport,
        ff.airplane_code,
        ff.scheduled_departure,
        ff.status,
        fcc.fare_conditions,
        fcc.class_capacity
    from future_flights ff
    join lab4d.flight_class_capacity fcc
      on fcc.airplane_code = ff.airplane_code
)
select
    fc.flight_id,
    fc.route_no,
    fc.departure_airport,
    fc.arrival_airport,
    fc.airplane_code,
    fc.scheduled_departure,
    fc.status,
    fc.fare_conditions,
    fc.class_capacity,
    chosen.rule_type as applied_rule_type,
    chosen.rule_priority as applied_rule_priority,
    chosen.observed_flights,
    chosen.observed_tickets,
    chosen.min_recommended_price,
    chosen.recommended_price,
    chosen.max_recommended_price
from future_classes fc
left join lateral (
    select pr.*
    from lab4d.pricing_rules pr
    where pr.fare_conditions = fc.fare_conditions
      and (
            (pr.rule_priority = 1 and pr.route_no = fc.route_no and pr.airplane_code = fc.airplane_code)
         or (pr.rule_priority = 2 and pr.departure_airport = fc.departure_airport and pr.arrival_airport = fc.arrival_airport)
         or (pr.rule_priority = 3)
      )
    order by pr.rule_priority
    limit 1
) chosen on true
order by
    fc.scheduled_departure,
    fc.flight_id,
    fc.fare_conditions;

create index ix_lab4d_upcoming_flight_prices_flight
    on lab4d.upcoming_flight_prices (flight_id, fare_conditions);

-- 5) Convenience view: one row = one future flight/class/final price.
create or replace view lab4d.final_price_list as
select
    u.flight_id,
    u.route_no,
    u.departure_airport,
    u.arrival_airport,
    u.airplane_code,
    u.scheduled_departure,
    u.status,
    u.fare_conditions,
    u.class_capacity,
    u.applied_rule_type,
    u.applied_rule_priority,
    u.recommended_price as final_price
from lab4d.upcoming_flight_prices u
order by
    u.scheduled_departure,
    u.flight_id,
    u.fare_conditions;

    