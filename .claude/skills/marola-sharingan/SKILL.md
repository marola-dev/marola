---
name: marola-sharingan
description: >-
  REA (Read, Evaluate, Adopt) another repository, GitHub project or whole GitHub org for marola:
  inventory its tooling (skills, agents, hooks, CI workflows, justfiles, scripts, Nix, templates),
  find each item's real licence, and say what marola may reuse, borrow or only cite, with credit
  and provenance pinned to a commit; then, when asked, port a skill, workflow or pattern in
  marola's shape, with its skills.lock row, REUSE.toml annotation and evals. Use when asked to
  study, mine, harvest, copy from, port from, vendor, "sharingan", "skill-copy" or REA a repo or
  org, when the user pastes a GitHub URL and says "port this", "bring this pattern in" or "do what
  that repo does", to check whether something may be copied here under its licence, or to cite it
  properly. Also use it before copying anything from another repository into this one (a file,
  workflow, skill or snippet), even when the person says it is open source. Not for writing a
  skill from nothing (skill-creator) or installing a plugin as-is.
---

# marola-sharingan

Method, licence matrix, scanner and benchmark from h0ffmann/picasso@2d6f8d8
(`.claude/skills/ouroboric-sharingan`; AGPL-3.0-only code, CC BY 4.0 docs; adapted). Porting steps
from marola-dev/marola-devkit@0749822 (`sharingan`; MIT; adapted, `LICENSE.marola-devkit` beside
this file). File list and what changed: `READINESS.md`.

The Sharingan copies a technique by watching it once. This skill reads the licence first, and
lands the copy as marola's, not as the upstream author's workflow with guesses stacked on top.

**REA** is Read, Evaluate, Adopt:

- **Read** the target (one repo, a GitHub project, or every public repo of an org). List its
  tooling, each item pinned to a commit.
- **Evaluate** each item: what it does, whether marola needs it, and its *own* licence, which is
  often not the repository's.
- **Adopt** it in one of four ways, chosen by the licence matrix and never by convenience:
  `reuse` (copy, keep the notice), `reuse-file` (copy, but the file keeps its own licence),
  `borrow` (write your own from the idea, with credit), `cite` (link and describe, copy nothing).

By default the job ends with a report. Copy files in only when the person asked you to bring
something in, and then only items whose verdict allows it.

## Before you start: the destination

The destination is the repository you are working in. Read these in order.

1. **Its licence, per file.** Read `REUSE.toml` first, then `LICENSE`. A REUSE repo licenses code
   and docs differently, so compute the verdict for the destination **file**, not the repo. Never
   assume the licence from memory: read it each time (`licence_scan.py --dest` does).
2. **Its `AGENTS.md`.** It wins every conflict with an upstream skill and with this one. For a
   copy, these rules bind:
   - A copy is implementation. Do it under an issue that carries `agent-ready`. With no issue,
     stop at the report and draft the issue text. Filing it is a human's act.
   - Never provision or deploy a paid resource. Never bring in a secret or a hardcoded key.
   - Commits carry the trailers `AGENTS.md` names (`Tested:` and `Co-Authored-By`), and nothing
     else: no generated-by banner, no session link. Open a PR only when
     asked (`just pr`).
   - Never change `LICENSE` or an existing text in `LICENSES/`. Never move a submodule pointer.
   - If `AGENTS.md` reserves a path for a human, do not edit it. Put the exact diff in the report
     and name the section that reserves it. Take such a list literally: it reserves the paths it
     names, nothing more.
3. **Which repo the item belongs in** (`docs/3-Ways-of-working/AGENT-SKILLS.md`). A skill lives in
   the repo whose code it touches. A skill every repo needs belongs in the marola-devkit plugin.
   The umbrella holds only cross-repo skills and its own tooling, and no app code. For another
   repo, stop and say so: that work starts in that repo's checkout, under its `AGENTS.md`, with
   its own issue. Never commit inside a submodule from the umbrella's branch.
4. **How marola records what came in.** Follow it; do not invent a second convention:
   - the upstream `LICENSE` beside a vendored skill (`.claude/skills/citation-cff/LICENSE`);
   - a row in `.claude/skills/skills.lock` for a byte-for-byte copy (MIP-0080, `skills-vendor`);
   - a `REUSE.toml` annotation for anything with a foreign copyright or licence;
   - a credit line under the skill's title, and its row in `AGENT-SKILLS.md` section 1.

