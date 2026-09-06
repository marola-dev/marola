# MIP-0020: An Instagram account for marola — first post delivers the live-map URL

| | |
|---|---|
| **Status** | Draft |
| **Author** | Claude Fable 5.1, for M. Hoffmann (request of 2026-09-06: "create MIP for instagram bot account, with first post being github pages URL delivery … check if instagram bot account enable programatic posting") |
| **Created** | 2026-09-06 |
| **Phase** | 0 — outreach for the project, not marola-the-product. No earlier-phase prerequisite; nothing here touches `Swimability`, `Recommender`, the CLI or the map |
| **Related** | MIP-0018 (self-documentation + multi-platform exporter — Instagram becomes one more export target, and the only one besides dev.to/Reddit with a publishing API a personal account can actually use), `docs/SELF-DOCUMENTING.md` (MIP-0018's research; it has no Instagram entry yet), MIP-0005 (the live map the first post points at), MIP-0014 (the other "community/outreach" MIP), `.claude/skills/mip/SKILL.md` "no unsourced facts reach a user" |
| **Effort** | M — no new module or JVM dependency: one Python script (`scripts/ig_publish.py`, stdlib, `--self-test` on a recorded fixture), one committed JPEG, one `just` recipe, one scheduled token-refresh workflow; the Meta developer-app setup is human clicking, not code |
| **Gain** | community/outreach (a public, visual channel for the map — the product's most shareable surface); infra/dev-loop (MIP-0018's exporter gets a target that publishes for real instead of "copy-paste by hand") |
| **Effort vs Gain** | cheap win for v1 (a human posts the committed image from a phone with the exporter's caption — zero API work); `do when MIP-0018 lands` for the API path, since publishing without the planner/queue is a script with nothing to schedule |
| **Depends on** | MIP-0005 (live, `https://h0ffmann.github.io/marola/`); MIP-0018 for anything beyond the first post. No Phase 1 gate, no paid resource: the Instagram API is free, an Instagram professional account is free, no Facebook Page is needed (§4.1), no Azure anywhere |
| **Risk** | a dormant account — MIP-0018's own research found changelog-style posts fail; an "Instagram bot" that posts machine text on a schedule is that failure with pictures. The API path is only worth building if the human actually writes the captions weekly |
| **Cost so far** | — |

## 1. Summary

marola gets an Instagram professional account whose first post is a picture of the live map with
the GitHub Pages URL in the caption and the bio. Programmatic posting is possible and free —
Meta's "Instagram API with Instagram Login" publishes single images, carousels, Reels and Stories
for a Business/Creator account, with no Facebook Page and no App Review as long as the app only
serves the account its developer owns (§4). v1 is deliberately a human posting from a phone; the
script that publishes through the API is v2, as an `instagram` target of MIP-0018's exporter.

## 2. Motivation

The map (`https://h0ffmann.github.io/marola/`, `README.md:22`) is the one artifact a non-developer
can look at and understand in five seconds, and today it is linked from a README badge and
nowhere else. MIP-0018 designed a weekly build-in-public workflow but every platform it researched
except dev.to/Reddit had no usable posting API (LinkedIn is gated to verified companies, Substack
has none). Instagram is the opposite case: visual-first, and — verified below — a personal
developer *can* publish to their own professional account programmatically. The question the
request asked ("does an Instagram bot account enable programmatic posting?") has a precise answer:
yes, for a **professional** account, with JPEG media hosted at a **public URL**, at most **100
posts per rolling 24 h**, and **no text-only posts**.

## 3. User-visible change

None for marola's Telegram/CLI users. For the public: an Instagram profile, e.g. `@marola.swim`
(name availability unverified — §11), bio:

```
marola — what's the best hour tomorrow to swim nearby?
Live map, Florianópolis, updated daily 👇
https://h0ffmann.github.io/marola/
```

First post: one square JPEG of the map (Florianópolis, wave markers coloured by score — after
MIP-0009; plain dots before it), caption built from the day's board, no model prose:

```
Tomorrow's best hour to swim, beach by beach — Florianópolis, 2026-09-07.
Best: Praia da Joaquina, 72/100 at 10:00.
Live map (updated daily, link in bio): h0ffmann.github.io/marola
#Florianópolis #praia #natação #openwater #swimming #marola
```

The URL in the caption is plain text — Instagram does not make caption links clickable — which is
why it is also the bio link.

## 4. Data sources and dependencies reviewed

### 4.1 Instagram Platform — content publishing (Meta developer docs, fetched 2026-09-06)

`https://developers.facebook.com/docs/instagram-platform/content-publishing`:

- **Who can publish:** only "Instagram professional accounts" — Business or Creator. A personal
  account cannot; converting is free and done in the app.
- **Flow:** two calls — `POST /<IG_ID>/media` creates a container (`image_url`, `caption`), then
  `POST /<IG_ID>/media_publish` with `creation_id`. Video/Reels containers are processed
  asynchronously (poll `status_code`).
- **Media:** single image, single video, Reels, Stories, carousels of up to 10 items. **No
  text-only posts.** "JPEG is the only image format supported." Media "must be hosted on a
  publicly accessible server" — a URL, not an upload (video has an upload path; images do not).
  Carousel items are cropped to the first item's ratio, default 1:1. Single-image aspect-ratio
  limits and maximum size were **not stated on that page** — §11.
- **Limit:** "Instagram accounts are limited to 100 API-published posts within a 24-hour moving
  period. Carousels count as a single post." Check with `GET /<IG_ID>/content_publishing_limit`.
- **Permissions:** with Instagram Login — `instagram_business_basic`,
  `instagram_business_content_publish`; with Facebook Login — `instagram_basic`,
  `instagram_content_publish`, `pages_read_engagement` (plus `ads_*` in some Business Manager
  setups).

`https://developers.facebook.com/docs/instagram-platform/instagram-api-with-instagram-login` and
its `/overview` (same date):

- "This API setup does not require a Facebook Page to be linked to the Instagram professional
  account." Host `graph.instagram.com`. It "cannot access ads or tagging" — irrelevant here.
- **Access levels:** Standard Access (the default, no review) is "sufficient if your app only
  serves your Instagram professional account or an account you manage". Advanced Access —
  "App Review and Business Verification" — is required only for "accounts that you don't own or
  manage". marola's account is owned by the developer, so **no App Review for v2**.
- **Tokens:** short-lived "valid for one hour", long-lived "valid for 60 days and can be refreshed
  before they expire" — a refresh job every <60 days is part of the design, not an afterthought.
- **Rate limit:** "4800 × number of impressions" calls per rolling 24 h — irrelevant at one post a
  week; the 100/24 h publishing cap is the binding one.

**Not checked (→ §11):** caption length (commonly cited as 2,200 characters) and the 30-hashtag
cap — not re-verified on Meta's pages today; how long a Meta developer account takes to create;
whether Meta later tightens Standard Access for publishing; Instagram's Platform Policy on
"automated" accounts beyond the API terms.

**What is *not* possible, plainly:** posting from a personal account; text-only posts; uploading an
image file directly (it must sit at a public URL); a clickable link in a caption; posting without
a Meta developer app and a long-lived token that someone renews.

### 4.2 The URL and the image

The Pages URL is `https://h0ffmann.github.io/marola/` — set by `.github/workflows/site.yml`
(`actions/deploy-pages@v5`, line 161; the URL is stated in its header comment, line 19), quoted
in `README.md:14,22` and `docs/RUN-LOCALLY.md:291`. It is derivable from the repo; nothing to
invent.

The image: the repo has **no PNG/JPEG of the map** and `site.yml` runs no headless browser
(`grep playwright|puppeteer|chromium` over the workflows and `justfile`: nothing). MIP-0005 keeps
the site "plain files, no build step". Three ways to get a public JPEG, weighed in §5.3.

### 4.3 The pick

Instagram API with Instagram Login (no Facebook Page, Standard Access, `graph.instagram.com`),
single-image posts, image served by GitHub Pages itself. v1 needs none of it.

## 5. Design

### 5.1 v1 — first post, by hand (this is the "cheap win")

1. Create the Instagram account, switch it to **Creator** (or Business), set the bio above.
2. Commit `site/static/social/marola-map.jpg` — a 1080×1080 JPEG screenshot of the live map taken
   by a human once, cropped square (§5.3 says why a committed file). `site.yml` already copies
   `site/static/**` into `site/dist`, so it becomes
   `https://h0ffmann.github.io/marola/social/marola-map.jpg` — the public URL v2 needs, for free.
3. Caption: `scripts/post-exporter.py` (MIP-0018 §5.3) gains an `instagram` formatter that
   renders the §3 template from `site/dist/data/<area>/latest.json` (best beach, score, hour,
   date) — deterministic fields plus the fixed hashtag list; no LLM. Until MIP-0018 exists, the
   human types the same eight lines.
4. Post from the phone. Done — the first post exists, the URL is delivered.

### 5.2 v2 — publishing through the API (`do when MIP-0018 lands`)

- `scripts/ig_publish.py` (Python, stdlib `urllib`, like `scripts/arxiv_digest.py`):
  `--image-url`, `--caption-file`, `--dry-run` (prints the two requests, sends nothing),
  `--check-limit` (`content_publishing_limit` first; refuse if `quota_usage` ≥ 90),
  `--self-test` (a recorded container + publish response fixture under `scripts/fixtures/`, no
  network — same stance as `cost-split.py --self-test`). Token from `IG_ACCESS_TOKEN` in the
  gitignored `.env` locally or a GitHub Actions secret; `IG_USER_ID` likewise. Never a default,
  never committed (`AGENTS.md` secret rule; `.env.example` gets both as placeholders).
- `just ig-post <caption-file>` wraps it; `just ig-post --dry-run` is the default form in docs.
- `.github/workflows/ig-token-refresh.yml`: cron every 45 days, `GET /refresh_access_token`,
  writes the new token back to the repo secret (`gh secret set`) — the one piece of automation
  that must exist for v2 to survive past day 60.
- MIP-0018's post-planner records the post (platform `instagram`, the container and media ids)
  in its run log, so a week's post is one row there, not a separate ledger.
- **What goes through the LLM: nothing.** Captions are template fields from the board JSON or
  human-written text from MIP-0018's draft queue; the script refuses to post a caption file that
  is empty or that contains the exporter's `<!-- draft -->` marker.

### 5.3 The image, deterministically — three options

| Option | Cost | Verdict |
|---|---|---|
| **A. One committed JPEG**, human screenshot, refreshed by hand when the map changes visibly | zero code, one 200 kB file | **v1 and v2's default** — the first post and most weekly posts don't need a fresh render |
| B. Render from `latest.json` with a tiny raster (a Python drawing of dots on a basemap tile) | a new dependency (Pillow) and a basemap-tile licence question | rejected for now — a worse picture than the real map, for licence homework |
| C. Headless Chromium in `site.yml` screenshotting `site/dist` | Playwright in CI, minutes per run | not a site build step (MIP-0005's rule is about the *page*), but a heavy dependency for a weekly image; revisit only if posts become daily |

## 6. Scoring / safety impact

None. No product code changes; nothing in `scoring/` or `Recommender` is touched. The only
safety-adjacent point is the caption: it must state numbers from the board verbatim (score, hour,
date) and never a recommendation the map itself would not show — the template is the guard.

## 7. Verification plan

- `scripts/ig_publish.py --self-test` in `just quality-other` (fixture-driven: container id
  parsed, `media_publish` body built, quota refusal at ≥ 90, empty/draft caption refused).
- `--dry-run` output reviewed in the PR body for the first API-published post.
- Live, by a human: `GET /me?fields=id,username` with the long-lived token → the expected
  account; `--check-limit` → `quota_usage: 0`; one real publish; the post visible; bio link
  resolves to the map.
- The refresh workflow's first run logged (a new `expires_in` ≈ 5,184,000 s).
- "Done" for v1: the first post is live with the URL in caption and bio, screenshot in the PR.
  "Done" for v2: one weekly post published by `just ig-post` from a MIP-0018 draft.

## 8. Risks, limitations, and honest caveats

- **Standard Access is documented as sufficient for an owned account today**; Meta changes access
  tiers without notice. If publishing ever requires Advanced Access, that is App Review + Business
  Verification — weeks, and a registered business — and the answer becomes "post by hand".
- **A token that nobody refreshes dies in 60 days**, silently: the refresh workflow needs a
  failure notification (a failed Actions run is visible; whether anyone looks is the caveat).
- **The image is a public URL on GitHub Pages** — fine for a screenshot of a public map; never
  reuse this path for anything that must not be world-readable.
- **"Bot" is the wrong word for what should exist:** the API posts, a human writes. MIP-0018's
  research is explicit that machine-generated changelog posts are the failure mode.
- Instagram's terms are Meta's; a policy strike takes the account with it. Keep the map's own
  channel (Pages) the source of truth, Instagram a mirror.

## 9. Alternatives considered

- **Do nothing** — the map stays a README link. Loses the one visual channel a non-developer
  would follow; costs nothing. The honest default if nobody will write weekly captions.
- **Post from a phone forever** (v1 only) — genuinely fine at one post a week; the API path only
  pays off with MIP-0018's queue. This is why v2 is `do when MIP-0018 lands`, not `do next`.
- **Instagram API with Facebook Login** — needs a Facebook Page linked to the account and
  `pages_read_engagement`; the Instagram-Login variant removes both for no loss marola cares
  about (ads/tagging). Rejected.
- **A third-party scheduler (Buffer/Later/Hootsuite)** — paid tiers for the useful parts, a second
  vendor holding the token; `AGENTS.md` keeps paid tooling out unless nothing free works. Rejected.
- **Unofficial/private-API clients** — against Instagram's terms, ban risk, and they break
  monthly. Rejected outright.
- **Threads instead** — has a publishing API too and is text-first; a different channel with a
  different audience. Not instead of, possibly in addition — out of scope.

## 10. Exam-coverage mapping

None. Loosely, `AI-103-MAPPING.md` §1 "Responsible AI: transparency" — the caption is
deterministic data, labelled as such — but this MIP does not claim that row.

## 11. Open questions

1. Account handle and ownership: `@marola.swim`? Who holds the credentials, and does the token
   live in this repo's Actions secrets or a personal password manager only?
2. Single-image constraints Meta's publishing page did not state: aspect-ratio bounds (4:5 to
   1.91:1 is the app's rule — not verified for the API), maximum file size, whether PNG is
   rejected or silently converted. Verify with one `--dry-run`-then-real post before writing
   image guidance into docs.
3. Caption limits (2,200 chars, 30 hashtags) — commonly cited, not re-verified on Meta's page.
4. Cadence: weekly with MIP-0018's post, or only when the map changes (MIP-0009, MIP-0016)? The
   100/24 h cap is irrelevant either way; the human's writing time is the real limit.
5. Should `site.yml` also emit a Story-sized 1080×1920 crop, or is one square image enough?
6. Threads as a second target of the same script (same Meta app, separate permissions) — worth a
   one-line addition to MIP-0018 §4 rather than its own MIP?

## Appendix

**A. Sources, fetched 2026-09-06**

- `https://developers.facebook.com/docs/instagram-platform/content-publishing` — account type,
  two-step flow, media types, "JPEG is the only image format supported", public-URL requirement,
  100 posts / 24 h, `content_publishing_limit`, permission names for both login variants.
- `https://developers.facebook.com/docs/instagram-platform/instagram-api-with-instagram-login` —
  "does not require a Facebook Page", "cannot access ads or tagging".
- `…/instagram-api-with-instagram-login/overview` — Standard vs Advanced Access wording, token
  lifetimes (1 h / 60 days, refreshable), `graph.instagram.com`, Business Verification trigger,
  4800 × impressions rate limit, the four `instagram_business_*` permissions.

**B. Repo facts checked 2026-09-06**

- Pages URL: `README.md:14` (badge), `README.md:22` ("Live map"), `docs/RUN-LOCALLY.md:291`,
  `.github/workflows/site.yml:19` (comment) and `:161` (`actions/deploy-pages@v5`).
- No `*.png`/`*.jpg` tracked anywhere in the repo; no headless-browser step in any workflow or
  `justfile` recipe.
- `docs/SELF-DOCUMENTING.md` has no Instagram entry — MIP-0018 §4 should gain one row when this
  MIP is accepted (Instagram: real API, professional account, JPEG at a public URL, 100/24 h).

**C. The two requests, for `--dry-run`'s reference**

```
POST https://graph.instagram.com/v21.0/<IG_USER_ID>/media
  image_url=https://h0ffmann.github.io/marola/social/marola-map.jpg
  caption=<§3 text>
  access_token=<long-lived>
→ {"id": "<CONTAINER_ID>"}

POST https://graph.instagram.com/v21.0/<IG_USER_ID>/media_publish
  creation_id=<CONTAINER_ID>
  access_token=<long-lived>
→ {"id": "<MEDIA_ID>"}
```

(API version `v21.0` is illustrative — pin whatever the dashboard offers when v2 is built.)
