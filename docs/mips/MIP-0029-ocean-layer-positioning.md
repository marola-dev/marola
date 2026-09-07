# MIP-0029: Positioning — marola as the ocean intelligence layer; "best hour to swim" as its first use case

| | |
|---|---|
| **Status** | Implemented — merged as PR #179 ("docs: implement MIP-0029 — marola as the ocean intelligence layer"), single-PR-sized per this MIP's own Effort row and the `mip-tasks` skill's "skip stacking for a single-PR change" rule |
| **Author** | Claude Sonnet 5, for M. Hoffmann (request of 2026-09-06: "rebrand marola to be the ocean layer intelligence, the best place to swim is just one of its own cases, consider this decision taking into consideration all planned mips"; refined the same day — "designed to be ocean intelligence layer, but acting like wave intelligence layer, but without being cocky … consider ALL MIP scopes") |
| **Created** | 2026-09-06 |
| **Number note** | 0029, not 0023 — 0020/0023/0024/0025 are already claimed by open drafts on remote branches (Instagram pipeline, waitlist-promotion, sea-model), and `docs/ROADMAP.md` §5 separately *proposes* 0023–0028 for unbuilt AI-500 work. Those two claims on 0023/0025 disagree and neither is this MIP's business to resolve — flagged here, revisited in §11 |
| **Phase** | 0 (docs and strings only; no pipeline, scoring, or infra change) — no earlier-phase prerequisite is missing |
| **Related** | Every MIP in `docs/mips/README.md` (0001–0022, surveyed in §4); `docs/ROADMAP.md` §5/§7's proposed 0023–0028; `docs/FUTURE-WORK.md` §1 (multi-activity generalization — the structural argument this MIP is a naming consequence of); `.claude/skills/site-frontend/SKILL.md` (the ≤12-word, no-adjective copy rule this MIP's wording must obey); open drafts `docs/mip-0020-instagram-pipeline` (handle `@marola.swim`) and `docs/mip-0023-waitlist-promotion` (domain strategy §5.1) — both name identity choices this MIP's wording must stay consistent with, flagged in §8/§11 |
| **Effort** | S — strings and docs only, no new module, no new dependency, no schema change. Touches ~9 files (§5) |
| **Gain** | `user value` (a five-second read of the map or the bot that doesn't undersell what the product already does — water quality, tides, sea lore, hazards, not swimming alone); `community/outreach` (a name that survives adding surf/dive/whale-watching without a second rebrand, relevant to MIP-0018/0020's promotion work) |
| **Effort vs Gain** | cheap win — no code risk, reversible in one PR, and every planned MIP in §4 already fits the frame without a scope change, so the cost of adopting it now is purely the string-and-doc diff |
| **Depends on** | Nothing blocks this at Phase 0. It should land before MIP-0018's exporter and MIP-0020's Instagram bio/first post start writing copy about the product (both cite `README.md`/`app.js` phrasing as their source text), so those don't have to be re-worded twice |
| **Risk** | A vaguer name loses the five-second clarity the map has today ("best hour to swim" tells a stranger exactly what to tap); if the tagline doesn't carry a concrete verb, the rebrand trades clarity for scope and nets nothing |
| **Cost so far** | — |

## 1. Summary

marola is already, in substance, more than a swim-time answer: MIP-0001 grounds it in bathing-water
quality and a sourced sea-lore corpus, MIP-0009/0016 put tides, wind, jellyfish and water-quality
marks on the map, MIP-0021/0022 add accessibility and a safety footer, and `FUTURE-WORK.md` §1
already argues the scoring engine should generalize past swimming to surf, dive, sailing, fishing,
whale watching. **The product name doesn't change — marola stays marola everywhere (the README and
site `<h1>`, the repo, the bot, the CLI banner).** What changes is the slogan paired with the name:
**"marola — the ocean intelligence layer,"** the deterministic, sourced layer that knows the sea
near you (conditions, water quality, sea life, tides, hazards, lore) and answers questions about
it — with "what's the best hour tomorrow to swim nearby" named as its first case, not the whole
product. No scoring, safety text, or architecture changes; only the strings and docs that describe
the product.

## 2. Motivation — where the current, swim-only positioning lives

Every place the "best hour to swim" one-liner is the *product description* (not a data field, a
test name, or a historical MIP title — those stay, they describe swimming, correctly, because
swimming is the use case they built):

