# Security policy

## Reporting a vulnerability

Please report privately using
[GitHub's private vulnerability reporting](https://github.com/h0ffmann/marola/security/advisories/new)
(Security tab → "Report a vulnerability") rather than opening a public issue. This repo has no
dedicated security email; GitHub's private advisory flow is the reporting channel. You should get
an acknowledgement within a few days — this is a one-person project, please be patient.

## Scope

marola is a local-first pipeline (Ollama by default) with per-integration, opt-in Azure services
(Foundry, Maps, Cosmos DB, AI Vision, Application Insights). In scope:

- No secret, API key, or connection string should ever be committed to this repo.
- `.env` and any `*.pem`/`*.key` file must stay untracked and masked from agent sandboxes
  (`.ai-jail`, see `AGENTS.md`).
- Azure clients should authenticate via `azure-identity`'s `DefaultAzureCredential` against a
  managed identity, not a static key. Today only `AzureFoundryLlmClient` does this — Cosmos DB,
  Azure AI Vision, and Azure Maps still take a key from the environment
  (`docs/FABLE_REVIEW.md` D1, tracked as a pre-Phase-2 migration in `AGENTS.md`).
- Nothing here provisions or deploys a paid Azure resource without an explicit human go-ahead
  (`AGENTS.md`, "Cost & deployment safety").

If you find a real key or secret committed in this repo's history, please still report it
privately rather than opening a public issue, so it can be rotated before disclosure.
