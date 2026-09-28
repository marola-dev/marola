# MIP-0054: Portuguese first — marola's whole user-facing experience in pt-BR, with a language choice that sticks and a location-aware default

| | |
|---|---|
| **Status** | Draft — **release blocker: Release 0 (MIP-0033) does not ship until this is Implemented** |
| **Author** | Claude (Fable 5.1), for M. Hoffmann |
| **Created** | 2026-09-12 |
| **Phase** | 0 for the site, CLI, MCP and corpus (every surface that exists); the Telegram reply (Phase 1, MIP-0002, not built) inherits the same catalog. No paid resource |
| **Related** | MIP-0050 §11 named this as its follow-up ("corpus, prompts and site copy are English for a Brazilian audience"); MIP-0022 (`SafetyFooter` already has a pt-BR table — the one surface done right); MIP-0001 §6/§9 (the agency verdict shown verbatim — untouchable by translation); MIP-0039 (the fact guard — §5.4 is the same post-model shape); MIP-0046/MIP-0042 (share `app.js`/`site_check.js`; coordinate, not blocked) |
| **Effort** | XL — a message catalog in two runtimes (Scala for CLI/MCP/board, JS for the site), a board-contract change (codes, not English prose), a pt-BR corpus with per-file sources, a pt-BR DSPy recompile of both prompts, a deterministic language guard, a rewrite of `scripts/site_check.js`'s copy assertions, and a language/location precedence in the client. No new dependency |
| **Gain** | `user value` — the audience is Brazilian and every surface but the safety footer answers in English (§2); `infra/dev-loop` — one catalog file per language instead of strings scattered over `Swimability`, `Report`, `app.js` and the prompts |
| **Effort vs Gain** | `do next` — a declared release blocker, and §4.3 shows the default model can do it; nothing in it waits on another MIP |
| **Depends on** | Nothing must merge first. MIP-0050's evaluation arm would give a second pt-BR model number, but `llama3.2` (today's default) is officially Portuguese (§4.3). MIP-0046 and MIP-0042 edit the same site files and `site_check.js`: land this before the `/v1/` freeze or rebase over it. No Phase 1 gate; no cost gate |
| **Blocked by** | none |
| **Risk** | The UI turns Portuguese and the model keeps answering in English — mixed-language output under a Portuguese header. §5.4's guard makes that show the deterministic line instead of English prose; §7's benchmark arm measures the rate per model before the default is chosen |
| **Cost so far** | — |

## 1. Summary

marola's users are Brazilian swimmers; its beach names, bulletins and CONAMA verdicts are Portuguese;
everything marola itself says, site copy, CLI output, scoring notes, sea lore, corpus, prompts, the
model's summary, is English. This MIP makes pt-BR the default on every surface, makes English a
choice the visitor can flip and keep, and opens the site on the coast the visitor is on when they
allow it, with an honest "not covered here yet" elsewhere. **It blocks Release 0: MIP-0033's release
is not cut until §7's gates are green on `main`.** Translation at the edges is rejected (§9): the
model answers in Portuguese natively, a deterministic guard refuses English prose under a Portuguese
header, and no threshold or safety note moves into the model to get there.

## 2. Motivation

What a visitor meets today, from the real files:

- `site/static/index.html:2` `<html lang="en">`; `:34` "The ocean near you: conditions, water
  quality, sea life, tides, hazards."; `:46` "best hour per beach"; `:64` "ask the ocean".
- `site/static/app.js:349` "good conditions"; `:397` "🐋 Sea life: " / "🌊 Did you know? "; `:545`
  "Location not granted — the list stays ranked by score."
- **The board JSON carries English prose**: `Swimability.scala:76-126` writes `"cold water (19.2°C)"`,
  `"elevated jellyfish likelihood"` into `hours[].notes` (`board.schema.json:103`); `wind_level` is
  the English enum `calm|breezy|strong` (`:107`); `water.summary` is `"1/1 PRÓPRIA (25 Aug)"`, a
  Portuguese verdict with an English month. A client cannot re-word what the build baked in.
- CLI: `Report.scala:42-150` ("parking nearby", "few of the warm-calm signals present");
  `Main.scala:150,497` errors; `OceanQa.scala:21-22` the no-answer reply, `:86-104` two English
  system prompts. MCP descriptions `SwimConditionsMcpServer.scala:123-163`; `docs/1-Using-marola/RUN-LOCALLY.md`.
