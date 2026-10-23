SET LOCAL lock_timeout='5s';
SET LOCAL statement_timeout='60s';

-- User-approved Amap POI (GCJ-02), used as the campus map center.
-- Source: https://amap.com/place/B021301003 (confirmed 2026-10-23).
-- Keep the published V73 migration unchanged; apply this on every site.
UPDATE public.campus
SET gcj_lng=117.140109, gcj_lat=36.667382
WHERE id=1 AND name='软件园';
