# MIP-0090: AGPL-3.0 for code and CC BY 4.0 for docs, as ouroboric-dev does

| | |
|---|---|
| **Status** | Draft |
| **Author** | Hoffmann |
| **Created** | 2026-10-12 |
| **Phase** | None: licensing, outside the phase list. It provisions nothing paid; Phase 1 is untouched |
| **Related** | h0ffmann/picasso#105 (the same change in picasso, merged 2026-10-12), MIP-0079 (a Zenodo DOI per release), MIP-0014 §5.6 (the book's licence), CONTRIBUTING "License" |
| **Effort** | M — one licence file and a few metadata lines per repo, but it needs written consent from four outside contributors before any repo moves |
| **Gain** | `community/outreach` — the code stays open when someone runs it as a service; the docs get a licence made for prose; marola matches ouroboric-dev and picasso |
| **Effort vs Gain** | `do when X lands` — when every contributor in §5.4 has agreed in writing; without that, a repo cannot move |
| **Depends on** | The written agreement of every copyright holder of a repo before that repo moves (§5.4) |
| **Blocked by** | none |
| **Risk** | One contributor does not agree, and their code must be rewritten or the repo stays MIT |

### Readiness

| | |
|---|---|
| **Manually reviewed** | no |
| **Written by** | Hoffmann, with Claude Code |
| **Tasks** | none yet — written once the per-repo choices in §5.2 and §11 are decided |
| **Tests** | none — a licence change has no code path; §7 lists the checks |
| **Spec-kit** | none |
| **Issues** | not filed — Draft |

## 1. Summary

Every marola-dev repository is MIT today. This MIP proposes the licence split that ouroboric-dev
uses and that picasso adopted in h0ffmann/picasso#105: **AGPL-3.0-only for code**, **CC BY 4.0 for
documentation and knowledge**, and **MIT kept for the code that other repositories copy**. The
names and logos stay outside every licence. Nothing changes until each repository's copyright
holders agree in writing. Releases that are already out keep MIT.

## 2. Motivation

- **marola runs as a service.** marola-app ships an image with a chat server and an MCP server, and
  marola.dev serves the map. Under MIT, anyone can run a modified marola as a hosted service and
  keep the changes. AGPL-3.0 §13 makes them offer the source to the users of that service.
- **The docs are under a software licence.** marola-corpus's sourced knowledge, the MIPs and
  docs.marola.dev are prose. MIT was written for software. CC BY 4.0 is made for prose and data, and
  it asks for attribution in a form readers know.
- **One scheme across the projects.** The ouroboric-dev repos (checked 2026-10-12) use this split:
  ouroboric is CC BY 4.0 with AGPL-3.0-only scripts, yggdrasil and asgard are AGPL-3.0 with an MIT
  part, and `.github` is MIT. picasso moved to it on 2026-10-12. Bruno Valério is a main contributor
  to both marola and ouroboric-dev, so one scheme means one set of rules for the same people.

## 3. User-visible change

Before, every repo's README ends with:

> Every repo carries an MIT `LICENSE`. By contributing you agree your changes are licensed under it.

After, CONTRIBUTING's "License" section:

> Code is AGPL-3.0-only (`LICENSE`). Documentation and the corpus are CC BY 4.0
> (`LICENSES/CC-BY-4.0.txt`). marola-devkit and the files that other repositories copy are MIT. The
> names and logos are not licensed. By contributing you agree that your changes are licensed under
> the licence of the files that you change. Releases before `<the first new tag>` stay MIT.

A visitor to marola.dev sees a footer link to the source and the licence, which AGPL-3.0 §13 asks for.

## 4. Data sources and dependencies reviewed

### 4.1 The current licences (checked 2026-10-12, at each default branch's head)

| Repo | `LICENSE` | Zenodo | Contributors other than Hoffmann |
|---|---|---|---|
| marola | MIT | concept DOI 10.5281/zenodo.23224155, `.zenodo.json` says `mit`; tags v0.2.0–v0.2.2 | Bruno Valério, William Macedo, Pablo Ribeiro, Rôb Klér Soares da Silva Júnior |
| marola-site | MIT | none | Bruno Valério, William Macedo, Pablo Ribeiro, Rôb Klér Soares da Silva Júnior |
| marola-app | MIT | none | Bruno Valério, William Macedo, Rôb Klér Soares da Silva Júnior |
| marola-devkit | MIT | none | Bruno Valério, William Macedo |
| marola-corpus | MIT | none | Bruno Valério |
| marola-ml | MIT | none | Bruno Valério |
| marola-oods | MIT | none | Bruno Valério |
| `.github` | none at the root | none | Bruno Valério |
| marola-skills | MIT | none | none (Claude commits are made for Hoffmann) |
| marola-agents | MIT | none | none |
| marola-research | MIT | none | none |