- LLM path: `recommendation_prompt.json` / `review_prompt.json`, English instructions, three English
  bootstrapped demos each. Corpus: seven `knowledge/*.md`, all English, which `--ask` is told to
  answer *from*, so an English corpus pulls the answer to English.
- The exception: `SafetyFooter.scala:8-22` defaults to `Lang.PtBr` (MIP-0022). Nothing else in the
  repo knows a locale (Appendix: the grep).

## 3. User-visible change

**Site, default (no stored choice):** `<html lang="pt-BR">`, and

```
marola   o mar perto de você: condições, balneabilidade, vida marinha, marés, perigos.
[Florianópolis ▾] [hoje] [amanhã]  [perto de mim] [🌊 som] [lista]          [pt] [en]
melhor hora por praia ──────●────
pontuação ● ≥70 ● 40–69 ● 1–39 ● imprópria ● sem dados       〰 passe o mouse numa onda
tooltip:  Praia da Joaquina — 55/100 às 10:00
          🌬️ brisa, 27 km/h S · 🌡️ água 19,0 °C · 〰️ ondas 1,3 m a cada 6 s
          🪼 água-viva baixa · 🐋 baleias baixa (melhor 07:00)
          ● 1/1 PRÓPRIA (25 ago) · 🅿️ estacionamento 3 · 🚻 banheiros 1
```

`PRÓPRIA`/`IMPRÓPRIA`, beach and provider names keep their case (`style.css:130-131`); what marola
says is lowercase. **The toggle** `[pt] [en]` is a two-`<button>` segmented control like `#days`
(`index.html:38`), `aria-pressed` on the active one, never `<a href="#">` (`site_check.js:339`).
Flipping it re-renders in place, stores the choice and sets `?lang=` so a shared link renders as seen.

**Location denied** (the "perto de mim" click): `localização não permitida — a lista continua
ordenada por pontuação. escolha a área acima.` **Outside coverage** (allowed, no area contains it):
`você está a ~410 km da área coberta mais próxima (Rio de Janeiro). o marola ainda não cobre esta
costa — sem balneabilidade oficial, não há recomendação de banho aqui. mostrando Rio.`

**CLI, `just run -- --summarize`**, same numbers, same order; the model's sentence last and only
if it passed §5.4:

```
Melhor escolha — Praia da Joaquina, sáb 13 set, 10:00-11:00
  ondas 1,3 m a cada 6 s · vento 27 km/h · água 19,0 °C
  notas: brisa (27 km/h), água fria (19,0 °C)
  balneabilidade: 1/1 PRÓPRIA (25 ago) · fonte: IMA/SC
  → mar tranquilo e água fria: vale entrar cedo, mas leve um agasalho para a saída.
     (revisor: 88/100, aprovado)
```

`--lang en` / `MAROLA_LANG=en` gives today's English. `--ask` answers in the language of the corpus
it searched (§5.3). MCP tool *names* stay English (identifiers other agents call); descriptions
carry both languages. The safety footer keeps its per-language table.

## 4. Data sources and dependencies reviewed

### 4.1 Upstream is already Portuguese or language-neutral
IMA/SC is Portuguese-only ("Próprio"/"Impróprio"); Open-Meteo returns numbers plus unit strings and
has no locale parameter; Overpass returns OSM `name` tags, Portuguese on this coast (Appendix).
**Every English string a user sees is marola's own, so every one is marola's to fix.**

### 4.2 Where English creeps back in
The model's summary (`llama3.2` replaying English demos), the `--ask` answer (English passages and
prompt), and the board (English notes baked in at build time). §5 addresses each; §7 gates each.

### 4.3 Can the local models write pt-BR? — the decisive question
Cards checked 2026-09-12 (Appendix):
- **`llama3.2`**, the runtime default (`LocalLlmClient.scala:29`): "English, German, French, Italian,
  **Portuguese**, Hindi, Spanish, and Thai are officially supported." The pt-BR default is built on it.
