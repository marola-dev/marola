# MIP-0024: The daily Instagram pipeline — automated, per-day content on top of MIP-0020's account

| | |
|---|---|
| **Status** | Draft |
| **Author** | Claude Sonnet 5, for M. Hoffmann (request of 2026-09-06: turn the maintainer's Instagram-automation notes into a MIP) |
| **Created** | 2026-09-06 |
| **Phase** | 0 for the mechanism (outreach, no product code touched); Phase 1 (MIP-0002) gates when it's *right* to promote a bot, not whether the pipeline can technically run — see Depends on |
| **Related** | **`MIP-0020`** (`./MIP-0020-instagram-bot-account.md`, on the `docs/mip-0020-instagram-pipeline` branch — read in full before this one) covers the account itself: professional-account setup, the Instagram-Login API path (no Facebook Page needed), `scripts/ig_publish.py` (container → poll → publish, human-typed `--publish`+`yes`), the token-refresh workflow, and a first, one-off, human-posted image. It explicitly stops short of scheduling: *"Autonomous daily posting… is exactly what this MIP does not build — the day a fully unattended post is wanted, that is a new MIP with a kill switch, a per-day cap and a consent story."* **This MIP is that MIP** — it adds the daily cron trigger, per-day image rendering, and the consent gate MIP-0020 asked for, on top of MIP-0020's script and account rather than duplicating either; `.github/workflows/site.yml` (the board JSON this reads), `docs/mips/MIP-0005-map-and-static-site.md` (the site this posts from), `docs/mips/MIP-0018-self-documentation-and-media-exporter.md` (§4/§9 already surveyed social-posting APIs), `MIP-0023` (`waitlist-promotion-and-maintenance` — the same promotion axis, platform-agnostic), `AGENTS.md` (never hardcode a secret; phase discipline), `docs/AI-500-MAPPING.md` §4 (human-confirmation gate for autonomous/proactive behaviour — the exact gate MIP-0020 flagged as missing) |
| **Effort** | S on top of MIP-0020 — no new module, no new Meta app/account/token flow (all reused); adds one render step (Pillow, a new Python dependency), one cron workflow, and one GitHub Environment with a required reviewer |
| **Gain** | user value (reach beyond people who already have the map bookmarked, genuinely fresh daily content instead of MIP-0020's static image); cost/ops ($0 — see §4) |
| **Effort vs Gain** | `do when X lands` — technically buildable today, but per Depends on, a daily cadence is only worth the reviewer's daily approval-click burden once there's a reason to post daily (i.e., once Phase 1 gives the caption something to point at beyond "see the map", which MIP-0020's weekly cadence already does adequately) |
| **Depends on** | **`MIP-0020` must land first** — this MIP has no account, no token, no publish script of its own; it is a scheduling and rendering layer over MIP-0020's. Phase 1 (MIP-0002) is not a hard technical blocker (§4.1 of MIP-0020 already established that) but the same promotion-timing argument MIP-0020 and MIP-0023 both make applies here too: don't announce a bot before it exists. No paid Azure resource anywhere in this design |
| **Risk** | turning MIP-0020's deliberately-human "type `yes`" gate into a cron job is exactly the autonomy step MIP-0020's own risk section warned against — the design below (§5) keeps a human in the loop via a GitHub Environment approval rather than removing the gate, but a future edit that swaps that approval for `--publish --yes` on a schedule would be the wrong outcome to build toward |
| **Cost so far** | — (nothing from this MIP has merged yet) |

## 1. Summary

MIP-0020 gets marola an Instagram account, a verified publishing script, and one human-posted
image. This MIP adds the piece MIP-0020 named but didn't design: a scheduled pipeline that renders
a fresh image and caption from *every* day's board (not a static committed JPEG) and pushes it
through MIP-0020's own `ig_publish.py`, gated by a GitHub Actions Environment approval so a human
still clicks before anything reaches the public account — automation of the busywork, not of the
consent.

## 2. Motivation

MIP-0020 §5.3 picked a single committed JPEG because weekly cadence doesn't justify rendering; its
own table says to revisit that "only if posts become daily." The maintainer's original ask — a
*daily* forecast + water-quality + one corpus-insight post — is exactly that trigger. Reusing
MIP-0020's static image for a daily cadence would mean posting the same picture every day with only
the caption changing, which is worse than not automating at all (Instagram's own algorithm and any
follower would notice). This MIP exists to make each day's post carry that day's real numbers, cheaply.

## 3. User-visible change

Same account, same caption shape MIP-0020 §3 already designed ("Daily-digest variant" — best
window, water quality, jellyfish/whale reads, link in bio, fixed hashtags), except:

- **The image is rendered fresh per day** instead of being MIP-0020's one committed JPEG (§5.1).
- **One sourced line from `knowledge/*.md`** is appended, chosen deterministically (§5.3) — not in
  MIP-0020's template, which the maintainer's original brief for this MIP specifically asked for.
- If the featured beach's water quality is `Impropria`, the caption's *first* line is the warning,
  and no swim-window time is shown for that beach that day (§6) — MIP-0020's template didn't need
  this rule because it wasn't designed for daily, potentially-unsafe-day cadence.

```
⚠️ Água IMPRÓPRIA hoje em Praia da Joaquina (IMA/SC, amostra 2026-09-05) — sem janela recomendada.
🌊 Confira outras praias no mapa ao vivo: h0ffmann.github.io/marola
🧠 [uma frase sobre águas-vivas, com fonte] — knowledge/jellyfish-and-man-o-war.md
#Florianópolis #marola
```

vs. a normal day, which is MIP-0020 §3's template plus the corpus line.

## 4. Data sources and dependencies reviewed

### 4.1 Meta Graph API — deferring to MIP-0020, not re-verifying

MIP-0020 §4.1 fetched `developers.facebook.com/docs/instagram-platform/{content-publishing,
instagram-api-with-instagram-login,instagram-api-with-instagram-login/overview}` directly on
2026-09-06 and recorded, from Meta's own pages: the Instagram-Login path needs no Facebook Page;
Standard Access is sufficient for an app that only serves its own account (no App Review); the
container → poll → `media_publish` flow; a **100 posts / rolling-24h** publish cap (`GET
/<IG_ID>/content_publishing_limit` to check usage); long-lived tokens last 60 days and are
refreshable; permission names are `instagram_business_basic` / `instagram_business_content_publish`
(not the deprecated `instagram_basic`/`instagram_content_publish` the maintainer's own draft used).
**This MIP does not re-verify any of that** — it was independently checked via WebSearch in this
session and found genuinely conflicting third-party numbers (25/50/100 for the rate cap; disagreement
on the current API version number), which is exactly why MIP-0020's direct fetch of Meta's own pages
is the source of record here, not this MIP's own search. One post/day is nowhere near the 100/24h
cap either way, so the exact number doesn't gate this design.

### 4.2 The board JSON (existing, no new dependency)

Same as every MIP-0020/MIP-0005 reference: `site/dist/data/<area>/latest.json`, produced by
`.github/workflows/site.yml`. This pipeline reads it read-only; no new Overpass/Open-Meteo call.

### 4.3 Image rendering — resolving MIP-0020 §5.3's deferred choice

MIP-0020's table listed three options and picked "one committed JPEG" for weekly cadence, flagging
Option B (Pillow render) as blocked on "a basemap-tile licence question" and Option C (headless
Chromium) as too heavy for a weekly image. For genuinely daily content, Option A no longer works
(§2). **Pick: a variant of Option B that sidesteps the licence question entirely** — a plain
stat-card render (beach name, numbers, a solid-colour or gradient background, no basemap tile at
all) via Pillow, matching the site's own colour/typography choices (`site/static/style.css`) rather
than screenshotting a map. This needs a new Python dependency (`Pillow`) not currently in any
`requirements.txt` in the repo — confirmed by `grep -r Pillow` returning nothing outside this MIP.
Option C stays rejected for the same CI-weight reason MIP-0020 gave; daily doesn't change that.

### 4.4 Image hosting

Reuses MIP-0020 §4.1's finding: GitHub Pages already serves whatever `site/dist` contains, and the
Graph API only needs a public HTTPS URL, not an upload. This pipeline pushes each day's rendered
PNG to the `site-data` orphan branch under `social/daily/<date>.png` — the same "orphan branch as
free static storage" mechanism `site.yml` already uses for `smoke/`/`coverage/` — and `site.yml`'s
existing archive-and-copy step (or a small addition to it) makes the latest one reachable at a
stable `social/marola-daily.png` URL Meta always re-fetches fresh (image URLs are read once at
container-creation time, so a stable path is fine — confirmed by the container flow itself only
reading the URL once, per MIP-0020 §4.1/§5.2).

## 5. Design

New file: `scripts/render_instagram_digest.py` (Python, adds `Pillow` to a new
`scripts/requirements.txt` or an existing one if `scripts/` already has a Python-deps file —
verify at build time; none was found in this review). Reuses MIP-0020's `scripts/ig_publish.py`
unchanged for the actual publish call — this MIP does not touch that script's container/poll/
publish logic, only how and when it gets invoked.

New workflow: `.github/workflows/instagram-daily.yml`:

```
1. schedule: cron, once daily, after site.yml's own cron (e.g. "30 9 * * *")
2. Checkout; fetch the freshest board JSON (site-data or Pages output, same pattern site.yml uses
   for smoke/coverage)
3. python scripts/render_instagram_digest.py <latest.json> --out digest.png
     - builds the caption per §3 (normal template from MIP-0020 §3, or the safety-lead template
       when Impropria — see §6)
     - renders digest.png via Pillow, no basemap
     - picks the corpus line deterministically (§5.3 below)
4. Push digest.png to site-data under social/daily/<date>.png and update the stable
   social/marola-daily.png copy
5. job `publish` (separate job, `needs: render`, `environment: instagram-daily`):
     - GitHub Environment `instagram-daily` configured with a required reviewer (repo Settings →
       Environments) — the workflow run PAUSES here until a human clicks Approve in the Actions
       UI, exactly the "kill switch and consent story" MIP-0020's risk section asked for, achieved
       with a built-in GitHub mechanism instead of a bespoke prompt-for-input step (which cron jobs
       can't do anyway — there is no terminal for `ig_publish.py`'s `--publish`+typed-`yes` gate to
       run against in CI, which is the concrete reason MIP-0020's human-typed-yes model doesn't
       transfer to a scheduled job unchanged)
     - once approved: python scripts/ig_publish.py --image-url <pages url> --caption-file
       digest.caption.txt --publish (MIP-0020's own script, unmodified, minus the interactive
       prompt — the Environment approval is the consent step instead)
6. On any failure at any stage: the run fails visibly in Actions; no silent retry, no fallback post
```

**5.1 The consent gate, explicitly** (`AI-500-MAPPING.md` §4, the human-confirmation rule for
proactive behaviour, and MIP-0020's own risk section asking for exactly this before any schedule
exists): the render and caption-build steps run unattended — they are deterministic and reversible
(nothing is posted yet). The one irreversible action, the actual `media_publish` call, is gated
behind a GitHub Environment with a required reviewer, so a human approves *that day's specific
image and caption* before it goes live, not a blanket "yes, automate this forever." This is
weaker than MIP-0020's typed-`yes` (a click, not typed confirmation) but stronger than true
autonomy, and it is the only mechanism available inside a scheduled Actions run — there is no
terminal to type into. If the reviewer doesn't approve within some window (Environment settings
support a wait timer), the run simply expires without posting.

**5.2 What goes through the LLM.** Nothing changes from MIP-0020's stance: the numeric facts come
straight from the board JSON. The corpus line is picked, not generated (§5.3) — no live model call
in this pipeline at all, so there is nothing for `Reviewer` (`core/llm/Reviewer.scala`) to check
and no new failure mode from a model call inside an unattended job.

**5.3 The corpus-insight line.** Deterministic selection from `knowledge/*.md`'s existing chunks
(the same corpus `ask_ocean_question` serves) — e.g. `hash(date + area) % len(chunks)` so the same
day always picks the same line if the workflow re-runs, and every chunk cycles through over roughly
one corpus-length's worth of days. The line is shown verbatim with its source, never paraphrased —
same "no unsourced facts reach a user" rule the `mip` skill states and MIP-0020 already applied to
drop the pasted draft's "Rip Current Risk" line for having no backing signal.

## 6. Scoring / safety impact

None to `Swimability.score`. The one rule this MIP adds on top of MIP-0020's template: **a
featured beach with `waterQuality == Impropria` gets the safety-lead caption variant (§3), never
the normal window-first template.** This is enforced by the caption-builder function in
`render_instagram_digest.py`, covered by a unit test (§7), not a judgment call left to formatting
convention.

## 7. Verification plan

- Unit tests: `test_caption_impropria_leads_with_warning`, `test_caption_normal_day_matches_
  mip0020_template`, `test_corpus_line_deterministic_for_same_date`, `test_render_produces_valid_png`.
- Live checks (require a human, real credentials, and MIP-0020 already merged and its account
  live): run the render step against a real `latest.json`, confirm the Environment approval gate
  actually pauses the workflow (a deliberately-not-approved run should time out and post nothing),
  then approve once and confirm the post appears via MIP-0020's own script.
- "Done" = one real automated-but-approved post published from the scheduled workflow, and one
  deliberately-skipped (not approved) run confirmed to post nothing.

## 8. Risks, limitations, and honest caveats

- A daily approval click is still a recurring human burden — if nobody clicks, the pipeline posts
  nothing, which is the safe failure mode but means "automated" here means "automated *up to* the
  approval," not fully unattended. This is a deliberate trade, not an oversight (§5.1).
- The stat-card render (§4.3) is a new visual style, not the map itself — it needs an actual design
  pass (colours/typography) before this ships; not designed further in this MIP.
- Depends entirely on MIP-0020 merging with its account/script/token-refresh design intact; if that
  MIP's design changes materially (e.g., switching to Facebook Login, needing a Page), this MIP's
  §4.1 deferral to it needs re-reading, not blind reuse.
- Every Graph API number in this MIP is inherited from MIP-0020's fetch, dated 2026-09-06 — Meta
  can change access tiers or limits without notice, same caveat MIP-0020 already states.

## 9. Alternatives considered

- **Extend MIP-0020's `ig_publish.py` with a `--cron` flag that skips the typed-`yes` prompt** —
  rejected: that removes the consent step MIP-0020 explicitly wanted kept, rather than replacing it
  with an equivalent one; the GitHub Environment approval (§5) keeps an equivalent gate.
- **No approval gate at all (true autonomy)** — matches the maintainer's original "autonomous daily
  posting agent" framing most literally, but MIP-0020's risk section and `AI-500-MAPPING.md` §4
  both argue against removing a human-confirmation gate for proactive/repeated public action;
  rejected in favor of §5's compromise.
- **Post to Telegram channel first instead of Instagram** — cheaper, no Meta dependency at all, and
  MIP-0020's own §9 already didn't need to consider it since it's an Instagram-specific MIP; this
  MIP inherits the same "Instagram, specifically" scope from the maintainer's ask, but a Telegram
  version of this same render+cron+approval pattern would be strictly simpler and is worth doing
  first if reach outside Instagram's audience isn't the point.
- **Do nothing beyond MIP-0020's weekly, human-posted cadence** — genuinely fine, and MIP-0020 says
  so; this MIP only exists because the maintainer specifically asked for *daily* automation.

## 10. Exam-coverage mapping

None directly. The consent-gate design (§5.1) is the same "keep a human in the loop for proactive
behaviour" principle `docs/AI-500-MAPPING.md` §4 already tracks for the escalation-agent idea in
`FUTURE-WORK.md` — this MIP applies that same principle to a different proactive action (a public
post) rather than adding new mapped coverage.

## 11. Open questions

1. Is a daily cadence actually wanted, or does MIP-0020's weekly, fully-human-posted flow already
   satisfy the reach goal at a fraction of the design/maintenance cost? This MIP's own Effort vs
   Gain says `do when X lands` for exactly this reason.
2. Environment-approval timeout: how long should an unapproved run wait before expiring? Not set
   in this design.
3. The stat-card visual design (§4.3/§8) needs an actual mockup pass before building
   `render_instagram_digest.py` for real.
4. Should the corpus-line rotation (§5.3) avoid repeating the same chunk within some window even
   across the `hash(date+area)` scheme, to reduce the odds of an obviously-repeated line on
   consecutive days for the same area? Not resolved here.
