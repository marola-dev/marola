# MIP-0013: OpenCode as marola's development agent — a tryout, and what replacing Claude Code would take

| | |
|---|---|
| **Status** | Draft |
| **Author** | Claude Fable 5.1, for M. Hoffmann (request of 5 Sep 2026: "a new MIP for OpenCode tryout for development, replacing Claude Code") |
| **Created** | 2026-09-05 |
| **Phase** | 0 — developer tooling; nothing a user of marola sees. No earlier-phase prerequisite |
| **Related** | `AGENTS.md` (attribution, `Cost:` trailer, gates, ai-jail — every rule the harness has to keep enforcing), `PHILOSOPHY.md` ("Why ai-jail", "Why a `justfile`"), `docs/DEV-FLOW.md`, `docs/AGENT-SKILLS.md`, MIP-0011 (the Claude Code practices this maps onto OpenCode or declares lost), `.claude/settings.json`, `.claude/skills/`, `.ai-jail`, `justfile` (`jail-claude`/`jcf`/`jcs`/`claude-cost`/`cost-split`), `scripts/cost-split.py` (reads Claude Code's session logs — the one hard dependency on the harness) |

## 1. Summary

Try [OpenCode](https://opencode.ai) — MIT, `anomalyco/opencode`, v1.18.29 on 2026-09-04, in
nixpkgs at 1.18.28 — as the day-to-day coding agent for this repository, for a bounded experiment:
two MIP-0010-sized tasks delivered end to end under OpenCode, measured on the things this repo
already measures (gates green, `Cost:` per PR, review findings), against the same two tasks'
Claude Code figures. Nothing under `.claude/` is removed during the tryout; OpenCode reads
`AGENTS.md` and `.claude/skills/` natively, so the overlap is large by construction. What is *not*
free: the `Cost:` pipeline (`cost-split.py` parses `~/.claude/projects/*.jsonl`), the commit
attribution line, the ai-jail recipes, and — the material one — **billing**: since 2026-04-04
Anthropic's terms forbid Claude Pro/Max subscription OAuth in third-party harnesses, so Claude
models under OpenCode are pay-per-token API, not the subscription that makes today's `Cost:`
figure a quota proxy rather than a bill. The MIP proposes the tryout with that stated up front,
the local Ollama model as the default per `PHILOSOPHY.md`, and a rollback that is "stop typing
`opencode`".

## 2. Motivation

- **The repo is written for "any AI coding agent … Claude Code or otherwise"** (`AGENTS.md` line
  3), yet every recipe and doc paragraph about the agent is Claude-specific: `just jail-claude`,
  `just claude-cost`, `/usage`, `.claude/settings.json`'s `attribution`, `CLAUDE.md`'s import.
  Trying a second harness is the only way to find out which rules are really harness-neutral.
- **Open source, one config file, the same instruction files.** OpenCode reads `AGENTS.md`
  first, `CLAUDE.md` as a fallback, `.claude/skills/*/SKILL.md` as skills, and puts permissions,
  MCP servers, agents, commands, formatters and plugins in one `opencode.json` (docs, fetched
  2026-09-05 — §4). MIP-0011's ten tasks are largely a port of practices OpenCode expresses as
  config keys.
- **Model choice per task, local first.** `PHILOSOPHY.md` wants a free local default everywhere;
  OpenCode's `provider.ollama` block (OpenAI-compatible, `http://localhost:11434/v1` — the same
  endpoint `LocalLlmClient` uses) plus a per-agent `model` makes "cheap agent on `llama3.2`, hard
  agent on a paid API" a config line, not a discipline.
- **Cost transparency is a house rule** (`AGENTS.md` "Attribution and cost accounting"), and a
  paid-per-token harness makes the `Cost:` line a real bill. That is a reason to measure, not to
  skip: the tryout's first deliverable is the same `Cost:` trailer, from OpenCode's own logs.

## 3. User-visible change

None for the swimmer. For whoever develops marola:

```
$ nix develop                                  # opencode from nixpkgs (1.18.28) joins the shell
$ just jail-opencode                           # ai-jail, same policy as jail-claude, opencode's dirs mapped
opencode v1.18.28 · agent build · model ollama/llama3.2 (default) · AGENTS.md loaded · 3 skills
> /mip 0014 …                                  # .opencode/commands/mip.md → the mip skill, same text
> just build && just test && just quality      # allowed by opencode.json permission rules, no prompt
> azd up
✗ bash "azd *" is denied by permission config   # the cost rule, enforced by the harness
$ just opencode-cost                           # ccusage opencode session — the per-session table
$ just cost-split MIP-0014                     # reads OpenCode's storage too (task 2 below)
```

Commits keep one trailer. Under OpenCode it reads `Co-Authored-By: opencode <noreply@opencode.ai>`
— OpenCode's own default (§4.5) — and `AGENTS.md`'s attribution rule is amended to name both.

## 4. Data sources and dependencies reviewed

All fetched 2026-09-05 unless noted. OpenCode is not installed on this machine; nothing below was
exercised live — every claim is from the documentation or the GitHub API, and §11 lists what a
live check must confirm.

### 4.1 The project

`anomalyco/opencode` (GitHub API): "The open source coding agent", MIT, 204 654 stars, pushed
2026-09-05, default branch `dev`, homepage opencode.ai; `sst/opencode` redirects there. Latest
release v1.18.29 (2026-09-04). Installs: `curl … opencode.ai/install`, `npm i -g opencode-ai`,
brew tap, pacman/paru, `mise`, `docker run ghcr.io/anomalyco/opencode`. **Nix:** nixpkgs
`pkgs/by-name/op/opencode/package.nix` on `nixos-unstable` is version 1.18.28, MIT, built from
source with Bun (one release behind). Terminal TUI, desktop app, IDE extension; also `opencode
serve` (headless HTTP), `opencode run` (non-interactive, `--format json`, `--model`, `--agent`).

### 4.2 Instructions, skills, commands, agents — where marola's existing files land

- **Rules** (`/docs/rules`): searches upward for `AGENTS.md`, then `CLAUDE.md`; then
  `~/.config/opencode/AGENTS.md`; then `~/.claude/CLAUDE.md`. "For users migrating from Claude
  Code, OpenCode supports Claude Code's file conventions as fallbacks." `instructions: [globs,
  URLs]` in config adds more. `/init` "will improve it in place" if `AGENTS.md` exists. → `AGENTS.md`
  is read as is; `CLAUDE.md` is redundant but harmless.
- **Agent Skills** (`/docs/skills`): `SKILL.md` discovered in `.opencode/skills/<name>/`,
  `~/.config/opencode/skills/`, **and the Claude-compatible `.claude/skills/`** (project and global).
  Frontmatter recognised: `name`, `description`, `license`, `compatibility`, `metadata` only.
  Loaded on demand by a native `skill` tool; access via pattern permissions. → `mip`, `mip-tasks`,
  `site-frontend` load unchanged; MIP-0011's `disable-model-invocation` has no equivalent — the
  `skill` permission (`"mip-tasks": "ask"`) is the nearest.
- **Commands** (`/docs/commands`): `.opencode/commands/*.md` with `description`, `agent`, `model`,
  `subtask`; `$ARGUMENTS`/`$1`, `` !`cmd` `` shell injection, `@file`. → the `/mip`, `/mip-tasks`
  slash commands become two small command files that point at the skills.
- **Agents** (`/docs/agents`): primary (`build`, `plan`) and subagents (`general`, `explore`,
  `scout`), custom ones in `opencode.json` `agent` or `.opencode/agents/*.md` with `description`,
  `mode`, `model`, `temperature`, `permission`, `prompt`, `steps`, `hidden`; invoked by `@name` or
  the Task tool. → MIP-0011's `mip-reviewer` and `jar-verifier` are two markdown files here too.

### 4.3 Permissions, plugins, formatters, MCP — MIP-0011's hooks as config

- **Permissions** (`/docs/permissions`): `permission.{read,edit,bash,glob,grep,task,skill,webfetch,
  external_directory,doom_loop,question}`, values `allow|ask|deny`, glob patterns per key, "the last
  matching rule winning", per-agent overrides, `.env` denied by default, `--auto` approves
  everything not denied. → the cost gate is `"bash": {"azd *": "deny", "az deployment *": "deny"}`:
  harness-enforced, no shell hook needed (MIP-0011 task 2).
- **Plugins** (`/docs/plugins`): JS/TS in `.opencode/plugins/` or npm; events include
  `tool.execute.before/after`, `permission.asked`, `session.idle`, `file.edited`, `shell.env`;
  `throw new Error(…)` in `tool.execute.before` blocks the call. → MIP-0011's Stop-gate ("tests
  not run this session") is a `session.idle` plugin; Bun/TypeScript is a new toolchain in the repo.
- **Formatters** (`/docs/formatters`): off by default; when enabled, run after every write/edit;
  built-ins include `ruff`, `prettier`, not `scalafmt`; custom `{"command": [...,"$FILE"],
  "extensions": [".scala"]}`. → MIP-0011 task 3 (format on write) is one config block, if a native
  `scalafmt` is in the shell (MIP-0011 §11 OQ1 still open).
- **MCP** (`/docs/mcp-servers`): `mcp.<name>: {type: "local", command: [...], environment,
  timeout}` or `type: "remote"`; per-agent enabling. → marola's own server, `just mcp-server`, is
  one entry (MIP-0011 task 9's `.mcp.json`, same idea).

### 4.4 Providers and billing

`/docs/providers`: 75+ providers via the AI SDK and the models.dev catalogue; Ollama as
`@ai-sdk/openai-compatible` with `baseURL: http://localhost:11434/v1` and a `models` block;
Azure OpenAI via `AZURE_RESOURCE_NAME` + key through `/connect` (deployment name = model name);
Anthropic via API key **or** "Claude Pro/Max authentication through browser-based login". That
last path is no longer allowed: Anthropic's consumer terms since 2026-04-04 state that using OAuth
tokens from Free/Pro/Max accounts "in any other product, tool, or service … is not permitted"
(coverage 2026-02/04, Appendix; OpenCode named among the affected tools). So under OpenCode,
Claude is an API key at list rates; the `Cost:` line stops being a quota proxy and becomes a bill.
The local default (`ollama/llama3.2`, or the `marola-llama3.2` variant) costs nothing.

### 4.5 Cost logs and attribution

- **Storage:** `~/.local/share/opencode/` — since v1.2 a SQLite `opencode.db` plus
  `storage/message/<session>/msg_*.json` and `storage/session/<projectHash>/*.json`, with
  per-message token usage; `cost` is stored as 0 and priced downstream. `opencode stats` ("Show
  token usage and cost statistics for your OpenCode sessions") and `opencode export` (session JSON)
  exist in the CLI. **ccusage** (the tool behind `just claude-cost`) has `ccusage opencode
  daily|weekly|monthly|session`, reads the JSON storage (`OPENCODE_DATA_DIR` to point elsewhere),
  prices from LiteLLM — "experimental, expects breaking changes"; unknown models show $0.00.
  → `just claude-cost` has a drop-in sibling; `cost-split.py` needs a second reader (§5, task 2).
- **Attribution:** OpenCode adds `Co-Authored-By: opencode <noreply@opencode.ai>` to commits by
  default; issue #919 ("Disabling co-authoring", closed) records that prompt instructions did not
  override it and asked for a Claude-Code-like `includeCoAuthoredBy`. **How #919 was resolved
  (a config key, or honouring `AGENTS.md`) was not verified** — §11. The community plugin
  `lannuttia/opencode-git-trailers` standardises trailers (`{{model}}`, `{{provider}}` variables)
  if the built-in behaviour cannot be shaped.
- **Privacy:** `share` defaults to `manual`; `"share": "disabled"` in the committed `opencode.json`
  turns the `/share` upload off for everyone (docs recommend exactly that for teams).

### 4.6 Sandboxing and CI

No sandbox page exists in the docs sidebar (36 pages listed; "Policies" is `experimental.policies`
restricting *providers*, e.g. deny `openai`, "separate from permissions"). Containment stays
ai-jail's job: `just jail-claude` is `ai-jail … --rw-map ~/.claude --rw-map ~/.claude.json …
claude`; the OpenCode twin maps `~/.config/opencode`, `~/.local/share/opencode`, `~/.cache/opencode`
(Bun's plugin installs) instead. `.ai-jail` has `command = ["claude"]` and can only tighten.
CI: `anomalyco/opencode/github@latest`, triggered by `/opencode` or `/oc` in comments, needs
`ANTHROPIC_API_KEY`, runs on the repo's own minutes — a paid run per mention; not part of the tryout.

### Pick

Try it, bounded (§5), with `ollama/llama3.2` as the default model and a paid model only per agent
and per explicit choice. Do not replace anything until the tryout's criteria (§7) are met.

## 5. Design

The tryout adds files next to `.claude/`, deletes nothing, and is one PR per bullet (`mip-tasks`).

1. **`opencode.json` (committed) + `flake.nix` + jail recipes.** `pkgs.opencode` in the dev shell;
   `just jail-opencode` / `just jo` (ai-jail with OpenCode's three directories mapped, `--network`,
   `--terminal-passthrough`, `--no-save-config`); `just opencode-cost` → `npx ccusage@latest
   opencode session`. Config:
   ```jsonc
   {
     "$schema": "https://opencode.ai/config.json",
     "model": "ollama/llama3.2",                       // local first (PHILOSOPHY.md)
     "small_model": "ollama/llama3.2",
     "share": "disabled",
     "provider": { "ollama": { "npm": "@ai-sdk/openai-compatible", "name": "Ollama (local)",
        "options": { "baseURL": "http://localhost:11434/v1" },
        "models": { "llama3.2": {}, "marola-llama3.2": {} } } },
     "permission": {
       "bash": { "*": "ask", "just build*": "allow", "just test*": "allow", "just quality*": "allow",
                 "just fmt*": "allow", "sbt *": "allow", "git status*": "allow", "git diff*": "allow",
                 "git log*": "allow", "scripts/stack.sh status*": "allow",
                 "azd *": "deny", "az deployment *": "deny", "az group create*": "deny" },
       "read": { "*": "allow", ".env": "deny", ".env.*": "deny", "**/*.pem": "deny", "**/*.key": "deny" },
       "skill": { "*": "allow", "mip-tasks": "ask" }
     },
     "mcp": { "marola": { "type": "local", "command": ["just", "mcp-server"], "enabled": false } },
     "formatter": false
   }
   ```
   No secrets: API keys go through `/connect` into `~/.local/share/opencode/auth.json`, never here.
   Paid models are not configured by default; a developer adds `"model": "anthropic/…"` in
   `~/.config/opencode/opencode.json` (global, per-machine) — the cost gate of `AGENTS.md` applies to
   the harness's own bill as much as to Azure.
2. **`scripts/cost-split.py` reads OpenCode storage too.** A second `messages()` source:
   `~/.local/share/opencode/storage/message/*/msg_*.json` (timestamp, model, `tokens.{input,
   output,cache.read,cache.write}` — **field names to confirm against a real file, §11**),
   priced from the same LiteLLM table; `--harness claude|opencode|all` (default `all`), so the
   `Cost:` trailer rule survives unchanged. Self-test on a recorded fixture, in `just quality`.
3. **Commands and agents.** `.opencode/commands/mip.md`, `mip-tasks.md`, `site-frontend.md`
   (three lines each: load the skill, pass `$ARGUMENTS`); `.opencode/agents/mip-reviewer.md` and
   `jar-verifier.md` — MIP-0011 task 7's two subagents, `mode: subagent`, `permission.edit: deny`,
   `model` unset (inherits the default). Skills themselves stay in `.claude/skills/` (read by both).
4. **Attribution.** `AGENTS.md`'s trailer rule names the harness: `Co-Authored-By: Claude
   <noreply@anthropic.com>` from Claude Code, `Co-Authored-By: opencode <noreply@opencode.ai>` from
   OpenCode — one trailer, whichever harness made the commit; `scripts/uprd.sh` keeps working (it
   reads `Cost:` trailers, not the co-author). If OpenCode's trailer cannot be pinned to that exact
   text (§11 OQ1), the `opencode-git-trailers` plugin sets it.
5. **Docs.** `DEV-FLOW.md` §7 gains an OpenCode column for the three harness-specific rows (`/usage`
   → `opencode stats`, `just claude-cost` → `just opencode-cost`, `/code-review` → the
   `mip-reviewer` agent); `AGENT-SKILLS.md` notes which skills load under OpenCode (all three) and
   that superpowers (a Claude Code plugin) does not.
6. **The experiment itself** (no code): two tasks from an accepted MIP's task list — one Scala
   task with a named test, one docs/config task — each in a fresh OpenCode session, `just jo`,
   default model first, escalating to a paid model only when the local one fails the task's test
   twice; PR bodies carry the `Cost:` from task 2's reader and one line "harness: opencode
   1.18.x, model …".

**If the tryout passes (§7), "replacing Claude Code" is a second MIP-sized change**, listed so
its size is known now: `.claude/settings.json` → the attribution line moves to `AGENTS.md` prose
(OpenCode has no `attribution` key); `.claude/skills/` stays (OpenCode reads it) or moves to
`.opencode/skills/`; `CLAUDE.md` becomes a two-line pointer or is deleted (OpenCode reads
`AGENTS.md` directly); `justfile` drops `jail-claude`/`jcf`/`jcs`/`claude-cost`; `.ai-jail`'s
`command` becomes `["opencode"]`; `AGENTS.md`'s two Claude Code paragraphs (attribution, ai-jail)
are rewritten; MIP-0011 is marked *Superseded by MIP-0013* for tasks 1-5, 7, 9 (config here) and
*still applies* for 6, 8, 10 (rules split, skills, memory); `docs/AGENT-SKILLS.md` §2 (superpowers)
is dropped or replaced by OpenCode-native skills; the auto-memory directory under
`~/.claude/projects/` is left as a file the harness no longer reads — its notes move into
`AGENTS.md` or a `SessionStart`-equivalent plugin.

## 6. Scoring / safety impact

None. No product code changes. The cost gate becomes a harness-enforced `deny` (a strict
improvement over prose), and the local model stays the default (no new paid path by default).

## 7. Verification plan

- `scripts/cost-split.py --self-test` covers a recorded OpenCode message fixture and asserts the
  same `Cost:` line shape the Claude reader produces; `just quality` runs it.
- `opencode.json` validates against `https://opencode.ai/config.json` (a `just quality` step with
  `check-jsonschema` if it is in the shell, else a one-off).