Contributors come from `git log --format=%an` on each default branch. Commits by `hoffmann`,
`mhoffmann` and `M.Hoffmann` are taken as one person; dependabot is not a copyright holder.

### 4.2 The ouroboric-dev scheme (checked 2026-10-12)

- `ouroboric`: `LICENSE` is CC BY 4.0; `REUSE.toml` gives `scripts/**` AGPL-3.0-only and the logos
  `LicenseRef-Ouroboric-Trademark`; `TRADEMARKS.md` keeps the names and logos out of every licence.
- `yggdrasil`: `LICENSE` is AGPL-3.0; `LICENSES/` has AGPL-3.0-only and CC-BY-4.0.
- `asgard`: `LICENSE` is AGPL-3.0; `LICENSES/` adds MIT.
- The umbrella's README: "The products use AGPL-3.0 for factory code and MIT for anything copied into
  what operators build."

### 4.3 What the licences permit

- AGPL-3.0 is GPL-3.0 with §13: a user who reaches a modified program over a network can get its
  source. MIT code can be used in an AGPL project; the reverse is not true.
- CC BY 4.0 permits any use with attribution. It is not a software licence, and the FSF and the
  Creative Commons FAQ both advise against it for code.
- Third-party content keeps its own licence: OpenStreetMap data is ODbL (share-alike), Open-Meteo
  is CC BY 4.0, the marola-site soundtrack is CC BY 3.0, and the marola-sea fine-tune carries its
  base model's licence (Apache-2.0 or Llama 3.2, `marola-ml/finetune/train_lora.py`). This MIP does
  not change any of them.

**Pick:** the ouroboric-dev split, with MIT kept where §5.2 says.

## 5. Design

### 5.1 Per file type

| What | Licence |
|---|---|
| Code: Scala, Python, JavaScript, shell, Nix, workflows | AGPL-3.0-only |
| Docs: READMEs, `docs/`, MIPs, docs.marola.dev | CC BY 4.0 |
| marola-corpus `knowledge/`, marola-oods data made by marola | CC BY 4.0 |
| Code that other repos copy or include (below) | MIT |
| The marola name and logos | not licensed, a new `TRADEMARKS.md` in the umbrella |
| Third-party content | unchanged, its own licence |

### 5.2 Per repo

| Repo | `LICENSE` | Also |
|---|---|---|
| marola | CC BY 4.0, since it holds no code (AGENTS.md) | `LICENSES/AGPL-3.0-only.txt` for `scripts/`; `REUSE.toml`; `TRADEMARKS.md` |
| marola-app | AGPL-3.0 | `LICENSES/CC-BY-4.0.txt` for `docs/` |
| marola-site | AGPL-3.0 | CC BY 4.0 for the page copy; the footer links the source |
| marola-ml | AGPL-3.0 | the published model keeps its base model's licence |
| marola-corpus | CC BY 4.0 | AGPL-3.0 for its scripts |
| marola-oods | CC BY 4.0, or ODbL where the data derives from OSM (§11) | — |
| marola-devkit | **stays MIT** | every repo copies its invariants block and runs its hooks, and repos outside marola (ww3-gpu) use it |
| marola-skills, marola-agents, marola-research | CC BY 4.0 | AGPL-3.0 for `scripts/` |
| `.github` | MIT, as ouroboric-dev's `.github` | its files are copied into every repo |

### 5.3 Files touched in each repo

`LICENSE`, `LICENSES/`, `REUSE.toml`, the README licence section and badge, `.zenodo.json` and
`CITATION.cff` where they exist (`just citation`), `build.sbt`'s `licenses`, `pyproject.toml`'s
`license`, `package.json`'s `license`, and CONTRIBUTING's "License" section in the umbrella. A
`reuse lint` step goes into the devkit's reusable CI workflow, so a file with no licence fails.

### 5.4 Consent

A change from MIT to AGPL-3.0 needs the agreement of each copyright holder of the code that moves.
MIT lets anyone sublicense, so Hoffmann can *add* AGPL terms to a copy, but the contributors' own
lines stay available under MIT from them. A clean relicence needs each contributor in §4.1 to agree
in writing:

1. Open one umbrella issue that lists each repo, each contributor, and the proposed licence.
2. Each contributor answers in a comment: "I agree to relicense my contributions to <repos> under
   the licences of MIP-0090." The comment is the record.
3. A repo moves only when every contributor of that repo has agreed. A contributor who does not
   agree: their files stay MIT under `LICENSES/MIT.txt` and `REUSE.toml`, or the files are rewritten.

marola-skills, marola-agents and marola-research have no outside contributors and can move first.

### 5.5 Zenodo

