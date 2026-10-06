# Gemini Code Assist

A free, advisory, on-request review pass on a repo's PRs, and how to stand up the full GCP product
two ways: by hand in the Google Cloud console, or as a Besom (Pulumi for Scala) program with state
in GCS. Everything dated here was checked on 2026-09-21; vendor pages move, so re-check before
relying on a number.

Why this tool, still: every marola repo is public now (MIP-0070's split), so the original reason to
reach for it — the only hosted reviewer free on a **private** repo — no longer holds, and being
public opens CodeRabbit's and Sourcery's free tiers too (§8). What marola-devkit ships as of v0.4.0
(2026-10-04) is lighter than any of these: `gemini-review`, a reusable workflow a repo opts into
with one file, calling the Gemini API directly, no GCP project or billing account
([`4-reference_workflows.md`](https://docs.marola.dev/5-Repos/marola-devkit/4-reference_workflows/)).
Each repo opts in with its own `gemini.yml` (MIP-0072 §5.4). This
page stays research because Gemini Code Assist for GitHub is still a materially different, more
complete product — a managed GitHub App with its own severity/comment tuning, nothing to hold an
API key for — and the Besom plan below (§4–§6) is what standing *that* up would look like, not a
dead idea just because a cheaper alternative shipped. MIP-0060's local route (open-code-review on
Ollama) is parked because `qwen2.5-coder:7b` cannot do the tool calls; this is the hosted
alternative it reserved for a human go-ahead in its §5.5, because source still leaves the machine
either way. §8 has the survey.

## 1. What marola-app already has

| File | Does |
|---|---|
| [`.gemini/config.yaml`](https://github.com/marola-dev/marola-app/blob/main/.gemini/config.yaml) | Turns off review-on-open, summary-on-open and draft handling; `MEDIUM` severity floor; at most 10 comments; ignores test fixtures, `flake.lock`, native-image metadata, vendored JS. |
| [`.gemini/styleguide.md`](https://github.com/marola-dev/marola-app/blob/main/.gemini/styleguide.md) | The subset of `AGENTS.md`/`.claude/rules/scala.md` a diff reviewer can check. Gemini injects this file into its prompt; it does **not** read `AGENTS.md` on its own. |
| `DEV-FLOW.md` §5 | Route 4: `/gemini review`, on request only. |

These files are committed; the GCP-side connection below (§3–§4) has not been, pending a human
go-ahead.

Gemini's default is to review every PR the moment it opens. `DEV-FLOW.md` §5 says nothing reviews
a PR automatically, and a nine-PR MIP stack would burn nine reviews at once, so
`pull_request_opened.code_review: false` is the one setting that matters. `.github/workflows/**`
is skipped by Gemini itself, always.

## 2. Using it

On a PR, comment:

| Comment | Effect |
|---|---|
| `/gemini review` | One summary comment plus inline comments with a severity and a committable suggestion. Repeat after new commits. |
| `/gemini summary` | Description only. |
| `/gemini <question>` | Ask about the diff. |
| `/gemini help` | The list above. |

Reply to an inline comment to argue with it. Same discipline as a Claude review (superpowers
`receiving-code-review`): verify the finding before acting on it. Never make it a required check.

## 3. GCP side, by hand (the fast path)

The Feb-2025 "free for individuals, install the GitHub app" path is gone from Google's docs; the
only documented install now goes through **Google Cloud Developer Connect** and needs a **GCP
project with a billing account attached**. Google states there are no charges during Preview, but a
card is on file. This is a human's action, never an agent's: it is the moment a card goes on file
and source starts going to Google.

1. Pick or create a project; confirm billing is linked. You need Owner/Admin on it, or Service
   Usage Admin plus `roles/geminicodeassistmanagement.scmConnectionAdmin` (grantable only via
   `gcloud projects add-iam-policy-binding`, not the console).
2. Console → **Gemini Code Assist → Agents & Tools → Source Code Management** → create the
   connection. Enable the Developer Connect API and the Gemini Code Assist Management API when
   the banners ask. The connection lands in `us-east1`; Google says an existing connection made
   for another feature (code customisation) cannot be reused.
3. When GitHub asks: **Only select repositories → `marola-dev/marola-app`**. Never "All
   repositories".
4. Back in Developer Connect → **Link repositories** → `marola-app`.
5. Gemini Code Assist privacy settings → turn off "use my data to improve Google products" (the
   individual edition defaults to sharing). §5 makes this a line of code instead.

Then open any PR and comment `/gemini review`.

## 4. GCP side, as Besom (candidate MIP — not built)

Every GCP-side piece has a Pulumi resource, and Besom is current: `besom-core` **0.5.2**
(2026-09-18) and `besom-gcp` **9.0.0-core.0.5** (2025-09-27, wrapping Pulumi GCP provider 9.0.0
of 2025-09-18) on Maven Central. Do not trust `search.maven.org`'s index for these: it lags a
year; read `repo1.maven.org/maven2/org/virtuslab/` directly.

| Need | Resource | In pulumi-gcp since |
|---|---|---|
| Enable the two APIs | `gcp.projects.Service` | — |
| The role the console cannot grant | `gcp.projects.IAMMember` | — |
| The connection, in `us-east1` | `gcp.developerconnect.Connection` | 8.2.0 |
| Link `marola-dev/marola-app` | `gcp.developerconnect.GitRepositoryLink` | 8.2.0 |
| Data-sharing opt-out, as code | `gcp.gemini.DataSharingWithGoogleSetting` + `…Binding` | 8.20.0 |
| Gemini enablement, as code | `gcp.gemini.GeminiGcpEnablementSetting` + `…Binding` | 8.20.0 |

Layout: a standalone scala-cli project under `infra/gemini/`, **not** an sbt module, so the
`besom-gcp` jar — the whole GCP surface — never loads into whichever repo's ordinary build or test
run. This would be that repo's first IaC, so it is a MIP before it is a branch.

```scala
// infra/gemini/project.scala — sketch, not compiled
//> using scala 3.3
//> using dep "org.virtuslab::besom-core:0.5.2"
//> using dep "org.virtuslab::besom-gcp:9.0.0-core.0.5"
import besom.*
import besom.api.gcp

@main def main = Pulumi.run {
  val apis = List("developerconnect.googleapis.com", "cloudaicompanion.googleapis.com")
    .map(s => gcp.projects.Service(s, gcp.projects.ServiceArgs(service = s)))

  val conn = gcp.developerconnect.Connection("marola-code-assist",
    gcp.developerconnect.ConnectionArgs(
      location = "us-east1", connectionId = "marola-code-assist",
      githubConfig = gcp.developerconnect.inputs.ConnectionGithubConfigArgs(
        githubApp = "DEVELOPER_CONNECT",
        appInstallationId = config.get("appInstallationId"),      // absent on the first `up`
        authorizerCredential = config.get("oauthSecretVersion").map(v =>
          gcp.developerconnect.inputs.ConnectionGithubConfigAuthorizerCredentialArgs(
            oauthTokenSecretVersion = v)))),
    opts(dependsOn = apis))

  val link = gcp.developerconnect.GitRepositoryLink("marola-app",
    gcp.developerconnect.GitRepositoryLinkArgs(
      location = "us-east1", parentConnection = conn.connectionId,
      gitRepositoryLinkId = "marola-app",
      cloneUri = "https://github.com/marola-dev/marola-app.git"))

  val noSharing = gcp.gemini.DataSharingWithGoogleSetting("no-sharing",
    gcp.gemini.DataSharingWithGoogleSettingArgs(
      dataSharingWithGoogleSettingId = "no-sharing", location = "global",
      enableDataSharing = false, enablePreviewDataSharing = false))

  Stack(link, noSharing).exports(
    nextStep = conn.installationStates.map(_.headOption.map(_.actionUri)))
}
```

Two things Besom does not remove:

- **The GitHub app install is a browser step.** The first `pulumi up` creates the connection
  pending and exports `installationStates[0].actionUri`; open it, install the app on marola-app only;
  the flow writes an OAuth token to Secret Manager. Set `appInstallationId` and
  `oauthSecretVersion` in stack config and run `up` again. Two runs, one click: the workflow
  Pulumi's own docs describe, not a Besom limit.
- **Whether an API-created connection counts as a "Code Assist" connection is undocumented.**
  If the console just makes a plain connection in `us-east1`, Besom's is identical. If it stamps
  a label the review bot filters on, Besom's is ignored silently. Verify before writing the MIP:
  do §3 once, `gcloud developer-connect connections describe marola-code-assist
  --location=us-east1`, look at `labels`/`annotations`; copy any marker into the program and
  `pulumi import` the existing connection rather than creating a second one.

Toolchain notes: nixpkgs has `pulumi` (3.255.0 today) but no Scala language plugin and no
`pulumiPackages.pulumi-gcp`; both install with `pulumi plugin install language scala 0.5.2
--server github://api.github.com/VirtusLab/besom` and `pulumi plugin install resource gcp 9.0.0`
into `~/.pulumi/plugins`. Inside `just jail-claude` `HOME` is ephemeral, so install them on the
host or the plan has to map the plugin dir in, same trap as `gh auth login` in the jail.

## 5. State: GCS self-managed, or Pulumi Cloud

Pulumi's CLI, engine and SDKs are Apache-2.0 and run without any account. What is paid is
**Pulumi Cloud**, the hosted state/secrets/deployments service. Its Individual tier is free: one
user, unlimited stacks and updates, no resource cap, no card, and marola's one maintainer fits it
by definition. There is no separate open-source programme and none is needed.

The choice is therefore not about money but about where the state file lives. The state holds
the connection's details and the Secret Manager *path* of the GitHub OAuth token (not the token).
Recommended: **a GCS bucket in the same project**, one fewer third party holding infra metadata,
and consistent with marola's per-integration opt-in stance.

```bash
gcloud storage buckets create gs://marola-pulumi-state --location=us-east1 \
  --uniform-bucket-level-access --public-access-prevention
gcloud storage buckets update gs://marola-pulumi-state --versioning      # undo a bad state write
pulumi login gs://marola-pulumi-state
cd infra/gemini && pulumi stack init prod --secrets-provider=passphrase   # $0; or gcpkms://… (~$0.06/key/month)
```

`pulumi login gs://…` reads Application Default Credentials (`gcloud auth application-default
login` once on the host). A passphrase secrets provider costs nothing and is enough for a stack
whose only secret is a config value; Cloud KMS is the upgrade when more than one person runs it.
Switch to Pulumi Cloud the day a second person needs the stack: that is the collaboration
feature it sells, at $40/month (Essentials), not free.

## 6. A CD layer

**Pulumi Deployments** (Pulumi's own CD: run `preview`/`up` on their compute or a self-hosted
agent, triggered by a PR or a `git push`) is a Pulumi Cloud feature. It needs Pulumi Cloud as the
backend; the Individual tier includes up to 500 workflow minutes/month. With state in GCS (§5) it
is not available: that is the trade.

The equivalent without Pulumi Cloud is a GitHub Actions workflow with `pulumi/actions`, which
marola can run on its own self-hosted runners (the same ones `ci.yml`'s profile ping and
MIP-0060's OCR job use):

- `pull_request` touching `infra/gemini/**` → `pulumi preview`, diff posted as a PR comment.
  Read-only; fine to run unattended.
- `workflow_dispatch` only → `pulumi up`. Never on push, never on merge: `AGENTS.md`'s
  cost/deployment rule ("never provision without explicit human confirmation") is about
  paid/irreversible provisioning, and a GitHub-triggered `up` is exactly the shape it forbids an
  agent to run. The human presses the button.
- Auth: Workload Identity Federation from the repo's OIDC token to a service account with
  `roles/developerconnect.admin`, `roles/storage.objectAdmin` on the bucket, and the two Gemini
  roles, no JSON key in a GitHub secret.

Follow-up for the MIP, not this PR: add `pulumi up` / `pulumi destroy` to
`.claude/settings.json`'s `permissions.deny`, so an agent cannot run them directly.

## 7. What it costs, and what leaves the machine

- No paid resource: Developer Connect and Secret Manager are within their free tiers, Gemini Code
  Assist on GitHub is free during Preview, GCS state is cents at most. The billing account is a
  prerequisite, not a charge.
- Source goes to Google on every `/gemini review`, repo visibility aside. §3 step 5 / §4's
  `DataSharingWithGoogleSetting` stop it being used to improve Google's products; it is still
  processed by Google. This is the go-ahead MIP-0060 §5.5 reserved for a human, and installing the
  app is that go-ahead. The devkit's `gemini-review` (see above) sends the same diff to the same
  Gemini API, under one call's worth of data, with no GCP project to install into.
- Pre-GA: "limited support", and the quota, the free status and the install path have all
  changed once already (2025 → 2026).

## 8. The alternatives, as of 2026-09-21

Checked when every marola repo was still private; since the split (MIP-0070) they are all public,
which changes the "On a private repo" column below for any repo that adopts one of these instead.

| Tool | On a private repo | Verdict |
|---|---|---|
| Gemini Code Assist for GitHub | Free, ≥ 100 reviews/day per installation (one source: 33/day individual edition); needs a GCP billing account on file | **This doc** |
| marola-devkit's `gemini-review` (v0.4.0) | Free, ~20 reviews/day per model, one Gemini API key, no GCP project | Already built; not yet adopted by any marola repo |
| Greptile Starter | Free, 50 credits/month (1 = one review), unlimited repos, 1 developer; indexes the codebase | Second opinion for the PRs that matter |
| CodeRabbit | Free forever on public repos only; private = 14-day trial then $24/dev/month | Free now that marola's repos are public; not re-verified since the split |
| Qodo Merge | No permanent free tier any more; 14-day trial then $30/month; OSS programme needs a public repo with 200+ stars | No; marola's repos don't have the stars |
| Sourcery | Free on public repos; private $15/dev/month | Free now that marola's repos are public; not re-verified since the split |
| GitHub Copilot code review | Not in Copilot Free; Pro $10/month, and each review also burns Actions minutes since 2026-06 | No |
| PR-Agent (Apache-2.0) / open-code-review (MIP-0060) | $0 software, bring your own model | The model is the cost; GitHub Models — the free-in-Actions inference — was retired 2026-07-30; the Gemini API free tier trains on requests |

## Sources

- marola-devkit [`CHANGELOG.md`](https://github.com/marola-dev/marola-devkit/blob/main/CHANGELOG.md) v0.4.0: the `gemini-review` reusable workflow
- [Customize Gemini Code Assist behavior in GitHub](https://docs.cloud.google.com/gemini/docs/code-review/customize-repo-review): `config.yaml` schema
- [Set up Gemini Code Assist on GitHub](https://docs.cloud.google.com/gemini/docs/code-review/set-up-code-assist-github)
- [Use Gemini Code Assist on GitHub](https://docs.cloud.google.com/gemini/docs/code-review/use-code-assist-github): commands
- [Gemini for Google Cloud — quotas](https://docs.cloud.google.com/gemini/docs/quotas)
- [pulumi `gcp.developerconnect.Connection`](https://www.pulumi.com/registry/packages/gcp/api-docs/developerconnect/connection/)
- [pulumi `gcp.gemini`](https://www.pulumi.com/registry/packages/gcp/api-docs/gemini/)
- [pulumi-gcp releases](https://github.com/pulumi/pulumi-gcp/releases): v8.2.0, v8.20.0, v9.0.0 notes
- [Besom](https://virtuslab.github.io/besom/docs/getting_started) · [`org.virtuslab` on Maven Central](https://repo1.maven.org/maven2/org/virtuslab/)
- [Pulumi pricing](https://www.pulumi.com/pricing/) · [Pulumi Deployments](https://www.pulumi.com/docs/pulumi-cloud/deployments/)
- [CodeRabbit pricing](https://www.coderabbit.ai/pricing) · [Qodo pricing](https://www.qodo.ai/pricing/) · [Greptile pricing](https://www.greptile.com/pricing) · [Copilot plans](https://github.com/features/copilot/plans) · [GitHub Models retirement](https://docs.github.com/en/github-models/use-github-models/prototyping-with-ai-models)
