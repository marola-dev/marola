# MIP-0062: Ressaca — a storm-surge hazard the score can veto on, and an erosion record per beach

| | |
|---|---|
| **Status** | Draft |
| **Author** | Claude (Fable 5.1), for M. Hoffmann |
| **Created** | 2026-09-19 |
| **Phase** | 0 (scoring and notes in the existing CLI/MCP/agent output; no new surface) |
| **Related** | `ARCHITECTURE.md` §8 (heuristics and their honest limits), MIP-0001 (water quality veto, sea lore, the `knowledge/` corpus), MIP-0022 (safety footer), MIP-0039 (fact guard), MIP-0061 (the agent's `hazards` list picks this up with no change) |
| **Effort** | M — one new deterministic function family in `scoring/Swimability.scala` with its spec, a pure moon-phase helper, one curated data file with a source per row, one `knowledge/` page. No new dependency, no new network call: every input is already fetched from Open-Meteo |
| **Gain** | `user value` (marola today will score a 3 m surf day at 60/100 minus a wind penalty and never say the word *ressaca*; on this coast that is the hazard that closes beaches) |
| **Effort vs Gain** | `do next` — small, deterministic, safety-relevant, and it needs nothing that is not already in `HourlyConditions` |
| **Depends on** | Nothing must merge first. Not gated by Phase 1 or by any paid resource |
| **Blocked by** | none |
| **Risk** | A veto that cries wolf. Open-Meteo's wave height is an offshore model value, not surf at the beach; a threshold borrowed from the Navy's *warning* criterion will be wrong for sheltered bays (Lagoa, the north bays) in one direction and for exposed beaches in the other. §6 keeps the veto to one sourced number and everything softer to a labelled note |
| **Cost so far** | — |

## 1. Summary

A *ressaca* — heavy surf driven onto the coast, worst when a spring tide and a southerly wind
coincide — is the event that erodes Florianópolis's beaches and the one condition under which the
right advice is "do not go in". marola has the inputs (wave height and direction, swell, wind
speed and direction, sea level) and no notion of it. This MIP adds a deterministic
`ressacaRisk` to `Swimability`: a **veto** at the wave height at which the Brazilian Navy issues a
ressaca warning, a labelled **watch** note when the spring-tide-plus-south-wind mechanism lines up
below that, and a curated per-beach **erosion record** note sourced from the published analysis of
the national disaster registry. No model is involved anywhere.

## 2. Motivation

The prompt for this MIP is a video essay, *"Florianópolis vai afundar?"* (channel Elementar,
2026-09-18, 11 min, https://www.youtube.com/watch?v=3nbNHs8Jq_s). Its argument, in summary: the
2100 inundation maps make headlines, but the island is not subsiding — the ground failures it
shows (two schools on soft clay, a road on landfill) are local engineering problems — and the real,
present-day damage is coastal erosion from ressacas hitting the same beaches year after year, with
emergency works destroyed within months and more than half of the municipal Civil Defense
call-outs never reaching the national statistics. It explains the mechanism as a spring tide (full
or new moon) stacked with southerly wind pushing water onshore.

A video is a pointer, not a source. What it points at is a real gap, checked in the code:

```scala
// scoring/Swimability.scala, today
case Some(h) if h >= RoughWaveHeightM => (-40, Some(f"rough seas ($h%.1fm waves)"))   // 1.5 m
```

There is no higher band. A forecast 3.5 m sea is "rough seas", −40, and if the water is warm and
the sky clear the hour still scores 60. Only a water-quality verdict can veto a swim today.

## 3. User-visible change

Before, on a ressaca day:

```
Praia do Campeche — score 45/100 · rough seas (3.2m waves); strong wind (38km/h)
```

After:

```
Praia do Campeche — score 0/100 · RESSACA: 3.2 m waves — the Navy issues a ressaca warning at 2.5 m. Do not swim.
   this beach has a record of coastal-erosion disasters (S2ID 2010–2022): expect a narrow sand
   strip, debris and damaged access after heavy surf
```

On a borderline day (1.8 m, full moon, 32 km/h from the south) the score keeps its existing
penalties and gains one note: `ressaca watch (heuristic): spring tide + strong southerly wind`.
The MCP tools and MIP-0061's agent show the same strings: they already relay `notes`.

## 4. Data sources and dependencies reviewed

### 4.1 The video — watched as captions, 2026-09-19
YouTube's automatic pt captions were read in full (kept locally, gitignored, not committed: the
narration is the channel's work). **Used from it:** the framing above and the mechanism, which it
attributes to Prof. Paulo Horta (UFSC). **Not used:** its figures — 72 occurrences on 17 beaches
2010–2024, 32 officially recognised, "55.6% under-reporting", R$ 141 M, Campeche 16 / Morro das
Pedras 11 / Armação 7, 66 state records, seven municipalities in emergency in 2025, and the
sea-level projections (24 cm by 2050, 65 cm by 2100). The description lists **no sources**, the
narration mentions a "fonte 6" that is not published, and the study behind the 2010–2024 numbers
**was not found** (one search, 2026-09-19). They stay out of the design. The sponsor segment
(06:20–07:35, 10:25–10:55) is advertising and irrelevant.

### 4.2 Dutra, Goerl, Scherer & Ribeiro — read in full (PDF), 2026-09-19
*Análise dos registros de desastres na zona costeira da Ilha de Santa Catarina*, III Encontro
Nacional de Desastres (ABRHidro), ISSN 2764-9040, UFSC. Source data: S2ID (the national disaster
registry), COBRADE types 1.3.1.1.2 (cyclones – storm tide/ressaca) and 1.1.4.1.0 (marine coastal
erosion), Florianópolis, 2010–2022. Findings used here: **9** emergency decrees, **13** ocean
beaches, 5,058 people affected, R$ 140,023,990.54 (IGP-M, Dec 2022), most frequent in **May and
September**; the 13 beaches (its Figure 1): Armação do Pântano do Sul, Balneário Açores, Barra da
Lagoa, Campeche, Canasvieiras, Ingleses, Joaquina, Jurerê Internacional, Morro das Pedras, Praia
Brava, Praia Mole, Caldeirão, Matadeiro. It names the drivers — storm tides, spring tides
(*sizígia*), extratropical cyclones, meteorological tide — and concludes that the registry does
not reflect what actually happens on the coast. This is evidently the predecessor of the study the
video cites; its numbers are the ones this MIP uses.
URL: https://files.abrhidro.org.br/Eventos/Trabalhos/190/III-END0080-2-0-20230124-211520.pdf

### 4.3 The Navy's ressaca warning — partly verified
The Centro de Hidrografia da Marinha publishes *avisos de mau tempo*, including *aviso de ressaca*,
at marinha.mil.br/chm/…/avisos-de-mau-tempo. A search summary of a CHM press note states the
criterion: waves **above 2.5 m** reaching the coast. **Not confirmed against the page:** the avisos
page answered HTTP 403 to an automated fetch and the press-note PDF failed TLS verification
(not bypassed). No JSON/RSS feed was found. So: the 2.5 m figure is "reported, not read at source",
and the page is **not** a dependency.

### 4.4 Inputs — already in the repo, confirmed in `OpenMeteoClient.scala`
`wave_height`, `wave_direction`, `swell_wave_height`, `swell_wave_period`, `wind_speed_10m`,
`wind_direction_10m`, `sea_level_height_msl`. Moon phase needs no source: the synodic month from a
reference new moon is arithmetic. **Not checked:** how Open-Meteo's offshore wave height relates to
surf at each beach.

**Pick:** compute from what is already fetched; one sourced threshold for the veto; the paper for
the per-beach record; the Navy's page as a later opt-in if it turns out to be fetchable.

## 5. Design

All in `core`, all pure, all unit-tested. No LLM, no new HTTP call.

```scala
// model/Models.scala
enum RessacaRisk derives CanEqual:
  case None, Watch, Warning

// scoring/MoonPhase.scala — days since a reference new moon, modulo 29.530588
object MoonPhase:
  def ageDays(date: LocalDate): Double
  def isSpringTide(date: LocalDate): Boolean          // within 2 days of new or full

// scoring/Swimability.scala
val RessacaWarningWaveM = 2.5                          // §4.3
def ressacaRisk(hour: HourlyConditions): RessacaRisk
private def ressacaDelta(hour): (Int, Option[String], Boolean /* veto */)
```

`score` gains the veto alongside the water one: `if water.veto || ressaca.veto then 0`.
`BestHour` gains `ressacaRisk`; `bestHourToJson` and `Report` print it; MIP-0061's `hazards`
needs no change because the note is already in `notes`.

**Erosion record.** `data/coastal-erosion-record.json`: one row per beach from §4.2's Figure 1 —
`{beach, osm_name_variants, source, period, url}` — matched to OSM beach names the way
`WaterQualityMatcher` matches sampling points. A matched beach gets one fixed sentence (§3),
shown verbatim; it never changes the score. Beaches outside Florianópolis get nothing: absence of
a row means "no record in this source", not "no erosion", and the note says which source.

**Knowledge.** `knowledge/ressaca-and-coastal-erosion.md` for `OceanQa`: the mechanism, the
months, the registry's blind spot — written from §4.2, each paragraph cited.

## 6. Scoring / safety impact

| Condition | Effect | Basis |
|---|---|---|
| `waveHeightM ≥ 2.5` | **veto → score 0**, note `RESSACA: … Do not swim.` | the Navy's warning criterion (§4.3, reported) |
| `1.5 ≤ waveHeightM < 2.5` **and** spring tide **and** wind ≥ 30 km/h from 135°–225° | existing −40/−25 stand; add note `ressaca watch (heuristic): …` | mechanism sourced (§4.2); the cut-offs reuse `RoughWaveHeightM` and `StrongWindKmh` and are marola's own — labelled *heuristic*, like the jellyfish rule |
| anything else | unchanged | — |

The veto is one number with a source. Everything softer is a note that says it is a heuristic.
`OutingPlanner` (MIP-0061) already refuses hours scored 0, so a ressaca hour can never be a target.

## 7. Verification plan

`SwimabilitySpec`: 2.5 m vetoes regardless of other deltas; 2.49 m does not; the watch note needs
all three conditions (one spec per missing leg); a northerly 40 km/h wind is not a watch; missing
wave data is neither. `MoonPhaseSpec`: known new and full moons from a published almanac, ±1 day.
`ErosionRecordSpec`: the 13 names load; "Praia do Campeche" and "Campeche" both match; a Bahia
beach matches nothing. Live: `just run` on a day the Navy has a ressaca warning out for Santa
Catarina, compared by hand. Done = that comparison recorded in this file.

## 8. Risks, limitations, and honest caveats

- Offshore model height is not surf height; exposure differs by beach. A per-beach exposure factor
  is the obvious refinement and needs data this MIP does not have.
- The 2.5 m criterion was not read at its source (§4.3). If it is wrong, the veto moves; the
  constant is one line.
- The erosion record is a registry of *declared disasters*, which the paper itself says undercounts.
  The note must not read as a ranking of dangerous beaches.
- Spring tide by date ignores the meteorological tide a cyclone adds — the part that does the damage.

## 9. Alternatives considered

- **Scrape the Navy's warnings.** Authoritative, and it refuses automated fetches; an HTML page
  with no feed is a brittle dependency for a safety veto. Revisit as opt-in.
- **Use the video's per-beach counts.** Unsourced until the 2010–2024 study is found.
- **Sea-level-rise lore.** True and irrelevant to tomorrow's swim; at most a `knowledge/` paragraph,
  once sourced.
- **Do nothing.** marola keeps calling a ressaca "rough seas" and scoring it above zero.

## 10. Exam-coverage mapping

None.

## 11. Open questions

1. The 2010–2024 UFSC study (72 occurrences, 17 beaches) — find it, then extend the record file.
2. Read the Navy's criterion at source; is there any machine-readable channel (the PAM app's API)?
3. Should the veto threshold be lower for beaches in the erosion record?
4. Defesa Civil SC alerts as a second official signal — not checked.

## Appendix

Checked 2026-09-19: the video's metadata, description and automatic captions (`yt-dlp`); the
ABRHidro PDF, all four pages; two web searches; `Swimability.scala`, `OpenMeteoClient.scala`,
`knowledge/` (no mention of ressaca anywhere in the repo).
