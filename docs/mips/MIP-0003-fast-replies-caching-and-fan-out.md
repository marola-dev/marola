# MIP-0003: Replies in under three seconds — caching, concurrent fetches, precomputed boards

| | |
|---|---|
| **Status** | Draft |
| **Author** | Claude Fable 5.1, for M. Hoffmann |
| **Created** | 2026-09-05 |
| **Phase** | 1 for the local cache and fan-out (needed by MIP-0002); 4 for the shared cache (`ARCHITECTURE.md` §11 "Harden & calibrate") |
| **Related** | `ARCHITECTURE.md` §7 (Overpass fair use), §9 (no caching, Overpass slowness), `SCALA3-JDK-REVIEW.md` §3 (virtual threads), MIP-0002 (the consumer) |
| **Effort** | M — one cache trait + decorator, concurrent fan-out; local file cache, no new module |
| **Gain** | infra/dev-loop (unblocks MIP-0002 adoption); cost/ops (less Overpass/Open-Meteo load, fewer 429s) |
| **Effort vs Gain** | do next — moderate effort, and MIP-0002 cannot ship to real users without it |
| **Depends on** | none technically; MIP-0002 is the consumer that makes it worth building now; Phase 1 (Phase 4 for the shared/Azure cache); no Azure resource in v1 |
| **Risk** | coarse-tile caching or a stale-served forecast could quietly mislead near a tile edge |
| **Cost so far** | — (nothing merged beyond the design doc, same untracked commit as MIP-0002) |

## 1. Summary

A marola answer today costs one 30-45s Overpass query, twelve sequential Open-Meteo calls and one
IMA download, every time, for every user, even for the same beach twice in a minute. That is fine
for one developer and fatal for adoption. This MIP adds three things, in order of payoff: a local
cache with per-source TTLs, concurrent forecast fetches, and a precomputed "board" for the places
people actually ask about — targeting a warm reply in under three seconds and a cold one under
fifteen, while *reducing* load on the free upstreams marola depends on.

## 2. Motivation

Measured on 2026-09-05 (`RUN-LOCALLY.md` §4): 35-47s per run, of which ~30s is Overpass's
relation query and ~5s is twelve serial Open-Meteo round-trips. Overpass also rate-limits per IP
(two slots) — ten users would hit 429s within a minute (`ARCHITECTURE.md` §9). Beaches do not move;
forecasts change hourly; water quality changes weekly. Re-fetching all three per request is waste
that gets marola throttled and users bored.

## 3. User-visible change

None in content. The `origin ->` line gains `cache: beaches hit (2d old), forecast miss, water hit`
so slow replies are explainable, and the bot's typing indicator lasts seconds, not the better part
of a minute.

## 4. Data sources and dependencies reviewed

- **No new upstream.** This is about calling the existing ones less.
- **Overpass fair-use policy** (`https://wiki.openstreetmap.org/wiki/Overpass_API`): asks for
  caching and modest rates; self-hosting is the recommended path for real traffic. A 30-day beach
  cache keyed by a coarse tile is squarely what they ask for.
- **Open-Meteo terms**: free non-commercial, 10,000 requests/day guideline. Forecasts are issued
  hourly; caching one hour per beach is lossless.
- **IMA feed**: weekly-to-monthly data; one download per day is generous.
- **Storage**: local = JSON files under `data/cache/` (already the pattern for the sighting store
  and the RAG index); Azure opt-in = Cosmos DB (already a dependency) or Azure Cache for Redis
  (not adopted — a new paid resource; `AGENTS.md` cost rule). Nothing new for the local path.

## 5. Design

### 5.1 `core/cache/Cache` — one trait, per-source TTL

```scala
trait Cache:
  def get[A](key: String, maxAge: Duration)(decode: String => A): Option[A] < Sync
  def put(key: String, value: String): Unit < Sync
```

`FileCache(dir)` (local default, one file per key, mtime = age) and `CosmosCache` (opt-in). Keys:

| Source | Key | TTL | Why |
|---|---|---|---|
| Overpass beaches | `beaches/<lat1>_<lon1>_<radius>` with lat/lon rounded to 0.05° (~5km tile) | 30 days | beaches don't move; the tile makes nearby origins share |
| Open-Meteo weather+marine | `forecast/<beach lat>_<lon>/<yyyy-MM-ddTHH>` | 1 hour | issued hourly |
| IMA points | `water/ima-sc/<yyyy-MM-dd>` | 1 day | weekly data at best |
| IP geolocation | `ip/<public-ip>` | 1 day | same machine, same city |

