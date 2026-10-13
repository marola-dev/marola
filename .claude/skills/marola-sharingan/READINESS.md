# marola-sharingan: provenance and what is still unproven

## Provenance

| Came from | At commit | Licence there | Files here |
|---|---|---|---|
| [h0ffmann/picasso](https://github.com/h0ffmann/picasso/tree/2d6f8d89f7859c97db2686fc16118e3da8657da7/.claude/skills/ouroboric-sharingan) `.claude/skills/ouroboric-sharingan/` | `2d6f8d89f7859c97db2686fc16118e3da8657da7` | AGPL-3.0-only for code, JSON and fixtures, CC BY 4.0 for `*.md` (its `REUSE.toml`); © 2026 Matheus Hoffmann | `scripts/licence_scan.py`, `scripts/bench.py`, `references/licences.md`, `evals/README.md`, `evals/BENCHMARK.md`, `evals/evals.json` (cases 1 to 4), `evals/trigger-evals.json` (26 of its queries), `evals/files/acme/` except `acme-notes/`, and the REA sections of `SKILL.md`; all adapted |
| [marola-dev/marola-devkit](https://github.com/marola-dev/marola-devkit/tree/0749822219a121f06c603e52e11c357648ffaed3/plugins/marola-devkit/skills/sharingan) `plugins/marola-devkit/skills/sharingan/` | `0749822219a121f06c603e52e11c357648ffaed3` (as shipped in v0.8.3) | MIT; © 2026 marola.dev (`LICENSE.marola-devkit`) | `references/porting.md`, `evals/evals.json` (cases 6 to 8), `evals/trigger-evals.json` (12 of its queries); all adapted |

Written for this port: `evals/files/acme/acme-notes/`, case 5, two trigger queries, the `--dest`
option of `licence_scan.py`, the `contains` check and the generated destination in `bench.py`.

`bench.py`'s trigger detection re-implements the approach of skill-creator's `run_eval.py`
(anthropics/skills, Apache-2.0); it copies no lines, as the upstream's docstring says.

What changed from the picasso original: the destination is read from the repo instead of assumed;
marola's rules, `skills.lock` and `REUSE.toml` steps replace picasso's; the picasso white-paper
and DOI plan is dropped, because it is picasso's.

## Checklist

Tick a box only with evidence: a commit, a benchmark row or a review link.

- [x] `SKILL.md` covers the destination, Read, Evaluate, Adopt, Report and Never, and pins no
      model or effort
- [x] Licence matrix in `references/licences.md`, the same table as the upstream's, whose rows
      were checked there against the FSF licence list and Creative Commons' compatibility page
      (2026-10-12); not re-checked for this port
- [x] `scripts/licence_scan.py`: stdlib only, reads files only, `--self-test` passes, covers an
      MIT destination and a CC BY 4.0 / AGPL-3.0-only split, and fails if the matrix in the doc
      and the code differ
- [x] Evals: 40 trigger queries, 5 output cases on 6 fixture repos with code checks on the diff,
      and the devkit `sharingan`'s 3 cases kept as manual
- [x] `scripts/bench.py --self-test` passes and makes no model calls
- [ ] CI runs both self-tests (they are not in `ci.yml` or `just quality-other` yet)
- [ ] **Benchmark run for this skill.** None so far; `evals/BENCHMARK.md` holds only the
      upstream's numbers
- [ ] Benchmark at 3 runs per output case: with the skill at 0.90 or more, and ahead of no skill
      in every case
- [ ] Trigger set at 95 % or more over 3 runs, with no false triggers
- [ ] `reuse lint` passes with this directory in the tree (not run: `reuse` was not installed)
- [ ] One real REA done with the skill, and its report reviewed by the human
- [ ] Licence matrix reviewed by a person who knows licensing (it is not legal advice)
- [ ] The devkit plugin's `sharingan` and `skill-copy` retired or pointed here
