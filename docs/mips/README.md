# Marola Improvement Proposals (MIPs)

Design docs for non-trivial changes, written before they're built. The process and template live
in the `mip` skill (`.claude/skills/mip/SKILL.md`); this file is the index. Statuses: Draft →
Accepted → Implemented (or Rejected / Superseded). A MIP built in stages before every task lands
carries `Partially implemented (tasks a–b of N — #PR #PR)`, naming exactly which tasks are done and
what's still missing in its own status row — never a bare "Implemented" until the whole task list
(`MIP-NNNN.tasks.md`, when it exists) is merged.

**Reading the table.** `Effort` and `Gain` are the one-line summary of each MIP's own metadata block
(right after its title table) — read that block for the full reasoning, the `Depends on`/`Risk`
detail, and the exact `Cost so far` sourcing. `Verdict` is the `Effort vs Gain` call from that same
block: `do next` (build it now), `do when X lands` (blocked on a named prerequisite), `cheap win`
(small effort, ships on its own), `expensive, defer` (real value, not worth the cost yet), or `park`
(needs a precondition — usually accumulated data or a later phase — that doesn't exist yet). `Cost
so far` is the summed `Cost:` trailers of a MIP's merged PRs (implementation and, where traceable,
the MIP's own drafting commit); "—" means nothing has merged for it yet, "n/a" means something
merged but carries no usable `Cost:` figure (predates the trailer convention, or the trailer was left
as an unfilled placeholder).

| MIP | Title | Status | Created | Effort | Gain | Verdict | Cost so far |
|---|---|---|---|---|---|---|---|
| [MIP-0001](./MIP-0001-water-quality-and-sea-lore.md) | Bathing-water quality in the ranking, and a sea-lore paragraph in every reply | Implemented | 2026-09-05 | M | user value; exam coverage | cheap win (delivered) | n/a |
| [MIP-0002](./MIP-0002-telegram-bot-phase-1.md) | The Telegram bot — marola's first real user surface | Draft | 2026-09-05 | L | user value | do next | — |
| [MIP-0003](./MIP-0003-fast-replies-caching-and-fan-out.md) | Replies in under three seconds — caching, concurrent fetches, precomputed boards | Draft | 2026-09-05 | M | infra/dev-loop; cost/ops | do next | — |
| [MIP-0004](./MIP-0004-daily-digest-subscriptions-and-reach.md) | The reason to come back — daily digest, subscriptions, and reach beyond Santa Catarina | Draft | 2026-09-05 | L | user value; exam coverage | do when X lands | — |
| [MIP-0005](./MIP-0005-map-and-static-site.md) | The map — every beach's daily recommendation on a static site the pipeline feeds | Implemented | 2026-09-05 | L | user value; cost/ops | cheap win (delivered) | ~$12.46 |
| [MIP-0006](./MIP-0006-live-look-user-cameras.md) | "How does it look right now?" — a live look at each beach, fed by users' cameras | Draft | 2026-09-05 | XL | user value; exam coverage | do when X lands | ~$0.7 (shared w/ MIP-0007) |
| [MIP-0007](./MIP-0007-time-series-foundation-models.md) | Time-series foundation models for marola's own series — local open models first, Azure opt-in | Draft | 2026-09-05 | L | infra/dev-loop; exam coverage | park | ~$0.7 (shared w/ MIP-0006) |
| [MIP-0008](./MIP-0008-docker-images-and-smoke-test.md) | Docker images — lightweight JVM, native (GraalVM), marola-ollama, a fine-tuned variant — built in CI, with a smoke test the map shows | Implemented | 2026-09-05 | XL | infra/dev-loop; cost/ops | cheap win (delivered) | ~$15.98 |
| [MIP-0009](./MIP-0009-map-wave-markers-and-hover-aspects.md) | A richer map — a wave marker per beach and, on hover, every aspect at that point (wind with an emoji, whales, jellyfish, water temperature) | Accepted (tasks: `MIP-0009.tasks.md`, 4 PRs) | 2026-09-05 | S | user value; exam coverage | cheap win | ~$3.18 (shared bucket) |
| [MIP-0010](./MIP-0010-mlflow-experiment-tracking.md) | MLflow as marola's experiment ledger — benchmark runs, prompt compiles and LLM traces, local server first, Azure ML as the opt-in | Implemented (v1, local only) | 2026-09-05 | L | infra/dev-loop; exam coverage | cheap win (delivered) | ~$16.52 |
| [MIP-0011](./MIP-0011-claude-code-best-practices.md) | Claude Code best practices in this repository — hooks as gates, a shared permission allowlist, path-scoped rules, subagents and skills | Draft | 2026-09-05 | M | infra/dev-loop; cost/ops | do next | — (shared bucket) |
| [MIP-0012](./MIP-0012-llm4s-adoption-and-dspy-deprecation.md) | llm4s as marola's Scala-native LLM/agent layer (opt-in module behind marola's traits) — and the deprecation of the Python DSPy step for a Scala prompt compiler | Draft | 2026-09-05 | XL | infra/dev-loop; exam coverage | do when X lands | n/a |
| [MIP-0013](./MIP-0013-opencode-tryout.md) | OpenCode as marola's development agent — a bounded tryout, and what replacing Claude Code would take | Draft | 2026-09-05 | S | infra/dev-loop | cheap win | ~289k tok (unpriced) |
| [MIP-0014](./MIP-0014-marola-book.md) | A marola book — *The Compiler Pushes Back*, written in LaTeX, versioned in a repo, built in CI | Draft | 2026-09-05 | XL | community/outreach; exam-prep artifact | expensive, defer | — |
| [MIP-0015](./MIP-0015-interacao-swim-matching.md) | Interação — opt-in "who else is swimming here" matching between marola users | Draft | 2026-09-05 | L | user value; exam coverage | park | — |
| [MIP-0016](./MIP-0016-water-quality-map-markers.md) | Water-quality points on the map — OK / not-OK marks, placed in the sea off the OSM coastline, not on the land where the agency and OSM centres put them | Draft | 2026-09-06 | M | user value; exam coverage | do next | — |
| [MIP-0017](./MIP-0017-agentic-tooling-survey.md) | Agentic tooling ideas from `ai-job-search` and September 2026's trending agent repos | Draft | 2026-09-06 | S | infra/dev-loop | cheap win (§5.1/§5.2); §5.3 no urgency | — |
| [MIP-0018](./MIP-0018-self-documentation-and-media-exporter.md) | Marola self-documentation — weekly post-planner and multi-platform exporter (LinkedIn, blog repo, Substack, Reddit) | Draft | 2026-09-06 | M | infra/dev-loop | cheap win | — |
| [MIP-0019](./MIP-0019-arxiv-ocean-forecasting-survey.md) | arXiv/trending-repo survey — sea-forecasting and jellyfish-prediction techniques for marola | Draft | 2026-09-06 | S/M/L (per §5 item) | user value; exam coverage | cheap win (§5.1); do when X lands (§5.2); park (§5.3) | — |
| [MIP-0020](./MIP-0020-instagram-bot-account.md) | An Instagram account for marola — first post delivers the live-map URL; programmatic publishing via the Instagram API with Instagram Login (professional account, JPEG at a public URL, no Facebook Page, no App Review for an owned account) | Draft | 2026-09-06 | M | community/outreach; infra/dev-loop | cheap win (v1: post by hand) / do when MIP-0018 lands (API path) | — |
