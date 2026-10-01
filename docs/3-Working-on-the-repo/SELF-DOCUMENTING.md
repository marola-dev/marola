# Self-documenting marola

A living reference for a question raised outside marola-the-product: M. Hoffmann builds this repo
in the open, session by session, and wants to turn that process (MIPs, PRs, Claude Code
sessions) into weekly build-in-public posts (LinkedIn first, other platforms later) without
reconstructing "what happened this week" from scratch each time. This doc is the research; the
proposal for what to actually build is [MIP-0018](../MIPs/MIP-0018-self-documentation-and-media-exporter.md).

Nothing here is marola-the-product. It's dev-tooling for the person building marola, same
category as `docs/3-Working-on-the-repo/DEV-FLOW.md` and `AGENTS.md`'s "Attribution and cost accounting" section.

## 1. What marola already produces that's (almost) pre-written narrative

This repo's existing conventions are unusually close to storytelling material already, without
anything new being built:

- **MIP `Motivation`/`Alternatives considered`/`Risks` sections** (`/marola-devkit:mip`'s
  template) are structurally a post's "why before what": a MIP's Motivation section is close to a
  post's opening paragraph, and its Alternatives-considered section is the "here's what I rejected
  and why" beat a plain changelog never has.
- **`Cost:`/`Tested:` commit trailers** (`AGENTS.md` "Attribution and cost accounting") give a real
  number and a verification story for free, per PR.
- **`GH_POST_MORTEM.md`** (introduced 2026-09-06, `.gitignore`d, operator-only) is a running log of
  what a session pushed and why `gh` couldn't finish the loop itself, a session-level "what
  happened, in order" record, close to a stand-up note.
- **`docs/MIPs/README.md`** already tracks Status/Effort/Gain/Verdict/Cost per MIP: a queryable
  "what shipped, at what cost" index without any new tooling.

None of this was built *for* storytelling. It's a happy accident of the repo's existing
cost-accounting and design-review discipline. The gap is turning "the raw material exists" into
"a human can produce a post in under 30 minutes a week," which is what MIP-0018 proposes.

## 2. Session → narrative: Claude Code transcript tooling

