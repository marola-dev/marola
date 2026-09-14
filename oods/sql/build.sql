-- raw/**/*.csv + raw/<source>/points.json + manifest/<source>.json → the `sample` and `point`
-- tables of schema.sql (MIP-0056 §5.3). `oods/Build` runs this, then copies one Parquet file per
-- partition whose content hash moved; nothing here writes to disk.
--
-- The value rules are `ImaScCsv`'s, restated in SQL because the build reads the raw bytes, not
-- the parser's output: `<20` → (20, 'below'), `> 24196` → (24196, 'above'), PRÓPRIA/IMPRÓPRIA →
-- 'propria'/'impropria', blanks → NULL.

SET VARIABLE data_dir = coalesce(getvariable('data_dir'), 'data/oods');

-- `WaterQualityMatcher.normalise`, transliterated: the join key between a CSV row and the points
-- feed. Any drift between the two erases a point's history into a slug key, so keep them equal.
CREATE OR REPLACE TEMP MACRO oods_norm(s) AS
  trim(regexp_replace(regexp_replace(regexp_replace(regexp_replace(regexp_replace(
    lower(strip_accents(s)),
    '\(.*?\)', ' ', 'g'),
    '^\s*(praia|prainha|praias)\s+(do|da|de|dos|das)\s+', '', 'g'),
    '^\s*(praia|prainha|praias)\s+', '', 'g'),
    '[^a-z0-9 ]', ' ', 'g'),
    '\s+', ' ', 'g'));

CREATE OR REPLACE TEMP MACRO oods_slug(s) AS replace(oods_norm(s), ' ', '-');

CREATE OR REPLACE TEMP MACRO oods_count(s) AS
  try_cast(nullif(regexp_extract(trim(s), '^[<>]?\s*(\d+)$', 1), '') AS INTEGER);

-- auto_detect=false: the sniffer reads a "Sem registros" export as a five-column file and then
-- refuses the thirteen columns below. header=true still skips the (BOM-carrying) header line.
CREATE OR REPLACE TEMP TABLE csv_row AS
SELECT
  regexp_extract(replace(filename, getvariable('data_dir') || '/', ''), '^raw/([^/]+)/', 1)
    AS source_id,
  replace(filename, getvariable('data_dir') || '/', '') AS raw_path,
  trim(municipio) AS municipality,
  trim(balneario) AS beach_name,
  trim(ponto) AS point_name,
  strptime(trim(data), '%d/%m/%Y')::DATE AS sampled_on,
  try_strptime(trim(hora), '%H:%M')::TIME AS sampled_at,
  CASE
    WHEN lower(strip_accents(trim(condicao))) LIKE 'impr%' THEN 'impropria'
    WHEN lower(strip_accents(trim(condicao))) LIKE 'pr%' THEN 'propria'
    ELSE 'unknown'
  END AS condition,
  oods_count(ecoli) AS indicator_value,
  CASE
    WHEN oods_count(ecoli) IS NULL THEN 'exact'
    WHEN trim(ecoli) LIKE '<%' THEN 'below'
    WHEN trim(ecoli) LIKE '>%' THEN 'above'
    ELSE 'exact'
  END AS indicator_qualifier,
  nullif(trim(chuva), '') AS rain,
  nullif(trim(vento), '') AS wind,
  nullif(trim(mare), '') AS tide,
  try_cast(trim(agua) AS DOUBLE) AS water_temp_c,
  try_cast(trim(ar) AS DOUBLE) AS air_temp_c
FROM read_csv(
  getvariable('data_dir') || '/raw/*/csv/*/*/*.csv',
  auto_detect = false, header = true, delim = ',', quote = '"', escape = '"',
  strict_mode = false, null_padding = true, filename = true,
  columns = {
    'municipio': 'VARCHAR', 'balneario': 'VARCHAR', 'ponto': 'VARCHAR', 'localizacao': 'VARCHAR',
    'data': 'VARCHAR', 'hora': 'VARCHAR', 'vento': 'VARCHAR', 'mare': 'VARCHAR',
    'chuva': 'VARCHAR', 'agua': 'VARCHAR', 'ar': 'VARCHAR', 'ecoli': 'VARCHAR',
    'condicao': 'VARCHAR'})
-- A year with no samples exports as `…,YYYY,"Sem registros"`: five columns, no date, no sample.
WHERE try_strptime(trim(data), '%d/%m/%Y') IS NOT NULL;

