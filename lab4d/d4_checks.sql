set search_path = bookings, public;

\echo ===== 1. Historical price facts =====
select count(*) as historical_flight_class_rows
from lab4d.historical_flight_prices;

select *
from lab4d.historical_flight_prices
order by scheduled_departure desc
limit 20;

\echo ===== 2. Pricing rules =====
select rule_priority, rule_type, fare_conditions, count(*) as rules_count
from lab4d.pricing_rules
group by rule_priority, rule_type, fare_conditions
order by rule_priority, fare_conditions;

select *
from lab4d.pricing_rules
order by rule_priority, fare_conditions, route_no nulls last, departure_airport nulls last
limit 30;

\echo ===== 3. Upcoming flights with assigned prices =====
select count(*) as upcoming_flight_class_rows
from lab4d.upcoming_flight_prices;

select *
from lab4d.final_price_list
limit 30;

\echo ===== 4. Coverage check: future flight/class rows without price =====
select count(*) as rows_without_price
from lab4d.upcoming_flight_prices
where recommended_price is null;

\echo ===== 5. Sample aggregated result by class =====
select
    fare_conditions,
    count(*) as future_rows,
    min(final_price) as min_price,
    round(avg(final_price), 2) as avg_price,
    max(final_price) as max_price
from lab4d.final_price_list
group by fare_conditions
order by fare_conditions;

