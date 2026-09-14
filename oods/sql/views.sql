-- The common view over every source's Parquet (MIP-0056 §5.3), plus the two aggregates the store
-- exists for. `just oods-sql "<query>"` loads this file first; a caller that keeps its store
-- somewhere else sets `data_dir` before reading it.

SET VARIABLE data_dir = coalesce(getvariable('data_dir'), 'data/oods');

-- One schema, every source, one row per (point, moment): the csv channel is the export with the
-- full history, the pdf channel (task 7) only fills weeks the export has not caught up with.
CREATE OR REPLACE VIEW br_bathing_water AS
  SELECT s.*, p.state, p.municipality, p.beach_name, p.point_name, p.lat, p.lon, p.geo_source
  FROM read_parquet(getvariable('data_dir') || '/parquet/*/samples/year=*/*.parquet',
                    hive_partitioning = true, union_by_name = true) s
  JOIN read_parquet(getvariable('data_dir') || '/parquet/*/points.parquet',
                    union_by_name = true) p USING (source_id, point_key)
  QUALIFY row_number() OVER (PARTITION BY source_id, point_key, sampled_on, sampled_at
                             ORDER BY CASE channel WHEN 'csv' THEN 0 WHEN 'pdf' THEN 1 ELSE 2 END)
          = 1;

-- What §5.5's `latest/<source>.json` export is built from: the newest sample of every point.
CREATE OR REPLACE VIEW latest_per_point AS
  SELECT * FROM br_bathing_water
  QUALIFY row_number() OVER (PARTITION BY source_id, point_key
                             ORDER BY sampled_on DESC, sampled_at DESC NULLS LAST) = 1;

-- "This point is usually imprópria after rain" — the question 23 years of history can answer and
-- the five-sample feed cannot. `rain` is the portal's own scale ('Ausente' | 'Fraca' | 'Moderada'
-- | 'Intensa'), so anything else non-NULL counts as rain.
CREATE OR REPLACE VIEW point_stats AS
  SELECT
    source_id, point_key, state, municipality, beach_name, point_name,
    count(*) AS n,
    min(sampled_on) AS first_sample,
    max(sampled_on) AS last_sample,
    count(*) FILTER (WHERE condition = 'impropria') AS n_impropria,
    count(*) FILTER (WHERE condition = 'impropria') / count(*) AS share_impropria,
    count(*) FILTER (WHERE rain IS NOT NULL AND rain <> 'Ausente') AS n_after_rain,
    count(*) FILTER (WHERE rain IS NOT NULL AND rain <> 'Ausente' AND condition = 'impropria')
      / nullif(count(*) FILTER (WHERE rain IS NOT NULL AND rain <> 'Ausente'), 0)
      AS share_impropria_after_rain
  FROM br_bathing_water
  GROUP BY ALL;