- **`qwen2.5`** (the `qwen-*` presets): "over 29 languages, including … **Portuguese**". A second row.
- **`smollm2:360m`** (the `tiny` preset): "SmolLM2 models primarily understand and generate content in
  English"; Ollama's page says nothing about language. **`tiny` cannot be the Portuguese model** and
  stays the pipeline proof `finetune/README.md` calls it. MIP-0050's Manacá-1B is the small-model
  road; its lowercase-only tokenizer collides with `PRÓPRIA`, which §5.2 keeps out of the model.
- Not measured: none was run in Portuguese here. The cards say it is not hopeless; §7 gets the number.

### 4.4 Browser APIs (MDN, Appendix)
Geolocation: secure context only, "users must grant explicit permission via a prompt" on
`getCurrentPosition()`, errors `1 PERMISSION_DENIED`/`2 POSITION_UNAVAILABLE`/`3 TIMEOUT`;
`marola.dev` is HTTPS and `app.js:538-545` already calls it on a click. `navigator.languages`: BCP-47
tags, most preferred first; Safari and Chrome incognito expose one. `localStorage`: per origin, across
sessions, throws `SecurityError` when the user blocks storage, cleared with the last private tab.

### 4.5 IP geolocation from the page — permitted by the gates, rejected by the promise
`index.html:14`'s CSP is `connect-src 'self' https:`, and `scripts/strip_external_scripts.py` only
strips `<script src="https://…">` from the generated docs tree (`out/scala`, `out/python`), a
`fetch()` to an HTTPS geo API would pass both. `ipwho.is` (the CLI's second provider) is keyless,
HTTPS, CORS, 1,000 requests/day *per domain*; `ip-api.com` (its third) is `http://` on the free tier
("SSL and commercial use" are Pro), so the CSP blocks it anyway. But `app.js:403` says "No cookies,
no tracking" and `index.html:7` "no tracking": sending every visitor's IP to a third party on load is
tracking, and 1,000/day per domain is a budget one good day exhausts. **Rejected for the site.** The
CLI keeps `IpGeolocation`, one's own machine asking about its own IP is a different consent story.

### 4.6 A zero-network coarse hint: the timezone
`site/areas.json` already carries `tz` per area. `Intl.DateTimeFormat().resolvedOptions().timeZone`
needs no permission, request or storage; §5.5 uses it to pick a default *area*, never shown as a
position. Not verified against MDN this session (Not checked).

## 5. Design

### 5.1 One catalog, two runtimes, codes on the wire
`core/src/main/resources/i18n/{pt-BR,en}.json`: flat `key → string`, `{0}` placeholders.
`marola.i18n.Messages(lang)` renders them in `core`; `Lang { PtBr, En }` moves from `SafetyFooter` to
`marola.i18n` (footer API unchanged). `SiteBuilder` copies both files to `site/dist/data/i18n/`;
`app.js` fetches the active one and renders every string via `t(key, args)`.
**Scoring notes become codes**: `Swimability` returns `Note(code: NoteCode, args)` instead of
`String`; `Report` and the board render it. The board gains `hours[].note_codes` next to `notes`
(additive, an older `app.js` still reads a new board; `schema` enum becomes `[1, 2]`). `wind_level`
stays the wire enum, rendered through the catalog. `water.summary`'s month is formatted per language
in `Report`; the verdict token is never touched.

### 5.2 The summary: recompile, don't translate
`dspy/compile_recommendation_prompt.py --lang pt-BR`: Portuguese signature docstrings and a
hand-labelled Portuguese trainset, compiled against a Portuguese-capable local model, saved as
`recommendation_prompt.pt-BR.json` / `review_prompt.pt-BR.json`; `CompiledPrompt.load(lang)` picks
the artefact. The reviewer's three checks are language-agnostic but it is recompiled too so its
`final_summary` is Portuguese. The numbers, the verdict and every threshold stay in the deterministic
lines beside the sentence, as today.

### 5.3 The corpus: a Portuguese one, sourced line by line
`knowledge/pt-BR/<same-name>.md` for every English file, same `# Title` + `Source:` contract, same
rule (say only what the source supports), same human sentence-by-sentence check `knowledge/README.md`
already demands before Phase 1. A Portuguese source (IMA/SC) is cited directly; an English one (NOAA,
Wikipedia) is cited as is, optionally with a Portuguese page beside it. `MAROLA_KNOWLEDGE_DIR`
selects the tree; the index fingerprint already follows it. `OceanQa`'s prompts move to the catalog;
`NO_ANSWER_IN_PASSAGES` stays the language-neutral sentinel. **`--ask` is one language at a time**,
the corpus's, and the UI says so instead of pretending otherwise.

### 5.4 The language guard — deterministic, after the model
`marola.i18n.LanguageGuard.looksLike(text, lang)`: a stopword-ratio test over two fixed lists (pt:
`de`, `que`, `não`, `para`, `com`, `uma`…; en: `the`, `and`, `with`, `for`, `you`…), no dependency,
unit-tested. `Recommender` applies it to the summary, `OceanQa` to the answer: a failing sentence is
dropped for the deterministic line plus a `summary_language_mismatch` note. An English sentence never
appears under a Portuguese header. Same shape as MIP-0022's footer and MIP-0039's fact guard.

### 5.5 Precedence — language, then location
**Language**, first hit wins: (1) `?lang=`: a shared link renders as shared; (2)
`localStorage['marola.lang']`: the toggle writes it and (1); (3) `navigator.languages`, any `pt*` →
`pt-BR`, else `en`; (4) `pt-BR`. The toggle beats inference because it writes (1) and (2). **Location
never selects language**: a denied prompt, a missing API, a thrown `localStorage` all still land on
(3)/(4). CLI: `--lang`, `MAROLA_LANG`, `Locale.getDefault`, `pt-BR`.
**Location** picks an *area*, never a language: (1) `?area=` (exists, `app.js:87,94`); (2) stored
`marola.area`; (3) on the "perto de mim" click, the nearest area whose `radius_km` contains the
position, else §3's outside-coverage line and the nearest area; (4) the area whose `tz` matches the
browser's (§4.6); (5) `areas[0]`. No prompt on load; Geolocation stays behind the click. `code 1`
shows the denied line; `2`/`3` show "não foi possível obter a localização" and keep the picker.
**Outside coverage is a data statement.** Overpass and Open-Meteo are global; water quality is not
(`ARCHITECTURE.md` §5g: IMA/SC in SC, `none` elsewhere; INEA/INEMA partial, MIP-0031). With no
provider covering the origin, `--summarize` and the card carry `water_not_covered`; the score takes
the same "no data" path as today. No beach is added that the pipeline cannot back.

### 5.6 Privacy, exactly
Collected: a position, after a click and a browser prompt, held in `state.here` for the page's life,
never written; a language and an area id in `localStorage`, per origin. Sent: nothing; no request
carries position, language or area; board and catalog fetches are same-origin static files. Stored
server-side: nothing (there is no server). The CLI's IP lookup is unchanged and CLI-only.

### 5.7 Not proposed
No translation API, free or paid: `AGENTS.md`'s cost gate is untouched. No machine-translated
corpus. No change to `Swimability`'s thresholds, weights or vetoes. No English removed: `en.json`
keeps every string the site shows today.

## 6. Scoring / safety impact

**None to the numbers.** `Swimability.score`, `CalmWaveHeightM = 0.6`, `RoughWaveHeightM = 1.5`,
`WhaleCalmWaveHeightM = 1.0`, the heuristics and the water veto are untouched; `SwimabilitySpec`
asserts on `NoteCode`s instead of English strings, so a wording change can no longer break a scoring
test. The verdict stays verbatim; the footer stays deterministic and per-language. §5.4 removes a
failure mode: the only model-authored text is now also checked for language.

## 7. Verification plan

**Done = every gate below green on `main`; MIP-0033's release checklist gets this line.**

- `scripts/site_check.js` (already a `quality-other`/`ci.yml` gate): `INDEX` has `<html
  lang="pt-BR">`; a default `runPage` (stub `navigator: {}`, empty `search`, a `localStorage` stub
  that **throws**) renders `hour-label` = `melhor hora por praia`, a tooltip with `água-viva` and
  `ondas … a cada`, and `1/1 PRÓPRIA (25 ago)`; a run with `search: '?lang=en'` renders today's English
  cells, so neither language regresses; stored `en` + URL `pt-BR` renders Portuguese; `#lang` is two
  `<button>`s with `aria-pressed`, no `href="#"`; clicking flips it and writes the stub store; the
  lowercase-exemption assertions stay as they are.
- `scripts/i18n_check.py --self-test` in `quality-other`: key parity between the two catalogs, no
  empty value, an English denylist (`jellyfish`, `waves`, `unfit`, `loading`…) in `pt-BR.json`, and a
  `Source:`-bearing `knowledge/pt-BR/` twin for every `knowledge/*.md`, a missing one fails the build.
- `MessagesSpec` (every `NoteCode` in both languages, placeholders consumed); `ReportSpec` (a fixed
  `BestHour` renders §3's block byte-for-byte); `BoardSpec` (`note_codes`, `schema` 2, a schema-1
  fixture still validates); `LanguageGuardSpec` (the three English demos fail `PtBr`; §3's sentence
  passes; an empty string passes, the guard refuses only what it can tell).
- `just benchmark` pt-BR arm: `benchmark_questions.pt-BR.json` (the 22 questions), reporting per model
  the share of answers passing the guard. **Acceptance: the default model passes 22/22; the run is
  kept in `docs/benchmarks/`.**
- Live: `just run -- --summarize` and `just ask "o que faço numa corrente de retorno?"` on `llama3.2`,
  output in the PR; `just site-build && just site-serve` checked at 390/1280 px in both languages, with
  location denied and allowed.

## 8. Risks, limitations, and honest caveats

- **Two corpora drift.** `i18n_check` catches a missing file, not a stale sentence, the human job
  `knowledge/README.md` already names. **The guard is a heuristic**: it cannot tell pt-BR from pt-PT
  and a sentence of names and numbers can fool it; it refuses English, it does not certify Portuguese.
- **Recompiled prompts are new prompts.** MIP-0040's "the reviewer was never validated" applies twice.
  **Small models, small margins**: with `smollm2:360m` the fallback line shows often, the design
  working, as the card predicts (§4.3).
- **Outside coverage stays honest and unhelpful** (a visitor in Recife sees Salvador and a line saying
  so; the fix is a provider, MIP-0031's shape). **Storage can vanish** (private tabs, blocked cookies):
  the language falls to `navigator.languages`, one tag in Safari; worst case one extra click. **A
  timezone is not a place**: §4.6 only picks a board, and the UI never says "you are in".

## 9. Alternatives considered

- **Do nothing.** Rejected by the release decision this MIP records and MIP-0050 §2's argument.
- **Translate at the edges** (English model, a translation pass after it): a second model call on
  the safety-relevant path, a second place to invent facts (MIP-0050 §9), an extra component for
  what `llama3.2` does natively; a machine-translated corpus is unsourced by construction. Rejected.
- **Portuguese only, no toggle**, wrong for a repo whose docs are English, and the catalog costs the
  same once it exists. **Language from location**, rejected (§5.5): a denied prompt must not cost
  the visitor their language. **IP geolocation in the page**, §4.5. **Regex over the English notes
  client-side**, hard-codes English as the wire format; codes are the contract.

## 11. Open questions

- **Which model backs the pt-BR default in Release 0?** `llama3.2` by the card; the benchmark decides.
  If it misses a question, does the release ship with the fallback line or wait for 22/22?
- **A native-speaker pass** on `pt-BR.json` and the corpus, the author is a model, §3's strings are
  proposals. Who reviews, and does it gate the merge or the release?
- **Does `notes` (English prose) stay on the wire**, or drop at `schema` 3 once `/v1/` is frozen
  (MIP-0042)?
- **Telegram's language** (MIP-0002, unbuilt): its user `language_code` as precedence (3), `/lang` as
  the toggle, MIP-0002's design when it resumes.
- **`docs/1-Using-marola/RUN-LOCALLY.md`**: a Portuguese twin, or a pt-BR pointer paragraph at the top? Proposed: the
  pointer.
- **Follow-up MIP:** MIP-0050 §5.2 (fine-tuning marola-sea on a Portuguese-native base) is unblocked
  once `knowledge/pt-BR/` exists, `build_dataset.py` already verifies excerpts verbatim against the
  corpus it reads. That is MIP-0050's own next task, not this one's.

## Appendix

### Checked live
All 2026-09-12.

- MDN Geolocation API (https://developer.mozilla.org/en-US/docs/Web/API/Geolocation_API): "available only in secure contexts (HTTPS)"; "users must grant explicit permission via a prompt when either `Geolocation.getCurrentPosition()` or `Geolocation.watchPosition()` is called (unless the permission state is already `granted` or `denied`)"; default Permissions-Policy allowlist `self`; a grant "may be time based, session based, or even permanent".
- MDN `GeolocationPositionError.code` (https://developer.mozilla.org/en-US/docs/Web/API/GeolocationPositionError/code): `1 PERMISSION_DENIED`, `2 POSITION_UNAVAILABLE`, `3 TIMEOUT`.
- MDN `Navigator.languages` (https://developer.mozilla.org/en-US/docs/Web/API/Navigator/languages): BCP-47 tags "ordered by preference with the most preferred language first"; `navigator.language` "is the first element"; Safari (always) and Chrome incognito list one language.
- MDN `Window.localStorage` (https://developer.mozilla.org/en-US/docs/Web/API/Window/localStorage): `SecurityError` when "the user has configured the browsers to prevent the page from persisting data"; per protocol/origin; "saved across browser sessions"; private-browsing data cleared with the last private tab; `file:` undefined.
- Ollama `llama3.2` (https://ollama.com/library/llama3.2): "Supported Languages: English, German, French, Italian, Portuguese, Hindi, Spanish, and Thai are officially supported."
- Ollama `qwen2.5` (https://ollama.com/library/qwen2.5): "multilingual support for over 29 languages, including Chinese, English, French, Spanish, Portuguese, …"; sizes 0.5B–72B.
- Ollama `smollm2` (https://ollama.com/library/smollm2): sizes 135M/360M/1.7B; no language statement on the page.
- Hugging Face `HuggingFaceTB/SmolLM2-360M-Instruct` (https://huggingface.co/HuggingFaceTB/SmolLM2-360M-Instruct): "SmolLM2 models primarily understand and generate content in English."; Apache-2.0.
- IMA/SC (https://balneabilidade.ima.sc.gov.br/): Portuguese only: "BALNEABILIDADE", "Selecione o município, o balneário e clique no botão 'Balneabilidade'", legend "Próprio"/"Impróprio"; no language switch.
- Open-Meteo Marine API (https://open-meteo.com/en/docs/marine-weather-api): numeric arrays plus `hourly_units` (`"wave_height": "m"`); no locale parameter; key "Only required to commercial use"; DWD attribution required.
- ipwho.is docs (https://ipwhois.io/docs): "No API key required"; "1,000 requests per day"; HTTPS "especially for browser-based requests from secure websites"; CORS requests "counted per domain, not per individual visitor IP"; "Commercial use allowed".
- ip-api.com docs (https://ip-api.com/docs): "Go pro" lists "unlimited queries, SSL and commercial use", i.e. the free tier is HTTP-only; the CLI calls it over `http://` (`IpGeolocation.scala:62`).
- ipinfo.io developers (https://ipinfo.io/developers): "JSONP and CORS are supported"; the documented *Lite* response has country fields and no `loc`.
- This repo: `index.html:2,7,14,34,38-39,46,64,73`; `app.js:87,94,349,397,402-403,538-545`; `site_check.js:69,169,207-211,239,339,347-356`; `style.css:121-133`; `Swimability.scala:76-179`; `Report.scala:42-150`; `OceanQa.scala:21-104`; `SafetyFooter.scala:8-22`; `LocalLlmClient.scala:29`; `train_lora.py:64-89`; `IpGeolocation.scala:28-70`; `Main.scala:26-77`; `board.schema.json:90-133`; `strip_external_scripts.py:1-14`; `areas.json` (`tz` per area); both compiled prompts (English instructions, 3 English demos each); seven English corpus files; `grep -rn 'i18n|locale|pt-BR'` → only `SafetyFooter`, `<html lang="en">` and a `repo_stats.py` flag.

### Not checked
- **No model was run in Portuguese.** §4.3 quotes cards; §7's arm is the measurement.
- `Intl.DateTimeFormat().resolvedOptions().timeZone`: from general knowledge, not fetched from MDN.
- Whether `https://ipinfo.io/json` without a token still returns `loc` (the CLI relies on it); only the Lite docs were read. Moot for the site (§4.5), relevant to the CLI's provider list.
- ip-api.com's free-tier rate limit; whether GitHub Pages sends a `Permissions-Policy` header.
- The Portuguese strings in §3, grammar, register and Brazilian usage unreviewed by a native speaker.
- Telegram's `language_code` field, from memory, not fetched. MIP-0050's Manacá findings, cited, not re-verified.
