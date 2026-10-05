# MIP-0034: RSS in and RSS out — operational alerts, a curated reading queue, and marola's own Atom feed

| | |
|---|---|
| **Status** | Draft |
| **Author** | Claude Opus 5, for M. Hoffmann (request of 2026-09-07, verbatim: "create a MIP to implement RSS feeds into marola (to knowledge base), and which specific media formats (youtube, medium, stack) we could use to transform RSS feed into a marola direct feed... think in other ideas for RSS feeds, marola could have its own feed in the website") |
| **Created** | 2026-09-07 |
| **Phase** | 0 (`--ask`, the corpus tooling, the static site — all three arms). §5.2's alert banner reaches Phase 1 users for free once MIP-0002's bot exists; nothing here requires Phase 2 or any cloud resource |
| **Related** | marola-site spec 001 and §5.2a (the alerts archive page, amended 2026-10-05), MIP-0075 (the store it writes to), MIP-0001 (the curated-corpus + "no unsourced fact reaches a user" rule this MIP refuses to relax), MIP-0022 (the `knowledge/safety/` directory convention — respected, not redesigned; merged as #195), MIP-0005 (`site/dist`, the 3-hourly rebuild the outbound feed rides), MIP-0019 + `scripts/arxiv_digest.py` (the Atom-parsing and flat-file-cache precedent this MIP copies rather than reinvents), MIP-0018 (Substack/blog exporter — the *outbound-to-platforms* half; this MIP is the *feed* half and defers to it), MIP-0031 (the "the agency publishes no machine-readable feed" finding this MIP partially overturns for INMET), MIP-0033 (Release 0 — a public repo is what makes an outbound feed worth having), `FUTURE-WORK.md` §9.1 (RAG grounding) and §9.2 (proactive hazard detection — deliberately *not* built here) |
| **Effort** | S/M/L per §5 item — §5.6 outbound Atom is S (one pure serializer + one `SiteBuilder` write); §5.3 reading queue is S/M (one Python script cloned from `arxiv_digest.py`); §5.1 + §5.2 inbound alerts are M/L (a new `core/feeds` parser, a `core/alerts` client, an area-name mapping table, and a new user-visible surface) |
| **Gain** | user value (a live INMET coastal-wind/storm warning next to the swim score; a subscribable daily "best hour" feed); infra/dev-loop (a reading queue that feeds `corpus-doc` instead of ad-hoc browsing) |
| **Effort vs Gain** | `cheap win` for §5.6 (outbound feed) and §5.3 (reading queue) — both ship alone, neither touches scoring; `do next` for §5.1/§5.2 (INMET alerts) once someone wants the banner; `park` for §5.5's transcript pipeline and §5.7c's alert feed; **reject** for auto-ingesting any feed into `knowledge/` (§9) |
| **Depends on** | Nothing blocking. MIP-0022 already merged (#195), so `knowledge/safety/` and `CorpusChunk.safety` exist — §5.4's rule is written against the real loader, not a proposal. §5.6 assumes MIP-0005's `site/dist` build, which is Implemented. No Phase 1 gate (the CLI and the site are Phase 0 surfaces), no paid cloud resource, no API key anywhere in this MIP |
| **Blocked by** | none |
| **Risk** | Feed rot that looks like health: a subscribed feed keeps returning HTTP 200 long after it stops being maintained (verified — NOAA's own `NOAAVisualizations` YouTube feed returns 200 with 15 entries whose newest is **2017-02-17**), so an ingestion path that trusts "the fetch worked" silently ages. The second risk is scope: an alert path that starts as a display banner and drifts into changing `Swimability.score` without a MIP of its own |
| **Cost so far** | — |

## 1. Summary

Three separable things, deliberately not one pipeline. **In:** marola subscribes to a small
allow-list of feeds, split by *what kind of thing the item is*: perishable operational warnings
(INMET's live South-America `avisos` RSS, which covers all three `site/areas.json` areas and
carries a real `Início`/`Fim` validity window) become a deterministic, expiring alert banner and
**never** a corpus document; durable explanatory articles become a *reading queue* that a human
turns into a `knowledge/*.md` document through the existing `corpus-doc` skill, one reviewed PR at
a time. **Out:** `just site-build` writes an Atom feed of each area's daily best swimming hour to
`site/dist`, for free, on the 3-hourly rebuild that already runs. No feed item ever becomes corpus
text automatically, and nothing feed-derived ever lands in `knowledge/safety/`.

## 2. Motivation

Two concrete gaps, one opportunity.

`knowledge/README.md` states the corpus rule plainly: "a wrong sentence here becomes a confidently
wrong answer", and admits the existing six documents have not yet had their human
sentence-by-sentence check. Growing that corpus today means a human browsing for sources by hand.
A reading queue is the cheap half of that problem: finding candidates is automatable, *trusting*
them is not.

Second, marola tells a user the sea is fine at 07:00 tomorrow while INMET has an active
`Aviso de Ventos Costeiros` over `Metropolitana do Rio de Janeiro`, verified live on 2026-09-07,
85 active warnings, 5 of them coastal-wind. MIP-0031 concluded that Brazilian agencies publish no
machine-readable feed; that is true of INEA/INEMA's bathing-water bulletins and of the Marinha's
weather warnings (Cloudflare-blocked, §4.6), but **not** of INMET, which publishes RSS 2.0 marked
`<copyright>public domain</copyright>`. That is a free, keyless, local-first source marola is
currently ignoring.

Third: marola's site rebuilds every 3 hours (`.github/workflows/site.yml`, `cron: "15 */3 * * *"`)
and produces a board per area per day. An Atom file is ~40 lines of pure serializer over data that
already exists, the cheapest subscribable surface marola will ever have, and one that works
before MIP-0002's bot exists.

## 3. User-visible change

**Outbound (§5.6)**: a new file, and one `<link>` in the page head:

```xml
<!-- https://marola.dev/feed.xml (also data/<area>/feed.xml per area) -->
<entry>
  <id>tag:marola.dev,2026:floripa/2026-09-08</id>
  <title>Florianópolis, Mon 8 Sep — best hour 07:00 at Praia da Joaquina (82/100)</title>
  <updated>2026-09-07T06:15:00-03:00</updated>
  <published>2026-09-07T06:15:00-03:00</published>
  <link href="https://marola.dev/?area=floripa&amp;day=2026-09-08"/>
  <summary type="text">07:00, water 21.6 °C, wind 8 km/h offshore, waves 0.9 m.
    Bathing water: própria (IMA/SC, collected 2026-09-04). Whales: likely (season).
    Sources: OpenStreetMap/Overpass, Open-Meteo, IMA/SC.</summary>
</entry>
```

**Inbound alerts (§5.2)**: one deterministic line above the existing output, present only while
the warning is inside its own validity window:

```
$ just run
⚠️ INMET, active until 2026-09-07 11:00: Aviso de Ventos Costeiros (Perigo Potencial) —
   areas: Metropolitana do Rio de Janeiro. https://avisos.inmet.gov.br/55632

Best hour tomorrow: 07:00 at Praia Vermelha — 78/100
...
```

**Inbound reading queue (§5.3)**: developer-facing only, no user surface:

```
$ python3 scripts/feed_digest.py
noaa-nasa-eo      3 new  (2 ocean-relevant)
medium-tag-ocean  8 new  (0 ocean-relevant — link-only, see §5.5)
→ 5 candidates in .tmp/feed_cache/index.jsonl; none written to knowledge/ (by design)
```

## 4. Data sources and dependencies reviewed

All fetches below are from **2026-09-07**, from this machine, with `curl`; raw status/content-type
lines are in the Appendix.

### 4.1 INMET `avisos` RSS — **picked** (§5.2)
`https://apiprevmet3.inmet.gov.br/avisos/rss`. RSS 2.0, `application/rss+xml`, 150 KB, **85 live
`<item>`s**, `<language>pt-BR</language>`, `<copyright>public domain</copyright>` plus an inline
licence comment ("O conteudo deste site, podera ser reproduzido desde que citada a fonte"). Each
item carries a title (`Aviso de Ventos Costeiros. Severidade Grau: Perigo Potencial`), a stable
`<guid>` (`.../avisos/rss/55632`), a `pubDate`, and an HTML `<table>` in `<description>` with
`Status`, `Evento`, `Severidade`, `Início`, `Fim`, `Descrição`, and `Área`. Event types seen in
one snapshot: Baixa Umidade 33, Tempestade 29, Chuvas Intensas 11, **Ventos Costeiros 5**,
Vendaval 4, plus Geada/Declínio de Temperatura/Acumulado de Chuva. Coverage of marola's areas is
real: `Catarinense` 62 mentions, `Florianópolis` 20, `Sul Baiano` 44, `Metropolitana do Rio de
Janeiro` 17, `Metropolitana de Salvador` 1. **No key, no quota published, free.** The catch:
`Área` is a list of **IBGE mesoregion names, not coordinates or a polygon**: matching a warning to
a beach needs a hand-written mesoregion→`areas.json` table (§5.2, §11.2). Not verified: any rate
limit or terms-of-use page beyond the embedded copyright line.

**Revision, 2026-09-08: the mesoregion table is not needed.** A second probing pass (originally
drafted as a separate MIP before finding this one already owned the source, and folded in here
rather than given a number of its own) found three things that change §5.2's design:

- **Each item's `<guid>`/`<link>` resolves to a full OASIS CAP 1.2 document.**
  `GET https://apiprevmet3.inmet.gov.br/avisos/rss/55649` → 200, `text/xml`, 8 973 bytes,
  `urn:oasis:names:tc:emergency:cap:1.2`. It carries `event`, `severity`, `urgency`, `certainty`,
  `onset`, `expires`, `description`, `instruction`, `web`, `<area><areaDesc>`, and a
  **`<polygon>` of 291 `lat,lon` pairs**. Real geometry. So a beach is matched by point-in-polygon
  against the alert's own boundary, and `AlertAreas.byArea` (§5.2) is deleted rather than written:
  no hand-maintained table, no per-area entry when `areas.json` grows, and the match is exact
  instead of "this mesoregion name sounds like that area".
- **Severity has two vocabularies.** RSS says `Perigo Potencial` / `Perigo` / `Grande Perigo`
  (80 / 8 / 1 of 89 live items); CAP says `Moderate` / `Severe` / `Extreme`. Alert 55649 is
  `Perigo Potencial` in RSS and `Moderate` in CAP, the same alert. Show INMET's Portuguese
  verbatim, order by the CAP enum.
- **INMET returns no HTTP response at all to curl's default User-Agent.** TCP connects, the TLS
  handshake completes (`Client hello` … both `Finished`), then nothing until timeout. With a
  browser UA the same URL is an immediate 200. This is the first thing to check when the client
  appears to hang, and it will otherwise read as an INMET outage.

### 4.1a INMET `avisos/ativos` JSON — enrichment only, deliberately not primary
`GET https://apiprevmet3.inmet.gov.br/avisos/ativos` → 200, `application/json`, 419 178 bytes,
`{"hoje": [...], "futuro": [...]}`. Strictly richer than RSS or CAP: `poligono` is already
**GeoJSON**, `municipios` carries **IBGE municipality codes** ("Aracruz - ES (3200607)"), plus
`estados`, `mesorregioes`, `microrregioes`, `geocodes`, `riscos`, `instrucoes`, and `aviso_cor`
(`#FFFE00`), INMET's own severity colour. It is **not** the primary source: no INMET page
documenting it was found, so its shape can change without notice, whereas RSS 2.0 and CAP 1.2 are
published standards. Use it best-effort for municipality names and the official colour; losing it
must not take the alert path down.

### 4.1b Backfill — viable by bounded id walk, no archive endpoint
`/avisos` and `/avisos/todos` both 404. But ids are dense and monotonic in time and old ones stay
served: `/avisos/rss/55000` → `sent` 2026-07-15, `50000` → 2025-02-28, `40000` → 2022-08-29, all
200 with full CAP; `/avisos/rss/1000` → 500. So history back to at least **2022** is reachable by
walking ids downward from the newest, a few KB each. Only five ids were sampled; density and the
exact floor are **not** established (Appendix, "Not checked").

### 4.2 YouTube channel Atom — fetchable, but thin (§5.5)
`https://www.youtube.com/feeds/videos.xml?channel_id=<id>` returns 200 Atom, no key. Verified
against a real id resolved from a channel page (`UC-87aDLv5WFJ83fxt21gsEQ`, NOAAVisualizations):
**exactly 15 entries, no paging**, each with `yt:videoId`, title, link, `media:description`
(the video description) and `media:community` stats. **No transcript, no captions, no article
text.** The newest entry on that NOAA channel is dated **2017-02-17**, a live-looking feed for a
dead channel, which is §8's headline risk in one example. Getting usable *text* out of a video
needs either YouTube's unofficial `timedtext` endpoint or `yt-dlp` + local Whisper; see §5.5 and
§9 for why that is parked, not designed.

### 4.3 Medium RSS — **rejected for ingestion, link-only** (§5.5)
`https://medium.com/feed/tag/ocean` → 200, RSS 2.0, 10 items, `<description>` ~583 chars of
excerpt HTML, **no `<content:encoded>`**. `https://medium.com/feed/@<user>` → 200 and **does**
carry `<content:encoded>` (full post HTML). Decisive finding: `https://medium.com/robots.txt`
names `ClaudeBot`, `GPTBot`, `Bytespider`, `Applebot-Extended`, `meta-externalagent`,
`FacebookBot`, `GoogleOther` and `Amazonbot` under a `Disallow: /` block. Medium's publishers have
opted out of AI ingestion at the site level; posts are author-copyright with no blanket licence.
Feeding that text to marola's LLM as retrieval context is exactly what the block asks us not to do.
Medium stays in the reading queue as **title + link only**, never as stored body text.

### 4.4 "stack" — reading the request
The user wrote "youtube, medium, stack". **The plausible reading is Substack**, because the other
two are publishing platforms and because MIP-0018 §5 already names Substack as a marola export
destination. This MIP adopts that reading and says so rather than guessing silently. Substack
verified: `https://<pub>.substack.com/feed` → 200, `application/xml`, RSS 2.0 with `content:encoded`
full text (checked against a large public newsletter, 1.2 MB). Same copyright position as Medium
(author-owned, no blanket licence) → same verdict: link-only inbound; the interesting Substack
direction is **outbound**, and that is MIP-0018's, not this MIP's.
**The alternate reading, Stack Overflow / Stack Exchange, was checked and fails anyway**:
`https://stackoverflow.com/feeds/tag/scala` returns **HTTP 403** with a Cloudflare "Just a moment…"
interstitial, both with a default and with a desktop-browser User-Agent. Even if it were
reachable, the content is CC BY-SA (a share-alike obligation on anything derived from it), and
is programming Q&A, not ocean knowledge. Rejected on all three grounds.

### 4.5 NASA Earth Observatory RSS — accepted into the reading queue (§5.3)
`https://earthobservatory.nasa.gov/feeds/earth-observatory.rss` → 200, `application/rss+xml`,
512 KB, 10 items **with `content:encoded`** (full article text), covering marine heatwaves, algal
blooms, cyclones. Licence checked at NASA's open-data policy page: "data and information generated
under NASA sponsorship are available to all users", with citation encouraged: free and open, but
the page does **not** use the words "public domain", so this MIP does not claim that it does. Still
a reading-queue source, not an auto-ingest source: relevance to "can I swim at Joaquina" is
occasional, and §5.4's rule is not licence-dependent.

### 4.6 Checked and rejected as unreachable
- **Marinha do Brasil / CHM** (`marinha.mil.br/chm/...`, the authoritative Brazilian *marine*
  warnings — Avisos de Mau Tempo) → **HTTP 403**, Cloudflare interstitial, on both the warnings
  page and a speculative `/chm/rss`. This is the source marola would most want; it is not
  scriptable from a plain client today. Do not design around it (§11.3).
- **NOAA / NWS CAP** (`https://api.weather.gov/alerts/active.atom?area=FL`) → 200, valid Atom,
  well-formed CAP. Rejected on geography, not quality: it covers US waters only, and marola's
  areas are Florianópolis, Rio and Salvador. Naming NOAA marine advisories as an inbound source
  would have been a plausible-sounding mistake; it is recorded here so nobody re-proposes it.
- **`https://oceanservice.noaa.gov/rss/`** → **404**. A NOAA ocean-news feed may exist elsewhere;
  the URL commonly cited for it does not resolve (§11.4).

### 4.7 Dependencies
**None new.** Parsing is `java.xml`/`ElementTree` on the JDK and Python already in `flake.nix`;
fetching is marola's own `core/http`. `scripts/arxiv_digest.py` already parses Atom with
`xml.etree.ElementTree` and caches one JSON file per item plus a JSONL index; §5.3 clones that
shape rather than inventing one. No new library, no key, no cloud service, nothing paid.

## 5. Design

Six pieces. §5.1 is shared; §5.2, §5.3 and §5.6 are independently shippable (a later `mip-tasks`
pass can stack them in any order); §5.4 is a rule, not code; §5.5 is a verdict table.

### 5.1 `core/feeds` — one pure parser (shared)

```scala
package marola.feeds

final case class FeedItem(
    id: String,               // <guid> or Atom <id>; the dedup key
    title: String,
    link: String,
    published: Option[OffsetDateTime],
    summaryHtml: String,      // <description> / <summary>
    contentHtml: Option[String] // <content:encoded> / Atom <content>, when present
)

object FeedParser:
  /** RSS 2.0 and Atom 1.0 in one function; malformed items are dropped, never thrown. */
  def parse(xml: String): List[FeedItem]
  def stripHtml(html: String): String
```

Pure and unit-tested against **saved fixtures of the real feeds** under
`core/src/test/resources/feeds/` (`inmet-avisos.xml`, `youtube-channel.xml`,
`medium-tag.xml`, `nasa-eo.xml`), the same discipline `Corpus.chunkDocument` follows. Network
lives in the callers. An unparseable item is dropped and counted, not fatal: one bad item in an
85-item government feed must not take out the whole banner.

### 5.2 Inbound A — operational alerts (`core/alerts`), never corpus

```scala
final case class MarineAlert(
    id: String, event: String, severity: String,
    startsAt: OffsetDateTime, endsAt: OffsetDateTime,
    areas: List[String], link: String, source: String // "INMET"
)

trait AlertClient:                       // mirrors WaterQualityClient's shape
  def name: String
  def alertsFor(area: SiteBuilder.Area, now: OffsetDateTime): List[MarineAlert] < Sync

object InmetAlertClient extends AlertClient   // core/alerts/InmetAlertClient.scala

// §4.1's revision: geometry comes from the alert's own CAP document, so there is no
// mesoregion→area table to hand-maintain. `areaDesc` is kept for display only.
final case class AlertArea(description: String, polygon: List[Coordinates])
object Geo:
  /** Ray-casting point-in-polygon. Pure, ~20 lines, no dependency. Safety-relevant: plain
    * Scala, exhaustively unit-tested, never model output. */
  def contains(polygon: List[Coordinates], c: Coordinates): Boolean
```

- **Geometry, not names.** The RSS gives the item list; each item's link gives CAP 1.2 with a
  `<polygon>`, and a beach is in the alert iff `Geo.contains` says so. This replaces the
  hand-written `AlertAreas.byArea` the first draft needed, removes §11.2's maintenance question,
  and is what makes the path work for **all of Brazil**: a new entry in `areas.json` needs no new
  mapping row. A CAP document with no `<polygon>` degrades to an alert with an empty area that
  matches nothing, and its `areaDesc` is still shown.
- The RSS `<description>` HTML table is then only a **fallback** for fields CAP does not carry,
  not the parse path, deterministic either way, no LLM anywhere on this path.
- **Expiry is load-bearing.** An alert is shown iff `now` is within `[startsAt, endsAt]`. Nothing
  from this path is ever persisted into the knowledge index, precisely because the index has no
  concept of expiry: a cached "storm warning" answered three weeks later is the worst failure this
  MIP could produce.
- **Event allow-list**, not everything: `Ventos Costeiros`, `Vendaval`, `Tempestade`, and
  `Chuvas Intensas` are sea-relevant; `Baixa Umidade` and `Geada` are not and are filtered out
  (they are 34 of the 85 items in the verified snapshot).
- Rendered by the same deterministic-label rule as MIP-0009's markers: `Main`'s CLI block, the
  board JSON (a new optional `alerts` array, `Board.SchemaVersion` bumped to 2), and the map panel.
- **Not in the LLM summary prompt in v1.** The banner is printed by Scala above the summary. §11.5
  asks whether the summarizer should also be *told* about an active warning.

### 5.2a The alerts archive page (amendment, 2026-10-05)

The maintainer's decisions for marola-dev/marola-site#10, a list of past alerts each checked by
marola. The detailed design is marola-site's spec 001
([`specs/001-alerts/`](https://github.com/marola-dev/marola-site/pull/58)); its storage is the open
ocean data store of [MIP-0075](MIP-0075-water-quality-store-r2.md) and marola-oods spec 001.

- **Source**: INMET only (§4.1), read as CAP 1.2 by id for live and past alerts alike.
- **Per state**: alerts are ingested, stored and shown by state (IBGE code prefix of the alert's
  municipalities), one row per alert and state. RJ first; another state is a configuration value.
- **Every event**: §5.2's sea-relevant allow-list applies to the map banner only; the archive
  keeps every INMET event.
- **Storage**: two tables (`alert`, `alert_check`) in the DuckLake on Backblaze B2, partitioned by
  state and year, written by marola-app's `oods` module from a marola-oods workflow; one JSON
  export per state that the site build copies into `site/dist/alerts/`. The page fetches nothing
  but its own origin.
- **The check**: once an alert's window has closed, Open-Meteo's recorded rain, gusts, humidity
  and temperature at the alert's municipalities in that state are compared with the bounds INMET
  forecast. The verdict is `confirmed`, `not_confirmed` or `not_checkable`, stored with its
  evidence and a rule version, and shown as marola's, next to INMET's own words.
- **History**: kept forever, and backfilled from before September 2026 by walking INMET's ids
  (§4.1b).
- **Language**: the alert's text in Portuguese only, as INMET wrote it.
- **Where**: a new page, `alerts.html`, linked from the nav (MIP-0044 §11 updated).

### 5.3 Inbound B — the reading queue (`scripts/feed_digest.py`)

A near-clone of `scripts/arxiv_digest.py`, same flat-file cache convention (MIP-0017 §5.1):

- Sources come from a tracked allow-list, `knowledge/feeds.json`, safe to put there because
  `Corpus.load` reads only `*.md` in `knowledge/` and `knowledge/safety/` (verified in the merged
  #195 loader), so a JSON file next to the corpus is invisible to the index. Each entry:
  `{id, url, kind: "reading", licence, notes, store_body: false}`.
- Cache under `.tmp/feed_cache/` (gitignored): `items/<sha1-of-guid>.json` + `index.jsonl`,
  rewritten from the item files each run so it can't drift, `arxiv_digest.py`'s exact pattern.
- **`store_body` defaults to `false`.** For Medium/Substack sources it is forced false (§4.3/§4.4)
  and the cache keeps title, link, date and nothing else.
- Scores candidates by keyword overlap with marola's domain and prints them. **It writes nothing
  under `knowledge/`.** The output is a to-do list for a human running the `corpus-doc` skill,
  which then writes a normal `# Title` / `Source:` document citing the *original* source URL, not
  the feed, and not the aggregator.
- `--self-test` (offline, fixture-based) is added to `just quality-other` next to the other
  script self-tests.

### 5.4 The `knowledge/safety/` rule — feed content never lands there, automatically or otherwise

MIP-0022 (merged, #195) made a directory into a behavioural trigger: any answer grounded in a
document under `knowledge/safety/` gets the 193/192 emergency footer appended by `SafetyFooter`.
That means a file placed there **inherits emergency-grade authority** and gets quoted back to
someone who may be standing on a beach.

**Rule: no automated process writes to `knowledge/` at all, and `knowledge/safety/` additionally
requires the same human-authored `corpus-doc` PR it requires today.** Recommended: *no*, feed
content should never land there automatically. The human review gate is not a nice-to-have here.
Reasons, in order: (a) a feed item's provenance is the feed, not the fact: a syndicated summary of
a first-aid guideline is a copy of a copy; (b) feed items are undated in practice (a 2017 video
looks identical to a 2026 one through the feed, §4.2); (c) MIP-0001's rule that no unsourced fact
reaches a user is enforced today by a human writing each sentence, and there is no cheaper
mechanism that keeps it true; (d) the failure is asymmetric: a missed corpus addition costs
nothing, a wrong sting-treatment sentence with an emergency footer under it costs a person.
Enforced, not just written: `feed_digest.py --self-test` asserts the writer path is absent, and a
`quality-other` check fails if any file under `knowledge/` contains an `Ingested-from:` line
(the marker a future ingestion attempt would inevitably add).

### 5.5 Media-format verdicts (what "transform an RSS feed into marola content" is actually worth)

| Source | Fetchable text | Transform needed | Verdict |
|---|---|---|---|
| YouTube channel/playlist Atom | title + `media:description`, 15 items | transcript needs `yt-dlp` + local Whisper, or the unofficial `timedtext` endpoint | **Reading queue only.** The transcript pipeline is real work (audio download, ASR, summarisation, review) for material that is unsourced narration; **parked**, and it would need its own MIP |
| Medium tag feed | excerpt only | — | **Link-only.** `robots.txt` disallows AI crawlers (§4.3) |
| Medium user feed | full `content:encoded` | — | **Link-only**, same reason; being *able* to read it is not permission |
| Substack `<pub>/feed` | full `content:encoded` | — | **Link-only inbound**; outbound Substack publishing belongs to MIP-0018 |
| Stack Overflow / Stack Exchange | none, 403 | — | **Rejected** (§4.4): blocked, CC BY-SA share-alike, off-domain |
| INMET avisos | structured HTML table | label parsing (§5.2) | **Picked**, the only one that carries operational value |
| NASA Earth Observatory | full `content:encoded` | — | **Reading queue**, licence-clean, occasionally on-topic |

The honest summary: of the three media formats named in the request, **none is a good corpus
source**, and the one worth wiring up (a government warning feed) is not a media format at all.

### 5.6 Outbound — marola's own Atom feed (`core/site/SiteFeed.scala`)

```scala
object SiteFeed:
  val Id = "tag:marola.dev,2026"
  /** Pure: boards in, Atom XML out. No clock, no I/O — same contract as Board.build. */
  def atom(area: SiteBuilder.Area, days: List[JsonValue], generatedAt: OffsetDateTime): String
```

`SiteBuilder.build` writes `data/<area>/feed.xml` per area and a combined `feed.xml` at the root;
`site/static/index.html` gains `<link rel="alternate" type="application/atom+xml">`. One entry per
`(area, day)`: title = the day's best hour and beach with its score, summary = the same
deterministic facts the board already carries plus its `sources` line, verbatim. **No LLM text in
the feed**, so nothing unsourced is syndicated (MIP-0001's rule applies to a feed reader exactly as
it applies to the page).

The one real design point: the site rebuilds **8×/day**, so a naive builder would emit 8 entries
per day per area. Entry `id` is therefore keyed on `(area, day)` (`tag:marola.dev,2026:floripa/
2026-09-08`), so a rebuild *updates* an entry (new `<updated>`) instead of appending one, and a
reader shows one item per day per area. `<published>` is the first build of that day; retaining
the last 14 days keeps the file small and diffable.

### 5.7 Other outbound-feed ideas, evaluated

- **(a) Corpus changelog feed** (`/knowledge.xml`): one entry per `knowledge/*.md` document added
  or materially changed, generated from `git log` at site-build time, linking the document and its
  `Source:` URL. **Worth doing** (S): it is marola's provenance story made subscribable, it costs
  one `git log` call, and it becomes genuinely useful the moment MIP-0033 makes the repo public.
- **(b) "Notable conditions only" feed**: an entry only when something crosses a threshold (score
  ≥ 85, water declared *imprópria*, an active INMET coastal warning, whale season opening). Highest
  signal-per-item of anything here, and the closest thing to a push channel that needs no bot. But
  it is a **proactive/autonomous behaviour**: `AGENTS.md` requires an explicit
  human gate before marola volunteers a hazard judgement, and it depends on §5.2 landing.
  **Park**, with §11.6 as the question to answer first.
- **(c) MIP / release feed for build-in-public**: **rejected as redundant**: GitHub already serves
  `<repo>/releases.atom` (verified 200, `application/atom+xml`) and `commits/<branch>.atom`.
  MIP-0018's exporter is the right home for narrative posts.
- **(d) Podcast-style feed with a TTS reading of the daily summary**: **rejected**: needs TTS (a
  cost or a heavy local model), audio can't carry MIP-0001's `[n]` citations, and a spoken safety
  sentence loses MIP-0022's footer. Novelty over value.
- **(e) JSON Feed 1.1 alongside Atom** — ~20 extra lines, no consumer asked for it. **Defer**.

## 6. Scoring / safety impact

**No change to `Swimability.score` in any arm of this MIP.** §5.2's alert is a display banner,
deterministic, computed from the feed's own `Início`/`Fim` window and an event allow-list; it
ranks nothing and suppresses nothing. Making a warning *lower a score* is a separate decision that
needs its own MIP (§11.5). The mesoregion granularity (§4.1) is far coarser than a beach, and
silently down-ranking every beach in "Metropolitana do Rio de Janeiro" on a coastal-wind advisory
would be a scoring change disguised as a data feed.

Safety text: unchanged. MIP-0022's footer semantics are untouched; §5.4 exists specifically so
that nothing this MIP adds can put an unreviewed sentence behind that footer. The corpus is
byte-identical after any `feed_digest.py` run.

## 7. Verification plan

- `FeedParserSpec` (pure, fixtures of the four real feeds saved 2026-09-07): RSS 2.0 and Atom both
  parse; `content:encoded` is picked up when present and `None` when absent (the Medium tag-feed
  fixture proves the `None` branch); a truncated/malformed item is dropped without throwing; ids
  are stable across two parses of the same bytes.
- `InmetAlertSpec` (pure): the HTML-table fields parse into `MarineAlert`; an alert whose `Fim` is
  before `now` is excluded; one whose window contains `now` is included; `Baixa Umidade` is
  filtered by the event allow-list; an area with no mesoregion match yields no alerts.
- `AlertAreasSpec`: every id in `site/areas.json` has at least one mesoregion mapping, and every
  mapped name appears in the saved INMET fixture (catches a typo'd mesoregion name at build time).
- `SiteFeedSpec`: output parses as XML; two builds on the same day with different `generatedAt`
  produce **the same entry ids** and different `<updated>` (the 8×/day rule); `&`/`<` in a beach
  name are escaped; at most 14 days of entries.
- `BoardSpec`: `site/board.schema.json` gains an optional `alerts` array; the schema check that
  already exists must pass with and without it.
- `feed_digest.py --self-test` (offline) added to `quality-other`, plus the `knowledge/`
  `Ingested-from:` guard from §5.4.
- `CapParserSpec` (pure, added 2026-09-08 with §4.1's revision): the checked-in alert 55649
  document parses into `event`, both severity vocabularies, `onset`/`expires`, its 291-point
  polygon and its `web` source URL; a CAP document with no `<polygon>` yields an empty area rather
  than throwing, and never matches.
- `GeoSpec` (pure): point-in-polygon inside, outside, on a vertex, on an edge, and a point whose
  latitude exactly equals a vertex's (the classic ray-casting off-by-one). Plus the test that
  earns its keep: a Joaquina coordinate is **not** covered by 55649's real Amazonas polygon, and a
  coordinate inside it is: this is what catches an inverted lat/lon, the likeliest silent bug on
  this path.
- Live checks, run by hand before merging the implementation: `curl -sS -A "$(scripts/…ua)"
  https://apiprevmet3.inmet.gov.br/avisos/rss | head` (**a User-Agent is required, see §4.1**),
  `just site-build floripa && xmllint --noout site/dist/feed.xml`, and the feed pasted into one
  real reader.
- **Done** = the alert banner appears for a real active warning and disappears after its `Fim`;
  `feed.xml` validates and shows one item per area per day across two consecutive rebuilds;
  `git status` shows no change under `knowledge/` after a digest run.

## 8. Risks, limitations, and honest caveats

- **Feed rot that returns 200.** Verified live: a NOAA YouTube feed whose newest entry is from 2017
  still serves a healthy 200 with 15 entries. Any subscribed source needs a staleness check
  ("newest item older than N days") reported by `feed_digest.py`, or the reading queue quietly
  becomes a museum.
- **Mesoregion ≠ beach.** INMET areas are IBGE mesoregions; "Metropolitana do Rio de Janeiro" is
  the whole metro area. The banner must say *what area the warning covers*, in INMET's own words,
  and never imply beach-level precision. This is why §6 keeps it out of the score.
- **The best marine source is unreachable.** The Marinha/CHM Avisos de Mau Tempo (403, §4.6) are
  more authoritative for sea state than INMET's land-oriented warnings. marola will be showing the
  second-best warning source and should say "INMET" in the banner, not "official marine warning".
- **Babysitting cost is real.** Every added feed is a URL that can move, start requiring a UA, or
  fall behind Cloudflare; two sources checked for this MIP already have. Keep
  `knowledge/feeds.json` short; a source that breaks twice gets deleted, not fixed.
- **Licence and robots are not the same question as "can I fetch it".** Medium serves full text
  over `/feed/@user` while its `robots.txt` tells AI agents to stay out; this MIP treats the stated
  wish as binding.
- **An outbound feed is not an audience.** Realistically a handful of subscribers. Proposed
  because it is nearly free and the right shape (open, no account, no tracking), not because it
  will move numbers.

## 9. Alternatives considered

- **Do nothing.** Costs nothing and loses nothing today; the corpus grows by hand either way. It
  loses the INMET warning, which is the one piece here with real user value.
- **Auto-ingest feed items into `knowledge/` with an LLM summarisation + `Reviewer` pass.** The
  obvious reading of "RSS feeds into the knowledge base", and **rejected**: MIP-0001's rule is that
  curated text is written by a human with a source per entry, and `Reviewer` is a fact-check pass
  over marola's *own* generated summary, not a substitute for provenance. It would also put
  arbitrary third-party prose into a retrieval index the model answers from verbatim, the exact
  "wrong sentence = confidently wrong answer" hazard `knowledge/README.md` names.
- **Store feed bodies in the index with a freshness decay** instead of a hard corpus/alert split.
  Much more work: the index has no expiry, `FileKnowledgeStore` re-embeds on fingerprint change,
  and a decayed-but-present storm warning is still a retrievable sentence. §5's two-path split is
  the cheap version of the same goal.
- **A hosted aggregator (Feedly/Inoreader API).** An account, a key, a quota and a paid tier, to
  replace ~80 lines of `ElementTree`. Rejected against the local-first, keyless default rule.
- **Scrape Marinha/CHM with a headless browser** past Cloudflare. Rejected: it is an explicit
  anti-bot measure, adds a browser dependency, and breaks on their next change. Ask them for a
  feed instead (§11.3).
- **Only do the outbound feed**, skip inbound. Genuinely viable and the cheapest subset, which is
  why §5.6 is written to ship alone.

## 11. Open questions

1. **Confirm the "stack" reading.** This MIP assumes **Substack** (§4.4) and checked Stack
   Overflow anyway (403, CC BY-SA, off-domain, rejected either way). If Stack Exchange was meant
   as a *developer* knowledge source for marola's own docs rather than the ocean corpus, that is a
   different MIP. Proposal: Substack, link-only, outbound handled by MIP-0018.
2. **Who maintains the mesoregion→area table** (§5.2)? It is hand-written and IBGE names change
   rarely but not never. Proposal: unit-tested against the saved INMET fixture, refreshed when
   `AlertAreasSpec` fails, i.e. it breaks loudly, not silently.
3. **Ask the Marinha/CHM for a feed.** Worth one email before assuming 403 forever; their Avisos de
   Mau Tempo are the authoritative marine source (§4.6). Needs a human.
4. **Find NOAA's actual ocean-news feed URL.** `oceanservice.noaa.gov/rss/` is a 404 (§4.6). Low
   priority; NASA EO (§4.5) already covers the reading-queue need.
5. **Should an active alert reach the LLM summary prompt, or stay a Scala-printed banner?** (§5.2,
   §6). Proposal: banner only in v1; telling the model about a warning invites it to editorialise
   about safety, which MIP-0001/0022 exist to prevent.
6. **Does §5.7b's "notable conditions" feed clear the human gate on proactive behaviour?** It is
   marola volunteering a hazard judgement to a subscriber with no one in the loop. Needs a human
   decision before it is designed, not after.
7. **Follow-up MIP:** the `Corpus.load` loader reads `knowledge/*.md` and `knowledge/safety/*.md`
   only, one level, no recursion (verified in the #195 loader). Any future corpus organisation
   into topic subdirectories is a loader change with an index-fingerprint consequence, and it is
   out of this MIP's scope; it needs the next free MIP number if someone wants it.

## Appendix

### Checked live (all 2026-09-07, `curl` from this machine)

- `https://apiprevmet3.inmet.gov.br/avisos/rss` — **200**, `application/rss+xml`, 150173 bytes,
  85 `<item>`s, `<copyright>public domain</copyright>`, `<language>pt-BR</language>`. First item:
  `Aviso de Ventos Costeiros. Severidade Grau: Perigo Potencial`, `Início 2026-09-06 11:20:00.0`,
  `Fim 2026-09-07 11:00:00.0`, `Área` listing 12 mesoregions. Area-name counts across the
  snapshot: Catarinense 62, Sul Baiano 44, Florianópolis 20, Metropolitana do Rio de Janeiro 17,
  Metropolitana de Salvador 1. Event counts: Baixa Umidade 33, Tempestade 29, Chuvas Intensas 11,
  Ventos Costeiros 5, Vendaval 4, Geada 1, Declínio de Temperatura 1, Acumulado de Chuva 1.
  (One earlier attempt returned `curl: (56) Recv failure`; it succeeded on retry — the endpoint is
  not perfectly stable.)
- `https://www.youtube.com/feeds/videos.xml?channel_id=UC-87aDLv5WFJ83fxt21gsEQ` — **200**,
  `text/xml`, 27611 bytes, Atom, **15** `<entry>`s, `media:description` present, no transcript
  element. Newest `<published>`: **2017-02-17T18:55:05+00:00**. The channel id was resolved by
  fetching `https://www.youtube.com/@oceanexplorergov` and reading `"channelId"` out of the page
  (a made-up id returns **404**, confirming the endpoint validates it).
- `https://medium.com/feed/tag/ocean` — **200**, `text/xml`, 15712 bytes, 10 items; item tags:
  `title, link, guid, category, dc:creator, pubDate, atom:updated, description` — **no
  `content:encoded`**; `description` ≈ 583 chars.
- `https://medium.com/feed/@belen.arcev` — **200**, `text/xml`, 84992 bytes, `content:encoded`
  present.
- `https://medium.com/robots.txt` — **200**; `Disallow: /` block applies to `Amazonbot`,
  `Applebot-Extended`, `Bytespider`, **`ClaudeBot`**, `FacebookBot`, `GoogleOther`, **`GPTBot`**,
  `meta-externalagent`.
- `https://stackoverflow.com/feeds/tag/scala` — **403**, Cloudflare "Just a moment…" HTML, with a
  default UA and again with a Chrome desktop UA.
- `https://astralcodexten.substack.com/feed` — **200**, `application/xml`, 1197042 bytes, RSS 2.0
  (used only to confirm the `<pub>/feed` mechanism and full-text `content:encoded`; not a marola
  source).
- `https://earthobservatory.nasa.gov/feeds/earth-observatory.rss` — **200**,
  `application/rss+xml`, 524642 bytes, 10 items with `content:encoded`.
- `https://www.earthdata.nasa.gov/engage/open-data-services-software-policies` — fetched;
  states NASA-sponsored data "are available to all users", citation encouraged; does **not** say
  "public domain".
- `https://oceanservice.noaa.gov/rss/` — **404**.
- `https://api.weather.gov/alerts/active.atom?area=FL` — **200**, `application/atom+xml`, valid
  CAP/Atom (US-only coverage).
- `https://www.marinha.mil.br/chm/dados-do-smm-cartas-sinoticas/avisos-de-mau-tempo` — **403**,
  Cloudflare; `https://www.marinha.mil.br/chm/rss` — **403**, same.
- `https://github.com/scala/scala3/releases.atom` — **200**, `application/atom+xml` (evidence for
  §5.7c's "GitHub already gives you this free" rejection).

### Code read (this repo, at `749ec54`, 2026-09-07)

`core/knowledge/Corpus.scala` (post-MIP-0022: `CorpusChunk(docTitle, source, text, safety)`; `load`
reads `*.md` directly under `knowledge/` **plus** directly under `knowledge/safety/` via
`Files.list` — one level, no recursion), `OceanQa.scala`, `KnowledgeStore.scala`,
`core/site/Board.scala` (`SchemaVersion = 1`, pure serializer, `Sources`),
`cli/site/SiteBuilder.scala` (`Area`, `Areas.parse`, `build` → `data/<area>/{day}.json` +
`latest.json` + copied static), `site/areas.json` (floripa, rio, salvador),
`.github/workflows/site.yml` (`cron: "15 */3 * * *"` — 8 rebuilds/day), `scripts/arxiv_digest.py`
(Atom parsing + per-item JSON + `index.jsonl` + `--self-test`), `justfile` `quality-other`,
`knowledge/README.md` (the safety-directory paragraph added by #195).

### Checked live (2026-09-08, second pass — folded into §4.1)

- `https://apiprevmet3.inmet.gov.br/avisos/rss` — **200**, `application/rss+xml`, 160 320 bytes,
  **89** `<item>`s. Severity split: `Perigo Potencial` 80, `Perigo` 8, `Grande Perigo` 1. Events:
  Baixa Umidade 34, Tempestade 32, Chuvas Intensas 12, Vendaval 3, Acumulado de Chuva 1, Geada 1,
  Declínio de Temperatura 1.
- The same URL with **curl's default User-Agent** — no HTTP response at all; TLS handshake
  completes, then timeout. `http://portal.inmet.gov.br/` → 302; `https://portal.inmet.gov.br/` →
  500 with a browser UA. DNS resolves and `example.com` → 200 from the same shell, so this is
  INMET-side, not the sandbox.
- `https://apiprevmet3.inmet.gov.br/avisos/rss/55649` — **200**, `text/xml`, 8 973 bytes, CAP 1.2.
  `event` Chuvas Intensas, `severity` Moderate, `urgency` Future, `certainty` Likely, `onset`
  2026-09-11T09:34:00-03:00, `expires` 2026-09-11T23:59:00-03:00, `areaDesc` naming five Amazonas
  mesoregions, `<polygon>` with **291** `lat,lon` pairs.
- `https://apiprevmet3.inmet.gov.br/avisos/ativos` — **200**, `application/json`, 419 178 bytes,
  keys `hoje` (3 records) / `futuro`. Fields include `poligono` (GeoJSON Polygon), `municipios`
  with IBGE codes, `estados`, `mesorregioes`, `microrregioes`, `geocodes`, `severidade`,
  `aviso_cor` (`#FFFE00`), `riscos`, `instrucoes`, `data_inicio`/`data_fim`.
- Backfill probe — `/avisos/rss/{55649,55000,50000,40000}` all **200**, `sent` 2026-09-08,
  2026-07-15, 2025-02-28, 2022-08-29. `/avisos/rss/1000` → **500**. `/avisos` → 404,
  `/avisos/todos` → 404.

### Not checked

- (2026-09-08) Whether alert ids are globally dense or have large gaps — only five were sampled,
  and the 500 floor was found by bisection, not documentation. The backfill walker must tolerate
  both and never assume density.
- (2026-09-08) Whether the CAP `identifier` (`urn:oid:2.49.0.0.76.0.2026.28220.1`) is a stabler
  dedupe key than the numeric id. The numeric id is proposed because both the RSS and the URL use it.
- (2026-09-08) Whether `avisos.inmet.gov.br/<id>` — the human page shown as `Source:` — serves old
  ids the way the API path does. Only the API was probed for history.
- INMET's terms of use beyond the `<copyright>` element and the inline licence comment in the feed
  itself; no separate ToS page was fetched, and no rate limit is documented anywhere I looked.
- Whether INMET's `avisos` feed ever carries a `Ressaca` (heavy-surf) event type — none appeared in
  the single snapshot taken; the event allow-list in §5.2 should be revisited against a week of
  samples, not one.
- Substack's and Medium's terms of service (only Medium's `robots.txt` was read); the copyright
  position stated in §4.3/§4.4 is the general default for author-published platforms, not a quoted
  clause.
- YouTube's `timedtext` endpoint and `yt-dlp`'s current behaviour — asserted from general knowledge
  in §5.5's "parked" verdict, deliberately not tested, since the verdict is "don't build it".
- Any claim about how many people would subscribe to the outbound feed. There is no data; §8 says
  so rather than inventing a number.