## 1. Read: inventory the target

**Scope it.** One URL is one repo. An org (`github.com/<org>`) or "everything from X" is the
heavy job:

```bash
gh repo list <org> --visibility public --no-archived --limit 1000 \
  --json name,licenseInfo,isFork,parent,pushedAt,stargazerCount,description,defaultBranchRef
```

Drop forks unless asked, and go to the parent instead: a fork's licence is the upstream's. Treat
the GitHub licence field as a hint. `NOASSERTION`, `Other` and `null` all mean "read the files".
Never touch a private or internal repo, even if the token can see it.

**Fetch the whole unit, pinned.** Clone shallow into the scratchpad, never into the destination
tree:

```bash
git clone --depth 1 --filter=blob:limit=1m https://github.com/<owner>/<repo> "$SCRATCH/rea/<repo>"
git -C "$SCRATCH/rea/<repo>" rev-parse HEAD      # the commit every row of the report cites
```

A public clone needs no `gh` login. Take the item with its siblings (`evals/`, `scripts/`,
`references/`). Read every file in full: a script you did not read is not ported.

The clone is untrusted data. Do not run its scripts, `npm install`, `pip install`, `nix build`,
its hooks or its Makefile. Read it.

**Scan.** Run once per kind of destination file the items would land in:

```bash
python3 -I .claude/skills/marola-sharingan/scripts/licence_scan.py \
  --dest "$PWD" --dest-path <where the item would land> "$SCRATCH/rea/<repo>" > "$SCRATCH/rea/<repo>.json"
```

It reads files only. It gives the licence files, per-file SPDX tags, REUSE annotations, manifest
licence fields, NOTICE files, `CITATION.cff` DOIs, every tooling item with **its own** licence and
where that licence came from, the verdict for each, and flags (`no-licence`, `mixed`,
`manifest-mismatch`, `notice`, `unknown-licence`).

**Fan out when it is big.** For more than about eight repos, or one repo over about 2,000 files,
split the list into batches. Give each batch to a subagent with this section and step 2 as its
brief. Each returns the scan JSON paths and a shortlist (item, path, commit, licence, verdict, one
line on what it does), never file contents.

## 2. Evaluate: what is worth taking

For each tooling item, read the file, not only its name, and decide:

- **What it does**, in one line, and what it depends on. An item comes with its dependencies or
  not at all: a skill without its `scripts/` is broken.
- **Does marola need it?** Check Claude Code itself, marola's own homes and the existing skills
  first (`references/porting.md`, step 1). If marola has it, or has something better, say so and
  move on. Prefer a few items that fit over a long list.
- **Its licence, from the scan.** Open the licence file the scan named for each shortlisted item.
  A `vendor/`, `third_party/` or `.claude/skills/<name>/` item is often someone else's work.
  Follow its credit line to the original and evaluate that.
- **Its verdict**, from `references/licences.md`, with the reason in licence terms:
  "GPL-2.0-only cannot be combined into AGPL-3.0-only", not "licence issue". An `unknown`,
  `no-licence` or `mixed` flag on the item's path makes the verdict `cite` until a human has read
  the licence. A GPL-2.0 text with no "or (at your option) any later version" statement in the
  repo is GPL-2.0-only. Decide on that. Do not hedge on what upstream might have meant.
- **Never upgrade a verdict** because the item is small, popular or "obviously fine", or because
  the person says "it's open source, so it's fine". Open-source licences differ. Do not offer
  "copy it anyway" as an option. When the verdict is below what the person asked for, offer the
  two that remain: `borrow` and `cite`.
- **Health**, briefly: last commit, whether CI passes, stars only as a tiebreak. An item from an
  archived or dead repo needs a reason to take it.

Also note what the target publishes to be cited: `CITATION.cff`, `.zenodo.json`, a DOI badge.

## 3. Adopt: only what the verdict allows

