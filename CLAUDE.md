@AGENTS.md

Read AGENTS.md before doing anything in this repository: it holds every rule (phase discipline,
cost and deployment safety, the Cost: trailer, code style, testing). The line above imports it
for Claude Code; tools that read this file as plain text must open AGENTS.md themselves.

## Claude Code-specific additions (MIP-0011 §5 item 10)

- **Use plan mode before touching `azure/**`.** Any multi-file or unfamiliar change under
  `azure/**`/`infra/**`/`*.bicep` should go through plan mode first, not just `.claude/rules/
  azure.md`'s auto-loaded rule text — the cost/deployment stakes there are the highest in the
  repo, and a plan the human can veto before code changes land is cheaper than a mid-edit
  correction.
- **`/clear` between features, `/rename` to the branch name** — restated from `AGENTS.md`'s
  "One feature, one session" rule because it's a session-hygiene habit, not just a cost-tracking
  one: a fresh context per feature keeps a reviewer subagent's read of `git diff` uncontaminated
  by an earlier, unrelated task's reasoning still sitting in the transcript.
- **On compaction, preserve: which files were modified, which test commands were run (and their
  result), and the `Cost:` figure so far.** These three are exactly what a resumed session needs
  to avoid re-deriving state or re-running gates that already passed — everything else (the
  back-and-forth that got there) is fine to lose.
- **`CLAUDE.local.md`** (gitignored, see `.gitignore`) is for genuinely personal,
  machine-specific overrides — a local Ollama port, a preferred jail flag — that shouldn't be
  forced on every clone of this repo the way `.claude/settings.json` is. Don't put anything here
  that changes shared behavior (hooks, permissions, attribution) — those belong in the committed
  `.claude/settings.json` per `AGENTS.md`'s own reasoning for why that file is committed.
