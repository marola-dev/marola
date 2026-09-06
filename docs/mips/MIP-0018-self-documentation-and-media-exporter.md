# MIP-0018: Marola self-documentation — weekly post-planner and multi-platform exporter

| | |
|---|---|
| **Status** | Draft |
| **Author** | Claude Sonnet 5, for M. Hoffmann (request of 2026-09-06: turn marola's dev sessions + git history into a repeatable weekly build-in-public content workflow) |
| **Created** | 2026-09-06 |
| **Phase** | 0 — developer tooling for the person building marola, not marola-the-product. No earlier-phase prerequisite. |
| **Related** | `docs/SELF-DOCUMENTING.md` (this MIP's research appendix, kept as a durable reference doc), `MIP-0017` (agentic-tooling survey — same dev-tooling category, and its §5.1 accepted the flat-file-not-database convention this MIP reuses), `docs/DEV-FLOW.md` (the idea→MIP→PR loop this MIP mines for content), `AGENTS.md` "Attribution and cost accounting" (the `Cost:`/`Tested:` trailers this MIP reads), `GH_POST_MORTEM.md` convention (introduced 2026-09-06, same session — the operator-log pattern this MIP's run-log follows) |
| **Effort** | M — no new module, no new runtime dependency for v1 (plain files + a script or two); the cross-repo git mechanic and per-platform formatters are the bulk of the work |
| **Gain** | infra/dev-loop (a repeatable weekly ritual instead of ad hoc reconstruction); user value in a loose sense — value to the human running marola, not to marola's Telegram users |
| **Effort vs Gain** | cheap win — every proposed piece is additive, file-based, and has a manual fallback, so a first version can ship small and grow |
| **Depends on** | none blocking; Phase 0, developer tooling only. Does not touch `Swimability`, `Recommender`, or any user-facing code. |
| **Risk** | scope creep into "build a social media management SaaS" — most social platforms don't have a scriptable posting API a personal project can actually use (see §4), so the real risk is over-designing automation for platforms that will stay manual regardless |
| **Cost so far** | — (nothing from this MIP has merged yet) |

## 1. Summary

Marola's own development already produces MIP design docs, PR bodies with `Cost:`/`Tested:`
trailers, and (as of this session) a `GH_POST_MORTEM.md` operator log — raw material that's
already close to storytelling content. This MIP proposes a **post-planner** (a flat-file weekly
content queue that surfaces what shipped and which decision is worth writing about) and a
**multi-platform exporter** (one written story, formatted per destination: a blog-repo `.md` file,
a LinkedIn-ready draft, a Substack-ready draft, a Reddit self-post draft) — both additive, both
assuming the human still writes the narrative, since research (§4, §9) found that automating the
writing itself produces the exact "reads like a changelog" failure mode to avoid.

## 2. Motivation

Today, producing a weekly build-in-public post means reconstructing "what happened this week" by
hand from `git log`, `gh pr list`, and memory — real friction that predicts the habit won't stick.
Meanwhile this session alone produced: four MIP-0011 task PRs each with a `Cost:`/`Tested:`
trailer and a real decision worth narrating (e.g. the Stop-hook's "block once, not eight times"
design choice), a `GH_POST_MORTEM.md` log of a real `gh`-auth workaround, and MIP-0017's own
"here's what we rejected and why" section — all of it already written, none of it in a form a
human can scan in under a minute to pick this week's post topic.

## 3. User-visible change

None for marola's Telegram/CLI users — Phase 0, developer tooling only. The "user" of this MIP's
output is M. Hoffmann, running a weekly command/script locally.

Before (today): open `gh pr list --state merged`, read every PR body by hand, try to remember which
MIP had the interesting rejected alternative, write a post from scratch.

After (proposed): run a planner script once a week; it prints a short list like:

```
Week of 2026-09-01 — marola

Shipped:
  - MIP-0011 tasks 1-4 (PRs #108-#111): Claude Code hooks (cost gate, format-on-write, stop-once)
  - MIP-0017 (PR #113): agentic tooling survey

Candidate "why" hooks (pick one to write around):
  [1] MIP-0011 task 4: Stop hook blocks once, not eight times — why the harness's own retry cap
      wasn't enough (docs/mips/MIP-0011.tasks.md row 4, PR #111 body)
  [2] gh had no session auth mid-run — GH_POST_MORTEM.md's fallback design, logged 2026-09-06
  [3] MIP-0017 §9: three trending agent tools reviewed and rejected — the "adopt everything
      trending" failure mode avoided, and why

Draft queue: content/posts/2026-W36.md (empty — write it, then run the exporter)
```

The human picks a candidate, writes ~150-300 words in `content/posts/2026-W36.md`, then runs the
exporter to get per-platform variants.

## 4. Data sources and dependencies reviewed

See `docs/SELF-DOCUMENTING.md` for the full research (session-transcript tools, git-to-narrative
tools, LinkedIn build-in-public norms, and the per-platform API survey). Summary of the pick per
destination, each verified via WebSearch result summaries on 2026-09-06 (not by installing/running
anything or reading full API references end-to-end — "confirmed direction," not "confirmed exact
endpoint shape"):

- **Blog repo (git-commit mechanic)** — no external API; verified pattern (§6 below). What's
  *not* verified: what static-site generator (if any) the user's actual blog repo runs — open
  question, §11.
- **Ghost Admin API** — real, documented (`docs.ghost.org/admin-api/posts/creating-a-post`),
  JWT-signed, create/publish. Only relevant if the blog repo turns out to be Ghost-backed.
- **dev.to REST API** (`POST /api/articles`) and **Hashnode GraphQL API** (`gql.hashnode.com`) —
  both real, both dev-audience-native, both have a documented create-post flow. Good v1
  automation candidates independent of what the blog repo is, since they're separate platforms.
- **LinkedIn Community Management API** — real but gated to registered companies with a verified
  Page and a two-tier app review; not usable by an individual for personal posting. **Not
  automated in v1** — assume manual copy-paste of the exporter's LinkedIn-formatted draft.
- **Substack** — no public posting API as of 2026-09 (a 2026 Developer API exists but only for
  profile search); third-party tools rely on scraped session cookies. **Not automated in v1** —
  manual copy-paste (or Substack's email-to-publish path, unverified here, flagged §11).
- **Reddit** — real OAuth2 API, free at 100 QPM for personal/non-commercial use, but Reddit closed
  self-service OAuth-app registration in late 2025; a new app needs manual, unqueued-turnaround
  approval. **Not automated in v1** pending that approval — draft generation only.
- **Wix Blog API** — real, scriptable, but heavier integration (external-image-to-media-ID
  conversion, SDK-preferred) and not dev-audience-native. Only worth it if the user is already
  committed to Wix for the blog specifically; not the default recommendation (§9).

## 5. Design

Everything below is file-based per this repo's established convention (MIP-0017 §5.1's
already-accepted "flat file, not a database" pattern) — no new Scala/Python runtime module, no new
sbt subproject. Proposed as two small standalone scripts, not integrated into the Scala pipeline
(`core`/`local`/`azure`/`cli` stay untouched):

### 5.1 Post-planner: `scripts/post-planner.py` (proposed name)

Inputs: `gh pr list --state merged --search "merged:>=<last-run-date>"` (or a local
`git log --since` fallback if `gh` has no auth, consistent with this session's own
`GH_POST_MORTEM.md` precedent), `docs/mips/README.md` for MIP metadata, and any `GH_POST_MORTEM.md`
entries dated in the window.

Output: a flat file per week, `content/planner/YYYY-Www.md` (gitignored — an operator artifact,
same status as `GH_POST_MORTEM.md`, not a deliverable) listing: what merged, and 2-4 "candidate
why hooks" extracted from each PR/MIP's own Motivation/Alternatives-considered/Risks prose (a
simple heuristic — pull the Motivation paragraph and any sentence containing "rejected",
"instead of", "because", or a number — not an LLM summarization pass, since the point is
surfacing the human's *own already-written* reasoning, not generating new text).

### 5.2 Draft queue: `content/posts/YYYY-Www.md` (human-written)

The human copies one candidate from the planner's output, writes the actual post body here in
plain Markdown with a small frontmatter block:

```markdown
---
week: 2026-W36
platforms: [linkedin, blog]
status: draft
---

<the actual ~150-300 word story, written by a human>
```

This file is the single source of truth for one week's content — the exporter (5.3) reads it, the
human never re-types the same story per platform.

### 5.3 Multi-platform exporter: `scripts/post-exporter.py` (proposed name)

Given one `content/posts/YYYY-Www.md`, produce:

- **`content/exports/YYYY-Www.linkedin.txt`** — the story as-is, no reformatting needed (LinkedIn
  has no strict length limit worth automating against; the value here is just "one file to
  copy-paste from," not transformation).
- **`content/exports/YYYY-Www.blog.md`** — the story wrapped in whatever frontmatter shape the
  destination static-site generator needs. **Blocked on §11's open question** (what the blog repo
  actually is) — v1 ships with a generic frontmatter (`title`/`date`/`tags`) that works for
  Hugo/Jekyll/Astro's common conventions, adjusted once the real target is known.
- **`content/exports/YYYY-Www.substack.txt`** — plain text, paragraph breaks preserved, no
  markdown syntax (Substack's editor doesn't take raw Markdown paste cleanly) — a copy-paste
  target.
- **`content/exports/YYYY-Www.reddit.md`** — a titled self-post draft (first line becomes the
  Reddit title, rest is the body), formatted per typical text-post norms (no LinkedIn-style
  hashtags, which read as spam on Reddit) — a copy-paste target, with a comment naming 2-3
  plausible subreddits (r/SideProject, r/ClaudeAI, r/artificial) for the human to pick, not an
  auto-post.

**Automated distribution, v1 scope:** only the blog-repo path (§6) actually pushes anywhere
without a human copy-paste step. Everything else is a formatted draft file. dev.to/Hashnode
scripted posting is named as a concrete **v1.1 candidate** (§11) once the blog-repo question is
settled and there's a real second data point on whether the manual-draft step is actually the
bottleneck it's assumed to be.

### 5.4 Cross-repo mechanic

Per `docs/SELF-DOCUMENTING.md` §6: v1 assumes the blog repo is cloned as a sibling directory
(`../<blog-repo-name>`, path configurable via an env var or a one-line config file, not
hardcoded). `post-exporter.py`'s blog-export step copies the generated `.md` file into that sibling
clone and runs `git add && git commit` there — **it does not push**. The human reviews the diff in
the blog repo and pushes themselves. GitHub Actions cross-repo automation (a PAT or deploy key
pushing directly) is named as a stated **future step**, not v1 — see §11.

## 6. Scoring / safety impact

None. This MIP touches no `Swimability`/`Recommender`/ranking code and produces no user-facing
output for marola's Telegram/CLI surface.

## 7. Verification plan

- `post-planner.py`: a unit test with a fixture `gh pr list`-shaped JSON and a fixture
  `docs/mips/README.md` snippet, asserting the extracted "candidate why hooks" match expected
  sentences from a known Motivation paragraph.
- `post-exporter.py`: a unit test per platform formatter, asserting the frontmatter shape and
  that plain-text platforms (Substack) strip Markdown syntax correctly.
- Manual, end-to-end: run the planner for a real past week of this repo's own history (this
  session's MIP-0011/MIP-0017 work is a real, available fixture), confirm the candidates it
  surfaces are ones a human would actually recognize as "the interesting decision that week."
- No change to `just build`/`just test`'s existing Scala suite — these are standalone Python
  scripts, tested with their own `--self-test` per this repo's `scripts/*.py` convention
  (`scripts/cost-split.py`, `scripts/smoke_record.py`, etc.), wired into `just quality-other`.

## 8. Risks, limitations, and honest caveats

- **The "candidate why hooks" heuristic is crude by design** (keyword matching on already-written
  prose, not an LLM summarization pass) — it can miss a good candidate whose Motivation section
  doesn't happen to use "instead of"/"rejected"/a number. Acceptable for v1 because a human still
  picks and writes; a missed candidate costs nothing beyond "the human thinks of it manually
  instead," same as today.
- **Every platform API claim here is a WebSearch-result-summary confirmation, not a
  built-and-tested integration** (per `docs/SELF-DOCUMENTING.md` §8's own disclosure) — before
  building §5.3's dev.to/Hashnode automation, re-verify the exact request shape against the live
  API docs, not this MIP's summary of them.
- **The blog-repo frontmatter format is a real unknown** (§11) — v1's generic frontmatter may need
  rework once the actual target static-site generator (if any) is known; this is stated up front
  rather than guessed around.
- **This is a habit-forming tool, not a guaranteed-outcome one** — nothing here makes the human
  actually write and post weekly; it only removes the "where do I even start" friction. If the
  habit doesn't form, the tooling isn't the fix.

## 9. Alternatives considered

- **Do nothing, keep reconstructing content manually each week** — real ongoing friction, and the
  counterfactual is the same "should marola-adjacent tooling help with X" question resurfacing
  later, same reasoning MIP-0017 §9 used for its own "do nothing" alternative.
- **Auto-generate the post text itself (LLM-written draft, not just a planner)** — rejected per
  §4's own research finding: the LinkedIn build-in-public failure mode is specifically "reads like
  a changelog," and an LLM asked to summarize merged PRs defaults to exactly that unless it's
  actually told the interesting decision, which is the human's job to pick, not the tool's to
  guess. The planner surfaces candidates; it does not draft prose.
- **A hosted/SaaS build-in-public tool** (e.g. a Buffer/Hypefury-style scheduler) — rejected: those
  tools solve "schedule and post," not "figure out what to write," which is marola's actual gap;
  adding a third-party SaaS account for a personal project with weekly cadence is disproportionate,
  and none of the platforms researched (§4) actually support scriptable posting for an individual
  anyway, so a scheduler buys nothing beyond what a local script + copy-paste already does.
- **Wix as the blog platform** — not rejected outright, but not the default: Wix's Blog API is real
  and scriptable, but heavier to integrate than a static-site git-push and not dev-audience-native.
  Recommended only if the user is already committed to Wix for reasons outside this MIP's scope.
- **`git subtree` for the cross-repo mechanic** — rejected (§6 of `docs/SELF-DOCUMENTING.md`):
  marola and the blog repo share no real history, so subtree's shared-subdirectory-history model
  is the wrong tool; a plain copy-and-commit script is simpler and sufficient.

## 10. Exam-coverage mapping

None — this is personal dev-tooling / content-workflow automation, not marola's product agent
architecture, and doesn't map to an AI-103 or AI-500 domain row.

## 11. Open questions

- **What is the blog repo, technically?** (Hugo/Jekyll/Astro/11ty static site, Next.js/MDX app, or
  something else.) Determines §5.3's blog-export frontmatter shape and whether Ghost's Admin API
  is even relevant. Needs a human answer before §5.3/§5.4 can be implemented for real, not guessed.
- **Does Substack's email-to-publish path still work for the user's account tier?** Not verified in
  this research pass (`docs/SELF-DOCUMENTING.md` §5 flags it explicitly) — if it does, it's a
  better v1 automation path than manual copy-paste into Substack's editor.
- **Is dev.to/Hashnode scripted posting worth building in v1**, or should v1 ship with only the
  blog-repo mechanic and treat every social platform as manual-copy-paste until the human confirms
  the copy-paste step is actually the friction point (as opposed to "writing the post" being the
  actual bottleneck, which no amount of exporter automation fixes)? Leaning toward the latter —
  ship the planner + blog-export mechanic first, add platform-specific scripted posting only after
  a few real weeks show it's worth it.
- **GitHub Actions cross-repo push automation** (§5.4's stated future step) — worth building once
  the sibling-clone manual flow proves the weekly habit sticks; premature before then.
- **Should the planner read `GH_POST_MORTEM.md`-style operator logs from *every* MIP going
  forward**, or only opportunistically when one exists? Leaning toward "opportunistically" — not
  every week's work will have hit a `gh`-auth gap or similar operator-log-worthy event.

## Appendix

See `docs/SELF-DOCUMENTING.md` for the full research write-up (session-transcript tooling,
git-to-narrative tools, LinkedIn norms, the per-platform API survey table, and cross-repo
publishing patterns) — kept as a separate durable reference doc rather than duplicated here, since
`docs/README.md`'s convention treats MIPs as proposals and other `docs/*.md` files as living
reference material that outlives any one MIP's Draft/Accepted/Implemented lifecycle.
