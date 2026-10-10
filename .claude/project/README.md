# The Claude project, as configured

A snapshot of the settings of the Claude project "Marola Agent" that live on the Claude service
and not in git, so the project can be rebuilt by hand and a change to it shows up in a diff. It
mirrors the Wave Forecaster project's snapshot in h0ffmann/ww3-gpu's `.claude/project/`, whose
[ADR-0005](https://github.com/h0ffmann/ww3-gpu/blob/main/docs/ADRs/ADR-0005-claude-project-settings-in-repo.md)
records why and what is left out; the same reasoning holds here.

| File | What it holds | Read with |
|---|---|---|
| [`project.json`](project.json) | name, topic, visibility, instructions, repositories, memory and routine settings, what a thread session runs with, the cloud environment, the routines | `get_project_settings`, `get_session`, `list_environments`, `list_triggers` |
| [`routines/a2a-inbox-marola.txt`](routines/a2a-inbox-marola.txt) | the stored prompt of the A2A inbox routine (`para:marola`), verbatim | `get_trigger trig_01UE8yJCJTN2HD2WfTnxbBve` |

Nothing here is read by Claude Code as configuration: it loads `CLAUDE.md`, `settings.json`,
`skills/`, `agents/`, `commands/` and `rules/` under `.claude/`, never this folder. The project's
instructions stored in `project.json` reach a session from the service, not from this file, so
editing them here changes nothing until a person pastes them back. Everything the repository
already holds is not repeated: the permissions are [`../settings.json`](../settings.json), the
skills [`../skills/`](../skills/), and the rules `AGENTS.md` and `CLAUDE.md`, where the A2A protocol
is "Messages between Claude projects".

## Evidence

All values were read on 2026-10-09: the `project` block with `get_project_settings`, which only
the project's coordinator session has, and the rest from a thread session. The model ids are not
written down, since no model id goes into a file pushed to a repository; the tools in the table
read them back. The project sets no effort default, so `thread_sessions.effort` is what the service
gave the session that wrote this file. The environment is the account's only one; the project names
no default. Memory is on for the project but off in the owner's account settings, so there was
nothing to read. The decisions the work follows are in the project instructions or already in git
(`AGENTS.md`, the MIPs); thread history is not copied.

The routine's cron has no `CRON_TZ`, so it runs at minute 5 of every third hour UTC. The account's
Wave Forecaster routines and the one-shot PR check-ins sessions schedule for themselves are left
out: the first are that project's, the second are not settings.

## Restoring the project

1. Create a private project named as in `project.json`, with its topic, and attach its
   repositories.
2. Pick the default model, and paste `instructions` into the project instructions.
3. Create each routine with its `cron` and the text of its `prompt` file, inside the project, so it
   wakes a session with this project's repositories.
4. Reconnect the connectors the work needs and re-enter any environment secret by hand; neither is
   recorded here.

## Keeping it current

The service is the truth and this folder is its dated copy. When a setting changes, read it back
with the tool in the table and commit the new value with `read_on` updated, in the same PR as
anything that depends on it. `scripts/claude_project_check.py` (in `just quality` and CI) checks
that the JSON parses, that every routine's prompt file exists, and that no e-mail address, account
id or session id slipped in. No CI job can check this folder against the service, since no command
outside a Claude session can read the project.
