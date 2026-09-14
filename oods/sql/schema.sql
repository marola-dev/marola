-- The schema every OODS Parquet partition conforms to (MIP-0056 §5.3). `oods-check` runs this
-- file and compares each file's columns against it, which is why it is DDL and not prose.
--
-- Two rules it encodes on purpose: a censored count (`<20`) keeps its number and gains a
-- qualifier, because "below the detection limit" is information; and the indicator is a column,
-- because IMA's CSV header says `E. coli` while marola's `WaterSample` field is named
-- `enterococciPer100ml` — the store must not inherit that guess (§8).

CREATE TABLE point (
  source_id     VARCHAR NOT NULL,           -- 'ima-sc'
  point_key     VARCHAR NOT NULL,           -- source-native stable id: IMA's UUID CODIGO
  country       VARCHAR NOT NULL DEFAULT 'BR',
  state         VARCHAR NOT NULL,           -- 'SC'
  municipality  VARCHAR NOT NULL,
  ibge_code     VARCHAR,                    -- MUNICIPIO_COD_IBGE when the source has it
  beach_name    VARCHAR NOT NULL,
  point_name    VARCHAR NOT NULL,           -- 'Ponto 35'
  location_desc VARCHAR,
  lat DOUBLE, lon DOUBLE,
  geo_source    VARCHAR NOT NULL,           -- 'feed' | 'curated' (MIP-0031's tables) | 'none'
  first_seen DATE, last_seen DATE,
  PRIMARY KEY (source_id, point_key));

CREATE TABLE sample (
  source_id VARCHAR NOT NULL, point_key VARCHAR NOT NULL,
  sampled_on DATE NOT NULL, sampled_at TIME,
  condition  VARCHAR NOT NULL,              -- 'propria' | 'impropria' | 'unknown' (BathingCondition.label)
  indicator  VARCHAR NOT NULL,              -- 'e_coli' | 'enterococci' | 'unknown'
  indicator_value INTEGER, indicator_qualifier VARCHAR NOT NULL DEFAULT 'exact',  -- 'exact' | 'below' | 'above'
  rain VARCHAR, wind VARCHAR, tide VARCHAR, water_temp_c DOUBLE, air_temp_c DOUBLE,
  channel VARCHAR NOT NULL,                 -- 'csv' | 'pdf' | 'json'
  bulletin_date DATE, raw_path VARCHAR NOT NULL, ingested_at TIMESTAMP NOT NULL,
  PRIMARY KEY (source_id, point_key, sampled_on, sampled_at, channel));
