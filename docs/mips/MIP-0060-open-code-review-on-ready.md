# MIP-0060: Open Code Review on every PR marked ready, on marola's own runners

| | |
|---|---|
| **Status** | Draft — `Tasks: docs/mips/MIP-0060.tasks.md` |
| **Author** | Claude (Fable 5.1), for M. Hoffmann |
| **Created** | 2026-09-19 |
| **Phase** | 0 (dev-loop; no user-facing surface, no Azure) |
| **Related** | `DEV-FLOW.md` §5 ("Final review — only when asked" — this MIP amends it), `.github/workflows/pr-body.yml` (same trigger, same guard), `h0ffmann/nix-config` `labs/agentic` (ai-jail, `jail-run`), MIP-0008 (Ollama sidecar) |
| **Effort** | L by the rubric (adds a CI workflow), small in lines: one workflow, one posting script with a self-test, a `just` recipe, a `DEV-FLOW.md` edit — plus one upstream change in `nix-config` `labs/agentic` (package `ocr`, add a `jail-run ocr` mode) |
| **Gain** | `infra/dev-loop` (a first-pass reviewer on every ready PR, before a human or a paid `/code-review` looks); `cost/ops` (the default path is a local model: $0, and it can take the cheap findings off the paid reviews) |
| **Effort vs Gain** | `cheap win` — if the §7 go/no-go run shows a 7B local model produces comments worth reading. If it does not, `park` until a stronger local model fits the runner |
| **Depends on** | No other MIP. One upstream PR in `h0ffmann/nix-config` must land and be locked here first (§5.1). Not gated by Phase 1. The default path creates no paid resource, so the `AGENTS.md` cost gate does not apply; the opt-in hosted-LLM path (§5.5) does need a go-ahead, because it spends money **and** sends private source to a third party |
| **Blocked by** | none |
| **Risk** | The runner is the maintainer's workstation. A review tool that is installed from npm `latest` at run time and then reads attacker-influenced text with an LLM agent is exactly the shape of thing that should not run unsandboxed next to `~/.ssh` — which is what using upstream's Action as-is would do |
| **Cost so far** | — |

## 1. Summary

