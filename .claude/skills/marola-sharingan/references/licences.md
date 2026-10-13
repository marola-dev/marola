# Licences: what may come in, and how

Read this when an item's verdict is not obvious, or before you write the report's licence
column. `scripts/licence_scan.py` gives the same verdict from the same table, for the licence named
with `--into <id>` or read from the destination with `--dest <repo> --dest-path <file>`. Its
`--self-test` fails if the table below and the script differ.

This is a working rule set for an agent, not legal advice. When a case is not in the table, or
the table and the licence text seem to disagree, the verdict is `cite` and a human decides.

## The destination's licence

Read it from the destination each time; do not assume it. `--dest` uses the same order as "Which
licence an item has" below: a `REUSE.toml` annotation first, then the nearest `LICENSE`.

marola declares its licences in `REUSE.toml`: CC BY 4.0 for docs and everything not named, and
AGPL-3.0-only for code (`scripts/`, `.github/`, the justfile, the flake). So an item that lands in
`scripts/` takes the `AGPL-3.0` column, and one that lands in `docs/` or `.claude/skills/` takes
the `CC-BY-4.0` column, where a code file is `reuse-file`: it comes in under its own licence, with
an annotation. Other marola repos differ (marola-devkit is MIT, the `permissive` column), which is
why the licence is read and not written here.

## The four verdicts

| Verdict | You may | You must |
|---|---|---|
| `reuse` | copy the file or text, and change it | keep the copyright line and licence notice; record upstream URL, commit and licence |
| `reuse-file` | copy the file, and change it | keep **that file** under its own licence: a REUSE annotation (or a header if the destination uses headers), its licence text in `LICENSES/`, its NOTICE if any. Do not relicense it to the destination's licence |
| `borrow` | take the idea, the structure, the approach | write your own text and code without the upstream file open beside you; copy no lines; add a credit line ("approach borrowed from <url> at <commit>, <licence>") |
| `cite` | link to it and describe it in your own words | copy nothing, not even a short block; a citation (URL at commit, or the DOI from its `CITATION.cff`) |

`borrow` and `cite` are always open, whatever the licence: ideas and facts are not covered by
copyright. Only the expression is. A patent clause or a trademark is a different thing; see
"Not covered by any licence" below.

## The matrix

Rows are the licence of the item you want. Columns are the licence of the destination **file**
(not the destination repo: a repo with REUSE.toml can have code under one licence and docs under
another). `permissive` covers MIT, MIT-0, BSD-2-Clause, BSD-3-Clause, ISC, 0BSD, Zlib, Unlicense,
CC0-1.0, BSL-1.0, PSF-2.0. `CC-NC-ND` is any Creative Commons licence with NC or ND.
`source-available` is BUSL, SSPL, Elastic-2.0, Commons Clause, PolyForm.

<!-- matrix:start -->
| from \ into | permissive | Apache-2.0 | GPL-3.0 | AGPL-3.0 | CC-BY-4.0 |
|---|---|---|---|---|---|
| permissive | reuse | reuse | reuse | reuse | reuse-file |
| Apache-2.0 | reuse-file | reuse | reuse | reuse | reuse-file |
| MPL-2.0 | reuse-file | reuse-file | reuse-file | reuse-file | reuse-file |
| LGPL | borrow | borrow | reuse-file | reuse-file | borrow |
| GPL-2.0-only | borrow | borrow | borrow | borrow | borrow |
| GPL-2.0-or-later | borrow | borrow | reuse | reuse | borrow |
| GPL-3.0 | borrow | borrow | reuse | reuse | borrow |
| AGPL-3.0 | borrow | borrow | reuse-file | reuse | borrow |
| CC-BY-4.0 | reuse-file | reuse-file | reuse-file | reuse-file | reuse |
| CC-BY-SA-4.0 | reuse-file | reuse-file | reuse-file | reuse-file | reuse-file |
| CC-NC-ND | cite | cite | cite | cite | cite |
| source-available | cite | cite | cite | cite | cite |
| none | cite | cite | cite | cite | cite |
| unknown | cite | cite | cite | cite | cite |
<!-- matrix:end -->

For an SPDX expression: `A OR B` takes the better verdict of the two (you choose the licence),
`A AND B` the worse (you get both). `WITH <exception>` is ignored, which only ever makes a
verdict stricter. When the destination file is itself under an expression, the verdict is the
worst over its parts.

## Why each row is what it is

Checked on 2026-10-12, for the picasso original of this file, against the FSF's [licence list](https://www.gnu.org/licenses/license-list.en.html)
and Creative Commons' [compatible licences](https://creativecommons.org/share-your-work/licensing-considerations/compatible-licenses/)
page, both fetched while that file was written. The rows were not re-checked for this port; the
quoted phrases are theirs.

- **Permissive into anything** is `reuse`: the FSF calls Expat (MIT) and Modified BSD "compatible
  with the GNU GPL". Into CC BY docs it is `reuse-file`: the code keeps its own notice, and CC
  licences are not for software (FSF).
- **Apache-2.0** is "compatible with version 3 of the GNU GPL" and "not compatible with GPL
  version 2" (FSF), so it is `borrow` into a GPL-2.0-only file, which has no column here. Into an
  MIT project the file may come in but keeps Apache's terms: section 4 asks for the licence, a
  change notice and the NOTICE file to travel with it. That is `reuse-file`.
