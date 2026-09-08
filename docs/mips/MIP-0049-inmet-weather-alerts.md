# MIP-0049: INMET weather alerts — the official warning feed, matched to a beach by polygon

| | |
|---|---|
| **Status** | Draft |
| **Author** | Claude (Opus 5), with M.Hoffmann |
| **Created** | 2026-09-08 |
| **Phase** | 1 (`ARCHITECTURE.md` §11) — a keyless public feed, no Azure, no Phase 2 prerequisite |
| **Related** | MIP-0001 (water quality: the same "an agency already decided this, don't re-derive it" shape), MIP-0042 (map v2), MIP-0046 (icon set the alert control must borrow from), `docs/FUTURE-WORK.md` |
| **Effort** | M — one `core/alerts` package (client, CAP parser, point-in-polygon), one field on `Board`, one UI control. No new dependency: CAP is XML and marola already has an HTTP/JSON layer; the polygon test is ~20 lines of Scala |
| **Gain** | `user value` — an official red/orange warning is the one thing that should outrank a good swim score, and marola currently cannot see it; `exam coverage (AI-103 §2 "ground a model in your own data")` — alerts become retrievable context, not model memory |
| **Effort vs Gain** | `do next` — self-contained, keyless, and it is the only safety-relevant input marola has no channel for. Land the ingestion + CLI before the UI control, so the map work can follow MIP-0046's icon set instead of inventing one |
| **Depends on** | Nothing blocks the ingestion half: the feed is public and needs no key, so `AGENTS.md`'s Phase-1 and cost gates do not apply. The UI half should follow MIP-0046 (the alert control must use its icon set, not a new emoji), and it shares `site/static/app.js` and `core/site/Board.scala` with MIP-0042 — coordinate, do not merge blind |
| **Blocked by** | none |
| **Risk** | The alert areas are meteorological polygons covering whole mesoregions. A "Perigo Potencial" over five Amazonas regions is true for a beach inside it and still nearly meaningless *for swimming there* — showing it prominently trains users to ignore the control, which is worse than not having it |
| **Cost so far** | — |

## 1. Summary

marola scores a swim from sea temperature, wind, waves and bathing-water class, and is blind to
the one signal a Brazilian already trusts: INMET's official weather warning. This adds a keyless
ingestion of INMET's national alert feed, matches each alert to a beach by point-in-polygon
against the alert's own CAP geometry, and surfaces active alerts on the map as a ranked list with
hover detail — the same idiom as the ranked beaches. Alerts never silently change a swim score;
they are shown, sourced and dated.

## 2. Motivation

Every number in a marola reply is a measurement. None of them is a *warning*. Today a beach can
score well on an afternoon INMET has under a `Grande Perigo` storm advisory, and marola will say
so cheerfully, because nothing in the pipeline has ever seen the advisory.

This is also the shape MIP-0001 already established and validated: an agency has done the
classification (CONAMA 274/2000 for water), and marola's job is to carry that verdict accurately,
not to re-derive it. INMET is the same case — it is the national meteorological authority, its
warnings are the ones civil defence acts on, and re-deriving "is this dangerous" from raw
Open-Meteo numbers would be both worse and out of policy (`AGENTS.md`: safety-relevant logic is
deterministic, and unsourced facts do not reach a user).

Nationwide matters. marola's areas are Santa Catarina today, but `site/areas.json` is a list, and
INMET covers all of Brazil from one feed — so this is built per-coordinate, never per-region.

## 3. User-visible change

CLI, when an alert covers the beach:

```
Joaquina — best hour tomorrow 09:00 (score 78)
  sea 21.4 °C · wind 12 km/h SW · waves 1.1 m · water PRÓPRIA (sampled 2026-09-05)

  ⚠ INMET — Chuvas Intensas · Perigo · 11/09 09:34 → 11/09 23:59
    Chuva entre 20 e 30 mm/h ou até 50 mm/dia, ventos intensos (40-60 km/h).
    Source: https://avisos.inmet.gov.br/55649
```