- Live, per tryout session, recorded in the PR body: `AGENTS.md` shown as loaded; `skill` tool
  lists the three skills; `azd up` denied with no prompt; `just test` allowed with no prompt;
  `opencode stats` and `just opencode-cost` agree on tokens for the session.
- **Success criteria (all three, for both tasks):** gates green with at most one human
  intervention beyond review; a measured `Cost:` on the PR; a reviewer (superpowers
  `requesting-code-review` under Claude Code, or the `mip-reviewer` agent) finding no Critical.
  **Compare** with the two most recent Claude Code task PRs of the same MIP on: wall time, `Cost:`,
  number of human interventions, review findings. **Done:** the comparison table is in this MIP's
  §11 → "Result", and the status moves to Accepted (replace) or Rejected (keep Claude Code).

## 8. Risks, limitations, and honest caveats

- **Billing changes character.** Claude via OpenCode is API-metered; a `Cost:` of ~$3 per task
  (MIP-0010 tasks 4-6, `just cost-split`) is a real ~$3. The local default avoids it; the tryout
  will show whether `llama3.2` can carry a marola task at all (MIP-0010's benchmark says it can
  answer; writing Kyo code against `-Wnonunit-statement` is another matter).
- **Everything in §4 is documentation, not use.** OpenCode is not installed here; version drift
  is fast (1.18.29 the day after 1.18.28); each task re-fetches the page it relies on.
