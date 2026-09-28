---
name: mip
description: Write or revise a Marola Improvement Proposal (MIP) — a numbered design doc under docs/MIPs/ for any non-trivial feature, integration, or architecture change in marola. Use when the user says "MIP", "improvement proposal", "propose a feature", "write up a design for", or wants a feature planned before it's built. Not the same thing as docs/4-Research-and-plans/SKILLS.md (a skills roadmap).
---

# Writing a Marola Improvement Proposal (MIP)

A MIP is how a non-trivial change to marola gets designed *before* it's built: what, why, how it
fits the repo's existing patterns, what was actually checked, and how we'll know it works. It is
the same "verify, don't guess" culture as `AGENTS.md` and `docs/4-Research-and-plans/FUTURE-WORK.md`, in a fixed shape
so proposals are comparable and don't rot.

## When a MIP is warranted

- A new external data source or third-party API.
- A new pluggable integration (a trait with a local default and an opt-in backend), or a new module.
- Anything that changes the ranking/scoring, the safety-relevant output, or what a user sees.
- Anything touching autonomous/proactive behaviour.

Not for: bug fixes, doc corrections, refactors with no behaviour change, or one-file tweaks: do
those directly, and note them in `docs/4-Research-and-plans/FABLE_REVIEW.md` or `docs/4-Research-and-plans/FUTURE-WORK.md` if relevant.

## Steps

1. **Pick the number and slug.** Numbers are sequential, zero-padded to four digits. The next
   number is one more than the highest in `docs/MIPs/README.md`. File:
   `docs/MIPs/MIP-NNNN-<kebab-slug>.md`.
2. **Read before writing.** Always: `AGENTS.md`, `docs/2-Building-marola/ARCHITECTURE.md` §5 (the integration
   pattern) and §11 (phases), `docs/4-Research-and-plans/FUTURE-WORK.md` (is this already sketched? link the section),
   and the source files the proposal would touch. Read existing MIPs in `docs/MIPs/` for tone.
3. **Verify every external claim.** Before naming a data source, library, or API: fetch its page,
   confirm the format, the update frequency, the licence/terms, and whether a key is needed. Record
   what was checked, when, and what was *not* checked. A source you could not verify goes in
   "Open questions", not in "Design".
4. **Write the MIP** using the template below. Every section is required; write "None" rather than
   deleting a heading. The numbering skips §10 on purpose, so §11 stays Open questions in every
   MIP. Keep it under ~250 lines; long research goes in an appendix at the end.
5. **Add it to the index.** Append a row to `docs/MIPs/README.md`: number, title, status, date, and
   the four triage columns (Effort, Gain, Verdict, Cost so far) copied from the MIP's own metadata
   block; see "Filling the six triage fields" below.
6. **Link it.** If it supersedes or implements a `FUTURE-WORK.md` section, add a one-line pointer
   there ("see `docs/MIPs/MIP-NNNN-...md`").
7. **The implementation PR carries a `Cost` line** (AGENTS.md "Attribution and cost accounting")
   and updates the MIP's status to Implemented with a link to the PR.
8. **Don't build it in the same change.** A MIP is merged as `Draft` or `Accepted`; implementation
   is a separate PR that flips the status to `Implemented` and links the PR. If the user asks for
   both, do the MIP first and confirm the design before writing code.
9. **Before pushing a new draft branch, check for an existing one.** Run `just docs-mip-stack list`
   (`scripts/docs-mip-stack.sh`) first: it discovers pending, un-merged `docs/mip-NNNN-*` design-doc
   branches and flags duplicates/staleness for the same MIP number. A real scan of this repo found
   several MIPs with *more than one* candidate branch (an original `docs/mip-NNNN-*` draft and a
   later rebuilt `mips/YYYY-MM-DD/K-mip-NNNN-*` branch, not always identical); several turned out to
   be already-merged duplicates nobody had cleaned up, with the merged MIP's own Status field still
   pointing at the stale branch name. `just docs-mip-stack plan <branch> ...` chains the drafts you
   pick into a base-linked stack of `gh pr create` commands once you've resolved which is canonical.
   It never guesses for you. This is distinct from `just mip-stack` (`scripts/mip-stack.sh`), which
   stacks an *implementation* task's PRs (`mip-NNNN/k-*` branches against a `.tasks.md`), not design
   docs.