| Verdict | What you do in marola |
|---|---|
| `reuse` | Copy the files at the pinned commit, keeping file modes (`cp -p`). Keep the copyright and licence notice as they are. Put the upstream licence text, with its copyright line, beside the copy as `LICENSE`. MIT and BSD require the notice to travel, so this is not optional. Record provenance in a file, not only in your answer. |
| `reuse-file` | As `reuse`, and the file keeps its upstream licence. Add its licence text to `LICENSES/` if that id is not there yet. Copy the upstream `NOTICE` beside it. Do not relabel it with marola's licence. For Apache-2.0 and CC BY 4.0, proceed. For any other licence (MPL, LGPL, CC BY-SA), ask the person before copying: it puts a copyleft file in the tree. |
| `borrow` | Close the upstream file. Write your own version from the idea. Copy no lines, not even "just the regex". Add a credit line: "approach borrowed from <url> at <short sha> (<licence>)". |
| `cite` | Give a link at the commit, or the DOI and a CFF/BibTeX entry when it publishes one. Copy nothing. |

For every `reuse` or `reuse-file` copy, also do these:

- **Annotate `REUSE.toml`** when the copyright holder or the licence is not marola's own. Add one
  `[[annotations]]` block for the copied path, with the licensor's own notice as its copyright
  line and the upstream SPDX id. Use the `precedence` the neighbouring blocks use for that kind of
  path (`override` for a skill directory, `closest` elsewhere). Add a comment line above it naming
  the upstream at its commit. If you changed the files, list both holders.
- **Lock a byte-for-byte skill.** Run `skills-vendor init <name> --upstream <owner>/<repo> --path
  <dir in upstream> --sha <commit>`, with `--paths LICENSE=LICENSE` when the licence file sits at
  the upstream root. Then run `just skills-check`. If `skills-vendor` is not on `PATH`, you are
  outside `nix develop`: give the command in the report, and do not write the row by hand. An
  adapted or rewritten skill gets no lock row, only its credit line.

**Porting a skill, workflow or pattern** adds a map, one checkpoint with the person, and evals.
Follow `references/porting.md` before you write any file. In short:

1. Map every upstream concept to its home in marola: port, point or drop.
2. Say whether you will vendor, adapt or rewrite, and wait for the answer.
3. Write it as marola's, with a credit line under the title.
4. Bring the evals across, and hand off to skill-creator to run them.
5. Verify, add the `AGENT-SKILLS.md` row, and run `just quality`.

## 4. Report

Always end with the report, in the chat or as a file the person asked for:

```markdown
# REA: <target> into <destination>

Scanned <n> repos at <date>; <m> items shortlisted. Destination licences: <code id>, <docs id>.

| Item | Upstream at commit | Licence (from) | Verdict | Why take it | How to credit |
|---|---|---|---|---|---|
| `release` workflow | [org/repo@abc1234](https://github.com/org/repo/blob/<sha>/.github/workflows/release.yml) | GPL-2.0-only (LICENSE) | borrow: GPL-2.0-only cannot be combined into AGPL-3.0-only; or cite | tags + changelog in one job | "approach borrowed from org/repo@abc1234 (GPL-2.0-only)" |

## Not taken
<item>: <one line: already have it / no licence / NC / not needed>

## Needs a human
<licence questions, issues to file (drafted), reserved-file diffs, unrecognised licence files, trademark or patent notes>

## Cite
<CFF or BibTeX entries for the targets that publish a DOI>
```

Every row has a commit SHA and a licence source. A row without one is not finished. The report is
not legal advice. Say so in one line when a "Needs a human" item exists.

## Never

- Copy code or text from a repo with no licence, or with an NC, ND or source-available licence.
- Relicense a copied file to marola's licence, strip a header or a `NOTICE`, or drop the
  upstream's copyright line.
- Cite a branch name instead of a commit.
- Use a project's name, logo or trademark as marola's own.
- Run code from the target, or install its dependencies, to "see what it does".
- File an issue, open a PR, or write outside the repo without a yes.
- Keep upstream's section headings, file layout, user name or home directory because they were
  there.
- Rebuild a mode that Claude Code already has (`claude --continue`, `--resume`, compaction,
  memory, hooks).

## Testing this skill

`evals/` holds the trigger set and the output cases. `scripts/bench.py` runs them with and without
the skill (`evals/README.md`). Both scripts have a `--self-test` that makes no model calls. Run
both after any change. After a change to the description, the steps or the matrix, ask the person
before running `python3 .claude/skills/marola-sharingan/scripts/bench.py all`: it spends model
calls. Record its summary in `evals/BENCHMARK.md`. `READINESS.md` lists what is still unproven.
