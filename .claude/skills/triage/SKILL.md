---
name: triage
description: Turn a raw idea, a voice-note fragment or a bug report into one well-formed marola issue — pick the tier, draft the body in the matching form's heading shape, check it against the Definition of Ready, and hand it to a human to file. Use when the user says "triage this", "make an issue out of this", "file this as a bug/story/task", or dumps something that should become tracked work.
disable-model-invocation: true
---

# From a raw idea to one issue that is ready to claim

`docs/ISSUE-FLOW.md` is the standard; this skill is the part a person keeps re-doing by hand.
The output is a draft body plus the labels it needs — not a filed issue.

## This skill is human-invoked, and stays that way

No label, no webhook and no schedule may start it (MIP-0063 §5.6, Decision 2, which is why the
frontmatter carries `disable-model-invocation: true`). The point of the standard is that an issue
says something real and ready; an agent filing its own would pollute the queue faster than anyone
can triage it, and `AGENTS.md`'s human-confirmation gate for proactive behaviour applies here
unchanged. **Draft, show, stop.** The person runs the `gh issue create` line, or pastes the body
into the form.

## Step 1 — the tier

Ask what the change is, not how big it feels:

| It is | Tier | Form | Heading shape to draft |
|---|---|---|---|
| something broken | 1 | `bug_report.yml` | What happened · **What you expected instead** · How to reproduce · Backend · **Failing test** · Relevant logs or output |
| a chore, refactor or docs change | 1 | `task.yml` | What · **Acceptance criteria** · **Named test** · Size |
| a small enhancement (≈ 2 tasks or fewer, no new dependency) | 2 | `story.yml` | Problem · Proposed behaviour · **Acceptance criteria** · **Named test** · Out of scope · Deliverable |
| a new data source, a scoring change, a new integration, anything paid | 3 | `mip_proposal.yml` | the proposal fields; it is a design request, never claimable work |

Read the form in `.github/ISSUE_TEMPLATE/` before drafting: the field labels are the literal
`### ` headings the readiness check greps for, so they are copied, not paraphrased. A tier 2 issue
body **is** the spec — there is no MIP file to defer the detail to.

## Step 2 — the body

Fill every heading the form renders. A field left blank renders as `_No response_` and fails the
check, so an unanswerable one is a sign the idea is not ready to be filed yet — say so rather than
inventing content.

The two that decide whether the issue is claimable:

- **Acceptance criteria** (a bug's is **What you expected instead**): testable checkboxes, in the
  repo's own vocabulary — a CLI output, a Telegram reply, a score, a file that exists.
- **Named test** (a bug's is **Failing test**): file plus test name, e.g.
  `core/src/test/scala/marola/ScoringSpec.scala — "penalises a rip-current hour"`. For a bug this
  is the test that fails today, per `AGENTS.md`'s reproduce-before-fixing rule.

Propose the labels too — one `area/*`, one `layer/*`, one `size/*` (S < 100 changed lines,
M 100–400, L means split it, so an L is a prompt to cut the issue in two).

## Step 3 — check it before handing it over

Read the draft against the five rules yourself: acceptance criteria, a named test, `area/*` **and**
`layer/*`, `size/*`, no open `blocked by` dependency. Name anything the issue would be blocked by;
those edges are drawn with `scripts/issues.sh deps add <issue> --blocked-by <n>` once it exists.

Then show the person the body, the labels, and the command:

```bash
gh issue create --title "<title>" --body-file <draft> --label "<area>,<layer>,<size>"
```

After they file it, `just issue-ready <n>` runs the same five rules against the real issue and
adds `agent-ready` on an all-pass — that is the authoritative check; this step only avoids filing
something that will obviously fail it. A `mip` proposal is refused by `issue-ready` outright,
correctly: it is a design request, and its next step is a MIP PR, not a claim.