CREATE OR REPLACE TEMP TABLE feed_point AS
SELECT * FROM read_json(
  getvariable('data_dir') || '/raw/*/points.json',
  format = 'array',
  columns = {
    'source_id': 'VARCHAR', 'point_key': 'VARCHAR', 'country': 'VARCHAR', 'state': 'VARCHAR',
    'municipality': 'VARCHAR', 'ibge_code': 'VARCHAR', 'beach_name': 'VARCHAR',
    'point_name': 'VARCHAR', 'location_desc': 'VARCHAR', 'lat': 'DOUBLE', 'lon': 'DOUBLE',
    'geo_source': 'VARCHAR', 'first_seen': 'DATE', 'last_seen': 'DATE'});

CREATE OR REPLACE TEMP TABLE keyed AS
SELECT
  c.*,
  f.point_key AS feed_key,
  coalesce(
    f.point_key,
    c.source_id || ':' || oods_slug(c.municipality) || '/' || oods_slug(c.beach_name) || '/'
      || oods_slug(c.point_name)) AS point_key
FROM csv_row c
LEFT JOIN (
  -- One row per normalised triple: `ImaScCsv.index` builds a Map from a point-key-sorted list, so
  -- the greatest key wins there too when two feed points normalise the same.
  SELECT source_id, oods_norm(municipality) AS m, oods_norm(beach_name) AS b,
         oods_norm(point_name) AS p, max(point_key) AS point_key
  FROM feed_point GROUP BY ALL) f
  ON f.source_id = c.source_id
  AND f.m = oods_norm(c.municipality)
  AND f.b = oods_norm(c.beach_name)
  AND f.p = oods_norm(c.point_name);

CREATE OR REPLACE TEMP TABLE sample AS
SELECT
  k.source_id, k.point_key, k.sampled_on, k.sampled_at, k.condition,
  'e_coli' AS indicator, k.indicator_value, k.indicator_qualifier,
  k.rain, k.wind, k.tide, k.water_temp_c, k.air_temp_c,
  'csv' AS channel, NULL::DATE AS bulletin_date, k.raw_path,
  -- The manifest's fetch time, never the build clock: with `now()` here every build would rewrite
  -- every partition. Epoch marks a raw file no manifest knows about.
  coalesce(m.ingested_at, TIMESTAMP '1970-01-01 00:00:00') AS ingested_at,
  year(k.sampled_on) AS year
FROM keyed k
LEFT JOIN (
  SELECT e.key AS raw_path,
         CAST(json_extract_string(e.value, '$.fetched_at') AS TIMESTAMP) AS ingested_at
  FROM (
    SELECT unnest(map_entries(raw)) AS e
    FROM read_json(getvariable('data_dir') || '/manifest/*.json',
                   columns = {'raw': 'MAP(VARCHAR, JSON)'}))) m
  USING (raw_path)
-- IMA's export records the same moment twice: byte-identically, or with two different readings
-- (three pairs statewide on 2026-09-14 — 161 vs 6131 E. coli at one point, 14 vs 15 °C air at
-- another). The schema's primary key says one row per moment, so keep the worse count — the
-- conservative reading for bathing water — and break a tie on the rest of the row, never on file
-- order, so the build stays reproducible.
QUALIFY row_number() OVER (
  PARTITION BY k.source_id, k.point_key, k.sampled_on, k.sampled_at
  ORDER BY k.indicator_value DESC NULLS LAST,
           concat_ws('|', k.condition, k.indicator_qualifier, k.rain, k.wind, k.tide,
                     k.water_temp_c, k.air_temp_c)) = 1;

CREATE OR REPLACE TEMP TABLE point AS
SELECT source_id, point_key, country, state, municipality, ibge_code, beach_name, point_name,
       location_desc, lat, lon, geo_source, first_seen, last_seen
FROM feed_point
UNION ALL
-- Carried forward from the adapter (§8): a point the feed never listed keeps its samples under a
-- slug key, so the build invents its row from the sample's own names — `sample JOIN point` must
-- never drop a sample, and `oods-check` counts what that costs in coordinates.
SELECT
  k.source_id, k.point_key, coalesce(s.country, 'BR'), coalesce(s.state, ''),
  min(k.municipality), NULL::VARCHAR, min(k.beach_name), min(k.point_name),
  NULL::VARCHAR, NULL::DOUBLE, NULL::DOUBLE, 'none', NULL::DATE, NULL::DATE
FROM keyed k
LEFT JOIN (SELECT source_id, min(country) AS country, min(state) AS state
           FROM feed_point GROUP BY 1) s USING (source_id)
WHERE k.feed_key IS NULL
GROUP BY k.source_id, k.point_key, s.country, s.state;
