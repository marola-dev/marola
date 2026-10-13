# Evals for marola-sharingan

Two sets, in skill-creator's formats, run by `../scripts/bench.py` with the `claude` CLI.

- `trigger-evals.json`: 40 queries that should and should not load the skill. `bench.py trigger`
  installs the skill in a throwaway project, runs `claude -p` on each query (3 runs by default)
  and records whether the first tool call is the skill. A query passes when its trigger rate is on
  the right side of 0.5.
- `evals.json`: eight output cases. `bench.py output` runs cases 1 to 5. It builds a throwaway
  workspace: the six fixture repos in `files/acme/` (each made a git repo with a fixed commit, so
  the agent has SHAs to cite) next to a generated destination that looks like marola (CC BY 4.0
  docs and AGPL-3.0-only code in a `REUSE.toml`, an `AGENTS.md` with marola's hard rules, a
  `skills.lock`, the `AGENT-SKILLS.md` table). It runs each case with the skill installed and
  without it, then grades the answer and the git diff: `expectations` by a second `claude -p` call
  with no tools, `checks` by code (`diff_empty`, `exists`, `unchanged`, `contains`,
  `not_identical`).
- Cases 6 to 8 are marked `"manual": true`. They are the devkit `sharingan`'s cases, they fetch
  real GitHub repos, and `bench.py` blocks the network tools. Run one by hand or with a subagent,
  as skill-creator does, and grade it against its `expectations`.

| Fixture repo | Licence | What it tests |
|---|---|---|
| `acme-skills` | MIT | a skill with a script: reuse, with its notice, lock row and annotation |
| `acme-ci` | GPL-2.0 (no "or later") | a workflow and a justfile: incompatible with AGPL-3.0-only, so borrow or cite |
| `acme-prompts` | none | all rights reserved: cite only |
| `acme-docs` | CC BY-SA 4.0, and CC BY-NC 4.0 in `docs/diagrams/` | ShareAlike stays ShareAlike; NC is cite only; a subdirectory licence beats the root |
| `acme-lint` | Apache-2.0 with NOTICE; `package.json` says MIT; `vendor/gplfmt/` is GPL-3.0 | manifest mismatch, NOTICE, a vendored dir under another licence |
| `acme-notes` | MIT | a skill whose concepts marola already has homes for: the map and the checkpoint before a port |

The licence files in the fixtures are headers only, enough for a reader and for
`licence_scan.py` to identify them. The fixtures are invented and belong to no real project. They
are test data: none of those licence files licenses anything in this repository.

```bash
python3 .claude/skills/marola-sharingan/scripts/bench.py --self-test     # no model calls
python3 .claude/skills/marola-sharingan/scripts/bench.py all             # trigger x3, output x2
python3 .claude/skills/marola-sharingan/scripts/bench.py output --ids 3 --runs 3
```

A full run makes 120 trigger calls, 20 agent runs and 20 grader calls. Its cost has not been
measured. The upstream's full run (78, 16 and 16 calls) cost about 3 USD at the CLI's default model
in October 2026. Ask the person before a full run. Record the summary it prints in `BENCHMARK.md`,
with the date and the CLI version.