10. **After a merge, double-check the MIP's own Status field names the branch/PR that actually
    landed**, not a branch that was superseded or renamed along the way. A MIP's Status field
    naming a stale branch/PR is easy to miss because the doc still reads as internally consistent;
    verify against `git log origin/main --grep="MIP-NNNN"`, not against what the doc itself claims.

## Rules of the house (apply to every MIP)

- **Local-first, cloud opt-in, per integration** (`ARCHITECTURE.md` §5). Every new data path needs a
  free, keyless, local default. If a paid cloud service is the *only* option, say so explicitly and
  state the expected cost; it needs a human go-ahead (`AGENTS.md` cost rule).
- **Safety-relevant logic stays deterministic and out of the LLM.** Anything that changes whether
  marola tells someone to swim is plain Scala in `scoring/`, unit-tested, never model output.
- **No unsourced facts reach a user.** If the proposal shows text to users that isn't computed from
  live data (lore, tips, explanations), the text must be curated with a source per entry, shown
  verbatim or fact-checked by `Reviewer`; an LLM does not get to invent it.
- **Phase discipline.** State which phase (`ARCHITECTURE.md` §11) the work lands in and what
  earlier-phase prerequisite, if any, is still missing.
- **Honest status vocabulary**, same as the rest of the docs: "verified live", "confirmed against
  the real page/jar", "written, not run", "not checked".

## Template

```markdown
# MIP-NNNN: <Title>

| | |
|---|---|
| **Status** | Draft / Accepted / Implemented / Rejected / Superseded by MIP-NNNN |
| **Author** | <name or agent> |
| **Created** | YYYY-MM-DD |
| **Phase** | 0 / 1 / 2 / 3 / 4 (`ARCHITECTURE.md` §11) |
| **Related** | `FUTURE-WORK.md` §N, MIP-NNNN |
| **Effort** | S / M / L / XL — one clause why (what's new: a module? a store? a CI workflow?). If §4's research changed the estimate from what a related MIP guessed, say so: `M, re-rated from S after §4` — copy the same clause into this MIP's `docs/MIPs/README.md` index cell, don't let the index show a bare letter that hides the correction |
| **Gain** | one or more of `user value`, `infra/dev-loop`, `cost/ops`, each with one clause |
| **Effort vs Gain** | `do next` / `do when X lands` / `cheap win` / `expensive, defer` / `park` — one sentence why |
| **Depends on** | prose, for humans: other MIPs it needs or that need it, whether Phase 1 or a paid cloud resource gates it (`AGENTS.md`), and any non-blocking coordination (shared files, shared design decisions) — say the relationship in words, this field is never parsed |
| **Blocked by** | machine-readable, for `scripts/mip_graph.py`: a comma-separated list of MIP numbers that must land first, or the literal `none`. Numbers only — no prose, no phase gates, no "not really, but". If a relationship doesn't cleanly reduce to "MIP-NNNN must merge before this one can", it belongs in `Depends on` only, not here — a wrong edge in the generated graph is worse than a missing one |
| **Risk** | the one thing most likely to make this not worth it |
| **Cost so far** | the summed `Cost:` trailers of its merged PRs, or "—" if nothing has merged yet |

## 1. Summary
Two to four sentences: what changes for the user, and why now.

## 2. Motivation
The concrete gap. Quote real output or a real limitation from the docs where possible.

## 3. User-visible change
Before/after of the CLI (and, once it exists, the Telegram reply). Show the actual proposed
output shape, not a description of it.

## 4. Data sources and dependencies reviewed
One subsection per candidate. For each: what it is, format, update cadence, coverage, terms/
licence/key, what was verified (URL + date) and what wasn't. End with the pick and why.

## 5. Design
Modules and files touched, new traits/case classes, the local default and the opt-in path,
how it plugs into `Recommender` / `Swimability` / `Main` / the MCP server. Sketch the Scala
signatures. Say what is deterministic and what (if anything) goes through the LLM.

## 6. Scoring / safety impact
Exactly how `Swimability.score` and notes change, with the thresholds. "None" if none.

## 7. Verification plan
Unit tests to add (name them), live checks to run (commands), and what "done" looks like.

## 8. Risks, limitations, and honest caveats
What can go wrong, what the data can't tell you, what to print so users aren't misled.

## 9. Alternatives considered
Including "do nothing". Why they lost.

## 11. Open questions
Things that need a human decision or a check that couldn't be done yet. A finding that's real but
out of this MIP's own scope (found while researching, not asked for) gets its own bullet prefixed
`**Follow-up MIP:**` naming what it is and that it needs the next MIP number, not folded into this
MIP's own Design section.

## Appendix
### Checked live
One line per external fact you fetched: the URL, the date, and what it actually returned —
including a failure ("404", "requires an auth token now", "no such endpoint found after checking
the page's own JS"). This is what `mip-claims-auditor` (`.claude/agents/mip-claims-auditor.md`)
reads first — a claim in §4/§5/§6/§9 that doesn't trace to a line here is exactly the failure mode
that subagent exists to catch, cheaply, before anyone re-fetches anything.

### Not checked
Anything referenced but not independently verified this session — a number repeated from another
MIP, a claim taken from a search-result summary rather than the source page, a library capability
assumed from memory. Say so here rather than letting it read as verified by omission.
```

