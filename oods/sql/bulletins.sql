-- raw/<source>/bulletins/YYYY-MM-DD.jsonl → the `sample` and `point` tables build.sql just made
-- (MIP-0056 §4.2). A script of its own, and not part of build.sql, because DuckDB errors on a glob
-- that matches no file: `oods/Build` runs this only once a bulletin is on disk.

CREATE OR REPLACE TEMP TABLE bulletin_row AS
SELECT
  * EXCLUDE (filename),
  replace(filename, getvariable('data_dir') || '/', '') AS raw_path
FROM read_json(
  getvariable('data_dir') || '/raw/*/bulletins/*.jsonl',
  format = 'newline_delimited', filename = true,
  columns = {
    'source_id': 'VARCHAR', 'point_key': 'VARCHAR', 'sampled_on': 'DATE', 'sampled_at': 'TIME',
    'condition': 'VARCHAR', 'indicator': 'VARCHAR', 'indicator_value': 'INTEGER',
    'indicator_qualifier': 'VARCHAR', 'rain': 'VARCHAR', 'wind': 'VARCHAR', 'tide': 'VARCHAR',
    'water_temp_c': 'DOUBLE', 'air_temp_c': 'DOUBLE', 'channel': 'VARCHAR',
    'bulletin_date': 'DATE'})
-- A bulletin repeats a point it could not resample, so the same (point, day) arrives from several
-- weeks. The bulletin that first published it is the record; the rest are carry-forwards, and
-- keeping them would duplicate the sample primary key.
QUALIFY row_number() OVER (PARTITION BY source_id, point_key, sampled_on
                           ORDER BY bulletin_date, raw_path) = 1;

-- The bulletin has no indicator count and no collection time, so where the export already covers
-- the day it carries strictly less: it is not stored at all, rather than stored and then hidden by
-- views.sql's channel precedence.
CREATE OR REPLACE TEMP TABLE bulletin_new AS
SELECT b.* FROM bulletin_row b
WHERE NOT EXISTS (SELECT 1 FROM sample s
                  WHERE s.source_id = b.source_id AND s.point_key = b.point_key
                    AND s.sampled_on = b.sampled_on);

-- Same rule as build.sql's own invented rows (§8): a point the feed never listed keeps its
-- samples, so it needs a row or `br_bathing_water`'s join would drop them. Its name comes back out
-- of the slug key the adapter built from the bulletin's own text, accents and all-caps aside.
INSERT INTO point
SELECT
  b.source_id, b.point_key, coalesce(s.country, 'BR'), coalesce(s.state, ''),
  '', NULL::VARCHAR,
  upper(replace(split_part(split_part(b.point_key, ':', 2), '/', 1), '-', ' ')),
  upper(replace(split_part(split_part(b.point_key, ':', 2), '/', 2), '-', ' ')),
  NULL::VARCHAR, NULL::DOUBLE, NULL::DOUBLE, 'none', NULL::DATE, NULL::DATE
FROM bulletin_new b
LEFT JOIN (SELECT source_id, min(country) AS country, min(state) AS state
           FROM feed_point GROUP BY 1) s USING (source_id)
WHERE NOT EXISTS (SELECT 1 FROM point p
                  WHERE p.source_id = b.source_id AND p.point_key = b.point_key)
GROUP BY b.source_id, b.point_key, s.country, s.state;

INSERT INTO sample
SELECT
  b.source_id, b.point_key, b.sampled_on, b.sampled_at, b.condition, b.indicator,
  b.indicator_value, b.indicator_qualifier, b.rain, b.wind, b.tide, b.water_temp_c, b.air_temp_c,
  b.channel, b.bulletin_date, b.raw_path,
  coalesce(m.ingested_at, TIMESTAMP '1970-01-01 00:00:00') AS ingested_at,
  year(b.sampled_on) AS year
FROM bulletin_new b
LEFT JOIN raw_ingest m USING (raw_path);