With no alert, one line so absence is legible rather than ambiguous:

```
  ⚠ INMET — no active warning for this area
```

Map: a control in the same family as the ranked beach list — a collapsed row per active alert,
ordered by severity then onset, each hovering to the full INMET text and its dates. The polygon
draws on the map when a row is hovered, so "who does this cover" is answered visually rather than
by reading five mesoregion names.

## 4. Data sources and dependencies reviewed

All three probed live on 2026-09-08 from this session; see the Appendix for exact returns.

**4a. `GET https://apiprevmet3.inmet.gov.br/avisos/rss` — RSS 2.0 (the pick, as the anchor).**
200, `application/rss+xml`, 160 320 bytes, 89 `<item>`s. `<channel><description>` is "Avisos
atuais na América do Sul"; `<copyright>public domain</copyright>`, and an inline licence comment:
"O conteudo deste site, podera ser reproduzido desde que citada a fonte" — reproduction permitted
**with attribution**, which is why every rendering above carries a `Source:` line.

The catch that decides the client design: **INMET returns no HTTP response at all to curl's
default User-Agent.** TCP connects and the TLS handshake completes, then nothing, until timeout.
With a browser UA the same URL is an immediate 200. Any client marola writes must send a real
User-Agent or it will look like INMET is down.

Per item: `title`, `link`, `guid`, `pubDate`, and a `description` that is an HTML `<table>` inside
CDATA (Status, Evento, Severidade, Início, Fim, Descrição, Área, Link Gráfico). **No geographic
data in the RSS** — `Área` is prose naming mesoregions ("Centro Amazonense, Sudoeste
Amazonense, ..."). Parsing that table would be scraping, and it is unnecessary:

**4b. `GET https://apiprevmet3.inmet.gov.br/avisos/rss/<id>` — CAP 1.2 (the pick, for content).**
Each RSS `<link>`/`<guid>` resolves to a full OASIS CAP 1.2 document
(`urn:oasis:names:tc:emergency:cap:1.2`), 8 973 bytes for alert 55649. It carries the fields the
RSS table only renders: `event`, `severity`, `urgency`, `certainty`, `onset`, `expires`,
`description`, `instruction`, `web`, and — the reason this MIP is tractable —
`<area><areaDesc>` plus a **`<polygon>` of 291 `lat,lon` pairs**. Real geometry, so beach matching
is exact point-in-polygon, not geocoding of region names.

Note the severity vocabulary differs between the two: RSS says `Perigo Potencial` / `Perigo` /
`Grande Perigo` (80 / 8 / 1 of the 89 live items), CAP says CAP-standard `Moderate` / `Severe` /
`Extreme`. Alert 55649 is `Perigo Potencial` in RSS and `Moderate` in CAP. Keep INMET's Portuguese
wording for display, map to the CAP enum for ordering.

**4c. `GET https://apiprevmet3.inmet.gov.br/avisos/ativos` — JSON (rejected as primary, kept as
optional enrichment).** 200, `application/json`, 419 178 bytes, split `{"hoje": [...], "futuro":
[...]}`. Strictly richer than either of the above: `poligono` is already **GeoJSON**
(`{"type":"Polygon","coordinates":[...]}`), `municipios` carries **IBGE codes**
("Aracruz - ES (3200607)"), plus `estados`, `mesorregioes`, `microrregioes`, `geocodes`,
`riscos`, `instrucoes`, and `aviso_cor` (`#FFFE00`) — a severity colour INMET itself uses.

It is not the primary source because it is **undocumented**: no INMET page found in this session
describes it, so its shape can change without notice, whereas RSS 2.0 + CAP 1.2 are published
standards INMET is committed to. Used as a best-effort enrichment (municipality names, the
official colour) it can fail without taking the feature down.

**4d. Backfill — viable, by bounded id walk.** There is no archive endpoint (`/avisos` and
`/avisos/todos` both 404). But alert ids are dense and monotonic in time, and old ones stay
served: id 55000 → `sent` 2026-07-15, 50000 → 2025-02-28, 40000 → 2022-08-29, all 200 with full
CAP. id 1000 returns 500. So history back to at least **2022** is reachable by walking ids
downward from the newest, and each fetch is a few KB.

## 5. Design

New package `core/src/main/scala/marola/alerts/`, following the `water/` shape exactly — a pure
model, a trait, one keyless implementation:

```scala
package marola.alerts

enum AlertSeverity derives CanEqual:      // CAP's ordering, INMET's words kept for display
  case Extreme, Severe, Moderate, Unknown

final case class AlertArea(description: String, polygon: List[Coordinates])

final case class WeatherAlert(
    id: String,                    // INMET's own, e.g. "55649" — the dedupe key for backfill
    event: String,                 // "Chuvas Intensas"
    severityLabel: String,         // "Perigo Potencial" — INMET's Portuguese, shown verbatim
    severity: AlertSeverity,       // CAP's enum, used only for ordering
    onset: Instant,
    expires: Instant,
    description: String,
    instruction: Option[String],
    areas: List[AlertArea],
    source: String                 // https://avisos.inmet.gov.br/<id> — the attribution licence requires
):
  def activeAt(t: Instant): Boolean = !t.isBefore(onset) && !t.isAfter(expires)
  def covers(c: Coordinates): Boolean = areas.exists(a => Geo.contains(a.polygon, c))

trait AlertSource:
  def activeAlerts: List[WeatherAlert] < (Async & Abort[AlertError])
```

- `InmetAlertClient` (in `local/`, the keyless default — no Azure counterpart is proposed, and
  none is needed): fetches the RSS index for ids, then each CAP document. **Sends an explicit
  User-Agent** (§4a) and treats a missing one as the first thing to check on failure.
- `CapParser` (pure, in `core`): CAP XML → `WeatherAlert`. Unit-tested against the real 55649
  document, checked in as a fixture.
- `Geo.contains` (pure, in `core`): standard ray-casting point-in-polygon. ~20 lines, no
  dependency, exhaustively testable — and it is safety-relevant, so it is plain Scala and never
  model output.
- `Board`: one new field `alerts: List[WeatherAlert] = Nil`, rendered by `Board.alertJson` beside
  the existing `waterJson`/`trailJson`. Defaulted so an older board still deserialises, the same
  precedent `trails` set in MIP-0030.
- `Main` / MCP server: alerts appear in `--summarize` output and as a field on the existing MCP
  tools rather than a new tool.

**Backfill** is a separate, offline path — `scripts/inmet_backfill.py`, stdlib-only with a
`--self-test`, walking ids downward and writing one JSON per alert. It is not on any request path
and not in CI: it is a one-off corpus builder whose output feeds MIP-0048's training data and
answers "how often is this beach actually under a warning", which no live feed can.

**Nothing here goes through the LLM.** The alert text is INMET's, shown verbatim with its source
URL, per `AGENTS.md`'s no-unsourced-facts rule. The LLM may *mention* an alert in a summary only
by quoting the text it was given, exactly as it already does for water-quality verdicts.

## 6. Scoring / safety impact

**`Swimability.score` does not change.** This is deliberate and is the main design decision in
this MIP.

An INMET polygon routinely spans several mesoregions; "Chuvas Intensas, Perigo Potencial" over
five Amazonas regions says almost nothing about whether the surf at one beach is swimmable in a
given hour. Folding that into a numeric score would corrupt a number built from measurements at
the beach with a warning about an area the size of a country.

Instead, alerts are a **separate, always-shown channel**: displayed with severity, window, text
and source, ranked above the score in the UI, and never silently arithmetic. If experience shows
`Grande Perigo` / `Extreme` should suppress a recommendation outright, that is a follow-up MIP
with its own threshold discussion — not a coefficient smuggled in here.

## 7. Verification plan

Unit tests (all offline, against checked-in fixtures captured this session):

- `CapParserSpec` — parses real alert 55649: event, both severity vocabularies, onset/expires,
  the 291-point polygon, and the `web` source URL.
- `CapParserSpec` — a CAP document with no `<polygon>` yields an alert with an empty area rather
  than throwing, and such an alert never `covers` anything.
- `GeoSpec` — point-in-polygon: inside, outside, on a vertex, on an edge, and a point whose
  latitude matches a vertex exactly (the classic ray-casting off-by-one).
- `GeoSpec` — a Joaquina coordinate against alert 55649's real Amazonas polygon is **not**
  covered; a coordinate inside it is. This is the test that would catch an inverted lat/lon,
  which is the likeliest silent bug in the whole MIP.
- `WeatherAlertSpec` — `activeAt` at onset, at expires, and one second outside each.
- `BoardSpec` — a board with no `alerts` key still deserialises (the MIP-0030 precedent).

Live checks (`just e2e`, skipped gracefully when the network is unavailable, as `E2ESpec`
already does):

```
sbt "cli/run -- --summarize"           # an alert line, or the explicit "no active warning"
scripts/inmet_backfill.py --self-test
scripts/inmet_backfill.py --from 55649 --count 10 --out .tmp/inmet   # bounded, no full walk
```

**Done** = a beach inside a live INMET polygon shows the alert with its source URL; a beach
outside shows the explicit no-warning line; `just build && just test && just quality` green; and
the map control renders at 390 px and 1280 px, not only in the stub harness (MIP-0046's standard).

## 8. Risks, limitations, and honest caveats

- **Area coarseness is the real limitation** (see the Risk field). Mitigation: always print the
  alert's own `areaDesc` and draw its polygon, so the user can see the warning covers half a
  state. Never paraphrase it as "there is a storm at Joaquina".
- **The User-Agent trap** (§4a) will read as an INMET outage to anyone who has not hit it. It is
  in the client's comment and in this section for that reason.
- **The undocumented JSON endpoint may vanish.** It is enrichment only; losing it costs
  municipality names and a colour, not the feature.
- **Backfill is an id walk, not an archive.** Ids may have gaps and the 500 floor was found by
  bisection, not documentation. The script must tolerate both and never assume density.
- **Timezones.** CAP timestamps are `-03:00`; marola's hourly scoring is local. Parse the offset,
  never assume it.
- **Not a civil-defence channel.** marola shows INMET's warning; it is not an alerting system and
  must not imply it. The UI says INMET and links INMET, and the safety footer stays.

## 9. Alternatives considered

- **Do nothing.** Loses the only official safety signal available for free, in a product whose
  whole output is a swim recommendation. Rejected.
- **Derive warnings from Open-Meteo numbers we already fetch.** No new source, but marola would
  be inventing a warning threshold and presenting it with authority it has not earned — against
  the no-unsourced-facts rule, and worse than INMET's own meteorologists. Rejected.
- **Scrape the RSS `description` table.** Avoids a second request per alert, but yields prose
  region names and no geometry, so matching would need a gazetteer marola does not have. The CAP
  link is right there. Rejected.
- **Use the JSON endpoint as primary.** Tempting — GeoJSON and IBGE codes for free — but
  undocumented, and this MIP's whole point is a channel that stays correct unattended.
  Kept as enrichment.
- **Alerts folded into the score.** Rejected in §6.

## 10. Exam-coverage mapping

- AI-103 §2 "Implement knowledge/grounding — ground a model in your own data": alerts are
  retrieved, attributed context, the same pattern as `knowledge/`, not model memory.
- AI-500: none directly. A future escalation agent that *acts* on a `Grande Perigo` alert would
  land squarely in `docs/AI-500-MAPPING.md` §4's human-confirmation rules — noted, not proposed.

## 11. Open questions

- Should `Extreme` / `Grande Perigo` suppress a swim recommendation outright rather than sit
  beside it? §6 argues no for now; it needs a human call, and it is a safety threshold, so it
  should not be decided by whoever implements this.
- Polling cadence. `pubDate` moves per publication, not on a fixed schedule; the site build is
  3-hourly, which may be too coarse for a same-day warning. Needs one observation window before
  a number is picked.
- Is `avisos.inmet.gov.br` (the human page each alert links to) stable enough to be the `Source:`
  URL shown to users, or should it be the API URL? The human page is friendlier; unverified
  whether old ids stay reachable there as they do on the API.
- **Follow-up MIP:** the `/avisos/ativos` JSON carries IBGE municipality codes, which would let
  marola answer "which beaches in this municipality" without any Overpass query. That is a
  gazetteer capability well beyond alerts and wants the next MIP number, not a section here.

## Appendix

### Checked live

All 2026-09-08, from inside `just jail-claude`, browser User-Agent unless stated.

- `https://apiprevmet3.inmet.gov.br/avisos/rss` — **200**, `application/rss+xml`, 160 320 bytes,
  89 `<item>`. `<copyright>public domain</copyright>` + inline licence comment requiring
  attribution. Severity counts: `Perigo Potencial` 80, `Perigo` 8, `Grande Perigo` 1. Events seen:
  Baixa Umidade 34, Tempestade 32, Chuvas Intensas 12, Vendaval 3, Acumulado de Chuva 1, Geada 1,
  Declínio de Temperatura 1.
- Same URL with **curl's default User-Agent** — no HTTP response; TLS handshake completes
  (`Client hello` … both `Finished`), then timeout. `http://portal.inmet.gov.br/` → 302,
  `https://portal.inmet.gov.br/` → 500 with a browser UA. DNS resolves; `example.com` → 200 from
  the same shell, so this is INMET-side, not the sandbox.
- `https://apiprevmet3.inmet.gov.br/avisos/rss/55649` — **200**, `text/xml`, 8 973 bytes, CAP 1.2.
  `event` Chuvas Intensas, `severity` Moderate, `urgency` Future, `certainty` Likely, `onset`
  2026-09-11T09:34:00-03:00, `expires` 2026-09-11T23:59:00-03:00, `areaDesc` naming five Amazonas
  mesoregions, `<polygon>` with **291** `lat,lon` pairs.
- `https://apiprevmet3.inmet.gov.br/avisos/ativos` — **200**, `application/json`, 419 178 bytes,
  keys `hoje` (3 records) / `futuro`. Record fields include `poligono` (GeoJSON Polygon),
  `municipios` with IBGE codes, `estados`, `mesorregioes`, `microrregioes`, `geocodes`,
  `severidade`, `aviso_cor` (`#FFFE00`), `riscos`, `instrucoes`, `data_inicio`/`data_fim`.
- Backfill probe — `/avisos/rss/{55649,55000,50000,40000}` all **200**; `sent` = 2026-09-08,
  2026-07-15, 2025-02-28, 2022-08-29 respectively. `/avisos/rss/1000` → **500**.
  `/avisos` → 404, `/avisos/todos` → 404.

### Not checked

- No INMET documentation page for any of these endpoints was located this session; the licence
  text quoted is the one embedded in the RSS itself, not a terms-of-use page. Whether INMET
  publishes rate limits is **unknown** — the backfill script must be conservative on that basis,
  not on a measured limit.
- Whether the CAP `identifier` (`urn:oid:2.49.0.0.76.0.2026.28220.1`) is stabler than the numeric
  id for dedupe. The numeric id is proposed because it is what both the RSS and the URL use.
- Whether `avisos.inmet.gov.br/<id>` (the human page) serves old ids — only the API path was
  probed for history.
- Whether alert ids are globally dense or have large gaps; only five ids were sampled.
- Nothing in §5 has been built or compiled. The Scala in this MIP is a design sketch, written
  against the `water/` package's shape, not code that has run.
