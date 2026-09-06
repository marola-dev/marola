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
  The rule is also a hook, not only prose: `.claude/hooks/guard-azure.sh` (`PreToolUse` on `Bash`,
  wired in `.claude/settings.json`) blocks `azd up|provision|deploy` and `az deployment …` with
  exit 2 unless `MAROLA_ALLOW_AZURE_DEPLOY=1` is set for that one command after a human go-ahead;
  its `--self-test` runs in `just quality`. ai-jail stays the second layer.
- Never hardcode an API key, connection string, or secret. Every Azure client *should*
  authenticate via `azure-identity`'s `DefaultAzureCredential` against a managed identity — today
  only `AzureFoundryLlmClient` does; Cosmos DB, AI Vision and Azure Maps still take a key from the
  environment (FABLE_REVIEW D1 — migrate before Phase 2). If a new credential is genuinely
  required, add it to `.env.example` as a placeholder — never commit a real value.
- Every optional Azure integration is opt-in per pattern (a trait with `local/` and `azure/`
  backends, see `docs/ARCHITECTURE.md` §5) — never assume Azure is configured; `AppConfig` picks a
  backend per integration from env vars, defaulting to the local/free path.