| Tool | What it does | Fit |
|---|---|---|
| [`simonw/claude-code-transcripts`](https://github.com/simonw/claude-code-transcripts) | Python CLI, converts Claude Code JSONL transcripts (local or web) to browsable HTML; `--gist` publishes straight to a shareable GitHub Gist URL | Best fit — built explicitly for a solo developer's public learning archive/build-in-public use |
| [`daaain/claude-code-log`](https://github.com/daaain/claude-code-log) | JSONL → readable HTML/Markdown | Similar category, less publish-oriented (no one-command share URL) |
| [`delexw/claude-code-trace`](https://github.com/delexw/claude-code-trace) | Desktop/web/TUI viewer, tool-call/token detail | Good for personal review, not built for publishing |
| Claude Code's own `/export` | Writes the current session to a file | The sanctioned snapshot mechanism — the raw `~/.claude/projects/.../*.jsonl` format is undocumented and can break across CLI versions, so prefer `/export` or a maintained tool over reading the JSONL directly |

**Recommendation:** keep `claude-code-transcripts --gist` in reach for the rare session where the
back-and-forth itself is the interesting part (a debugging back-and-forth, a design pivot). Link
it as supporting evidence under a written post, not as the post itself. Verified only via search
result summaries, not by installing and running the tool: MIP-0018 §4 marks this "not run."

## 3. Git history → narrative tools

Thin, mostly weekend-project-grade category, none clearly best-in-class:

- [`Garinmckayl/devlog-cli`](https://github.com/Garinmckayl/devlog-cli): git history → weekly
  narrative via GitHub Copilot CLI, categorizes commits (features/fixes/refactors).
- [`mihael/devlog`](https://github.com/mihael/devlog): local, Ollama-based (fits marola's
  local-first ethos), turns commit history into blog-post drafts, no data leaves the machine.
- [GitStory](https://www.git-story.dev/): commits → stylized narrative via Gemini; reads as more
  novelty than a serious tool.

**Recommendation:** not worth adopting a dependency here. A git-log-based summary is a changelog,
and the LinkedIn research below says changelogs are exactly the failure mode to avoid. MIP-0018's
post-planner pulls the same raw material (merged MIPs, PR trailers) directly instead.

## 4. LinkedIn / build-in-public content norms

Research converged on one clear, load-bearing point: **the failure mode is posting a changelog
("shipped X, fixed Y") instead of a story**: why a decision was forced, what was tried, what was
learned or rejected. Cadence-wise, one solid weekly post beats several thin daily updates; a
single "here's what shipped and why, here's the one interesting decision" post is what gets
saved/shared, not a list of merges.

**Implication for the post-planner (MIP-0018):** the planner's job is to surface *candidate*
decisions (one MIP's Alternatives-considered section, one PR's Cost surprise, one rejected
approach) for the human to pick from and write around, not to auto-generate the post text itself.
The human still writes the narrative; the tooling's job is finding the raw material and handling
per-platform formatting/distribution.

## 5. Publishing destinations — what's actually scriptable

Ranked by how well each supports **programmatic/scripted posting**, since that's what a marola
exporter would actually need (not by popularity):

| Platform | API for posting | Verdict for v1 |
|---|---|---|
| **Ghost** | Real, documented [Admin API](https://docs.ghost.org/admin-api/posts/creating-a-post) — JWT-signed requests, create/publish posts, tags, authors | Best programmatic fit *if* the blog repo runs Ghost. Confirmed via Ghost's own dev docs. |
| **dev.to** | Simple REST API, `POST https://dev.to/api/articles` with an API key, dev-audience-native | Good fit, zero app-review friction — a real automation candidate |
| **Hashnode** | Public GraphQL API (`https://gql.hashnode.com/`), API key from account settings, create-draft-then-publish flow, dev-audience-native | Good fit, slightly more integration work than dev.to (GraphQL, two-step publish) |
| **A static-site blog on git push** (Hugo/Jekyll/Astro/11ty on GitHub Pages/Netlify/Vercel) | No API at all needed — committing a `.md` file to the repo *is* the publish step | Simplest possible v1 if the user's existing blog repo is (or becomes) a static-site generator — see §6 open question |
| **Wix Headless / Blog API** | Real REST/SDK Blog API exists (`dev.wix.com/docs/api-reference/business-solutions/blog`), can create/publish posts, handle rich content and media import | Technically scriptable, but heavier to integrate (external-image-to-Wix-media-ID conversion, SDK-preferred) and not dev-audience-native — a real option only if the user is already committed to Wix for other reasons |
| **LinkedIn** | Community Management API exists but is **gated to registered companies with a verified Page, two-tier app review, a screencast demo, and an accepted commercial use case** — not available to an individual developer for personal posting as of this research (2026-09) | Not scriptable for v1. Assume manual copy-paste. |
| **Substack** | No public posting API as of 2026-09 (a 2026 Developer API exists but only exposes a profile-search endpoint, not article/Notes publishing); third-party tools (`API Substack`) exist but authenticate via scraped browser session cookies, not an official API | Not scriptable for v1 without accepting cookie-auth fragility. Assume manual copy-paste (or Substack's own email-to-publish path, unverified here — flagged as an open question). |
| **Reddit** | Real OAuth2 API, PRAW for Python; **free at 100 queries/minute for non-commercial personal use** — but Reddit closed self-service OAuth-app registration in late 2025 ("Responsible Builder Policy"), so a *new* app now needs manual approval with no published turnaround time | Scriptable in principle, but the up-front approval step is a real, unquantified blocker — not assumed free-and-easy for v1 |

**v1 recommendation:** automate only what genuinely has a scriptable, low-friction path: the
git-commit-to-blog-repo mechanic (§6) and, once the blog repo's platform is known, whichever of
Ghost/dev.to/Hashnode fits it. Everything else (LinkedIn, Substack, Reddit until an app is
approved) is a formatted draft the human copy-pastes by hand. This isn't a permanent limitation of
the design, it's an honest reflection of which platforms actually let a personal project post
without a company, a review board, or a cookie-scraping dependency.

## 6. Cross-repo publishing: marola → the blog repo

Concrete patterns researched, ranked simplest-first:

1. **A local script, sibling-clone assumption.** If the blog repo is cloned as a sibling directory
   on the same machine, a script copies the generated `.md` file across and makes a commit there;
   the human reviews and pushes it themselves. Zero new credentials, zero CI, works today. This is
   the recommended v1 default: the simplest thing that could work for a single human operator.
2. **GitHub Actions cross-repo push**, for later automation: a PAT with least-privilege scope
   (classic PAT with repo access to the blog repo, or a deploy key added to the blog repo with
   write access, or a GitHub App installation token for tighter scoping) lets a workflow in
   marola's own CI push a commit to the blog repo directly. Real, well-documented pattern
   (`ad-m/github-push-action` and similar marketplace actions exist), but it's a stated *future*
   step, not v1, since it adds a secret to manage and a CI dependency for something a human doing
   this weekly can do by hand in seconds.
3. **`git subtree`**: workable but adds real git complexity (subtree merges, history entanglement)
   for a use case that doesn't need shared history between the two repos: marola and the blog repo
   are conceptually unrelated projects, so subtree's "shared subdirectory history" model is the
   wrong shape here. Not recommended.

**Open question, not resolved by this research:** what the blog repo actually *is* (a Hugo/Jekyll/
Astro/11ty static site, a Next.js/MDX app, or something else) determines the exact frontmatter
shape and directory convention the exporter needs to target. This research cannot know that
without the human specifying it. MIP-0018 §11 carries this as an open question rather than
guessing a static-site generator.

## 7. Video — noted, not designed

Out of scope for v1, flagged only as a future extension point: a recorded terminal session
(`asciinema`) or a screen recording of a live demo, distributed via YouTube's or LinkedIn's native
video-upload APIs (both real, both requiring their own OAuth app setup, not researched in depth
here since nothing is being built against them yet).

## 8. Sources

- Ghost Admin API: [docs.ghost.org/admin-api/posts/creating-a-post](https://docs.ghost.org/admin-api/posts/creating-a-post)
- Wix Blog API: [dev.wix.com/docs/api-reference/business-solutions/blog/introduction](https://dev.wix.com/docs/api-reference/business-solutions/blog/introduction)
- Reddit API 2026 pricing/access: [socialcrawl.dev/blog/reddit-data-api-2026](https://www.socialcrawl.dev/blog/reddit-data-api-2026), [redditapis.com/pricing](https://www.redditapis.com/pricing)
- Substack API status: [github.com/topics/substack-api](https://github.com/topics/substack-api), [apisubstack.com](https://apisubstack.com/)
- dev.to/Hashnode APIs: [hackernoon.com/how-to-post-to-dev-hashnode-and-medium-via-their-apis-v11u379h](https://hackernoon.com/how-to-post-to-dev-hashnode-and-medium-via-their-apis-v11u379h)
- LinkedIn Community Management API gating: [singhamandeep.com/linkedin-community-management-api-access](https://singhamandeep.com/linkedin-community-management-api-access/), [learn.microsoft.com/en-us/linkedin/marketing/community-management-app-review](https://learn.microsoft.com/en-us/linkedin/marketing/community-management-app-review?view=li-lms-2026-08)
- Cross-repo GitHub Actions push patterns: [some-natalie.dev/blog/multi-repo-actions](https://some-natalie.dev/blog/multi-repo-actions/), [github.com/marketplace/actions/commit-with-deploy-key](https://github.com/marketplace/actions/commit-with-deploy-key)
- `simonw/claude-code-transcripts`: [github.com/simonw/claude-code-transcripts](https://github.com/simonw/claude-code-transcripts)

All findings above are from WebSearch result summaries on 2026-09-06, not from installing/running
the tools or reading full API reference pages end-to-end. Treat platform specifics (rate limits,
exact endpoint shapes) as "confirmed direction, verify exact details before building against them."
