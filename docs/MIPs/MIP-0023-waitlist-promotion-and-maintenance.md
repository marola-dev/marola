# MIP-0023: Product expansion — a wait-list, honest promotion, and the easiest way to keep marola's site and backend maintained

| | |
|---|---|
| **Status** | Draft |
| **Author** | Claude Sonnet 5, for M. Hoffmann (request of 2026-09-06: "Mip product expansion, wait-list betalist... How to promote easiest way to maintain and deploy marola for external users marola site and backend updates, use state of the art tooling with agentic") |
| **Created** | 2026-09-06 |
| **Phase** | 0 (wait-list can start today, site has no backend) → 1 (promotion of the bot itself needs the Telegram bot working, `ARCHITECTURE.md` §11) → 3 (bot deploy is the existing Container-App-or-cheaper-alternative design point) |
| **Related** | `ARCHITECTURE.md` §5/§11, `MIP-0005` (the static site this sits on), `MIP-0002` (Telegram bot, Phase 1 — prerequisite for promotion), `MIP-0004` (in-bot digest *subscriptions*, distinct from this pre-launch wait-list), `MIP-0017` (agentic dev-tooling survey — §4.3 extends it to the deploy/maintain axis), `MIP-0020` (Instagram bot account — a promotion channel §4.2 should link to once that MIP ships), `MIP-0020` §5.5 (the daily Instagram pipeline — the same promotion axis, platform-specific; the former MIP-0024 draft was folded into it), `MIP-0025` (custom-model work — orthogonal, not a promotion or deploy concern), `docs/3-Working-on-the-repo/DEV-FLOW.md`, `AGENTS.md` (cost/deployment-safety and phase-discipline gates) |
| **Effort** | S for §5.1's default (a GitHub Discussion, zero new code); M if the Formspree-form alternative is chosen instead (one static form + one Pages redirect page, no server); L if a Cloudflare Worker + KV path is chosen (a new tiny stateful component, its own secrets and quota to watch) |
| **Gain** | user value (a way to say "I want this" before the bot exists); infra/dev-loop (§5.3's maintenance recommendation); cost/ops (every option here is free or near-free by design) |
| **Effort vs Gain** | cheap win for §5.1 (wait-list) and §5.3 (maintenance tooling) — both buildable now, zero paid resource; §5.2 (promotion) is `do when X lands` — it needs Phase 1 (a working bot) to be worth doing at all, per `AGENTS.md`'s phase-discipline rule |
| **Depends on** | Phase 1 (MIP-0002) gates promotion (§4.2), not the wait-list (§4.1) or tooling (§4.3), both usable today; no paid cloud resource anywhere here, per `AGENTS.md`'s cost-safety rule |
| **Risk** | promoting before Phase 1 ships wastes a hobby project's one good first impression — a "sign up to be told later" link with no bot behind it reads as vaporware; no promotion push should start before MIP-0002 merges |
| **Cost so far** | — (nothing from this MIP has merged yet) |

## 1. Summary

marola has a real product surface today, the static map (MIP-0005, live at
https://h0ffmann.github.io/marola/), and a real gap: no way for an interested visitor to leave
contact info for the not-yet-built Telegram bot, no plan for where a hobby launch gets seen, and no
written answer for keeping the site and eventual bot maintained without babysitting. This MIP
proposes: (1) a wait-list that respects the site's "no cookies, no tracking, zero third-party
requests" claim, defaulting to a GitHub Discussion thread over an embedded third-party form; (2) an
honest, hobby-scale promotion plan gated on Phase 1 actually shipping; (3) a maintenance/deploy
recommendation: GitHub Actions + Pages for the site (already built), Fly.io for the eventual bot,
and the already-running Dependabot/scala-steward plus the
Claude Code GitHub Action as the "agentic" maintenance layer, instead of a bespoke system.

## 2. Motivation

- The site's copy makes an explicit privacy promise: `app.js:11`, *"Nothing leaves the browser: no
  analytics, no cookies"*, and the footer at `app.js:334`, *"...and, soon, in the Telegram bot"*.
  That "soon" has nowhere for a visitor to register interest today; the maintainer's only signal is
  asking friends by hand.
- The repo already has the free deploy artefact for the site (`site.yml`, MIP-0005/§11 Phase 3),
  but nothing is written down about the equivalent decision for the *backend* once the bot exists.
  No cheap option has been evaluated in writing.
- `AGENTS.md`'s phase-discipline rule says not to promote or scale what isn't built: marola has
  zero live bot users because the bot doesn't exist. Any "get external users" plan must say, out
  loud, which part is gated on Phase 1 and which isn't.

## 3. User-visible change

**Before:** the map's footer says "soon, in the Telegram bot" with no link. A visitor who wants to
be told when it ships has no way to say so except messaging the maintainer directly.

**After** (site copy, once §5.1's default ships):

```
No cookies, no tracking. The same pipeline answers one beach at a time on the command line
and, soon, in the Telegram bot — want to know when it's live? Leave a note on the wait-list.
```

`Leave a note on the wait-list` links out to a GitHub Discussion (`Announcements` category,
pinned), not an embedded widget. The click is the user's own choice to leave the zero-third-
party-requests page, not a background request the page makes for them. Once the bot exists
(Phase 1), the same Discussion thread's pinned comment is edited to say "it's live: t.me/<bot>"
and closed to new sign-ups.

## 4. Data sources and dependencies reviewed

### 4.1 Wait-list options

**GitHub Discussions sign-up thread (recommended default).** A pinned Discussion in this
already-public repo; a visitor comments "notify me" or reacts. Cost $0, no new account/secret/
dependency. Privacy: comments are public GitHub data under GitHub's own terms, the same trust
boundary a visitor already crosses to read the repo; nothing reaches a third party marola chose.
Maintenance: zero, no cron, no server, no library to patch. Downside: needs a free GitHub account
and it's a public thread, not a private list. **Verified:** GitHub Discussions is free on public
repos, a long-standing, widely known GitHub feature, not independently re-fetched from a pricing
page this session.

**Formspree-style hosted form (Buttondown for the email-list variant).** An external service POSTs
submissions to the vendor. **Buttondown checked live 2026-09-06** (`buttondown.com/pricing`): free
for up to 100 subscribers, no card required; paid add-ons ($9-79/mo) are opt-in extras not needed
for a bare wait-list. Its exact data-retention/third-party-sharing terms (`/legal/privacy`) were
not read in full; Open Questions. **Formspree's pricing page did not render numeric limits to
this session's fetch** (JS-heavy, empty extraction); its widely-cited ~50 submissions/month free
tier is **unverified this session**, Open Questions. Either service embeds a form, a third-party
request on page load, conflicting with the site's "zero third-party requests" claim unless the
form lives on a separate linked-to page rather than inline on `index.html`.

**Telegram-bot-native `/join`.** Only possible once the bot exists (Phase 1), a `/join` command
for a small beta cohort. Cost $0 beyond the bot; privacy is the best of all options (no new data
path). Doesn't solve the pre-Phase-1 problem this MIP is about; the natural **graduation point**
once Phase 1 ships, not a Phase-0 answer.

**Cloudflare Worker + KV/D1, a self-hosted sign-up endpoint.** A Worker POST target, storing the
signup in KV/D1. **Checked live 2026-09-06**: both `cloudflare.com/products/workers` and
`workers.cloudflare.com` redirected to marketing pages with no numeric free-tier figures in this
session's fetch; the commonly-cited 100k requests/day plus free KV/D1 allowances are
**unverified this session**, Open Questions. Even if those hold, this is real new infrastructure,
a script, a namespace, an account/token to manage, and the only option that makes "zero
third-party requests" literally false the moment the form loads. Highest effort, least
differentiated gain over the Discussions default.

**Pick: GitHub Discussions as the default.** No new account/secret/dependency, and its
"public thread" trade-off is disclosed in the sign-up copy rather than hidden behind a form that
quietly adds a third party. Buttondown is the named fallback for a private list; its verified
100-subscriber free tier covers a hobby launch, but isn't the default since it's still a vendor
dependency the Discussions option avoids entirely.

### 4.2 Promotion channels

| Channel | Effort | Likely gain at hobby scale | Prerequisite |
|---|---|---|---|
| README badge/link to the live map | Trivial | Low reach, zero cost, already partly done | None |
| Telegram groups of local swimmers/surfers | Low, post where the audience already is | Highest gain-per-effort; direct, non-spammy if framed as "I built this" | Site only today; wait for Phase 1 to promote the bot |
| A local subreddit (r/florianopolis-style) | Low | Moderate; self-promotion rules vary per subreddit, check before posting | None for the site |
| BetaList | Medium (submission + review wait) | Low-to-moderate, audience skews general-startup, not local-utility | Checked 2026-09-06: submission page needed login/JS, no pricing disclosed, **unverified**, Open Questions |
| Product Hunt | Medium-high (a launch day, assets) | High-variance visibility; audience is tech-early-adopter, a stretch fit unless framed as the AI-agent story | Checked 2026-09-06: launch page needed login/JS, no requirements disclosed, **unverified**, Open Questions |

**Pick, in order of effort-adjusted gain:** README badge (do now, trivial), then local Telegram
groups and a relevant subreddit, framed honestly as a hobby project (do once the map alone is
worth sharing, and it already is, MIP-0005 is Implemented), then BetaList/Product Hunt only after Phase 1
ships and the wait-list has some names on it, since both are one-shot "launch day" channels better
spent on a product that actually replies when someone messages it. An Instagram account (MIP-0020)
is a fifth channel in the same "post where the audience already is" spirit as the Telegram groups
row above: visual, beach-photo-friendly, and a natural fit once that MIP ships; not designed here.

**What must be true first**, per `AGENTS.md`'s phase-discipline rule: `ARCHITECTURE.md` §11 Phase
1 (the Telegram bot actually working): do not spend a BetaList/Product Hunt launch slot on a
"soon" promise. The static site can be shared today; the bot cannot be promoted until it exists.

### 4.3 Maintain-and-deploy tooling

**Site (already built, verify only).** `site.yml` builds every 3h and on `main` pushes touching
`site/**`/`core/**`/etc., deploys to GitHub Pages via `actions/deploy-pages@v5`, confirmed by
reading the workflow file directly (`.github/workflows/site.yml`), free (GitHub Actions minutes on
a public repo, GitHub Pages hosting). No change proposed here; it already is the "easiest way."

**Backend deploy, once Phase 1 ships: Fly.io vs. Hetzner.** Any paid host needs `AGENTS.md`'s human
go-ahead before provisioning. Two options, checked live 2026-09-06: **Fly.io**
(`fly.io/docs/about/pricing/`) quotes a shared-cpu-1x/256MB machine around **$2/mo** continuous, or
a $36/yr reservation block (~40% off); whether a perpetual no-card free allowance still exists in
2026 is **unverified**, Open Questions (a long-polling bot needs one always-on process; webhooks
could scale to zero; polling-vs-webhook is undecided, also Open Questions). **Hetzner Cloud**
(`hetzner.com/cloud/`) didn't render numeric prices to this fetch; its cheapest shared-vCPU VMs are
widely cited around €3-4/month but that figure is **unverified this session**, and Hetzner has no
free tier: plain pay-for-what-you-provision, more OS-patching burden than a managed platform.

**Pick: Fly.io as the default recommendation.** Cheapest verified always-on option with the least
operational surface; explicitly **not** provisioned by this MIP, a recommendation to revisit with
a real go/no-go once Phase 1 ships and traffic is real.

**"Agentic" maintenance layer.** Already running in this repo, not proposed new:
- **`scala-steward.yml`** (weekly Scala/sbt PRs) and **`.github/dependabot.yml`** (Actions + the
  two Python `requirements.txt` files), confirmed present by reading both files this session;
  `just deps-stack` already chains their PRs (`docs/3-Working-on-the-repo/DEV-FLOW.md` §6).
- **The Claude Code GitHub Action** (`code.claude.com/docs/en/github-actions`, fetched
  2026-09-06): supports a `schedule`-triggered cron workflow in automation mode (a `prompt` input,
  no `@claude` mention needed), billed via the repo owner's `ANTHROPIC_API_KEY` or a
  `CLAUDE_CODE_OAUTH_TOKEN` from an existing subscription; no separate product cost beyond Actions
  minutes. This is the "state of the art agentic" piece the request names, and it fits the existing
  `just`/MIP-stack workflow rather than replacing it: a scheduled run could summarize
  `just deps-stack status` or flag a stalled MIP task file, to the workflow log, or (narrowly
  `--allowedTools`-scoped) a comment on a stale PR. Not proposed to merge anything:
  `.claude/settings.json`'s `permissions.deny` already blocks `gh pr merge`/`close` project-wide
  with no override, matching `AGENTS.md`'s "merging is a human decision" stance.
- **OpenTelemetry cost export** (`AGENTS.md`'s "heavier option"). Not adopted here; a future
  Phase-2-adjacent nicety (agent spend next to marola's own traces).

**Recommendation, cheapest-first:** keep `site.yml` as-is (already the answer for the site); add
**one** new scheduled Claude Code GitHub Action workflow, in automation mode, that reports (does
not act on) the state of open dependency/MIP-stack PRs weekly, the smallest possible "agentic
maintenance" addition, no new secret beyond the `ANTHROPIC_API_KEY`/`CLAUDE_CODE_OAUTH_TOKEN` the
maintainer already has for local Claude Code use; defer the Fly.io backend deploy decision itself
until Phase 1 ships, per phase discipline.

## 5. Design

This MIP proposes copy/workflow decisions, not new Scala modules; nothing in `core/`, `local/`,
or `cli/` changes. Deferred to follow-up implementation PR(s) per the `mip` skill's
"don't build it in the same change" rule:

- **`site/static/index.html`/`app.js`**: one new sentence + link in the existing footer block
  (`app.js:334`), pointing at a GitHub Discussion URL. No new JS logic, no new network call from
  the page itself, a plain `<a href>`, so "zero third-party requests" survives (a click is the
  visitor's own navigation, not a request the page makes on load).
- **One GitHub Discussion**, created by the maintainer (a one-time repo-UI action, not code),
  pinned, category `Announcements`, titled roughly "Wait-list: get notified when the bot ships."
- **Optionally, a follow-up MIP** for the Fly.io bot deploy itself once Phase 1 (MIP-0002) is
  Accepted or in progress: the actual Dockerfile/fly.toml and webhook-vs-polling decision belongs
  there, per this repo's "one MIP, one concern" habit.
- **Optionally, a follow-up PR** adding `.github/workflows/claude-maintenance-report.yml`, a
  `schedule`-triggered Claude Code GitHub Action in automation mode, `--allowedTools` scoped to
  read-only `gh`/`just deps-stack status` calls, writing its summary to the workflow log or a
  tracking-issue comment, never given merge/close permission, per `.claude/settings.json`'s deny
  rules.

Nothing here is deterministic scoring or safety-relevant output, so none of it touches
`Swimability`/`Recommender`/the reviewer pass.

### 5.1 Identity & domain strategy

The maintainer separately proposed (since `.ocean`/`.sea` aren't valid TLDs and marola.ai is
already taken): `marola.app.br` as the primary domain, `marola.dev.br`/`marola.bot.br` as
alternatives, `marola.io`/`marola.bot` as global options.

Checked 2026-09-06: IANA's root zone db lists no `.ocean` or `.sea` TLD, confirming the premise. An
RDAP query to `.bot`'s own registry endpoint (`rdap.nominet.uk/bot/domain/marola.bot`, from IANA's
bootstrap file) returned 404, consistent with unregistered, not cross-checked elsewhere. `.ai`/
`.io` publish no RDAP endpoint in that bootstrap file (both ccTLDs historically lack public RDAP);
direct/guessed registry RDAP hosts 403'd or failed to resolve. marola.ai's taken status (the
maintainer's own claim) and marola.io's status are **unverified this session**. Registro.br's
pricing and WHOIS/availability pages are JS-rendered, returning no static content; the cited
R$40/yr `.app.br` price and `marola.app.br`/`.dev.br`/`.bot.br` availability are **unverified**;
check `registro.br` directly before registering anything.

**What a domain buys the wait-list:** GitHub Pages accepts a custom domain for free, a `CNAME`
file plus one DNS record at the registrar, no paid GitHub feature, no change to the "no cookies,
no tracking" design. It buys a memorizable URL for §4.2's promotion, nothing more; it doesn't
move the bot closer to shipping. The registrar's annual fee, whichever domain is chosen, is a
small but real recurring cost and, like any spend, a human decision to make and pay for
directly, not something this MIP or an agent provisions.

## 6. Scoring / safety impact

None. No change to `Swimability.score`, ranking, or any user-facing swim recommendation.

## 7. Verification plan

- Manual: open the new footer link, confirm it lands on the Discussion, confirm no new network
  request fires on page load (browser devtools).
- `scripts/site_check.js` (MIP-0009 task 2) should still pass unchanged: a static link, not new
  DOM structure; re-run in the implementation PR to confirm.
- If the scheduled Claude Code Action workflow is built: a manual `workflow_dispatch` run, confirm
  it stays read-only (no `gh pr merge`/`close` succeeds) and the summary is legible.
- "Done" for this MIP: Draft merged with the index row added; follow-up implementation PR(s) are
  separate, each carrying its own `Cost:`/`Tested:` trailers.

## 8. Risks, limitations, and honest caveats

- A public GitHub Discussion sign-up is not private; anyone can see who signed up; state this in
  the sign-up copy itself, not just here.
- Promotion (§4.2) before Phase 1 ships risks a bad first impression on a small audience that
  doesn't get a second launch day; this MIP explicitly gates BetaList/Product Hunt on Phase 1.
- Several external figures in §4/§5.1 could not be confirmed live this session (pricing/quotas for
  Formspree, Cloudflare Workers, Hetzner, Fly.io, BetaList, Product Hunt, Registro.br, plus
  marola.ai/marola.io's registration status); each is flagged inline and repeated in §11.
- Fly.io and any domain are design-time picks, not provisioned/purchased resources; no cost
  incurred; both real go/no-gos are later, human decisions.

## 9. Alternatives considered

- **Do nothing (keep asking friends by hand).** Free, but no durable signal or scale past the
  maintainer's own network. Loses because the point was a written, repeatable answer.
- **Embed a Formspree/Tally form directly on `index.html`.** Rejected as default: silently breaks
  "zero third-party requests" every page load, not just on a click-through; kept as the §4.1
  fallback for a private list.
- **Build a bespoke Cloudflare Worker sign-up form now.** Rejected: highest effort/attack surface
  for a wait-list a free GitHub Discussion already solves with zero new code.
- **Launch on Product Hunt/BetaList immediately, wait-list as the "product."** Rejected: a poor
  audience match for "not live yet," burning a one-shot slot on the weakest form of it.

## 11. Open questions

1. Exact free-tier figures not confirmed live this session (JS-rendered/empty fetches): Formspree's
   submission cap, Cloudflare Workers' request/day and KV/D1 allowances, Hetzner's cheapest VM
   price, Fly.io's no-card-free-tier status (its ~$2/mo shared-cpu-1x price did come through).
2. BetaList's and Product Hunt's actual submission cost/requirements: both pages needed login/JS.
3. Buttondown's exact data-retention/third-party-sharing terms (`/legal/privacy`): only its
   pricing page was checked.
4. Webhook vs. long-polling for the eventual bot deploy: affects whether Fly.io scale-to-zero is
   usable; belongs to the Phase-3 follow-up MIP, not decided here.
5. Whether the maintainer wants the wait-list to double as a private list (→ revisit Buttondown).
6. marola.ai's taken status and marola.io's registration status: `.ai`/`.io` publish no RDAP
   endpoint IANA lists, and guessed registry RDAP hosts 403'd/failed to resolve this session.
7. Registro.br's actual `.app.br`/`.dev.br`/`.bot.br` pricing and availability for `marola.*`:
   the pricing and WHOIS pages are JS-only and returned no static content this session.

## Appendix

- `site/static/app.js:11` and `:334`: existing privacy copy this MIP's footer addition must not
  contradict.
- `.github/workflows/site.yml`, `.github/dependabot.yml`, `.github/workflows/scala-steward.yml`:
  read in full this session; the existing tooling this MIP builds on.
- `code.claude.com/docs/en/github-actions`, fetched 2026-09-06: schedule-trigger automation mode,
  billing/permission model. `buttondown.com/pricing`, fetched 2026-09-06: free-tier cap.
- `data.iana.org/rdap/dns.json` and `iana.org/domains/root/db`, fetched 2026-09-06: no `.ocean`/
  `.sea` TLD, `.bot`'s RDAP host; `rdap.nominet.uk/bot/domain/marola.bot`, fetched 2026-09-06: 404.
- `fly.io/docs/about/pricing/`, `hetzner.com/cloud/`, `formspree.io/plans`,
  `cloudflare.com/products/workers`, `betalist.com/submit`, `producthunt.com/posts/new`,
  `registro.br/precos/`, its WHOIS tool, `rdap.org/domain/marola.{io,ai}`: all fetched 2026-09-06;
  none returned usable numeric pricing/terms or a resolvable RDAP result (JS content, a login
  wall, 403, or DNS failure); see §11 before relying on them.