Wrapped where the calls are made (`BeachFinder.nearby`, `OpenMeteoClient.forecastFor`,
`ImaScWaterQualityClient.samplingPoints`, `IpGeolocation.locate`) via a `Cached(cache)` decorator,
so `Recommender` doesn't know. A miss that then fails upstream serves a *stale* entry if one exists
(up to 7 days for beaches, 6 hours for forecasts) with a note — better a slightly old forecast than
"Overpass is down".

### 5.2 Concurrent forecast fetches

`Recommender.traverse` runs the six beaches serially by design (only `map`/`flatMap` were verified
against the pinned Kyo). `Async.foreach`/`Async.collectAll` are confirmed present in
`kyo-core:1.0.0-RC5` (`SKILLS.md` Stage 6): fan the six `scoreTomorrow` calls out, bounded to 4 in
flight (Open-Meteo is free; be polite). With virtual-thread workers the blocking `java.net.http`
calls stop pinning (`SCALA3-JDK-REVIEW.md` §3). Expected: 12 serial calls → ~3 rounds.

### 5.3 Precomputed boards (Phase 4)

For the top-N origin tiles by request count (a JSON-lines counter, no PII), a `just board` task —
or the bot's own scheduler — recomputes the ranked list every hour and stores it under
`boards/<tile>/<hour>`. A request from a hot tile is then a cache read: sub-second. This is also
the substrate for MIP-0004's daily digest.

### 5.4 Rate limiting at the edge

Token bucket per chat (MIP-0002) and a global bucket per upstream (Overpass: 1 query / 20s;
Open-Meteo: 5/s) so a burst degrades to "queued" rather than to a 429 that poisons every user.

## 6. Scoring / safety impact

None. A stale-served forecast is flagged in the notes ("forecast from 14:00, upstream unavailable")
and stale water data already follows MIP-0001's 45-day rule.

## 7. Verification plan

- Unit: `FileCache` TTL/eviction on a temp dir; the `Cached` decorator with a `ReplayTransport`
  proving the second call makes **zero** HTTP requests (extend `PipelineGoldenSpec`'s call-count
  test: warm run = 0 upstream calls).
- Latency budget test: golden suite measures wall-clock of the fan-out with an artificial 200ms
  transport delay — serial ≈ 2.4s, concurrent ≈ 0.6s; assert the ratio.
- Live: `just run` twice; second `origin ->` line shows all hits and completes < 3s.
- Upstream courtesy: after a day of bot use, the counter shows ≤ 1 Overpass query per tile per
  month.

## 8. Risks, limitations, and honest caveats

- **Stale beaches**: a newly mapped beach appears up to 30 days late. Acceptable; `--no-cache`
  flag for the impatient.
- **Disk growth**: forecasts per beach per hour — cap the cache dir at, say, 200MB with oldest-first
  eviction; note it in `.gitignore` (already covers `data/`).
- **Coarse tiles**: two origins 5km apart share a beach list computed from the tile centre — the
  distance column is recomputed per origin, so ranking stays right; only the *set* of candidates is
  shared. Fine at 15-20km radii.
- **Concurrency + Overpass**: never parallelise Overpass; it's the one upstream that punishes it.

## 9. Alternatives considered

- **Self-hosted Overpass**: the "proper" fix, and a 100GB+ planet import. Later, if usage
  justifies it; caching first.
- **SQLite instead of files**: nicer queries, one more dependency, no need yet.
- **Redis**: right tool for a multi-instance bot; wrong tool for a laptop. Opt-in path noted.

## 10. Exam-coverage mapping

AI-103 §1 "plan resource requirements / manage costs": cache hit rate vs. upstream calls is the
cost model for the eventual Azure deployment. AI-500 §3 "monitor": the cache/latency counters are
the first operational metrics.

## 11. Open questions

1. Tile size: 0.05° (~5km) vs 0.1° (~10km). Smaller = more misses, larger = more shared lists.
2. Should the board precompute *with* the LLM summary (one call per tile per hour) so bot replies
   include it instantly? Yes if a GPU host exists; otherwise on demand only.
3. Where does the request counter live once there are two processes (CLI + bot)? File with
   append is fine until Phase 3.