marola's v0.2.0–v0.2.2 are published under MIT with the concept DOI 10.5281/zenodo.23224155. A
Zenodo version cannot be changed or withdrawn, and the grant for those versions stays. The next
umbrella release after the change carries the new licence in `.zenodo.json`; Zenodo's concept DOI
then resolves to a version under the new terms. MIP-0079's rule that a pointer move never makes a
release stands: the licence change itself is a change outside the gitlinks, so it can make one.

### 5.6 Consumers

- marola-app's image, `ml-resources` tarball and Scaladoc: AGPL-3.0 from the next tag. A third party
  that runs a modified image as a service must offer its source.
- marola-corpus's tarball: CC BY 4.0 from the next tag; app and ml already credit the corpus.
- ww3-gpu and any repo outside marola that uses marola-devkit: no change, the devkit stays MIT.
- picasso's lint lab is consumed by marola as a flake input; picasso is AGPL-3.0 from its next
  release, and a flake input is not a copy, so marola is not affected.

### 5.7 What is deterministic

All of it. No step goes through an LLM.

## 6. Scoring / safety impact

None.

## 7. Verification plan

- `reuse lint` passes in every moved repo (new CI step through the devkit).
- GitHub's licence detection shows the intended SPDX id on each repo's main page.
- `just citation --check` passes in the umbrella after `.zenodo.json` changes.
- The consent issue has one agreeing comment per contributor per repo before that repo's PR merges.
- Done: every row of §5.2 is in place, the next release of each repo states its licence, and
  CONTRIBUTING says the new terms.

## 8. Risks, limitations, and honest caveats

- **This is not legal advice.** Nobody with legal training checked it. The consent wording in §5.4
  should be read by someone who knows Brazilian copyright law (Lei 9.610/98) before it is used.
- **AGPL scares some adopters.** A public body or a company may refuse AGPL code
  (MIP-0082, GovTech, is in this tension). MIT for the devkit and `.github` limits the effect on
  repos that only copy the harness.
- **CC BY 4.0 on the umbrella** means its few scripts need the AGPL annotation, or they fall under a
  licence that is not for code.
- **Contributions by AI agents.** Many commits are Claude's, made for a person. This MIP takes them as
  the work of the person who directed them; that is a common reading, not a settled one.
- **A partial move is worse than none.** Repos under different licences, with a few MIT files left
  inside AGPL repos, are harder to understand. §5.4 makes each repo move whole or not at all.

## 9. Alternatives considered

- **Do nothing.** MIT is simple and adopters like it. It loses the service clause and keeps prose
  under a software licence. This is the default if consent fails.
- **AGPL-3.0 for everything, docs included.** One licence, but a bad fit for prose and corpus data.
- **GPL-3.0 instead of AGPL-3.0.** It does not close the hosted-service gap, which is the main reason
  for the change.
- **Apache-2.0.** It adds a patent grant but no copyleft; it does not match ouroboric-dev.
- **CC BY-SA 4.0 for docs.** Share-alike would match OSM's ODbL spirit, but ouroboric-dev uses
  CC BY 4.0 and MIP-0014's book has its own decision.

## 11. Open questions

- Should marola-devkit stay MIT? **Default:** yes, because other repos copy it; Hoffmann and Bruno
  Valério decide.
- Should marola-oods data be CC BY 4.0 or ODbL? **Default:** ODbL for data derived from
  OpenStreetMap, CC BY 4.0 for the rest, decided when MIP-0056's ingest lands.
- Does marola need a contributor licence agreement or a DCO from now on? **Default:** no; the
  "licence of the files that you change" sentence in CONTRIBUTING is inbound=outbound, as today.
- Does the umbrella's `LICENSE` say CC BY 4.0 (as ouroboric's) or AGPL-3.0 (as the code repos)?
  **Default:** CC BY 4.0, since the umbrella holds no code.

## Appendix

### Checked live

- github.com/ouroboric-dev/ouroboric, 2026-10-12: `LICENSE` (CC BY 4.0), `REUSE.toml`, `LICENSES/`,
  README "Licence".
- github.com/ouroboric-dev/yggdrasil and asgard, 2026-10-12: `LICENSE` (AGPL-3.0), `LICENSES/`.
- github.com/ouroboric-dev/.github, 2026-10-12: `LICENSE` (MIT).
- Each marola-dev repo in §4.1, 2026-10-12: `LICENSE`, `.zenodo.json`, `CITATION.cff`, `git log`
  authors on the default branch.
- h0ffmann/picasso#105, merged 2026-10-12.

### Not checked

- The org's member list and any contributor who is not in a default branch's `git log` (open PRs
  from forks, such as #796).
- The legal reading of §5.4 under Brazilian law.
- Whether Zenodo's concept DOI page shows each version's own licence.
