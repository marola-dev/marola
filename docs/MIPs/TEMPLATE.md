# MIP template

Copy the block below to `docs/MIPs/MIP-NNNN-<kebab-slug>.md`. The `/marola-devkit:mip` skill says
how to research and fill each section; this file is the shape, and it wins where the two differ.

Before the first push:

- **Number**: one more than the highest MIP in `README.md` *and* in every open PR that adds a
  `docs/MIPs/MIP-NNNN-*.md`. An agent picks it; two drafts never share one.
- **Branch**: `docs/mip-NNNN-<slug>`, the same name in every repo the MIP touches.
- **Commits**: made by the agent through the flow (`just pr`, `just uprds` for a stack), with the
  three trailers; nothing committed by hand.
- **Draft until done**: the PR stays a draft until every row of the *Readiness* table below is
  filled and the gates pass. Ready for review means the author has nothing left to do.

Every section is required: write "None" rather than deleting a heading. §10 is skipped on purpose.

```markdown
# MIP-NNNN: <Title>

| | |
|---|---|
| **Status** | Draft / Accepted / Implemented / Rejected / Superseded by MIP-NNNN |
| **Author** | <the person who asked for it and owns it> |
| **Created** | YYYY-MM-DD |
| **Phase** | 0 / 1 / 2 / 3 / 4 (`docs/PHASES.md`) |
| **Related** | `FUTURE-WORK.md` §N, MIP-NNNN, the issue it came from |
| **Effort** | S / M / L / XL — one clause why |
| **Gain** | `user value`, `infra/dev-loop`, `cost/ops`, `community/outreach`, each with one clause |
| **Effort vs Gain** | `do next` / `do when X lands` / `cheap win` / `expensive, defer` / `park` — one sentence why |
| **Depends on** | prose: other MIPs, the Phase 1 gate, a paid resource |
| **Blocked by** | MIP numbers that must merge first, comma-separated, or `none` (read by `mip_graph.py`) |
| **Risk** | the one thing most likely to make this not worth it |
| **Cost so far** | summed `Cost:` trailers of its merged PRs, or `—` |

### Readiness

| | |
|---|---|
| **Manually reviewed** | `yes — <person>, YYYY-MM-DD`, once a person has read the whole MIP; `no` until then |
| **Written by** | `<person>, with <agent and model>`, or `<person>, by hand` |
| **Tasks** | `MIP-NNNN.tasks.md`, or `none needed — <why>` |
| **Tests** | the named tests §7 adds, or `none — <why>` |
| **Spec-kit** | the spec-kit spec this MIP is tied to (`specs/<NNN-slug>/spec.md`), or `none` |
| **Issues** | one per tasks row (`owner/repo#N`), filed by the agent once the MIP is Accepted; `not filed — Draft` before that |

## 1. Summary
Two to four sentences: what changes for the user, and why now.

## 2. Motivation
The concrete gap. Quote real output or a real limitation.

## 3. User-visible change
Before and after, as the actual output, not a description of it.

## 4. Data sources and dependencies reviewed
One subsection per candidate: format, cadence, coverage, licence, key, what was verified (URL and
date) and what was not. End with the pick and why.

## 5. Design
Modules and files touched, the local default and the opt-in path, what is deterministic and what
goes through an LLM. Plan in enough detail that the implementing agent has nothing to guess.

## 6. Scoring / safety impact
How `Swimability.score` and its notes change, with thresholds, or "None".

## 7. Verification plan
Named tests, live checks (commands), and what done looks like.

## 8. Risks, limitations, and honest caveats

## 9. Alternatives considered
Including "do nothing", and why each lost.

## 11. Open questions
Each question carries the author's answer for now, so nothing is left open by omission:

- <question> **Default:** <what this MIP does until someone decides otherwise, and who decides>.

## Appendix
### Checked live
One line per external fact fetched: URL, date, what it returned.

### Not checked
Anything referenced but not verified this session.
```
</content>
</invoke>
