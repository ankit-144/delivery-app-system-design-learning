-- The db-nearby locator asks: available riders in this city, inside this latitude band, nearest first.
-- A partial index over available riders only, ordered by city then latitude, turns that into a short
-- index range scan instead of visiting every available rider in the city (scenario 2.6).
CREATE INDEX riders_available_nearby_idx ON riders (city_id, lat) WHERE status = 'AVAILABLE';