| Surface | File:line | Current text |
|---|---|---|
| README hero | `README.md:4` | "Local-first ocean intelligence for open-water swimmers: the best hour to swim tomorrow, official bathing-water quality…" |
| PHILOSOPHY.md, "Why marola" | `PHILOSOPHY.md:23` | "The question 'what is the best hour tomorrow to swim nearby?' is small enough to finish and hard enough to be honest about." |
| ARCHITECTURE.md §1, MVP hypothesis | `docs/ARCHITECTURE.md:23` | "**MVP hypothesis:** a Telegram message — 'what's the best hour tomorrow to swim nearby?' — gets back a ranked list…" |
| AGENTS.md, "What this repo is" | `AGENTS.md:8` | "**marola** — a Telegram assistant answering 'what's the best hour tomorrow to swim nearby?'" |
| Telegram bot description (BotFather) | `docs/TELEGRAM-SETUP.md:22` | example command list: `` `swim - best hour to swim nearby` `` |
| Site `<title>` | `site/static/index.html:6` | "marola — best hour to swim, every beach" |
| Site meta description | `site/static/index.html:7` | "Every beach around the area ranked by swim conditions for today and tomorrow: sea, wind, waves, water quality, tides, jellyfish. Computed once, no tracking." |
| Site tagline (under the `<h1>`) | `site/static/index.html:16` | "Best hour to swim at every beach: sea, wind, tide, water quality." |
| CLI banner (`--summarize`/default run) | `cli/src/main/scala/marola/Main.scala:394` | `Console.printLine("marola :: best hour tomorrow to swim nearby (POC)")` |
| CLI entry-point doc comment | `cli/src/main/scala/marola/Main.scala:19` | "POC entry point for 'what's the best hour tomorrow to swim nearby?'." |

Notably, `README.md:4` already says "ocean intelligence" — the phrase exists, but is immediately
narrowed to "for open-water swimmers" in the same sentence, and every other surface above still
frames the whole product as the swim question, not one case of a broader layer. `PHILOSOPHY.md:23`
and `AGENTS.md:8` are the strongest examples: both use the swim question to justify the *entire*
architecture, when the actual justification (live data + a decision that can hurt someone if wrong
+ a sentence a person will read) applies to every use case §4 surveys, not swimming specifically.

## 3. User-visible change

### 3.1 The frame

**Before:** marola is a swim-time app that also happens to know about water quality and jellyfish.

**After:** the product name doesn't change — it's still marola, everywhere (README/site `<h1>`,
repo, bot, CLI). What changes is the slogan that now sits next to the name: **"marola — the ocean
intelligence layer"** — the sourced, deterministic layer that knows what the sea near you is doing
(conditions, water quality, sea life, tides, hazards, lore) and answers questions about it. "The
best hour tomorrow to swim nearby" is named as its first case — the one every current MIP was
built against — not a ceiling on what the layer can be asked, and not a rename.

### 3.2 On "OIA"

