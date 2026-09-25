---
name: sharingan
description: Port a skill, workflow or pattern from another repository into marola, given its URL — fetch the whole unit, check its licence, map every upstream concept to what marola or Claude Code already has, then vendor, adapt or rewrite it in marola's shape with evals. Use when the user pastes a GitHub/raw URL and says "port this", "copy this skill", "bring this pattern in", "do what that repo does", "/sharingan <url>" or "/skill-copy <url>". Not for writing a skill from nothing (skill-creator) or installing a plugin as-is.
argument-hint: <url> [name]
model: claude-opus-5-5
effort: xhigh
---

# sharingan — copy a pattern, land it as marola's

Target: $ARGUMENTS

Copying is the easy part. The failure this skill exists to prevent is the one a colleague hit
porting a vault skill between repos: the upstream author's workflow carried over wholesale, the
agent's own guesses stacked on top ("menos com menos dá menos"), and a whole mode rebuilt that
`claude --continue` already did.

## Steps

1. **Fetch the whole unit, pinned.** A GitHub blob URL becomes
   `https://raw.githubusercontent.com/<owner>/<repo>/<ref>/<path>`. List the siblings with
   `gh api repos/<owner>/<repo>/contents/<dir>?ref=<ref>` (or `curl` on `api.github.com` when
   `gh` has no login — the jail never does) and download all of them — `evals/`, `scripts/`,
   `references/` — into the scratchpad. Pin the commit: `.../commits/<ref>` → `.sha`. Read every
   file in full; a script not read is not ported.

2. **Licence decides what is allowed.** `.../repos/<owner>/<repo>` → `.license.spdx_id`, plus
   any `LICENSE` beside the unit.

   | Licence | Allowed |
   |---|---|
   | MIT, Apache-2.0, BSD, ISC | vendor verbatim with `LICENSE` beside it (`humanizer`, `ponytail`), or adapt with a credit line (`eli5`) |
   | none, `NOASSERTION` | the idea only: write from scratch, no sentence copied, credit the URL as inspiration |
   | GPL, AGPL, anything unclear | stop and ask |

3. **Map before writing.** One row per concept upstream introduces: *upstream concept → marola's
   home → port / point / drop*. Look, in this order:
   - **Claude Code itself** — `claude --continue`/`--resume`, compaction, auto-memory, `/rename`,
     hooks, permissions, plugins, `enabledPlugins`.
   - **marola's own homes** — MIPs (decisions: an ADR here *is* a MIP), `AGENTS.md` and
     `.claude/rules/` (practices), `docs/mips/MIP-NNNN.tasks.md` and the GitHub project (tasks),
     `docs/*.md`, the justfile.
   - **Existing skills** — `.claude/skills/`, `docs/AGENT-SKILLS.md`, installed plugins.

   Every row "point" or "drop" → stop and tell the user no skill is the right port.

4. **Choose vendor, adapt, or from scratch — and say so before writing.** Vendor only when the
   licence allows it and the map is almost all "port". Rewrite from scratch when more than about a
   third of the rows were re-pointed or dropped: an adaptation that keeps someone else's structure
   reads right and behaves wrong. This is the one checkpoint with the user.

5. **Write it as marola's.**
   - `.claude/skills/<name>/SKILL.md`, frontmatter like its neighbours: `name`, then a
     `description` that says what it does, "Use when …" with phrases people actually type, and
     "Not for …" naming the neighbour skill it could be confused with.
   - Upstream's paths, user names and tools become marola's: `just` recipes, `nix develop`,
     `${CLAUDE_PROJECT_DIR}`, an env var with a default for anything machine-specific. Say what
     the ai-jail cannot see (`~/.config/gh`, most of `$HOME`).
   - `AGENTS.md` wins every conflict: comment restraint, the commit trailers, no secrets, the
     cloud cost gate, phase discipline.
   - Anything upstream does "proactively" becomes an offer that waits for a yes (`AGENTS.md`,
     "Before implementing a feature"). Nothing writes outside the repo without one.
   - A credit line under the title: `Pattern from <owner>/<repo>@<sha7> (<licence>; vendored |
     adapted | rewritten).`
   - Under ~150 lines; long reference material in a sibling file the skill reads on demand.

6. **Evals.** Carry upstream's evals across, rewritten for marola — its project names, paths and
   what "done" means here. None upstream: an output case per main mode, plus ~6 should-trigger /
   ~6 should-not queries where the negatives are near misses for neighbour skills.
   Output cases in `evals/evals.json`, trigger queries in `evals/trigger-evals.json`, both in
   skill-creator's formats (`docs/AGENT-SKILLS.md` §2.3).

7. **Verify, then report what happened.**
   - `python3 "$(find ~/.claude/plugins -path '*skill-creator/scripts/quick_validate.py' | head -1)" .claude/skills/<name>`,
     — it knows only the portable spec's keys, so `model`/`effort`/`argument-hint`/
     `disable-model-invocation` warnings are expected; anything else is a real error.
   - One eval case run by a subagent with the skill; the same case without it as a baseline when
     cheap (`writing-skills`). Report what the agent did, not what the skill says it should do.
   - A row in `docs/AGENT-SKILLS.md` §1 carrying the provenance; `just quality-other`.
   - Commit per `AGENTS.md`; a PR only when asked (`just pr`).

## Red flags

- Upstream's section headings or file layout kept because they were there.
- A file per upstream concept (`tasks.md`, `adrs/`, `practices.md`) where marola already has a home.
- Upstream's user name, home directory or tool path surviving the port.
- A "proactively do X" with no confirmation step.
- A mode that re-implements a Claude Code built-in.