- **MPL-2.0** is file-level copyleft: the copied file stays MPL, the rest of the project can be
  anything. `reuse-file` everywhere. If the file is marked "Incompatible With Secondary Licenses",
  it may not be combined into a GPL file: a human checks.
- **LGPL** is compatible with GPLv3 (FSF), so it comes into a GPL or AGPL project as its own file.
  Into a permissive project, copying its source turns that part copyleft; `borrow` the idea, or
  depend on the library instead of vendoring it.
- **GPL-2.0-only** "is, by itself, not compatible with GPLv3" (FSF). It never comes into anything
  in this table. A bare GPL-2.0 licence text with no "or any later version" header counts as
  `-only`.
- **GPL-2.0-or-later and GPL-3.0** come into GPL-3.0 and AGPL-3.0 files: AGPL-3.0 section 13 and
  GPL-3.0 section 13 allow combining them. Into permissive or Apache projects they would relicense
  the project: `borrow`.
- **AGPL-3.0 into GPL-3.0** is "technically not compatible ... in a strict sense", but "you are
  allowed to combine separate modules or source files released under both" (FSF): `reuse-file`,
  and the AGPL file keeps its network clause.
- **CC BY 4.0** is "compatible with all versions of the GNU GPL" (FSF). It comes into docs as
  `reuse`, and into a code project as its own file (an image, a doc page) with its credit.
- **CC BY-SA 4.0** is one-way compatible with GPLv3 (CC: "you may license your contributions to
  adaptations of BY-SA 4.0 materials under GPLv3"). It never becomes CC BY: in CC BY docs it stays
  a separate BY-SA file. `reuse-file` everywhere.
- **NC and ND** are not free (FSF: NC restricts "charging money for copies", ND restricts
  "distributing modified versions"). Every destination here allows both, so `cite`.
- **Source-available** licences (BUSL, SSPL, Elastic, Commons Clause, PolyForm) restrict use or
  field. `cite`; a human reads the specific terms if more is wanted.
- **No licence** means all rights reserved, even on a public GitHub repo (GitHub's terms let
  people view and fork on GitHub, not reuse). `cite`, and `borrow` only for an idea general enough
  that you would have written it anyway. Never copy text or code.
- **Unknown** (the scanner did not recognise the text, GitHub says `NOASSERTION` or `Other`):
  `cite` until a human has read the licence.

## Which licence an item has

The most specific source wins, in this order:

1. a REUSE.toml annotation with `precedence = "override"`;
2. the file's own SPDX tag in a header comment;
3. a REUSE.toml annotation (the last matching one), or `.reuse/dep5`;
4. the nearest `LICENSE` / `COPYING` file in the item's directory or a parent directory;
5. the repository's root licence (what GitHub shows);
6. none.

A `package.json` or `pyproject.toml` licence field that differs from the `LICENSE` file is a
conflict, not a tie-breaker: report it (`manifest-mismatch`) and use the stricter one.

A fork's licence is the upstream's at the commit the fork was taken. A file in `vendor/`,
`third_party/` or `.claude/skills/<name>/` of the upstream is often someone else's: follow its
own credit line to the original and take it from there, under the original's terms.

## Not covered by any licence

- **Trademarks, names and logos.** A licence on code does not grant the right to use a project's
  name or logo as your brand. Link to them; do not copy them into your repository.
- **Patents.** Apache-2.0 and GPL-3.0 carry a patent grant; MIT and BSD do not. Note it in the
  report when the item implements a patented method.
- **Model weights and datasets** carry their own licence (often a custom or RAIL-style one),
  separate from the code that loads them. Scan them as their own item.
- **Content a licence file does not reach**: screenshots, fonts, third-party images inside a docs
  folder. Each needs its own source.

- **The holder's own grant.** The matrix says what a licence allows a stranger. The copyright
  holder may allow more, for example the same person owning both repos. Use such a grant only
  when it is written (in the issue or the PR), the holder is the only author (`git log` on the
  files), and the PR records it. Otherwise the matrix decides.

## Recording what came in

For every `reuse` or `reuse-file` item, the destination records, in its own convention:

- upstream URL **at a commit** (40-char SHA, never a branch name), the path in that commit;
- the licence's SPDX id and the copyright holders, as the upstream states them;
- what was changed;
- the licence text in `LICENSES/` (REUSE) or beside the file, and the upstream NOTICE if any.

In marola that is: the upstream `LICENSE` beside the copy; a `REUSE.toml` annotation for the copied
path, with the licensor's own copyright notice, the upstream SPDX id and the `precedence` its
neighbouring blocks use (`override` for a skill directory, so nothing inside is read as a tag);
a `skills.lock` row for a skill copied byte for byte (`skills-vendor init`, MIP-0080), which holds
the upstream, the path, the 40-character commit and a blob sha per file; and the credit line and
`AGENT-SKILLS.md` row that `porting.md` asks for.

For `borrow`: a credit line in the file or the PR, naming the upstream at a commit and its
licence. For `cite`: the link, or the DOI and a BibTeX/CFF entry when the upstream has a
`CITATION.cff` or a Zenodo record.
