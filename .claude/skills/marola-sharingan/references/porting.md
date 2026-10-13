# Porting a skill, workflow or pattern into marola

Read this before you write any file of a port. It applies after `SKILL.md` steps 1 and 2: the unit
is fetched whole, pinned to a commit, and has a verdict.

Copying is the easy part. The failure these steps prevent is a real one: an upstream author's
workflow carried over wholesale, the agent's guesses stacked on top, and a whole mode rebuilt that
`claude --continue` already did.

## 1. Map before writing

Write one row per concept the upstream introduces: *upstream concept → marola's home → port, point
or drop*. Look for the home in this order:

- **Claude Code itself**: `claude --continue` and `--resume`, compaction, auto-memory, `/rename`,
  hooks, permissions, plugins, `enabledPlugins`.
- **marola's own homes**: MIPs (a decision or ADR here is a MIP), `AGENTS.md` and `.claude/rules/`
  (practices), `docs/MIPs/MIP-NNNN.tasks.md` and the org Project (tasks), `docs/`, the justfile and
  the devkit's recipes.
- **Existing skills**: `.claude/skills/`, `docs/3-Ways-of-working/AGENT-SKILLS.md`, the
  marola-devkit plugin and the other installed plugins.

If every row is "point" or "drop", stop. Tell the person that no skill is the right port.

## 2. Choose vendor, adapt or rewrite, and say so before writing

This is the one checkpoint with the person. Show the map, the licence, the commit and your choice.
Then wait.

| Choice | When | What it leaves in the repo |
|---|---|---|
| vendor | the verdict is `reuse` or `reuse-file`, and the map is almost all "port" | the upstream bytes, `LICENSE` beside them, a `skills.lock` row, a `REUSE.toml` annotation |
| adapt | the verdict allows a copy, and some rows were re-pointed | marola's own file with a credit line; `LICENSE` beside it; a `REUSE.toml` annotation naming both holders; no lock row |
| rewrite | the verdict is `borrow`, or more than about a third of the rows were re-pointed or dropped | marola's own file, no upstream sentence in it, a credit line |

An adaptation that keeps someone else's structure reads right and behaves wrong. Prefer the
rewrite when in doubt.

A vendored skill is edited only through a patch. Keep the edit as `.claude/skills/<name>.patch`,
name it in the lock row's `local_edits`, and check that `just skills-update <name>` re-applies it.

## 3. Write it as marola's

- Put the skill at `.claude/skills/<name>/SKILL.md`. Give it front matter like its neighbours:
  `name`, then a `description` that says what it does, "Use when …" with phrases people type, and
  "Not for …" naming the neighbour skill it could be confused with. Pin no model and no effort.
- Replace upstream's paths, user names and tools with marola's: `just` recipes, `nix develop`,
  `${CLAUDE_PROJECT_DIR}`, an environment variable with a default for anything machine-specific.
  Say what the ai-jail cannot see (`~/.config/gh`, most of `$HOME`).
- `AGENTS.md` wins every conflict: comment restraint, the commit trailers, no secrets, the cost
  gate, phase discipline, the `agent-ready` gate.
- Turn anything the upstream does "proactively" into an offer that waits for a yes (`AGENTS.md`,
  "Before implementing a feature"). Nothing writes outside the repo without one.
- Put a credit line under the title: `Pattern from <owner>/<repo>@<sha7> (<licence>; vendored |
  adapted | rewritten).`
- Keep `SKILL.md` near 150 lines. Move long reference material to a sibling file that the skill
  reads on demand.

## 4. Evals

- Carry the upstream's evals across, rewritten for marola: its project names, its paths, and what
  "done" means here.
- If the upstream has none, write an output case per main mode, and about six should-trigger and
  six should-not queries. Make the negatives near misses for neighbour skills.
- Put output cases in `evals/evals.json` and trigger queries in `evals/trigger-evals.json`, both in
  skill-creator's formats (`AGENT-SKILLS.md` section 2.2).
- Hand off to skill-creator to run them and to tune the description, when it is installed.

## 5. Verify, then report what happened

- Validate the front matter:
  `python3 "$(find ~/.claude/plugins -path '*skill-creator/scripts/quick_validate.py' | head -1)" .claude/skills/<name>`.
  It knows only the portable keys, so a warning about `argument-hint` or
  `disable-model-invocation` is expected. Anything else is a real error.
- Run one eval case with a subagent that has the skill. Run the same case without it as a
  baseline when that is cheap. Report what the agent did, not what the skill says it should do.
- Add the row to `AGENT-SKILLS.md` section 1, with the provenance.
- Run `just quality`, and `just skills-check` for a vendored skill.
- Commit as `AGENTS.md` says. Open a PR only when asked (`just pr`).

## Red flags

- The upstream's section headings or file layout are kept because they were there.
- There is a file per upstream concept (`tasks.md`, `adrs/`, `practices.md`) where marola already
  has a home.
- The upstream's user name, home directory or tool path survived the port.
- A "proactively do X" has no confirmation step.
- A mode re-implements a Claude Code built-in.