- **ccusage's OpenCode support is experimental** and prices unknown models at $0.00 — a local
  Ollama model shows $0.00 for the right reason, an unknown paid model for the wrong one.
- **Plugins are TypeScript run by Bun** at startup; a plugin is a third toolchain (after Scala and
  Python) in a repo that prizes few. The design uses none; the Stop-gate stays MIP-0011's.
- **Two harnesses at once double the config surface** (`.claude/` and `.opencode/`/`opencode.json`)
  during the tryout; the skills are shared, the permission lists are not — drift is possible and
  the tryout is short on purpose.
- **Prose rules are still prose** for anything not expressible as a permission (phase discipline,
  the `Cost:` trailer). OpenCode does not make `AGENTS.md` more binding than Claude Code does.

## 9. Alternatives considered

- **Do nothing** — Claude Code works and the subscription covers it; the argument for trying is
  vendor independence and a harness whose config is one committed file, not that anything is broken.
- **Other harnesses** (Codex CLI, Gemini CLI, Aider): none reads `.claude/skills/` and `AGENTS.md`
  as OpenCode does; not researched here, and one tryout at a time.
- **OpenCode for CI only** (`/oc` in PR comments): a paid run per mention with no reviewer; the
  same reason MIP-0011 declined `claude -p` in CI.
