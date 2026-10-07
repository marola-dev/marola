---
name: zenodo-release
description: "Cut a citable marola release and keep its Zenodo and citation metadata right. Use when asked to release or tag a version, mint or update a DOI, add an author or contributor, change .zenodo.json, CITATION.cff or the README's DOI badge and \"How to cite\". Use it before the vendored citation-cff skill, which it limits to exporting and checking."
---

# zenodo-release

Adapted from h0ffmann/ww3-gpu's `release` skill, the setup behind its record
10.5281/zenodo.23221352. The design is MIP-0079.

**Audience.** Researchers cite this. Write the metadata so an ocean, coastal or water-quality
researcher finds it: the title, description and keywords they would search for, each author's
ORCID, and related work by DOI.

## The chain

Every published GitHub release of this repo is archived by Zenodo, which mints a version DOI
under one concept DOI. There are no secrets in it:

1. `just release X.Y.Z` (`scripts/release.sh`) tags `main` as `vX.Y.Z` and pushes. It refuses
   unless `main` is clean and level with `origin/main`, the tag is new and
   `scripts/citation.py --check` passes, and the pointer guard below. `--dry-run` runs every check
   and tags nothing; it is the rehearsal (there are no `-rc` tags: each archived release is a
   permanent DOI). `just releases` lists past tags.
2. `.github/workflows/release.yml` reruns the citation check and the guard, then turns the tag into
   a published release with generated notes.
3. Zenodo's webhook (switched on once by the owner at zenodo.org → GitHub) archives the release
   with `.zenodo.json` from the tagged commit and mints the DOI a few minutes later.

**Hard rule: a submodule pointer move never makes a Zenodo version.** The umbrella's daily
`chore/pointer-sync` PR moves gitlinks; if that were all that changed since the previous `v*` tag,
`scripts/release.sh --guard` refuses, in both step 1 and step 2, so a tag pushed by hand is caught
too. Never suggest a release whose only change is pointers, and never weaken the guard.

An agent does not tag or release: that is the maintainer's act. Prepare the metadata PR, then
give him the `just release` command.

## Metadata rules

- **`.zenodo.json` is the only file edited by hand.** `CITATION.cff` and the BibTeX, APA and ABNT
  references between the READMEs' `<!-- citation:start/end -->` markers are generated from it by
  `just citation`; `--check` (in `just quality`, `citation.yml` and `release.yml`) fails on drift.
  Never edit them directly, and never let another skill write them.
- Zenodo sets `version` (from the tag), `publication_date` and `doi`; the check refuses them in
  `.zenodo.json`.
- People: `just contributor-add "Family, Given" [--orcid ID] [--affiliation TEXT] [--type T]`
  adds a Zenodo contributor with a role (`ProjectMember`, `Researcher`, `Supervisor`, …);
  `--author` makes them a cited creator, which also puts them in the CFF. Supervisors are
  contributors, never creators. The maintainer cites as "Santos, Matheus Hoffmann Fernandes".
- ORCID: bare in `.zenodo.json` (`0000-0000-0000-0000`); the generator writes the full URL in the
  CFF. The check verifies the checksum.
- Related works use Zenodo's relation vocabulary (the check lists it): the six repos are
  `hasPart`, the WW3 GPU Lab `references`. A repo that gets its own concept DOI moves from its URL
  to that DOI.
- Concept DOI (once it exists): `CONCEPT_DOI` in `scripts/citation.py` (then `just citation`
  adds it to the CFF and the references) and the README badge. The badge and the concept DOI,
  never a version DOI: `[![DOI](https://zenodo.org/badge/DOI/<concept>.svg)](https://doi.org/<concept>)`.
- An edit reaches Zenodo only with the next release. A DOI cannot be deleted: confirm the version
  number with the maintainer before he tags. SemVer: PATCH for fixes and docs, MINOR for new
  MIPs, tools or repos, MAJOR only when he says so.

## The vendored `citation-cff` skill

`.claude/skills/citation-cff/` is zircote/github-social's skill (MIT, unchanged). Here it is
for read-only work: exporting RIS or CodeMeta on request, and its validation and
Zenodo-reconciliation checks. BibTeX, APA and ABNT for the READMEs come from `just citation`,
which follows its export formats. Its `--init`, `--apply`, sync and workflow modes write
`CITATION.cff` or a workflow; here they are replaced by `just citation` and `citation.yml`.
