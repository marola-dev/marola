# MIP-0065: CI/CD on GitHub-hosted runners — nothing builds or deploys from a desktop

| | |
|---|---|
| **Status** | Partially implemented (task 1 of 4 — #481; docker-on, workflow-fixes, ci-cd-doc to go) — `Tasks: docs/MIPs/MIP-0065.tasks.md` |
| **Author** | Claude (Opus 5), with Bruno |
| **Created** | 2026-09-28 |
| **Phase** | 3 — the existing deploys (Pages, GHCR images) move runner; no Phase 1 or Phase 2 prerequisite, no cloud spend. The bot's own deploy (§5.7) is Phase 2 and out of scope |
| **Related** | MIP-0064 (mkdocs, lands first), MIP-0008 (Docker images), MIP-0005 (the site), MIP-0063 (issue projection), MIP-0057 (GCP, where §5.7 points), `FUTURE-WORK.md` §7.2 |
| **Effort** | L — nine jobs move runner, one job's toolchain moves to Nix, three workflows switch back on, one new guard script, one new doc; no Scala |
| **Gain** | `infra/dev-loop` — CI and deploys no longer stop when one machine sleeps; `cost/ops` — a public repo's hosted minutes are free, and the fork-PR exposure of a self-hosted runner closes |
| **Effort vs Gain** | do when MIP-0064 lands — both rewrite `api-docs.yml` and `quality-other`, and the maintainer chose to let MIP-0064 go first as designed |
| **Depends on** | MIP-0064, all four tasks (#455–#458): it edits the same `api-docs.yml` and `quality-other` job, and this MIP re-verifies its mkdocs/Kroki build on the hosted runner. A small prerequisite fix to `scripts/lib/tasks_issues.py` (cross-MIP `depends on` tokens, §5.6) must merge before this MIP's tasks are projected into issues. No Phase 1 gate, no paid resource |
| **Blocked by** | 0064 |
| **Risk** | The first cold run on `ubuntu-latest` surfaces an environment assumption nobody wrote down (a tool on the desktop's `PATH`, a warm cache, a Docker login), and the move stalls half done — some jobs hosted, some not, both tool paths still alive |
| **Cost so far** | — |

## 1. Summary

Every gate and both deploys already run in GitHub Actions, but nine jobs are routed to the
maintainer's desktop through `runs-on: ${{ vars.CI_RUNNER || 'self-hosted' }}`. That was a fix
for exhausted minutes on a private repo (#340). The repo is public now, so standard hosted
runners cost nothing, and a public repo with a self-hosted runner on `pull_request` lets a fork
run code on that desktop. This MIP moves everything except GPU training to `ubuntu-latest`,
turns the Docker workflows back on, fixes four broken workflows, and writes the flow down.

## 2. Motivation

State on 2026-09-28 (`gh run list` per workflow):

- `ci.yml` (4 jobs), `site.yml` (build + Pages deploy), `api-docs`, `pr-body`, `scala-steward`,
  `profile-activity` run on the desktop. When it sleeps, scheduled runs queue — `site.yml` and
  `scala-steward.yml` both carry comments timing their cron around the machine being awake.
- `docker.yml`, `docker-smoke.yml`, `docker-local.yml` are gated on `vars.DOCKER_CI == 'on'`,
  unset, so every run is `skipped`; the map's "Last live run" has not refreshed since at least
  09-23. The gate's reason ("~20% of this repo's Actions minutes") no longer applies.
- `ghcr-retention` has failed four Mondays running: `Failed to fetch packages: missing field 'id'`.
- `scala-steward` has failed on every run since 09-07: `Unable to install managed tools`.
- `ci-short-circuit-pr-close` fails whenever there is something to cancel: `failed to determine
  base repo: … not a git repository` (`gh run cancel` with no checkout and no `--repo`).
- The repo moved from `h0ffmann/marola` to `marola-dev/marola`. `docker.yml` pushes to
  `ghcr.io/${{ github.repository_owner }}` — now `marola-dev` — while `docker-smoke.yml` and
  `docker-compose.yml` still hardcode `ghcr.io/h0ffmann/marola`, a **private** package.
- No doc describes any of it. `FUTURE-WORK.md` §7.2 says `ci.yml` runs one job and that
  `marola-e2e` was never run; both statements are stale.

## 3. User-visible change

For a visitor: marola.dev's "Last live run" updates daily again, and the site and docs redeploy
whether or not anyone's machine is on. For a contributor:

```
before: PR from a fork → CI job queued on [self-hosted] → runs on the maintainer's desktop
after:  PR from a fork → waits for approval → runs on ubuntu-latest
        runs-on: self-hosted anywhere but marola-sea-publish.yml → quality-other fails:
          workflow_runners: ci.yml:21 targets self-hosted — only marola-sea-publish.yml may
```

`docs/CI-CD.md` exists: one row per workflow — trigger, runner, what it gates or deploys, the
secrets and variables it reads, how to run it by hand.

## 4. Data sources and dependencies reviewed

Filled from the Appendix; each claim below traces to a "Checked live" line.

### 4.1 Hosted runner cost and image

GitHub's Actions billing page: "free for self-hosted runners and for public repositories that
use standard GitHub-hosted runners", no minute cap; larger runners are "always charged for, even
when used by public repositories". So `ubuntu-latest` only, never a larger or GPU runner. The
`ubuntu-latest` = 24.04 image (20260920.314.1) ships Docker 28.0.4, Docker Compose 2.38.2 and
Buildx 0.37.1, which is what MIP-0064's three-container Kroki build needs.

### 4.2 Self-hosted runners on a public repo

GitHub's secure-use reference: "Self-hosted runners should almost never be used for public
repositories on GitHub, because any user can open pull requests against the repository and
compromise the environment." The fork-PR approval setting (Settings → Actions → General,
"Approval for running fork pull request workflows from contributors") has three levels; the
strictest is **"Require approval for all external contributors"**. The two weaker ones are
bypassed once a user has had any commit merged, and `pull_request_target` runs regardless of the
setting. Pick: the strictest level, plus §5.2's guard so no `pull_request` job can reach the desktop.

### 4.3 GHCR visibility and cost

Public packages are free, and "container image storage and bandwidth for the Container registry
is currently free". A package's first publish is **private** by default; one pushed with
`GITHUB_TOKEN` is linked to the workflow's repository and inherits its permissions. So task 2's
first push creates a private `marola-dev/marola` package, and making it public stays a manual step.

### 4.4 Retention action

`snok/container-retention-policy`: issue #119 is this exact error ("missing field `id`"), closed
with no fix, only a workaround (a classic PAT instead of the workflow token); #96 on the same
failure is still open; v3.1.0 (2026-05-29) is the latest release. `actions/delete-package-versions`
v5.0.0 (2024-01-16, "not taking contributions") has no tag filter, and its `ignore-versions` regex
matches the version *name*, which for a container is the digest, so `jvm-*` cannot be expressed.
**Pick: delete `ghcr-retention.yml`.** Its reason was billed storage on a private repo; a public
package costs nothing to keep. A classic PAT to keep a housekeeping job alive would be a
long-lived broad credential for no saving. Reopen if GitHub ever bills public storage (it promises a
month's notice).

### 4.5 Nix on hosted runners

`DeterminateSystems/nix-installer-action` v23 (2026-09-09) supports GitHub-hosted runners.
`DeterminateSystems/magic-nix-cache-action` v15 (2026-09-09) works and is free again, backed by the
Actions cache, with a warning about 429 rate limits. The 2025 end-of-life post was reversed in code
(#134/#135 removed the deprecation warnings) but the post itself was never updated. FlakeHub Cache is
the paid tier and is not used. **Pick:** installer v23 + magic-nix-cache v15. The fallback is a cold
`nix develop .#lint` with no cache. `nix-community/cache-nix-action` does not list this installer as
compatible, so it is not a drop-in.

`scala-steward-action`'s "Unable to install managed tools" is issue #793: no JVM on the runner
when coursier installs its tools. It was fixed in v2.87.0 (2026-04-24); the latest is v2.96.0,
which `@v2` resolves to. The workflow already runs `setup-java` first, so a failure that persists on
`@v2` points at the desktop's environment. That fits §5.5's expectation.

## 5. Design

### 5.1 Runner layout

| Workflow | After | Change |
|---|---|---|
| `ci.yml`, `site.yml`, `api-docs`, `pr-body`, `scala-steward`, `site-health` | `ubuntu-latest` | `vars.CI_RUNNER` fallback removed, label hardcoded |
| `profile-activity` | `ubuntu-latest` | the reusable workflow's `runner:` input, same change |
| `docker*`, `marola-e2e`, `ci-short-circuit-pr-close` | `ubuntu-latest` | already |
| `ghcr-retention` | — | deleted (§4.4) |
| `marola-sea-publish` | `[self-hosted, marola-sea]` | unchanged; `workflow_dispatch` only |

Cron comments written around the desktop sleeping (`site.yml`, `scala-steward.yml`) go.
`runner-preflight`/`gha-runner` self-tests stay: they still describe the GPU runner.

### 5.2 Guard: only the GPU job may be self-hosted

`scripts/workflow_runners.py`, stdlib only, with `--self-test`, run from `quality-other`. It scans
`.github/workflows/*.yml` line by line (no YAML dependency) and fails when any `runs-on:` or
reusable `runner:` value names `self-hosted` or `vars.CI_RUNNER` outside `marola-sea-publish.yml`,
or when `marola-sea-publish.yml`'s `on:` block gains `pull_request`/`pull_request_target`.
Self-test fixtures: a clean tree, a stray `self-hosted` in `ci.yml`, a `CI_RUNNER` fallback, and
`pull_request` added to the GPU workflow.

### 5.3 Toolchain: Nix for lint, `setup-java`/`setup-sbt` for sbt

sbt jobs (`build-test`, `site` build, `api-docs`, `scala-steward`) keep Temurin 25 via
`setup-java`, `setup-sbt`, and their existing `actions/cache` keys; the first hosted run is a cold
build. `quality-other` and `repo-stats` install Nix (§4.5) and prepend `nix develop .#lint`'s tool
directories to `$GITHUB_PATH` once — the `which … | dirname` step `ci.yml` already has, made
unconditional. Deleted: every `runner.environment == 'self-hosted'`/`'github-hosted'` pair, the
two `apt-get` steps, and the `ruff-action` steps with their hand-pinned `0.16.5`, since `ruff`
then comes from the same lock as `just quality`. actionlint, hadolint and shellcheck come from the
lint shell where it carries them; the marketplace actions stay only for any it does not.

### 5.4 Docker back on, under the org's name

- The `if: vars.DOCKER_CI == 'on'` gates and their comment go from all three workflows. PRs build
  and run the image; `main` pushes; `docker-smoke` runs daily at 09:30 UTC. `dev` stays dispatch-only.
- Every image reference becomes `ghcr.io/marola-dev/marola`: `docker-smoke.yml`'s two defaults,
  `docker-compose.yml`'s two `image:` lines and its comment.
- `docker-local`'s last real failure is its hadolint step on `Dockerfile.local`. Fixed in the
  Dockerfile, or a `# hadolint ignore=` naming why the rule does not apply — not by lowering
  `failure-threshold`. The benchmark gate behind it then runs for the first time on a hosted
  runner; if the gate itself is wrong, that is a follow-up issue, not a silenced step.

### 5.5 The broken workflows

- `ci-short-circuit-pr-close`: `gh run cancel --repo "$GITHUB_REPOSITORY" "$id"`.
- `ghcr-retention`: deleted (§4.4), with its row in `docs/CI-CD.md` saying why.
- `scala-steward`: expected to recover on the hosted image; if not, its own issue.
- `site-health`: not broken — it reports IMA/SC with no Campeche sampling point and INEMA/BA
  returning nothing. Out of scope; filed separately as a data issue.

### 5.6 Delivery and cross-MIP blocking

**Prerequisite, its own issue, not a task here:** `parse_deps` in `scripts/lib/tasks_issues.py`
rejects any `depends on` token that is not a row of the same table. It learns `NNNN-TK` tokens,
resolved to an issue with the same `dedup_re` title match it already uses; an unmatched token is
still an error. Self-test cases: `0064-T4` resolves against a stub issue list, a missing one
raises, `1, 0064-T4` yields both edges. This keeps "edges come from the column and nowhere else"
true for cross-MIP edges too.

[`MIP-0065.tasks.md`](./MIP-0065.tasks.md) has one root, task 1, whose `depends on` cell is
`0064-T4` (#458), resolved by #462. MIP-0064 is a chain, so that one edge blocks the
whole stack until MIP-0064's last task closes, and readiness rule 5 keeps `just issue-claim` from
handing any task out early.

Tasks, each a PR in the stack:

1. **hosted-runners** — §5.1, §5.2, §5.3; the guard ships with the move, since on its own it would
   fail `main`. Includes verifying MIP-0064's mkdocs + Kroki step on the hosted runner and correcting
   MIP-0064 §5.4's "the runners are self-hosted with Docker on the host". Depends on `0064-T4`.
2. **docker-on** — §5.4. Depends on 1.
3. **workflow-fixes** — §5.5. Depends on 1.
4. **ci-cd-doc** — `docs/CI-CD.md`, its rows in `AGENTS.md` and `docs/index.md` (MIP-0064 renames
   `docs/README.md`), `FUTURE-WORK.md` §7.2 reduced to a pointer; passes MIP-0064's `--strict`
   build. Depends on 2 and 3.

**Manual steps for the maintainer, between tasks 1 and 2** (settings an agent cannot change):
relabel the desktop runner `marola-sea` only; set fork PR approval to "Require approval for all
external contributors" (§4.2); delete the `CI_RUNNER` variable; after task 2's first push, make
`marola-dev`'s `marola` package public (§4.3).

### 5.7 Later, not designed here: the bot's continuous deployment

A merge to `main` rolling the Telegram bot out is Phase 2 — MIP-0057's GCP backend, a paid
resource needing a cost estimate and a human go-ahead — and Phase 1 (the bot working) is not done.
What this MIP leaves for it: every merge pushes `ghcr.io/marola-dev/marola:jvm`, the artefact such
a deploy would run. That MIP gets the next free number when Phase 1 closes.

## 6. Scoring / safety impact

None. No scoring, no user-facing text beyond the map's "Last live run" timestamp.

## 7. Verification plan

1. `scripts/workflow_runners.py --self-test`; then the script against the real tree: zero findings
   after task 1, one per job before it.
2. Task 1's PR forces every `ci.yml` path filter on once (a throwaway commit touching each filtered
   path, reverted before merge) so no job is green by being skipped.
3. Tool parity: `quality-other` prints `ruff`, `actionlint`, `hadolint`, `shellcheck` versions;
   they equal `nix develop .#lint --command <tool> --version` locally.
4. After task 1 merges: `site.yml` and `api-docs` run on `ubuntu-latest`, marola.dev and
   marola.dev/docs/ (MIP-0064's sidebar and search) serve, with the desktop runner **offline**.
5. After task 2: `docker.yml` green on its PR; `gh workflow run docker-smoke.yml` and marola.dev
   shows a new "Last live run"; `docker pull ghcr.io/marola-dev/marola:jvm` works logged out.
6. After task 3: a closed PR's short-circuit run succeeds while that PR still has a run in
   flight; `scala-steward` dispatched by hand opens or finds its PRs.
7. Done = the desktop runner stays off for a week and nothing but `marola-sea-publish` notices.

## 8. Risks, limitations, and honest caveats

- **Cold caches.** The first hosted sbt run pays full dependency resolution and compile; slower,
  not broken. The time is recorded in task 1's PR, not hidden.
- **Nix install time** on every `quality-other` run, bounded by §4.5's cache choice.
- **GPU training stays on one machine.** `marola-sea-publish` still needs the desktop; hosted GPU
  runners are paid and not proposed.
- **MIP-0064's Kroki stack** runs three containers per docs build on a 4-vCPU hosted runner; if it
  outgrows `timeout-minutes: 30`, that is found in task 1, not assumed away.
- **Fork approval is a setting, not code.** The guard (§5.2) stops a workflow change from routing a
  fork's code to the desktop; it cannot check the repository setting itself.

## 9. Alternatives considered

- **Flip `CI_RUNNER=ubuntu-latest`, change nothing else.** One click, but both tool paths stay,
  the fallback can route fork PRs back to the desktop, and the lint versions drift from the lock.
- **Nix for every job.** One mechanism, but a Nix install in front of every JVM job for parity the
  sbt jobs already have through `setup-java`; rejected for run time.
- **Keep `CI_RUNNER` as an escape hatch.** Reopens the fork exposure whenever it is used.
- **Unregister the desktop runner entirely.** Loses GPU publishing, or needs a paid GPU runner.
- **This MIP first, MIP-0064 rebased on top.** Offered; the maintainer chose MIP-0064 first.
- **Cross-MIP edges by hand (`issues.sh deps add`).** No code, but breaks "edges come from the
  `depends on` column" and a re-projection would not know the edge exists.
- **Do nothing.** CI keeps stopping when the desktop sleeps, and a fork can run code on it.

## 11. Open questions

- Is the desktop runner still registered after the transfer to `marola-dev`, and at repo or org
  level? `gh api …/actions/runners` returned 403 with the session's token. Checked in task 1.
- **Follow-up MIP:** the bot's continuous deployment (§5.7), next free number, after Phase 1.
- **Follow-up issue:** `site-health`'s water-data gaps for Florianópolis and Salvador (§5.5).

## Appendix

### Checked live

- 2026-09-28 `gh repo view marola-dev/marola` → `PUBLIC`; `git remote` → `marola-dev/marola`.
- 2026-09-28 `gh api users/h0ffmann/packages?package_type=container` → `marola private`.
- 2026-09-28 `gh run list` per workflow: results as quoted in §2.
- 2026-09-28 `gh run view --log-failed` for `ghcr-retention` (36417928504), `scala-steward`
  (35630836506), `site-health` (36383640675), `ci-short-circuit-pr-close` (36428833596),
  `docker-local` (last failure: the hadolint step) — error lines as quoted in §2.
- 2026-09-28 `git show 85d191a` (#340): the move to self-hosted, reason "exhausted
  minutes/spending limit", and the `CI_RUNNER` escape hatch.
- 2026-09-28 `gh api repos/marola-dev/marola/milestones/2` and issues #455–#458: MIP-0064's chain.
- 2026-09-28 docs.github.com/en/billing/concepts/product-billing/github-actions — free for public
  repos on standard hosted runners; larger runners always billed.
- 2026-09-28 docs.github.com/en/actions/reference/security/secure-use — "Self-hosted runners should
  almost never be used for public repositories".
- 2026-09-28 docs.github.com/…/managing-github-actions-settings-for-a-repository — the three fork
  approval levels; strictest "Require approval for all external contributors". (A summariser named
  the weakest one as strictest; the raw page was read.)
- 2026-09-28 docs.github.com/en/billing/concepts/product-billing/github-packages — public packages
  free; container storage and bandwidth currently free.
- 2026-09-28 docs.github.com/…/working-with-the-container-registry — first publish is private;
  `GITHUB_TOKEN` pushes link the repository.
- 2026-09-28 github.com/snok/container-retention-policy issues #119 (closed, workaround only), #96
  (open); latest release v3.1.0, 2026-05-29.
- 2026-09-28 github.com/actions/delete-package-versions — v5.0.0 (2024-01-16); no tag input;
  `ignore-versions` matched against `version.name` (`get-versions.ts`).
- 2026-09-28 github.com/actions/runner-images Ubuntu2404-Readme (20260920.314.1) — Docker 28.0.4,
  Compose 2.38.2, Buildx 0.37.1.
- 2026-09-28 DeterminateSystems/nix-installer-action v23 and magic-nix-cache-action v15 (both
  2026-09-09); magic-nix-cache README "Totally free. Backed by GitHub Actions' cache."
- 2026-09-28 scala-steward-org/scala-steward-action — `coursier.ts` error string; issue #793 closed
  2026-09-23; fix #784 in v2.87.0; latest v2.96.0 (2026-08-08).

### Not checked

- The desktop runner's registration after the transfer (403, §11).
- The repository's current fork-PR approval setting (needs admin scope).
- magic-nix-cache's real hit rate and 429 behaviour on this repo; measured in task 1.
- Why `scala-steward` still fails on `@v2` after the upstream fix; assumed to be the desktop.
