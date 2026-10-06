# MIP-0072: A Gemini review on request, by adding the Gemini reviewer

| | |
|---|---|
| **Status** | Partially implemented (tasks 1–3 of 3 — marola-dev/marola-devkit#19, marola-dev/marola-devkit#24, a caller in every repo, §5.4). Missing: the team's Read on marola-app, marola-ml and marola-corpus (task H). Issues #546, #641. `Tasks: docs/MIPs/MIP-0072.tasks.md` |
| **Author** | Claude, for M. Hoffmann |
| **Created** | 2026-09-30; revised 2026-10-06 to the design that shipped |
| **Phase** | 0 (dev-loop; no user-facing surface) |
| **Related** | `GEMINI-CODE-ASSIST.md` (#400, the hosted product this replaces for now), `DEV-FLOW.md` §5 route 4, MIP-0060 (§5.5 reserved a hosted review for a human go-ahead), MIP-0070 (the devkit's reusable workflows) |
| **Effort** | M: one reusable workflow and one script in marola-devkit, a ~15-line caller per repo, human-only org settings |
| **Gain** | `infra/dev-loop`: one gesture anyone with triage access can make, from the PR sidebar or `gh pr edit --add-reviewer`, in every repo; `cost/ops`: $0 on the Gemini API free tier, no GCP project or billing account |
| **Effort vs Gain** | `do now` |
| **Depends on** | No MIP. No paid resource: the API key is a free AI Studio key with no billing attached |
| **Blocked by** | none |
| **Risk** | Google cuts the free tier (about 20 requests a day per model) or retires the default model; `GEMINI_MODEL` switches models without a release |
| **Cost so far** | see each PR's `Cost:` trailers |

## 1. Summary

Requesting the empty, visible org team `marola-dev/gemini` as a reviewer on any marola pull
request starts a Gemini review. Each repo's `.github/workflows/gemini.yml` calls marola-devkit's
reusable `gemini-review.yml`, which sends the diff to the Gemini API in one call, posts one review
as the GitHub App `marola-gemini-bot`, and pushes one commit with the fixes it can apply safely.
Every repo has the caller (§5.4).

## 2. Motivation

The maintainer wants a review to start when someone adds "the Gemini reviewer" to a PR. The first
draft of this MIP (2026-09-30) did that through Gemini Code Assist on GitHub. Google shut its
consumer app down on 2026-07-17, and the enterprise install needs a GCP project with a billing
account (`GEMINI-CODE-ASSIST.md`), which the cost gate in `AGENTS.md` would hold. Calling the
Gemini API directly with a free AI Studio key needs neither, and the same workflow serves every
repo from the devkit.

## 3. User-visible change

Before: no gesture asks for a review, and no Gemini comment exists on any marola PR.

After (PR timeline):

```text
h0ffmann requested a review from marola-dev/gemini
github-actions removed the review request for marola-dev/gemini
marola-gemini-bot reviewed: one summary, ≤ 10 inline comments tagged [high]/[medium]/[low]
marola-gemini-bot pushed: fix: apply Gemini review findings   (same-repo PRs only)
```

From a terminal: `gh pr edit <N> --add-reviewer marola-dev/gemini`. Request it again after new
commits for a fresh review. A fork PR gets the review and no fix commit.

## 4. Data sources and dependencies reviewed

- **A GitHub App cannot be requested as a reviewer**, so a team stands in for the bot. A team is
  offered in the Reviewers box only when it has access to the repo; on 2026-10-06 the team had
  Read on marola, marola-site, marola-devkit and marola-oods, and requesting it on a marola-oods PR
  failed until it was added there (`gh api repos/marola-dev/<repo>/teams`).
- **The `review_requested` payload** carries `requested_team.slug`, which the caller's `if` reads.
- **The free tier** allows about 20 requests a day per model. An agent loop (`run-gemini-cli`)
  spent them on one PR in testing, so the design makes one API call per review.
- **`pull_request_target`** runs the base branch's workflow with the repo's secrets. It is safe here
  because only someone with triage access can request a reviewer, and nothing from a fork's head is
  executed (§5.3).
- **A push with `GITHUB_TOKEN` starts no CI** on the PR; a push with the App's installation token
  does. An App with no Workflows permission cannot push a change under `.github/workflows/`.

## 5. Design

### 5.1 The gesture

The team `marola-dev/gemini`: visible, no members, Read on every repo that calls the workflow.
The workflow removes the team request first, so it can be requested again.

### 5.2 `gemini-review.yml` (marola-devkit)

One job. `scripts/gemini_review.py review` sends the diff, numbered by new-file line, with the
repo's `.gemini/styleguide.md` and `AGENTS.md`, and asks for JSON. It keeps at most 10 comments
GitHub accepts and posts one `COMMENT` review with suggestion blocks. `gemini_review.py fix` then
applies a comment's fix only where its `original` text still matches, only in files the PR changed
and never under `.github/`; the caller's `check-command` runs without the token; one commit carrying
`Tested:`/`Cost:` (and any `extra-trailers`) is pushed. Inputs and secrets are in the devkit's
[`4-reference_workflows`](https://docs.marola.dev/5-Repos/marola-devkit/4-reference_workflows/#gemini-review).

### 5.3 Fork PRs (marola-dev/marola-devkit#23)

The workspace is the base commit, so the style guide is the repo's own. The fork's head is checked
out into `.pr-head` without credentials and read as text, and `review --root .pr-head` anchors the
suggestions there. The fix step is skipped: the App cannot push to a fork.

### 5.4 Where it is wired

| Repo | Caller | State |
|---|---|---|
| marola-devkit | the reusable workflow plus its own `gemini.yml` (`devkit-ref: ${{ github.sha }}`) | marola-dev/marola-devkit#19, #24, merged (v0.5.0) |
| marola | `gemini.yml` | #642, merged |
| marola-site | `gemini.yml` with `check-command: node scripts/site_check.js` and `MIP: none` | marola-dev/marola-site#52, merged |
| marola-app | `gemini.yml` | marola-dev/marola-app#37, merged |
| marola-ml | `gemini.yml` | marola-dev/marola-ml#15, merged |
| marola-oods | `gemini.yml` | marola-dev/marola-oods#6, merged |
| marola-corpus | `gemini.yml` | marola-dev/marola-corpus#5, merged |

Every caller pins `@v0.5.0` with `devkit-ref: v0.5.0` and triggers on `pull_request_target`.

### 5.5 Human-only settings (task H)

The org secrets `GEMINI_API_KEY` and `GEMINI_APP_PRIVATE_KEY` and the variable `GEMINI_APP_ID`,
scoped to the repos above; the App installed on them with Contents and Pull requests read/write
and no Workflows permission; the team with Read on each. The optional org variable `GEMINI_MODEL`
overrides the default `gemini-3.5-flash`.

## 6. Scoring / safety impact

None. The review is advisory and touches no app code path.

## 7. Verification plan

- Each caller passes `actionlint` in its repo's CI.
- Live, on the devkit's own PRs: requesting the team posted a review and a fix commit whose push
  started CI (marola-dev/marola-devkit#19).
- Live, a fork PR (marola-dev/marola-site#54): one review, no fix commit, nothing from the fork run.
- Per repo: request `marola-dev/gemini` on any PR; the request disappears and a review appears.

## 8. Risks, limitations, and honest caveats

- The free tier's daily quota is shared by every repo; a busy day runs out and the job fails
  until the next day.
- One call cannot follow up: the review sees the diff, the style guide and `AGENTS.md`, not the
  rest of the repo.
- Source leaves the org for Google's API, as Gemini Code Assist would have sent it.

## 9. Alternatives considered

- **Gemini Code Assist on GitHub** (this MIP's first draft): needs a GCP project with billing since
  the consumer app's shutdown. `GEMINI-CODE-ASSIST.md` keeps the plan.
- **An agent loop (`run-gemini-cli`)**: spent the day's quota on a single PR.

## 11. Open questions

None open. The draft's questions on Code Assist's bot-comment filter and drafts no longer apply.