The maintainer's shorthand needs a fixed expansion or an explicit decision to stay internal.
Proposed: **do not adopt "OIA" as a public name.** "Ocean Intelligence Agent" overstates what
marola does today — no autonomous agent exists yet (`docs/AI-500-MAPPING.md`'s own gap: "one
implicit two-agent pipeline… hardcoded"), and "Assistant" duplicates `AGENTS.md:8`'s existing
"Telegram assistant." Keep **"ocean intelligence layer"** as the prose phrase (matches the
layer/pluggable-integration language `ARCHITECTURE.md` already uses for
`LlmClient`/`VisionClient`/`SightingStore`); "OIA" stays an internal planning shorthand only,
never in README/site/bot copy — an initialism in front of a five-second read works against the
site-frontend skill's own rule (`.claude/skills/site-frontend/SKILL.md:74`, "nouns from the
data," not an acronym).

### 3.3 The wording, before/after

No marketing adjectives anywhere below ("smart", "AI-powered", "seamless", "revolutionary") — the
repo's tone, per `PHILOSOPHY.md` and the site-frontend skill, is the number and its source.

**Name — slogan pair** (the name never changes; this string is the pairing used wherever a title
line appears): **"marola — the ocean intelligence layer."** Used for the README title line (the
bold subtitle directly under the `<h1>🌊 marola</h1>` wordmark, which stays as-is) and the site's
`<title>` tag (the browser-tab text, distinct from the on-page `<h1>marola</h1>` wordmark, which
also stays as-is).

**One-line description** (≤ 12 words, concrete nouns, site-frontend skill rule) — sits *under* the
name—slogan pair, e.g. as the on-page tagline:

- Before (`site/static/index.html:16`): "Best hour to swim at every beach: sea, wind, tide, water quality." (11 words, already compliant, but swim-only)
- After: "The ocean near you: conditions, water quality, sea life, tides, hazards." (11 words, unchanged from the earlier draft of this MIP)

**README title line + hero paragraph** (`README.md:1-7`):

- Before: `<h1>🌊 marola</h1>` then "**When LLMs meet the ocean.** Local-first ocean intelligence for open-water swimmers: the best hour to swim tomorrow, official bathing-water quality per sampling point, tides, jellyfish and whale odds, and a grounded 'ask the ocean' — all on your own machine with a free model (Scala 3 / Kyo / Ollama), sourced or clearly labelled, never invented."
- After: `<h1>🌊 marola</h1>` (unchanged) then "**marola — the ocean intelligence layer.** The ocean near you: conditions, official bathing-water quality per sampling point, tides, jellyfish and whale odds, and a grounded 'ask the ocean' — first case, the best hour tomorrow to swim, all on your own machine with a free model (Scala 3 / Kyo / Ollama), sourced or clearly labelled, never invented."

**Site `<title>` and on-page tagline** (`site/static/index.html:6,16`): `<title>` changes from
"marola — best hour to swim, every beach" to **"marola — the ocean intelligence layer"** (the
name—slogan pair, browser tab only). The on-page `<h1>marola</h1>` wordmark is unchanged; the
`<p class="tagline">` under it becomes the one-line description above: "The ocean near you:
conditions, water quality, sea life, tides, hazards." Meta description (`index.html:7`) drops
"swim conditions" for "conditions" and keeps the rest verbatim (jellyfish, tides, water quality
already read as more than swimming), and gains a closing clause naming the first case: "…Computed
once, no tracking. First case: the best hour tomorrow to swim."

**Bot description** (`docs/TELEGRAM-SETUP.md:22`'s example, cosmetic, set via BotFather):

- Before: `swim - best hour to swim nearby`
- After: `swim - best hour to swim nearby` stays as the *command* description (the command is
  literally about swimming — no reason to rename it), but the bot's own `/setdescription` text
  (not yet written, since Phase 1 isn't built) should read: "marola — the ocean intelligence
  layer: conditions, water quality, tides, sea life. First case, `/swim`, the best hour tomorrow."
  — documented in `docs/TELEGRAM-SETUP.md` as guidance for whoever registers the description at
  Phase 1, since the bot isn't live to set it today.

**CLI top banner** (`cli/src/main/scala/marola/Main.scala:394`) — the name stays first, unchanged:

- Before: `"marola :: best hour tomorrow to swim nearby (POC)"`
- After: `"marola :: the ocean intelligence layer (POC) — first case: best hour tomorrow to swim nearby"`

**PHILOSOPHY.md's "Why marola" opening** (`PHILOSOPHY.md:23`):

- Before: "The question 'what is the best hour tomorrow to swim nearby?' is small enough to finish
  and hard enough to be honest about."
- After: "marola — the ocean intelligence layer: the question it answers first, 'what is the best
  hour tomorrow to swim nearby?', is small enough to finish and hard enough to be honest about —
  and the same layer (live data, a decision that can hurt someone if wrong, a sentence a person
  will read) is what any other question about the sea near you needs too."

**AGENTS.md "What this repo is"** (`AGENTS.md:8`):

- Before: "**marola** — a Telegram assistant answering 'what's the best hour tomorrow to swim
  nearby?' — real nearby beach discovery…"
- After: "**marola** — the ocean intelligence layer for a stretch of coast, reachable as a
  Telegram assistant: real nearby beach discovery… Its first case is 'what's the best hour
  tomorrow to swim nearby?'…" (rest of the paragraph unchanged — it already lists water quality,
  jellyfish/whale, corpus, local-first, unchanged by this MIP).

## 4. MIP-by-MIP fit — every planned or drafted MIP as part of one ocean-intelligence project

Read against the new frame, not a swim app with extras bolted on. "Scope/wording change?" is
almost always **no** — the point of the rebrand is that it costs nothing to adopt, because the
work these MIPs describe was never swim-specific; it was described in swim terms because that was
the product's only stated use case.

| MIP | What it adds to the ocean layer | Use case(s) served | Scope/wording change? |
|---|---|---|---|
| 0001 water quality + sea lore | Bathing-water quality per point; sourced sea-lore corpus (any topic) | swim, sea life/lore | No — already layer-shaped |
| 0002 Telegram bot, Phase 1 | The addressable surface the layer is reached through | all | No — only its description text (§3) |
| 0003 fast replies / caching | Answers in under 3s regardless of question | all | No |
| 0004 daily digest / subscriptions | Push habit; digest content is swim-shaped today | swim now; any case once parameterized | Wording — "conditions," not "swim conditions" |
| 0005 the map, static site | Visual face of the layer, one marker per beach | all (renders swim score today) | Wording only — §3.3's title/tagline/meta |
| 0006 live look, user cameras | Ground-truth visual conditions, activity-independent | swim, dive, surf | No |
| 0007 time-series foundation models | Better forecasts of the same live series | all | No |
| 0008 Docker images | Ships the layer, any backend | all | No |
| 0009 wave markers + hover aspects | Wind/whale/jellyfish/temperature per point | swim, sea life, any activity | No |
| 0010 MLflow experiment tracking | Ledger for runs across whatever the layer is asked | all | No |
| 0011 Claude Code best practices | Dev-tooling only | none (infra) | No |
| 0012 llm4s / DSPy deprecation | Synthesis step's implementation, not its scope | all | No |
| 0013 OpenCode tryout | Dev-tooling only | none (infra) | No |
| 0014 marola book | Outreach artifact explaining the project | outreach | Wording — lead with the layer, not "swim app" |
| 0015 Interação swim matching | Who else is swimming here — deliberately swim-only | swim only | No — correctly scoped, no need to generalize |
| 0016 water-quality map markers | Water quality placed correctly on the map | swim, any water-touching activity | No |
| 0017 agentic tooling survey | Dev-tooling ideas | none (infra) | No |
| 0018 self-documentation / exporter | Publishes about the project (LinkedIn/blog/Reddit) | outreach | Wording — posts lead with the layer, swim as example |
| 0019 arXiv ocean-forecasting survey | Better forecast/jellyfish-prediction techniques | all (activity-agnostic) | No |
| 0020 Instagram bot (draft) | Promotion; bio/captions say "best hour…to swim" today, handle `@marola.swim` | outreach | Wording — bio should follow §3.3; handle is that MIP's call, flagged §8 |
| 0021 beach accessibility | Parking/toilets/showers/lifeguard from OSM | swim, any beach-based activity | No |
| 0022 safety answer footer | Lifeguard/193/SAMU grounding, activity-agnostic | all | No — §6 says this MIP doesn't touch safety text |
| 0023 waitlist draft — wait-list/promotion/maintenance | Site copy ("soon, in the bot"), domain strategy | outreach | Wording — copy tracks §3.3; domain should read coastal, not swim-only, per §8 |
| 0025 sea-model draft — marola-sea-1.0 fine-tune | Domain-tuned local model for the whole synthesis step (tool-calls, safety adherence) | all | No — "marola-sea" already reads layer-shaped |
| ROADMAP §5's planned 0023–0028 (hazard/escalation agent, actor topology, Foundry+A2A, eval harness, managed identity, AI-103 RAG gap) | Multi-agent/AI-500 build-out: escalation agent on the same live series, addressable roles, shared state, governed identity | safety (activity-agnostic hazard), all (eval/identity underlie every use case) | No — architecture work; this frame makes "a third agent with a genuinely different responsibility" (`AI-500-MAPPING.md`) legible as a layer capability |
| `FUTURE-WORK.md` §1 — surf, dive, sail, whale-watch, fish | The naming consequence this MIP responds to: one `ActivityScoring` per activity, same data, same layer | surf, dive, sail, whale-watch, fishing | Not drafted; a second use case needs an `ActivityScoring` (§1.2), activity-aware `Recommender`/`BestHour`, per-activity card copy, a bot "what are you doing?" turn (`ROADMAP.md` K1) — none built; this MIP only clears the naming precondition |

## 5. Design — what changes in code, and what doesn't

**Only strings and docs change.** No trait, module, schema, or scoring function is touched. Files
this MIP's implementation PR would edit:

- `README.md:3-7` — hero paragraph (§3.3)
- `PHILOSOPHY.md:23` — "Why marola" opening sentence (§3.3)
- `docs/ARCHITECTURE.md:23-24` — reframe "MVP hypothesis" as the layer's first use case, keep the
  Telegram-message example verbatim (it's still accurate)
- `AGENTS.md:8` — "What this repo is" (§3.3); this is also where `CLAUDE.md`'s `@AGENTS.md` import
  means the change propagates automatically to Claude Code's view — no separate `CLAUDE.md` edit
- `docs/TELEGRAM-SETUP.md:22` — add the proposed `/setdescription` text as guidance (§3.3); leave
  the `/swim` command example as-is
- `site/static/index.html:6,7,16` — title, meta description, tagline (§3.3)
- `cli/src/main/scala/marola/Main.scala:19,394` — doc comment and banner string (§3.3)
- `docs/mips/README.md` — this MIP's index row (this same PR, per the `mip` skill step 5)

**Docs index update:** `docs/README.md` (the doc-index file `AGENTS.md` points to) gets no new row
— it already indexes `docs/mips/` as a directory, not per-MIP; nothing there names the swim
question specifically enough to need a change. `docs/ROADMAP.md` is left untouched by this MIP
(it's a snapshot dated 2026-09-06, and editing history there is out of scope) but its own "How this
file stays true" rule means whoever files the MIP-0029 implementation PR should add one line
noting the frame is adopted, per that file's own maintenance instruction.

## 6. Scoring / safety impact

**None — and this MIP explicitly must not touch:**

- `scoring/Swimability.scala`'s thresholds or any ranking logic.
- Safety text: the MIP-0022 footer (lifeguard/193/SAMU), the whale/jellyfish heuristic disclaimers
  in `docs/ARCHITECTURE.md` §8.
- The local-first promise: "entirely locally with a free Ollama model, zero Azure account needed"
  (`AGENTS.md:8`'s clause, unchanged) and every per-integration opt-in design in `ARCHITECTURE.md` §5.
- The "no cookies, no tracking" line — verified live at `site/static/app.js:11` ("no analytics, no
  cookies") and `site/static/app.js:280` ("No cookies, no tracking…soon, in the Telegram bot") —
  stays verbatim; §3.3 only changes the *tagline* above it, not this footer sentence.
- MIP-0005's own constraints (plain files, no build step, one accent colour, the score-colour
  tokens) — this MIP changes copy inside `index.html`, not layout, markers, or the schema
  `site/board.schema.json` depends on.

## 7. Verification plan

No test gate applies to a docs/strings-only change (`just quality` runs scalafmt/scalafix/ruff/
actionlint/hadolint — none of it checks prose). Verification here is manual, and was already done
while writing this MIP:

- Every file:line cited in §2 and §5 was read directly (`grep -n` against the actual file in this
  worktree, not memory) before being quoted — see the Appendix for the raw commands.
- The proposed one-liner and tagline (§3.3) were word-counted by hand: 11 words each, under the
  site-frontend skill's ≤12-word rule (`.claude/skills/site-frontend/SKILL.md:74`).
- No instance of "smart", "AI-powered", "seamless", "revolutionary", "world-class", or an
  exclamation mark appears in any proposed replacement string above — checked by re-reading §3.3.
- Once implemented: `node --check site/static/app.js` (unaffected — no JS changes), the
  site-frontend skill's `site_check.js` harness re-run to confirm the tagline renders (its DOM
  assertions target markers/list rendering, not copy content, so this is a smoke check not a gate).

## 8. Risks, limitations, and honest caveats

- **A vaguer name loses five-second clarity.** "Best hour to swim, every beach" tells a stranger
  exactly what to tap; "the ocean near you" needs one more glance to land on an action. §3.3 keeps
  a concrete verb ("swim tomorrow," in the README hero and CLI line) to hedge this — bounded to
  the site tagline and title, which stay short by design.
- **Identity choices in open drafts must agree with this name.** The waitlist draft's §5.1 domain
  strategy (`marola.app.br` etc.) doesn't reference "ocean"/"layer" — fine, a domain needn't
  restate the tagline, but its site-copy quote should be re-checked against §3.3 when finalized.
  The Instagram draft's handle `@marola.swim` is a stronger conflict: a handle saying "swim" next
  to a site saying "the ocean near you" is a real cross-surface inconsistency. This MIP doesn't
  resolve it — flagged for whoever finalizes that MIP, repeated in §11.
- **Nothing here needed external verification.** No web search or pricing check was made or
  needed — every citation in §2/§5 is a local file read.

## 9. Alternatives considered

- **Keep "swim" as the brand.** Zero cost, maximum clarity, but caps the story as MIP-0009/0016/
  0021's non-swim work keeps growing past what "swim app" implies.
- **"Beach intelligence."** Anchors to a place rather than the sea itself (which matters offshore,
  for divers and sailors who never touch the beach) — narrower than "ocean," not chosen.
- **"Sea assistant."** Duplicates `AGENTS.md:8`'s existing "assistant" noun and undersells the
  deterministic-layer framing `PHILOSOPHY.md` argues for — not chosen.
- **A separate product name per use case** ("marola-swim," "marola-surf"). Multiplies the branding
  work `FUTURE-WORK.md` §1 explicitly designed against (one `ActivityScoring` object per activity,
  no other code touched) — rejected as the opposite of that design goal.
- **Do nothing.** Cheapest, and not wrong today since no second use case is built — but every MIP
  in §4 already assumes the layer framing works without a rename, so the only real cost of waiting
  is a second copy pass once MIP-0018/0020 have written more text against the swim-only wording.

## 10. Exam-coverage mapping

None. This is a product-positioning change; it does not touch any AI-103 or AI-500 domain row.

## 11. Open questions

- **Does the Portuguese wording change?** "Marola" (a small wave) already carries the intended
  modesty — big scope by design (asks about anything the sea is doing), small and concrete in what
  it says (one beach, one hour, one number with its source). No Portuguese string in the repo was
  found that needs a corresponding English-side change (`grep -rn "marola" *.md docs/*.md` returns
  only the product name itself, never translated prose) — open question is whether a future
  Portuguese-language bot reply (`docs/ROADMAP.md` K10, i18n) should state the wordplay explicitly
  or leave it implicit, as English copy does today.
- **Do the Telegram handle / domain choices follow this rebrand?** `@marola.swim` (MIP-0020) and
  the `marola.app.br` family (MIP-0023 draft §5.1) were proposed before this positioning existed.
  A human decision is needed on whether to hold those MIPs' identity sections for this MIP to
  merge first, or let them proceed and reconcile wording only (not the handle/domain itself, which
  has its own registration-cost and availability constraints unrelated to naming taste).
- **Does the map's `<h1>` stay "marola"?** Resolved by the maintainer, 2026-09-06: yes — the
  product name stays marola everywhere (README/site `<h1>`, repo, bot, CLI banner); "the ocean
  intelligence layer" is a slogan paired with the name (in the site's `<title>` and the README's
  title line), not a replacement for the wordmark. §3.3 reflects this; the on-page `<h1>` and
  `site/static/index.html:14`'s wave icon are unchanged, only `<title>` (browser tab) and the
  `<p class="tagline">` text change.
- **Who resolves the 0023/0025 numbering collision noted in the metadata table?** Not this MIP's
  job, but the collision (ROADMAP's proposed AI-500 numbers vs. the waitlist/sea-model drafts that
  already claimed them) should be settled before either set of drafts merges, to avoid two MIPs
  sharing a number.

## Appendix

Raw verification commands run against this worktree (`docs/mip-0029-ocean-layer-positioning`
branch, based on `origin/main`) while drafting this MIP:

```
grep -n "best hour to swim tomorrow" README.md                 → README.md:4
grep -n "what is the best hour tomorrow to swim nearby" PHILOSOPHY.md → PHILOSOPHY.md:23
grep -n "MVP hypothesis" docs/ARCHITECTURE.md                  → docs/ARCHITECTURE.md:23
grep -n "^\*\*marola\*\*" AGENTS.md                             → AGENTS.md:8
grep -n "swim - best hour to swim nearby" docs/TELEGRAM-SETUP.md → docs/TELEGRAM-SETUP.md:22
grep -n "best hour to swim, every beach|tagline|meta name=\"description\"" site/static/index.html
    → index.html:6 (title), 7 (meta description), 16 (tagline)
grep -n "best hour tomorrow to swim nearby (POC)|POC entry point" cli/src/main/scala/marola/Main.scala
    → Main.scala:19 (doc comment), 394 (banner)
grep -n "Nothing leaves the browser|no cookies|no analytics|soon, in the Telegram" site/static/app.js
    → app.js:11 ("no analytics, no cookies"), app.js:280 ("No cookies, no tracking...")
```

Remote drafts read for §4/§8 (not merged, fetched via `git fetch origin` then `git show`):
`origin/docs/mip-0020-instagram-pipeline:docs/mips/MIP-0020-instagram-bot-account.md`,
`origin/docs/mip-0023-waitlist-promotion:docs/mips/MIP-0023-waitlist-promotion-and-maintenance.md`,
`origin/docs/mip-0025-marola-sea-model:docs/mips/MIP-0025-marola-sea-domain-model.md`.
`docs/ROADMAP.md` and `docs/mips/README.md` read from `origin/main` for §4's ROADMAP row and the
full 0001–0022 index.