- **Replace outright, skip the tryout** — would discard a working, measured flow on documentation
  alone; the house rule is verify first.

## 10. Exam-coverage mapping

None. Developer tooling.

## 11. Open questions

1. **Attribution:** how was #919 resolved — is there a config key for the co-author trailer, does
   an `AGENTS.md` instruction now shape it, or is the `opencode-git-trailers` plugin needed to get
   exactly one trailer with the required text?
2. **Message JSON schema** in `~/.local/share/opencode/storage/message/` (token field names, model
   id form, timestamps) — confirm on a real file before writing task 2's reader; whether the
   SQLite `opencode.db` is the better source since v1.2.
3. **Can `llama3.2` (or `marola-llama3.2`) drive OpenCode's tool loop at all** on a marola task?
   If not, what is the smallest local model that can (`qwen`/`devstral`-class), and does it fit
   the machine?
4. **`.claude/skills/` compatibility depth:** are `disable-model-invocation`/`allowed-tools`
   frontmatter fields ignored silently or rejected? Do the three skills' descriptions surface in
   the `skill` tool as written?
5. **ai-jail under OpenCode:** does Bun's plugin/npm install at startup need write access beyond
   the three mapped directories; does the TUI work with `--terminal-passthrough`?
6. **Result** (filled after the tryout): the §7 comparison table.

