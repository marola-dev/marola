# Benchmark

## marola-sharingan: not run yet

`scripts/bench.py all` has not been run for this skill. There is no pass rate and no trigger rate
for it. Run it, with the person's yes because it spends model calls, and put the summary it prints
here with the date and the `claude` CLI version.

Only the two self-tests have been run (`scripts/bench.py --self-test`,
`scripts/licence_scan.py --self-test`). They check the eval files, the fixtures, the checks and
the matrix. They make no model calls and say nothing about how an agent behaves with the skill.

## The upstream's numbers (ouroboric-sharingan, not this skill)

These are for h0ffmann/picasso's `ouroboric-sharingan` at commit `2d6f8d8`, recorded in its own
`evals/BENCHMARK.md`. Its `scripts/bench.py all` was run in a cloud session on 2026-10-12 at the
`claude` CLI's default model, with no model or effort pin. They are the baseline this port has to
match. They do not describe this port, because its `SKILL.md`, destination, trigger set and output
cases all differ:

| | ouroboric-sharingan at `2d6f8d8` | marola-sharingan |
|---|---|---|
| Destination in the output cases | AGPL-3.0-only code, CC BY 4.0 docs; `AGENTS.md` reserves `REUSE.toml` and workflows | the same licence split; `REUSE.toml` is annotated by the agent; marola's hard rules, `skills.lock`, `AGENT-SKILLS.md` |
| Trigger queries | 26 | 40 (those 26 adapted, the devkit `sharingan`'s 12, 2 new) |
| Output cases `bench.py` runs | 4, on 5 fixture repos | 5, on 6 fixture repos (the 4 adapted, plus `map-before-port`) |
| Output cases run by hand | 0 | 3 (the devkit `sharingan`'s, which need the network) |

| Round | Change before it | With skill | Without skill | Trigger |
|---|---|---|---|---|
| 1 | first version (1 run per case) | 0.79 | 0.51 | 20/20 (1 run, first 20 queries) |
| 2 | vendoring licence text and holders are required; reserved paths taken literally; GPL-2.0 text without "or later" is -only; no "copy it anyway" | 0.79 ± 0.27 | 0.59 ± 0.33 | 25/26 (3 runs) |
| 3 | description also covers "copy, paste or port a file from another repo" | 0.94 ± 0.06 | 0.46 ± 0.32 | 26/26 (3 runs) |

Round 3 by case, 2 runs each, passed/total:

| Case | With skill | Without skill |
|---|---|---|
| org-report | 8/9, 8/9 | 5/9, 3/9 |
| bring-in-mit-skill | 8/9, 8/9 | 8/9, 6/9 |
| refuse-gpl2-copy | 5/5, 5/5 | 0/5, 0/5 |
| docs-sharealike-nc | 5/5, 5/5 | 3/5, 3/5 |

Gaps the upstream recorded in round 3, which this port inherits until its own run says otherwise:
the org report twice missed that the NC diagram is cite-only, and in 2 of the 8 runs with the
skill installed the agent did not call the Skill tool and still passed.