## Filling the six triage fields

- **Effort**: size the *build*, not the design. S is a single-file, no-new-dependency change; M
  touches a few files or adds one small trait; L adds a module, a store, or a CI workflow; XL is
  several of those together or a new user-facing surface. Say what specifically drives the size.
- **Gain**: pick every tag that genuinely applies from the fixed list (`user value`,
  `infra/dev-loop`, `cost/ops`); most MIPs carry two, not one.
- **Effort vs Gain**: the honest triage call given today's Effort and Gain, not a sales pitch: name
  the blocking MIP for `do when X lands`, the missing precondition for `park`.
- **Depends on**: list other MIPs by number, and say explicitly whether `AGENTS.md`'s Phase 1 gate
  (the Telegram bot) or its cost-and-deployment-safety gate (a paid cloud resource) blocks this one.
- **Blocked by**: the strict subset of `Depends on` that's a pure "must merge first" relationship:
  comma-separated numbers or `none`. When in doubt whether something belongs here, it doesn't:
  leave it in `Depends on`'s prose only.
- **Risk**: one real failure mode, not a hedge: the thing that would make you regret building it.
- **Cost so far**: pull it from the merged PRs' `Cost:` trailers (`just cost-split MIP-NNNN`); write
  "—" for nothing merged yet, never a guess.

## Index file

`docs/MIPs/README.md` holds one table: `| MIP | Title | Status | Created | Effort | Gain | Verdict |
Cost so far |` (`Verdict` = the MIP's `Effort vs Gain` field). Keep it sorted by number. Create it
with the first MIP if it doesn't exist.

**Dependency graph.** `just mip-graph` regenerates a Mermaid graph from every MIP's `Blocked by`
field into `docs/MIPs/README.md` (between `<!-- mip-graph:start -->`/`-end -->` markers);
`just quality`'s `quality-other` fails if it's stale, same as any other generated-and-checked-in
artifact here. `just mip-graph --parallel NNNN MMMM` answers "can these two be worked on at once":
no path between them in the `Blocked by` graph **and** no overlap in the backticked source paths
their §5 Design sections name: the graph alone only catches the first kind of collision, not two
MIPs quietly touching the same file.
</content>