## Appendix

Fetched 2026-09-05: `https://api.github.com/repos/sst/opencode` (→ `anomalyco/opencode`, MIT,
204 654 stars, pushed 2026-09-05T21:00Z); `…/releases/latest` (v1.18.29, 2026-09-04T23:47Z);
`https://opencode.ai/docs/` (install list; sidebar: Intro, Config, Providers, Network, Enterprise,
Troubleshooting, Windows, Go, TUI, CLI, Web, IDE, Zen, Share, GitHub, GitLab, Tools, Rules, Agents,
Models, Themes, Keybinds, Commands, Formatters, Permissions, Policies, LSP Servers, MCP servers, ACP
Support, Agent Skills, References, Custom Tools, SDK, Server, Plugins, Ecosystem);
`/docs/config/` (precedence: remote `.well-known/opencode` → global → `OPENCODE_CONFIG` → project
`opencode.json[c]` → `.opencode/` → `OPENCODE_CONFIG_CONTENT` → managed → MDM; keys listed in §4);
`/docs/rules/`, `/docs/agents/`, `/docs/permissions/`, `/docs/mcp-servers/`, `/docs/plugins/`,
`/docs/commands/`, `/docs/skills/`, `/docs/cli/`, `/docs/providers/`, `/docs/share/`,
`/docs/formatters/`, `/docs/github/`, `/docs/policies/`;
`https://raw.githubusercontent.com/NixOS/nixpkgs/nixos-unstable/pkgs/by-name/op/opencode/package.nix`
(version "1.18.28", MIT, Bun build); `https://github.com/anomalyco/opencode/issues/919` (closed;
default trailer `Co-Authored-By: opencode <noreply@opencode.ai>`, prompt instructions did not
override); `https://ccusage.com/guide/opencode/` (`ccusage opencode daily|weekly|monthly|session`,
`OPENCODE_DATA_DIR`, "cost: 0 in message files", experimental); search results on storage
(`opencode.db` since v1.2; `storage/message/<session>/msg_*.json`) and on the Anthropic consumer
terms change (2026-01-09 block, 2026-02 ToS update, enforced 2026-04-04; sources: dev.to,
alternativeto.net, decodethefuture.org — secondary coverage, the ToS text itself was not fetched).
Not fetched: `/docs/enterprise/`, `/docs/acp/`, `/docs/sdk/`, `/docs/custom-tools/`, the OpenCode
config JSON schema, any OpenCode source file. Repo state read: `.ai-jail` (`command = ["claude"]`,
rw_maps `~/.claude`, `~/.claude.json`), `.claude/settings.json` (attribution + superpowers plugin),
`justfile` recipes `jail-claude`/`jcf`/`jcs`/`claude-cost`/`cost-split`, `flake.nix` (ai-jail input,
ccusage via npx), `scripts/cost-split.py` (`~/.claude/projects/<slug>/*.jsonl`, `message.usage`,
`message.model`, dedup by message id).
