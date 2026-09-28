---
name: triage
description: Turn a raw idea, a voice-note fragment or a bug report into one well-formed marola issue — pick the tier, draft the body in the matching form's heading shape, check it against the Definition of Ready, and hand it to a human to file. Use when the user says "triage this", "make an issue out of this", "file this as a bug/story/task", or dumps something that should become tracked work.
disable-model-invocation: true
---

# From a raw idea to one issue that is ready to claim

`docs/3-Working-on-the-repo/ISSUE-FLOW.md` is the standard; this skill is the part a person keeps re-doing by hand.
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
| something broken in what a user touches — the pipeline, the CLI, the bot, the site | 1 | `bug_report.yml` | What happened · **What you expected instead** · How to reproduce · Backend · **Failing test** · Relevant logs or output |
| a chore, refactor or docs change — **or something broken in the repo's own tooling**: a script, a hook, CI | 1 | `task.yml` | What · **Acceptance criteria** · **Named test** · Size |
| a small enhancement (≈ 2 tasks or fewer, no new dependency) | 2 | `story.yml` | Problem · Proposed behaviour · **Acceptance criteria** · **Named test** · Out of scope · Deliverable |
| a new data source, a scoring change, a new integration, anything paid | 3 | `mip_proposal.yml` | the proposal fields; it is a design request, never claimable work |

**The tier is the size of the change; the form is the surface it is on.** The two rows above share
tier 1 and differ only in surface, so "it is broken" does not on its own pick `bug_report.yml`:
that form requires a **Backend** (`Local (Ollama)` or not-applicable) and asks for the `just run`
or Telegram message that triggers it. A broken shell script, hook or workflow has neither, and
belongs in `task.yml` with the breakage described under **What**. #451 (`cost-fill.sh` passing a
`git cherry-pick` flag its git does not have) is the worked example.

Read the form in `.github/ISSUE_TEMPLATE/` before drafting: the field labels are the literal
`### ` headings the readiness check greps for, so they are copied, not paraphrased. A tier 2 issue
body **is** the spec — there is no MIP file to defer the detail to.

## Step 2 — the body

Fill every heading the form renders. A field left blank renders as `_No response_`, and for every
**required** field that means the idea is not ready to be filed yet — say so rather than inventing
content. `bug_report.yml`'s **Failing test** is the one optional field, and the paragraph below
says what to do with it.

The two that decide whether the issue is claimable:

- **Acceptance criteria** (a bug's is **What you expected instead**): testable checkboxes, in the
  repo's own vocabulary — a CLI output, a Telegram reply, a score, a file that exists.
- **Named test** (a bug's is **Failing test**): file plus test name, e.g.
  `core/src/test/scala/marola/ScoringSpec.scala — "penalises a rip-current hour"`. For a bug this
  is the test that fails today, per `AGENTS.md`'s reproduce-before-fixing rule.

`bug_report.yml` is the one form where this field is optional, so that an outside reporter who
cannot write Scala is not turned away. Leaving it blank is allowed and costs the issue its
readiness, not its welcome: `issue-ready` will name rule 2, and the bug waits for a maintainer to
decide the test. Say that to the person rather than inventing a test name to make the check pass
(`docs/3-Working-on-the-repo/ISSUE-FLOW.md`, the Definition of Ready).

Propose the labels too — the form's own label (see the command below), one `area/*`, one
`layer/*`, one `size/*` (S < 100 changed lines,
M 100–400, L means split it, so an L is a prompt to cut the issue in two).

## Step 3 — check it before handing it over

Read the draft against the five rules yourself: acceptance criteria, a named test, `area/*` **and**
`layer/*`, `size/*`, no open `blocked by` dependency. Name anything the issue would be blocked by;
those edges are drawn with `scripts/issues.sh deps add <issue> --blocked-by <n>` once it exists.

Then show the person the body, the labels, and the command:

```bash
gh issue create --repo marola-dev/marola --title "<title>" --body-file <draft> \
  --label "<form's own label>,<area>,<layer>,<size>"
```

Two flags that are not optional in practice. `--repo`, because a worktree or a fork resolves to a
different default. And the **form's own label** — `bug` for `bug_report.yml`, `enhancement` for
`story.yml`, `mip` for `mip_proposal.yml`, none for `task.yml` — because GitHub applies it from the
form's `labels:` key only when the issue is filed through the web form, and `gh issue create`
bypasses that. The label is what selects the heading set: `issues.sh` reads the tier off `bug` /
`mip` (`dor_tier`), so a correctly-written bug body filed from the CLI without `bug` is checked
against `### Acceptance criteria` / `### Named test`, does not have them, and fails rules 1 and 2
on nothing the author did wrong.

After they file it, `just issue-ready <n>` (or `scripts/issues.sh ready <n>`, when `just` is not on
PATH outside `nix develop`) runs the same five rules against the real issue and
adds `agent-ready` on an all-pass — that is the authoritative check; this step only avoids filing
something that will obviously fail it. A `mip` proposal is refused by `issue-ready` outright,
correctly: it is a design request, and its next step is a MIP PR, not a claim.
