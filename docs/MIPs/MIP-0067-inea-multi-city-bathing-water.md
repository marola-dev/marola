# MIP-0067: INEA bathing water beyond one PDF — discovery, per-zone layouts, Niterói and Cabo Frio

| | |
|---|---|
| **Status** | Draft |
| **Author** | Claude Sonnet 5.5, for W. Macedo (brainstorm of 2026-09-28: "Rio data is not matching and weird points") |
| **Created** | 2026-09-28 |
| **Phase** | 0 — CLI and board data only; no earlier-phase prerequisite is missing, no cloud spend |
| **Related** | MIP-0031 (the INEA parser, the Rio coordinate table, `IneaRjWaterQualityClient`), MIP-0056 §5.6 (`inea-rj` is the store's second adapter and wants the same discovery step), MIP-0001 (`WaterQuality.fresh`), #487/PR #489 (parser reads the header row), #488 (discovery and real date) |
| **Effort** | L — four bulletin layouts beyond the one MIP-0031 parses, per-city coordinate tables (~100 points), a new site area, and a change to what a sample's date means; no new dependency |
| **Gain** | `user value` — Rio shows current verdicts for all four zones, and Niterói and Cabo Frio get one at all; `user value` (safety) — a stale "PRÓPRIA" stops looking current |
| **Effort vs Gain** | `do next` — #487 and #488 are already filed and unblock the rest; the per-zone work is mechanical once they land |
| **Depends on** | MIP-0031 (built on its parser and tables). MIP-0056's `inea-rj` adapter should reuse this MIP's discovery and layouts rather than grow its own; coordination, not a blocker. No Phase 1 gate, no paid resource. |
| **Blocked by** | none |
| **Risk** | INEA's PDFs are Excel exports whose layout moved ~55pt between June and September 2026 and differs per zone; a fifth silent shift returns "no data" for a city until someone notices |
| **Cost so far** | — |

## 1. Summary

MIP-0031 shipped one INEA parser and pointed it at one hardcoded June PDF. Verified 2026-09-28,
that is not enough: the parser returned 0 of 39 rows on INEA's current bulletin (fixed by
#487), the client stamps every reading with today's date so the 45-day freshness gate never
fires, and four more INEA bulletins in Rio plus Niterói and Cabo Frio each have their own layout.
This MIP makes the INEA source *current* (discover the newest bulletin per zone, use its real
date), *complete* (Rio's four zones, Niterói, Cabo Frio) and *honest* (one coordinate per point,
no verdict older than the freshness window).

## 2. Motivation

What the brainstorm found, all verified 2026-09-28 (Appendix):

- `IneaRjWaterQualityClient.DefaultEndpoint` is `.../2026/06/Zona-sudoeste-e-Zona-sul-17-06-26.pdf`,
  three months old, one of Rio's zones. MIP-0031 task 6 said "find the newest PDF"; the code does
  not.
- `IneaRjWaterQualityClient.sampleOf` sets `sampledOn = LocalDate.now()`. `WaterQuality.fresh`
  (`MaxSampleAgeDays = 45`, used by `Swimability`) therefore cannot age an INEA sample out.
- `sampling_points_rj.json` holds 29 points, one coordinate per *beach* (the three Ipanema points
  and four Barra da Tijuca points share a coordinate). The bulletin has 39.
- The other four bulletins parse to 0 rows: Ilha do Governador e Ramos and Paquetá put
  `PONTO COLETA` between the beach and location columns and use `GL0001`-style codes; Niterói
  (`GR000`) and Cabo Frio (`CF0001`) differ again.
- Niterói's PDF header reads "24 de SETEMBRO de **2025**" on a 2026 bulletin.

## 3. User-visible change

Illustrative shape and values (`--brief`), for beaches in zones that have no data today:

```
  Praia de Icaraí (Niterói)   62/100 at 09:00  |  water: 1/6 IMPRÓPRIA — IC003 (24 Sep)  |  ...
  Praia de Paquetá            —                |  water: PRÓPRIA — Grossa (21 Sep)       |  ...
```

The date is the bulletin's, not today's. A reading older than the freshness window shows "no
data", as an IMA/SC one does. Cabo Frio appears as a fourth area on the map.

## 4. Data sources and dependencies reviewed

### 4.1 INEA's city pages (the pick)

`https://www.inea.rj.gov.br/ar-agua-e-solo/balneabilidade-das-praias/` links one page per
municipality. Checked 2026-09-28: `/rio-de-janeiro/` (81 file links), `/niteroi/` (24),
`/cabo-frio/` (21). Each holds, for the current period, **one dated bulletin per zone**, named
`<Zone>-DD-MM-YY.pdf`, plus year-by-year `*_historico*` PDFs and `QualificaÃ§Ã£o-anual` PDFs:

| City | Zone | Current bulletin | Layout (header row) |
|---|---|---|---|
| Rio | Zona Sudoeste e Sul | `…-21-09-26` (Nº 38) | `PRAIAS · LOCALIZAÇÃO · Ponto Coleta · CONAMA`, codes `BG00` |
| Rio | Ilha do Governador e Ramos | `…-14-09-26` (Nº 16) | `PRAIAS · PONTO COLETA · LOCALIZAÇÃO · CONAMA`, `GL0001` |
| Rio | Paquetá | `…-21-09-26` (Nº 35) | same as Ilha, `IU0000` |
| Rio | Sepetiba | `Sepetiba-28-11-24` | not updated since Nov 2024 |
| Niterói | Niterói | `Niteroi-24-09-26` (Nº 65) | code before location, `GR000` |
| Cabo Frio | Cabo Frio | `Cabo-Frio-05-08-26` (Nº 10) | code before location, `CF0001` |

The `-N` suffix on `historico` files (`barra_e_zona_sul_historico-3.pdf`) is WordPress's
re-upload counter, not a week. The same filename convention held for the 17 Jun and 21 Sep Rio
bulletins.

### 4.2 The `historico` PDFs and the xlsx (not the pick, kept for MIP-0056)

`barra_e_zona_sul_historico-3.pdf` is a year-to-date matrix, one column per weekly collection,
dated in its header (Jul 8 … Sep 21), with a third code format (`BD005`). It is not what the
parser reads. `.../2026/08/Ultima-Atualizacao-25.08.2026.xlsx` (41 sheets, 1.5 MB) holds
enterococci counts (NMP/100 mL) per point with real dates (Barra e Z. Sul to 2026-08-19), since 2005
or 2011 by sheet; it has **no coordinates** and a fourth code format (`CF-01`, `GR00`). That is
the "history export" MIP-0056 §5.6 row 2 says it does not know of; see §11.

### 4.3 Rejected

- Brasil.IO (auth wall, frozen 2020) — MIP-0031 §4.1.
- Any third-party aggregator: a second-hand, later verdict is the wrong shape for a swim-safety
  flag, and none was found that INEA does not already feed.
- Niterói's municipal ArcGIS map — MIP-0031 §11, still not fetched (§11 here).

## 5. Design

**Discovery** (`local/…/water/IneaBulletinIndex.scala`, pure over the page HTML): from a city
page, every `href` whose filename matches `-(\d{2})-(\d{2})-(\d{2})\.pdf$` and is not
`historico`/`Qualifica*`; the zone is the filename before the date and the sample date is
`20YY-MM-DD` from it, never from the PDF text (§2, Niterói's 2025). One fetch of the page, one of
each PDF. Any failure returns no points and logs why, never a stale hardcoded PDF.

**Layouts** (`IneaPdfParser`): #487 already bounds the beach-name column from the header row.
This MIP generalises it to a small `Layout` read from the same row — which of code and location
comes first, and a code pattern `[A-Z]{2,4}[0-9]{2,4}` — chosen per bulletin from its header, so
`Ilha`, `Paquetá`, `Niterói` and `Cabo Frio` are fixtures, not four parsers. A header it cannot
place yields no rows and a warning, as in #487.

**Freshness**: the client sets `WaterSample.sampledOn` to the bulletin's date; the existing
`WaterQuality.fresh` (45 days) then applies unchanged. No new window is introduced (§11 asks
whether 45 is right for a weekly agency).

**Clients**: `IneaRjWaterQualityClient` becomes one client over a list of city pages and their
coordinate tables; `AppConfig`'s `Auto` case and `WaterProvider.IneaRj` stay. Niterói and Cabo
Frio sit inside the existing Rio-state bounding box (`coversOrigin`), so no new provider.

**Coordinates**: `sampling_points_rj.json` gains the missing Rio points; new
`sampling_points_nit.json` and `sampling_points_cf.json`, same `code`/`beach_hint`/`lat`/`lon`/
`source` shape, one coordinate per *point* geocoded from its `LOCALIZAÇÃO` text (a street
number, a landmark), each with its source. Rows without a coordinate are dropped, never guessed.

**Cabo Frio area**: a fourth entry in `site/areas.json` (about 120 km east of `rio`, by
coordinate arithmetic, not surveyed), so Cabo Frio beaches are found by `BeachFinder`.

Deterministic throughout; nothing goes through the LLM.

## 6. Scoring / safety impact

`Swimability.waterVerdict` is unchanged. Three behaviours change, all toward more honest:

- More beaches get a real IMPRÓPRIA veto (Niterói, Cabo Frio, Rio's other zones).
- INEA samples now age out at 45 days. Today they never do, so this **removes** a verdict from
  the screen when its bulletin is old, e.g. Cabo Frio's 5 Aug bulletin (54 days on 28 Sep).
- The date shown is the collection bulletin's, so a June reading no longer reads as today's.

## 7. Verification plan

- `IneaBulletinIndexSpec`: a saved `/rio-de-janeiro/` page yields exactly the four dated links
  and ignores `historico` and `Qualifica*`; Sepetiba's date is 2024-11-28.
- `IneaPdfParserSpec`: a fixture per zone (Ilha e Ramos, Paquetá, Niterói, Cabo Frio) parses
  every row, checked against an independent `pdftotext` count, as #487 did for Sudoeste/Sul.
- `IneaRjWaterQualityClientSpec`: `sampledOn` equals the filename date, never `now()`; a 54-day
  bulletin yields no fresh points.
- `SamplingPointCoordinatesSpec`: every bulletin code resolves, and a code absent from the table
  is dropped.
- Live, before merge: `just run -- --brief --lat -22.9027 --lon -43.1029` (Niterói) and Cabo
  Frio's origin each show a dated water line for at least one beach.
- Done: no INEA zone parses to 0 rows, and a bulletin with an unplaceable header is a logged
  warning in CI's smoke run, not a silent "no data".

## 8. Risks, limitations, and honest caveats

- **Layout drift is the standing risk.** It has already happened once in three months. The
  header-derived layout survives a shift; it does not survive a redesign. The per-zone fixtures
  are the tripwire, and a periodic live parse (like `just smoke`) is the alarm.
- **Coordinates are still hand-curated** and will drift as INEA adds points (MIP-0031 §8).
- **Cadence differs per zone**: Sudoeste/Sul and Paquetá were both issued 21 Sep, Niterói 24 Sep,
  Ilha e Ramos 14 Sep (the maintainer says about twice a month; not verified), Cabo Frio 5 Aug
  (54 days old). A single 45-day window is lenient for the weekly ones (§11).
- **One point per bulletin row, one verdict**: the bulletin carries no enterococci count or
  collection hour, so nothing here says how bad an IMPRÓPRIA is (§11's follow-up).

## 9. Alternatives considered

- **Do nothing.** Rio keeps a frozen June verdict that reads as today's. Rejected: it is the
  unsafe direction.
- **A parser per zone.** Four near-identical files that drift apart. Rejected for a header-derived
  layout.
- **Parse the `historico` PDFs instead.** One file per zone-year and a third code format, but the
  newest column is the same information as the bulletin, harder to extract. Rejected as the
  runtime source; useful only as backfill.
- **URL guessing** from the `-N` suffix or the upload month. Rejected: the suffix is a re-upload
  counter and the folder is the upload month (§4.1).

## 11. Open questions

- Freshness window: reuse 45 days, or set one per agency (a weekly bulletin 45 days old is
  long stale)? A person decides; the design reads the constant from one place either way.
- Does INEA publish point coordinates anywhere (a geoportal, a GIS layer)? The xlsx does not.
  Not searched beyond the city pages and the xlsx.
- Should Sepetiba be excluded by name or left to the freshness rule? The rule alone drops it.
- **Follow-up MIP:** use the xlsx's enterococci history (real NMP/100 mL and dates, 2005/2011–
  2026) as MIP-0056's `inea-rj` backfill and for a "last 5 campaigns" check, which the Niterói bulletin's footer
  states as INEA's Imprópria rule (latest above 400, or two of the last five above 100). Needs the next MIP number after this one.
- **Follow-up (not a MIP):** junk OSM beaches such as `parquinho` (`way/1202822690`, tags
  `name` and `natural=beach` only, a playground in Botafogo) reach the map because `BeachFinder`
  applies no filter. A story, not a design.

## Appendix

### Checked live

All 2026-09-28 unless stated.

- `GET .../ar-agua-e-solo/balneabilidade-das-praias/`, `/rio-de-janeiro/`, `/niteroi/`,
  `/cabo-frio/` → 200; link lists as in §4.1.
- `GET .../2026/09/Zona-sudoeste-e-Zona-sul-21-09-26.pdf` → 200, 218 KB, 39 code+verdict rows by
  `pdftotext`; header `PRAIAS x=215, LOCALIZAÇÃO x=366` vs June's `155/305`.
- `IneaPdfParser` (pre-#487) on that file → 0 rows; on the 17 Jun fixture → 39.
- The same parser on Ilha e Ramos, Paquetá, Niterói, Cabo Frio bulletins → 0 rows; extracted
  headers and code shapes as in §4.1.
- `GET .../2026/09/barra_e_zona_sul_historico-3.pdf` → 200, 2 pages, columns Jul 8 … Sep 21.
- `GET .../2026/08/Ultima-Atualizacao-25.08.2026.xlsx` → 200, 41 sheets, no latitude/longitude
  string among 781 shared strings; Barra e Z. Sul dates run to 2026-08-19.
- OSM Overpass, `natural=beach` within 2500 m of -22.95,-43.185 → `way/1202822690`,
  `name=parquinho`, no other tag, at -22.9506,-43.1949.
- `core/…/WaterQuality.scala`: `MaxSampleAgeDays = 45`; `IneaRjWaterQualityClient.sampleOf` uses
  `LocalDate.now()`.

### Not checked

- Whether Ilha e Ramos, Paquetá, Niterói and Cabo Frio parse once the layout is generalised; only
  their extracted geometry was read.
- Whether Sepetiba is discontinued or merely late; only its filename date was read.
- INEA's publishing cadence per zone beyond the dates seen on 2026-09-28 (Cabo Frio "roughly
  monthly" is one 54-day gap).
- The distance from the `rio` area to Cabo Frio (about 120 km is coordinate arithmetic).
- A Niterói or INEA coordinate source (§11).
