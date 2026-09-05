---
name: mip
description: Write or revise a Marola Improvement Proposal (MIP) — a numbered design doc under docs/mips/ for any non-trivial feature, integration, or architecture change in marola. Use when the user says "MIP", "improvement proposal", "propose a feature", "write up a design for", or wants a feature planned before it's built. Not the same thing as docs/SKILLS.md (an exam-skills roadmap).
---

# Writing a Marola Improvement Proposal (MIP)

A MIP is how a non-trivial change to marola gets designed *before* it's built: what, why, how it
fits the repo's existing patterns, what was actually checked, and how we'll know it works. It is
the same "verify, don't guess" culture as `AGENTS.md` and `docs/FUTURE-WORK.md`, in a fixed shape
so proposals are comparable and don't rot.

## When a MIP is warranted

- A new external data source or third-party API.
- A new pluggable integration (a trait with local/Azure backends), or a new module.
- Anything that changes the ranking/scoring, the safety-relevant output, or what a user sees.
- Anything touching autonomous/proactive behaviour (see `docs/AI-500-MAPPING.md` §4).

Not for: bug fixes, doc corrections, refactors with no behaviour change, or one-file tweaks — do
those directly, and note them in `docs/FABLE_REVIEW.md` or `docs/FUTURE-WORK.md` if relevant.

## Steps

1. **Pick the number and slug.** Numbers are sequential, zero-padded to four digits. The next
   number is one more than the highest in `docs/mips/README.md`. File:
   `docs/mips/MIP-NNNN-<kebab-slug>.md`.
2. **Read before writing.** Always: `AGENTS.md`, `docs/ARCHITECTURE.md` §5 (the integration
   pattern) and §11 (phases), `docs/FUTURE-WORK.md` (is this already sketched? link the section),
   `docs/AI-103-MAPPING.md`/`docs/AI-500-MAPPING.md` (does this close a gap? say which row), and the
   source files the proposal would touch. Read existing MIPs in `docs/mips/` for tone.
3. **Verify every external claim.** Before naming a data source, library, or API: fetch its page,
   confirm the format, the update frequency, the licence/terms, and whether a key is needed. Record
   what was checked, when, and what was *not* checked. A source you could not verify goes in
   "Open questions", not in "Design".
4. **Write the MIP** using the template below. Every section is required; write "None" rather than
   deleting a heading. Keep it under ~250 lines; long research goes in an appendix at the end.
5. **Add it to the index.** Append a row to `docs/mips/README.md`: number, title, status, date.
6. **Link it.** If it supersedes or implements a `FUTURE-WORK.md` section, add a one-line pointer
   there ("see `docs/mips/MIP-NNNN-...md`"). If it closes an exam-mapping gap, note it in the
   relevant mapping row's Status column as "proposed: MIP-NNNN".
7. **The implementation PR carries a `Cost` line** (AGENTS.md "Attribution and cost accounting")
   and updates the MIP's status to Implemented with a link to the PR.
8. **Don't build it in the same change.** A MIP is merged as `Draft` or `Accepted`; implementation
   is a separate PR that flips the status to `Implemented` and links the PR. If the user asks for
   both, do the MIP first and confirm the design before writing code.

## Rules of the house (apply to every MIP)

- **Local-first, Azure opt-in, per integration** (`ARCHITECTURE.md` §5). Every new data path needs a
  free, keyless, zero-Azure default. If an Azure service is the *only* option, say so explicitly and
  state the expected cost — it needs a human go-ahead (`AGENTS.md` cost rule).
- **Safety-relevant logic stays deterministic and out of the LLM.** Anything that changes whether
  marola tells someone to swim is plain Scala in `scoring/`, unit-tested, never model output.
- **No unsourced facts reach a user.** If the proposal shows text to users that isn't computed from
  live data (lore, tips, explanations), the text must be curated with a source per entry, shown
  verbatim or fact-checked by `Reviewer` — an LLM does not get to invent it.
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
| **Related** | `FUTURE-WORK.md` §N, `AI-103-MAPPING.md` row "...", MIP-NNNN |

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

## 10. Exam-coverage mapping
Which AI-103 / AI-500 rows this touches, if any. "None" is a fine answer.

## 11. Open questions
Things that need a human decision or a check that couldn't be done yet.

## Appendix
Raw research notes, sample payloads, links.
```

## Index file

`docs/mips/README.md` holds one table: `| MIP | Title | Status | Created |`. Keep it sorted by
number. Create it with the first MIP if it doesn't exist.