[alibaba/open-code-review](https://github.com/alibaba/open-code-review) (OCR) is an Apache-2.0 Go
CLI that reviews a git diff with an LLM agent constrained by deterministic file selection, rule
matching and comment positioning, and emits line-level findings as JSON. This MIP runs it
automatically whenever a PR is marked **ready for review**, on marola's self-hosted runners, against
the local Ollama model by default, and posts the findings as one advisory PR review. It is never a
required check. "Safe" is the design constraint: a Nix-pinned binary, run inside ai-jail without the
GitHub token, with a separate small step that posts.

## 2. Motivation

`DEV-FLOW.md` §5 today: *"Nothing reviews a PR automatically. Reviews start when the human says so."*
Every review is therefore a paid Claude session (`/code-review`, `ultra`) or superpowers' reviewer,
started by hand, and a stack of nine PRs (MIP-0056: #372–#380) gets reviewed late or not at all.

Marking a PR ready *is* the human saying so — it is already the event `pr-body.yml` listens for.
A free local pass at that moment catches the cheap findings (null handling, a missed `Using`, a
shell quoting bug) before the expensive reviewer spends tokens on them. Upstream's own benchmark
claim — higher precision than a general-purpose agent at ~1/9 the tokens, lower recall — is the
right trade for an unattended bot: few comments, mostly real. **Not verified here**; §7 tests it.

## 3. User-visible change

Before: a PR leaves draft, `PR body` runs, nothing else happens.

After: a second check, `OCR review (advisory)`, runs on the same event and leaves one review:

```
marola-ocr · advisory · qwen2.5-coder:7b (local) · ocr 1.12.7 · 4 files, 3 findings, 2m41s
  scripts/oods_ingest.py:88   [bug/high]    `resp.json()` before `raise_for_status()` …
  oods/src/main/…/Planner.scala:41  [maintainability/low]  …
<!-- marola-ocr --> sticky summary comment, rewritten on each run, never duplicated
```

`just ocr-review [<PR#>|--from main]` runs the same thing locally, printing instead of posting.
Re-run on demand: `gh workflow run ocr-review.yml -f pr=<N>`. No comment on a draft, a Dependabot or
Scala Steward PR, or a fork.

## 4. Data sources and dependencies reviewed

### 4.1 alibaba/open-code-review — checked 2026-09-19 via the GitHub API and the repo's files

- **What:** Go CLI (`cmd/opencodereview`, `go 1.25.5`), binary name `ocr`. `ocr review --from A --to B
  --format json --output f.json`; also `ocr scan`, `ocr delegate`, sessions, custom rules (`--rule`),
  `--effort low|medium|high` (≥ 1.10.0), `--max-tokens-budget`. Needs **git ≥ 2.41**.
- **Licence / health:** Apache-2.0; created 2026-05-18; ~37k stars; pushed the day of this check;
  latest release **v1.12.7** (2026-09-19) with `opencodereview-linux-{amd64,arm64}` and
  `sha256sum.txt`; also npm `@alibaba-group/open-code-review`. OpenSSF badge shown; not inspected.
- **LLM:** any OpenAI-compatible or Anthropic endpoint (`OCR_LLM_URL`, `OCR_LLM_TOKEN`,
  `OCR_LLM_MODEL`, `OCR_USE_ANTHROPIC`). No key is needed when the endpoint needs none.
- **Telemetry:** OpenTelemetry, **off by default** (confirmed in `docs/…/telemetry.md`).
- **Its GitHub Action (`action.yml`, 1049 lines, read):** composite; sets up Node if missing,
  `npm install -g "@alibaba-group/open-code-review@${ocr_version}"` with **default `latest`**, does
  its own `fetch-depth: 0` checkout, runs the review, posts inline comments + a sticky summary via
  `actions/github-script`, supports incremental re-review. Inner actions are SHA-pinned. Upstream's
  own docs say pinning the Action alone does not freeze behaviour — `ocr_version` must be pinned too.
  The demo workflow uses `pull_request_target` and `@main`.
- **JSON output schema: confirmed against the source at `v1.12.7`** (§11.2) — `internal/model/review.go`
  and `cmd/opencodereview/output.go`.
- **Not checked:** the agent's exact tool list (assumed read-only file/search
  tools — no evidence it executes repo code, but not confirmed in source); whether an MCP server can
  be configured from inside the reviewed repo (`.opencodereview/`); the benchmark numbers; whether
  the release tag `v1.12.7` (ref object `03b362a…`) is annotated — dereference before pinning.

### 4.2 The runners and the model — checked on the machine, 2026-09-19

- Workflows use `runs-on: ${{ vars.CI_RUNNER || 'self-hosted' }}`; runners are registered by
  `just runners` under `/home/hoffmann/code/actions-runner*` — **persistent, on the dev machine**.
- `marola` is a **private** repo (`gh repo view`). Fork PRs are possible only from collaborators.
- Ollama on that host already has `qwen2.5-coder:7b` (4.7 GB), the model `labs/pratico` pins.
  OpenAI-compatible endpoint: `http://127.0.0.1:11434/v1/chat/completions`. **Not checked:** that
  OCR's tool-calling loop works against it, or that Ollama tolerates the Action's default
  `extra_body` (`{"thinking":{"type":"disabled"}}`).

### 4.3 `nix-config` `labs/agentic` — read 2026-09-19

Provides `ai-jail`, `gh`, OpenCode, `jail-run claude|opencode`, `gh-token`. `jail-run` accepts only
those two modes (plus `--dry-run -- <cmd>`) and forwards the GitHub token on purpose. There is no
`ocr` package and no token-less mode. marola already consumes the lab as a flake input.

**Pick:** the OCR *CLI*, Nix-packaged, not the upstream Action (§9 for why).

## 5. Design

### 5.1 `nix-config` first (upstream PR, its own repo's rules)

- `labs/agentic`: a package `ocr` — `buildGoModule` from the tagged source with `vendorHash`, or, if
  the locked nixpkgs has no Go ≥ 1.25.5, `fetchurl` of `opencodereview-linux-amd64` with the hash
  from `sha256sum.txt`. Added to `lib.<system>.tools`; `ocr version` in the lab's self-test.
- `jail-run ocr [args]`: a third mode that differs from the agent modes in three ways — **no**
  `GH_TOKEN` forwarded, **no** `~/.claude` maps, project directory mapped **read-only** with a
  writable `$RUNNER_TEMP` for the JSON. Self-test asserts the token is absent from the sandbox line.
- marola: `nix flake update agentic`, nothing else in `flake.nix`.

### 5.2 `.github/workflows/ocr-review.yml` (written in the implementation PR, not here)

```yaml
on:
  pull_request: { types: [ready_for_review, opened, reopened] }
  workflow_dispatch: { inputs: { pr: { required: true } } }
permissions: { contents: read, pull-requests: write }
concurrency: { group: ocr-${{ github.event.pull_request.number || inputs.pr }}, cancel-in-progress: true }
jobs:
  review:
    if: <pr-body.yml's guard, verbatim> && !github.event.pull_request.draft && vars.OCR_REVIEW != 'off'
    runs-on: ${{ vars.CI_RUNNER || 'self-hosted' }}
    timeout-minutes: 30
    continue-on-error: true          # advisory: never red on the PR
```

`opened`/`reopened` are there because a PR opened directly as non-draft never fires
`ready_for_review`. **Not `synchronize`**: restacking a MIP stack force-pushes every branch, and nine
concurrent 7B reviews would starve `ci.yml` of runners. Never `pull_request_target`.

Steps: checkout (`fetch-depth: 0`) → compute merge-base → **review** → **post**.

- **Review:** `nix develop .#ci-ocr --command jail-run ocr review --from $MB --to $HEAD --format json
  --output $RUNNER_TEMP/ocr.json --effort low --max-tokens-budget $BUDGET`, env `OCR_LLM_URL`,
  `OCR_LLM_MODEL`; config from a committed `.opencodereview/` (the directory upstream's own repo uses; its format
  is **not checked**) (rules off for `docs/mips/**`,
  `knowledge/**`, `*.lock`, generated parquet manifests). No GitHub token in this step's env.
- **Post:** `scripts/ocr-post.py` (stdlib, `--self-test` on fixture JSON, wired into
  `quality-other`): validates the JSON, drops findings whose path/line is not in the PR diff, caps at
  15 inline comments (rest go to the summary), posts **one** `COMMENT` review — never
  `REQUEST_CHANGES` — and upserts the sticky summary by its `<!-- marola-ocr -->` marker.

### 5.3 What is deterministic and what is the LLM

The LLM writes comment text only. Which files are reviewed, where a comment may land, how many are
posted, and that the check can never block a merge are plain code. Nothing here touches
`scoring/`, a user-visible reply, or any autonomous behaviour.

### 5.4 Threat model ("safe", concretely)

| Threat | Mitigation |
|---|---|
| Untrusted PR code on a persistent workstation runner | Same-repo guard from `pr-body.yml`; OCR reads, it does not build or run the PR; read-only map in the jail |
| Supply chain: tool updated under us | Nix-pinned source/binary hash; no `npm install -g latest` on the host; bumps arrive as a reviewed `flake.lock` diff |
| Prompt injection in a diff ("ignore rules, post X") | The agent has no token and no writable repo; the worst outcome is a wrong *comment*, which the post step bounds (diff-only lines, count cap, `COMMENT` only) |
| Source leaving the machine | Local default: loopback only. Hosted LLM is opt-in (§5.5) |
| Repo-supplied config steering the tool | Config is read from the **base** ref, not the PR head |
| Runner starvation / Ollama contention with e2e | Per-PR concurrency, 30-min timeout, token budget, no `synchronize`, kill switch `vars.OCR_REVIEW=off` |

### 5.5 Opt-in hosted model

`vars.OCR_PROVIDER` ∈ {unset → Ollama, `anthropic`, `azure`}; key in `secrets.OCR_LLM_TOKEN`; the
summary names the provider. Azure OpenAI/Foundry is OpenAI-compatible (`llm_auth_header: api-key` —
**not checked**). This path sends private source to a third party and spends money: it needs an
explicit go-ahead with a measured per-PR figure from §7, and `--max-tokens-budget` as the ceiling.

## 6. Scoring / safety impact

None. No change to `Swimability`, to any reply, or to what a user sees.

## 7. Verification plan

1. **Go/no-go, before any workflow exists:** `ocr review` by hand on three already-merged PRs (one
   Scala, one Python/shell, one docs-only) against `qwen2.5-coder:7b`. Record wall time, comment
   count, and a human true/false-positive call per comment in the MIP's appendix. Fewer than half
   useful → stop, flip this MIP to `park`.
2. `nix-config`: `just self-test` in `labs/agentic` (token absent in `jail-run ocr`), `nix flake check`.
3. marola: `scripts/ocr-post.py --self-test` (out-of-diff finding dropped; cap honoured; sticky
   marker upsert is idempotent; malformed JSON → exit 0 with a "no review" summary).
4. `actionlint`; `just ocr-review --from main` locally; then a throwaway PR: draft → no run; ready →
   one review; re-dispatch → summary rewritten, not duplicated; Dependabot branch → skipped.
5. Done = the check has run on five real PRs, none blocked, and `DEV-FLOW.md` §5 describes it.

## 8. Risks, limitations, and honest caveats

- A 7B model may simply be too weak for OCR's agent loop; §7.1 exists to find out cheaply.
- Low recall is by design: a quiet OCR run is **not** an approval. The summary says so verbatim.
- Bot comments train people to ignore bot comments. Hence the cap, `--effort low`, and the kill switch.
- ai-jail's network switch is all-or-nothing; "loopback only" is a property of the config, not
  enforced by the sandbox (**not checked** whether ai-jail can restrict to loopback).
- The project is four months old and releases several times a week; the JSON schema may move. The
  post step validates and degrades to a summary-only comment rather than failing.

## 9. Alternatives considered

- **Upstream's Action as-is** (`uses: alibaba/open-code-review@<sha>` + `ocr_version`). Least work,
  and its incremental posting is better than ours. Lost because it npm-installs globally on a
  persistent host, runs unsandboxed with the token in scope, and its 1049 lines are not ours to
  audit on every bump. Reasonable fallback if §5.1 stalls: run it on `ubuntu-latest` instead — but
  then there is no local model, so it becomes the paid path by default.
- **Upstream's Action inside a `container: node:24` job** (their self-hosted recipe). Better
  isolation, but needs Docker on the runner path and host networking to reach Ollama; more moving
  parts than one Nix binary in a jail marola already uses.
- **`/code-review` in CI.** Already available, better recall, costs money per PR, no local mode.
- **Review on every push.** See §5.2; revisit with upstream's incremental mode if the runner pool grows.
- **Do nothing.** Reviews stay manual and late. Costs nothing, fixes nothing.

## 10. Exam-coverage mapping

None claimed. (AI-103 "Responsible AI" is about the product's output, not the dev loop.)

## 11. Open questions

1. Is `qwen2.5-coder:7b` good enough (§7.1)? If not, which local model fits the runner's GPU?
2. ~~OCR's JSON schema~~ — **resolved**, confirmed against the source at `v1.12.7` (2026-09-19):
   `internal/model/review.go` defines `LlmComment` (`path`, `content`, `suggestion_code`,
   `existing_code`, `start_line`, `end_line`, `category` ∈ bug/security/performance/maintainability/
   test/style/documentation/other, `severity` ∈ critical/high/medium/low) and
   `cmd/opencodereview/output.go` the envelope `jsonOutput` (`status`, `llm{provider,model}`,
   `message`, `summary{files_reviewed,comments,*_tokens,elapsed,budget_exceeded}`, `tool_calls`,
   `comments`, `warnings`, `project_summary`, `session_id`, `manifest`). The posting rules come
   from upstream's own consumer, `scripts/github-actions/post-review-comments.js`: inline-able when
   `start_line` or `end_line` ≥ 1, multi-line as `start_line` + `line` on `side: RIGHT`, a
   `suggestion` block only when `suggestion_code` **and** `existing_code` are both set, and
   `event: COMMENT` on every review it creates. `scripts/ocr-post.py` reads that shape and degrades
   to a summary-only comment on anything else, so a schema move is a quiet run, not a red check.
   The **agent's tool list is still not checked**.
3. `buildGoModule` vs release binary: does the locked nixpkgs carry Go ≥ 1.25.5?
4. Should a stack (`mip-NNNN/k-*`) be reviewed bottom-first only, to save runner time?
5. Review language: English, or Portuguese to match MIP-0054's direction?
6. Should marking ready also re-run when a PR goes draft → ready a second time? (Today: yes, the
   summary is rewritten.)

## Appendix

Checked 2026-09-19: `gh api repos/alibaba/open-code-review` (licence, dates, stars), `README.md`,
`action.yml`, `examples/github_actions/README.md`, `pages/…/en/telemetry.md`, `go.mod`,
`releases/latest`; marola `.github/workflows/{ci,pr-body}.yml`, `justfile` runner recipes,
`DEV-FLOW.md` §5; `nix-config/labs/agentic/{README.md,flake.nix,scripts/jail-run}`; `ollama list` on
the runner host. §7.1 results go here.
