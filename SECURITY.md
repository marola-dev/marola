# Security policy

## Reporting a vulnerability

Please report privately using
[GitHub's private vulnerability reporting](https://github.com/h0ffmann/marola/security/advisories/new)
(Security tab → "Report a vulnerability") rather than opening a public issue. This repo has no
dedicated security email; GitHub's private advisory flow is the reporting channel. You should get
an acknowledgement within a few days; this is a one-person project, please be patient.

## Scope

marola is a local-first pipeline (Ollama by default). In scope:

- No secret, API key, or connection string should ever be committed to this repo.
- `.env` and any `*.pem`/`*.key` file must stay untracked and masked from agent sandboxes
  (`.ai-jail`, see `AGENTS.md`).
- Nothing here provisions or deploys a paid cloud resource without an explicit human go-ahead
  (`AGENTS.md`, "Cost & deployment safety").

If you find a real key or secret committed in this repo's history, please still report it
privately rather than opening a public issue, so it can be rotated before disclosure.
</content>
