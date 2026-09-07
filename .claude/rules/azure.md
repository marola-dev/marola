---
paths: ["azure/**", "infra/**", "**/*.bicep"]
---

# Azure integration rules

Full detail excerpted from `AGENTS.md`'s "Cost & deployment safety" section — it lives here so it
loads while you're actually touching an Azure integration; `AGENTS.md` keeps the hard-rule
one-liner for every session, since the cost gate matters even outside `azure/**`.

- **Never provision or deploy a paid Azure resource** (anything beyond a free tier: Azure
  OpenAI/Foundry model deployments, Container Apps usage beyond the free grant, etc.) **without
  explicit human confirmation first.** Propose the change, state the expected cost, and wait for a
  go-ahead. This applies to `azd up`, `azd provision`, and `az deployment group create` alike.
  The rule is enforced two ways, and they are *not* interchangeable (an ultrareview on 2026-09-06
  found the docs describing them as one bypass, which they aren't): `.claude/settings.json`'s
  `permissions.deny` refuses the exact literal prefixes (`azd up`, `azd provision`,
  `az deployment `, `az group create`) before any hook runs — for those,
  `MAROLA_ALLOW_AZURE_DEPLOY=1` does nothing, the call never reaches the hook; the human runs the
  command directly, or adds a one-shot rule to `.claude/settings.local.json`.
  `.claude/hooks/guard-azure.sh` (`PreToolUse` on `Bash`, wired in `.claude/settings.json`) is the
  second, broader layer, blocking every other invocation shape (`./azd up`, `bash -c 'azd up'`,
  a wrapped `cd infra && azd up`) with exit 2 unless `MAROLA_ALLOW_AZURE_DEPLOY=1` is set for that
  one command after a human go-ahead — its `--self-test` runs in `just quality`. ai-jail is a
  third layer, orthogonal to both.
- Never hardcode an API key, connection string, or secret. Every Azure client *should*
  authenticate via `azure-identity`'s `DefaultAzureCredential` against a managed identity — today
  only `AzureFoundryLlmClient` does; Cosmos DB, AI Vision and Azure Maps still take a key from the
  environment (FABLE_REVIEW D1 — migrate before Phase 2). If a new credential is genuinely
  required, add it to `.env.example` as a placeholder — never commit a real value.
- Every optional Azure integration is opt-in per pattern (a trait with `local/` and `azure/`
  backends, see `docs/ARCHITECTURE.md` §5) — never assume Azure is configured; `AppConfig` picks a
  backend per integration from env vars, defaulting to the local/free path.
