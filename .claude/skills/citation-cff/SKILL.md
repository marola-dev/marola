---
name: citation-cff
description: This skill should be used when the user asks to "create citation file", "generate CITATION.cff", "validate citation", "update citation", "export citation as bibtex", "export citation as apa", "add DOI to citation", "enrich citation metadata", "sync citation version", "create cff file", "citation workflow", or needs to create, validate, update, or export CITATION.cff files for proper academic citation of software projects.
---

# CITATION.cff Manager

Create, validate, update, enrich, and export CITATION.cff files for proper academic citation of software projects. Generates valid CFF 1.2.0 files from project metadata, syncs version and author information, converts to BibTeX/APA/RIS formats, and optionally generates GitHub Actions validation workflows.

## Overview

This skill manages the full lifecycle of CITATION.cff files:
1. **Create** - Generate a valid CITATION.cff from project metadata
2. **Validate** - Check an existing file against the CFF 1.2.0 schema
3. **Update / Sync** - Keep version, authors, and metadata in sync with the project
4. **Convert / Export** - Output citations in BibTeX, APA, RIS, CodeMeta, or EndNote format
5. **Enrich** - Add ORCIDs, DOIs, preferred-citation, and identifiers
6. **Workflow** - Generate a GitHub Actions workflow for CI validation

## Step 0: Check for Configuration

Look for optional settings file at `.claude/github-social.local.md`.

If file exists, check for citation preferences:
- `cff_auto_version`: `true` | `false` - Automatically sync version from package manifest (default: `true`)
- `cff_doi`: DOI string to embed (e.g., `10.5281/zenodo.1234567`)
- `cff_license`: SPDX license identifier override (e.g., `Apache-2.0`)
- `cff_identifiers`: Additional identifier entries (DOI, URL, SWHL)
- `cff_auto_authors`: `true` | `false` - Sync authors from git contributors (default: `false`)

If no config exists, proceed with defaults.

## Step 1: Detect Project Ecosystem

Before any operation, determine the project's language ecosystem and canonical metadata sources. Check for these files in order:

| File | Ecosystem | Extracts |
|------|-----------|----------|
| `package.json` | Node.js | name, version, description, license, author, contributors, keywords, repository |
| `Cargo.toml` | Rust | name, version, description, license, authors, keywords, repository |
| `pyproject.toml` | Python | name, version, description, license, authors, keywords, urls |
| `setup.py` / `setup.cfg` | Python (legacy) | name, version, description, license, author |
| `go.mod` | Go | module path |
| `pom.xml` | Java/Maven | groupId, artifactId, version, name, description, licenses, developers |
| `build.gradle` / `build.gradle.kts` | Java/Kotlin/Gradle | version, group, description |
| `*.gemspec` | Ruby | name, version, summary, license, authors |
| `mix.exs` | Elixir | app, version, description |
| `DESCRIPTION` | R | Package, Version, Title, Author, License |
| `composer.json` | PHP | name, version, description, license, authors |

Also check:
- `README.md` - Project title, description, badges (DOI, license)
- `CLAUDE.md` - Project context and guidelines
- `LICENSE` or `LICENSE.md` - License type detection
- `.zenodo.json` - DOI, creators, keywords, license

Record which ecosystem was detected for version-sync operations later.

## Step 2: Create (`--init`)

Generate a new CITATION.cff file from discovered project metadata.

### Required CFF Fields

Every generated file MUST include these fields to comply with CFF 1.2.0:

```yaml
cff-version: 1.2.0
message: "If you use this software, please cite it as below."
title: "<project name>"
type: software
authors:
  - family-names: "<last name>"
    given-names: "<first name>"
date-released: "YYYY-MM-DD"
version: "<current version>"
```

### Author Discovery

Gather authors from multiple sources, deduplicated by name:

1. **Package manifest** (`author`, `contributors`, `authors` fields)
2. **Git history** (if `cff_auto_authors: true` or user confirms):
   ```bash
   git log --format='%aN <%aE>' | sort -u
   ```
3. **`.zenodo.json`** (`creators` array with `name`, `orcid`, `affiliation`)

For each author, structure as:
```yaml
authors:
  - family-names: "Doe"
    given-names: "Jane"
    email: "jane@example.com"        # optional
    orcid: "https://orcid.org/0000-0001-2345-6789"  # optional
    affiliation: "University of X"   # optional
```

Split full names into `family-names` and `given-names`. If a name cannot be reliably split (e.g., mononym, organizational name), use `name` field instead:
```yaml
  - name: "The OpenSSL Project"
```

### Optional Fields

Include these when data is available:

```yaml
doi: "10.5281/zenodo.1234567"          # from config, .zenodo.json, or README badge
url: "https://github.com/owner/repo"    # from git remote
repository-code: "https://github.com/owner/repo"  # from git remote
license: "MIT"                           # SPDX identifier from LICENSE file or manifest
keywords:                                # from manifest keywords or repo topics
  - "keyword-one"
  - "keyword-two"
abstract: "One paragraph description."   # from README first paragraph or manifest description
identifiers:                             # from config or .zenodo.json
  - type: doi
    value: "10.5281/zenodo.1234567"
preferred-citation:                      # only if published paper exists
  type: article
  authors:
    - family-names: "Doe"
      given-names: "Jane"
  title: "Paper Title"
  journal: "Journal Name"
  year: 2024
  doi: "10.1234/example"
references: []                           # populated during enrich if applicable
```

### Remote Metadata

Extract repository URL from git remote:
```bash
git remote get-url origin
```

Convert SSH URLs to HTTPS format for the `url` and `repository-code` fields:
- `git@github.com:owner/repo.git` -> `https://github.com/owner/repo`

Fetch repo topics for keywords:
```bash
gh repo view --json repositoryTopics --jq '.repositoryTopics[].name'
```

### Output

Generate valid YAML. The file MUST pass `cffconvert --validate` if the tool is available.

**`--dry-run` mode (default):** Display the generated CITATION.cff content and ask user to confirm before writing.

**`--apply` mode:** Write directly to `CITATION.cff` in the repository root.

## Step 3: Validate

Validate an existing CITATION.cff against the CFF 1.2.0 schema.

### Validation Checks

Perform these checks in order, collecting all errors before reporting:

**Structure:**
- File is valid YAML (no syntax errors)
- Top-level keys are recognized CFF 1.2.0 fields (warn on unknown keys)

**Required fields:**
- `cff-version` is present and equals `1.2.0`
- `message` is present and non-empty
- `title` is present and non-empty
- `type` is present and is `software` or `dataset`
- `authors` is present and contains at least one entry
- Each author has `family-names` + `given-names` OR `name`
- `date-released` is present

**Format validation:**
- `date-released` matches `YYYY-MM-DD` format and is a valid date
- `version` is a string (not a bare number — quote if needed)
- `doi` matches pattern `10.\d{4,}/\S+`
- `orcid` URLs match `https://orcid.org/\d{4}-\d{4}-\d{4}-\d{3}[\dX]`
- `license` is a valid SPDX license identifier
- `url` and `repository-code` are valid URLs

**Cross-reference:**
- If `doi` is in both top-level and `identifiers`, values should match
- If `license` differs from LICENSE file, warn

### External Validation

If `cffconvert` is available locally, also run:
```bash
cffconvert --validate -i CITATION.cff
```

Check availability first:
```bash
command -v cffconvert >/dev/null 2>&1
```

### Report Format

```markdown
## CITATION.cff Validation Report

### Errors (must fix)
- Line 5: `date-released` format invalid — got "2024-1-5", expected "2024-01-05"
- Line 8: Author entry missing required `family-names` field

### Warnings (should fix)
- Line 3: `license` value "MIT License" is not a valid SPDX identifier — use "MIT"
- `version` in CITATION.cff ("1.2.0") differs from package.json ("1.3.0")

### Info
- 2 authors listed
- DOI: not set
- ORCID: 0 of 2 authors have ORCID iDs

### Result: FAIL (2 errors, 2 warnings)
```

## Step 4: Update / Sync (`--sync`)

Synchronize CITATION.cff fields with canonical project sources.

### Version Sync

Detect the canonical version source (in priority order):
1. Git tag: `git describe --tags --abbrev=0 2>/dev/null`
2. `package.json` → `.version`
3. `Cargo.toml` → `[package].version`
4. `pyproject.toml` → `[project].version` or `[tool.poetry].version`
5. `setup.cfg` → `[metadata].version`
6. `pom.xml` → `<version>`

Strip leading `v` from git tags (e.g., `v1.2.3` -> `1.2.3`).

Update `version` and set `date-released` to today's date if version changed.

### Author Sync

Only when `cff_auto_authors: true` in config:

```bash
git log --format='%aN <%aE>' | sort -u
```

Merge with existing authors, preserving manually-added ORCID and affiliation data. New authors are appended, existing authors are not removed.

### Keywords Sync

Fetch current repo topics:
```bash
gh repo view --json repositoryTopics --jq '.repositoryTopics[].name'
```

Merge with existing keywords (union, no duplicates).

### URL Sync

Update `url` and `repository-code` from current git remote if they differ.

### Diff Display

Before writing changes, display a diff of old vs new values:

```markdown
## CITATION.cff Sync Changes

| Field | Current | Updated |
|-------|---------|---------|
| version | 1.2.0 | 1.3.0 |
| date-released | 2024-01-15 | 2024-06-20 |
| keywords | +data-science, +python | (2 added) |
| authors | | +New Contributor |

Apply changes? [--dry-run: showing only | --apply: writing file]
```

## Step 5: Convert / Export (`--export <format>`)

Generate citation text from CITATION.cff in the requested format.

### Supported Formats

#### BibTeX (`--export bibtex`)

```bibtex
@software{doe_2024_projectname,
  author       = {Doe, Jane and Smith, John},
  title        = {Project Name},
  version      = {1.2.0},
  date         = {2024-01-15},
  url          = {https://github.com/owner/repo},
  doi          = {10.5281/zenodo.1234567},
  license      = {MIT}
}
```

Field mapping:
- `authors` -> `author` (comma-separated, "Family, Given" format)
- `title` -> `title`
- `version` -> `version`
- `date-released` -> `date`
- `doi` -> `doi`
- `url` -> `url`
- `license` -> `license`
- BibTeX key: `{first_author_family}_{year}_{title_slug}`

#### APA (`--export apa`)

```
Doe, J., & Smith, J. (2024). Project Name (Version 1.2.0) [Computer software]. https://doi.org/10.5281/zenodo.1234567
```

Format rules:
- Authors: "Family, Initial." separated by ", " with "& " before last author
- Year in parentheses
- Title in italics (when rendered)
- Version in parentheses after title
- "[Computer software]" type indicator
- DOI URL or repository URL at end

#### RIS (`--export ris`)

```
TY  - COMP
AU  - Doe, Jane
AU  - Smith, John
TI  - Project Name
PY  - 2024
DA  - 2024/01/15
UR  - https://github.com/owner/repo
DO  - 10.5281/zenodo.1234567
ET  - 1.2.0
ER  -
```

#### CodeMeta (`--export codemeta`)

Generate a `codemeta.json` following the CodeMeta 2.0 schema:

```json
{
  "@context": "https://doi.org/10.5063/schema/codemeta-2.0",
  "@type": "SoftwareSourceCode",
  "name": "Project Name",
  "version": "1.2.0",
  "datePublished": "2024-01-15",
  "author": [
    {
      "@type": "Person",
      "familyName": "Doe",
      "givenName": "Jane"
    }
  ],
  "codeRepository": "https://github.com/owner/repo",
  "license": "https://spdx.org/licenses/MIT"
}
```

#### EndNote (`--export endnote`)

```
%0 Computer Program
%A Doe, Jane
%A Smith, John
%T Project Name
%D 2024
%7 1.2.0
%U https://github.com/owner/repo
%R 10.5281/zenodo.1234567
```

### Output

**Default (stdout):** Display the formatted citation text.

**`--output <file>`:** Write to the specified file path.

## Step 6: Enrich (`--enrich`)

Enhance an existing CITATION.cff with additional metadata.

### ORCID Discovery

For each author without an `orcid` field:
1. Search the ORCID public API (if available) or prompt user
2. Validate ORCID format: `https://orcid.org/XXXX-XXXX-XXXX-XXXX`
3. Add to author entry if confirmed

### DOI Discovery

Check for existing DOI in these locations:
1. `cff_doi` in `.claude/github-social.local.md`
2. `.zenodo.json` → `doi` field
3. README.md badges: scan for `doi.org/10.` patterns
4. `CITATION.cff` itself (already set)

If found, ensure `doi` is set at top level AND in `identifiers` array.

### Preferred Citation

If a published paper exists for the software:
1. Check README for "cite this paper" or "publication" sections
2. Prompt user: "Is there a published paper associated with this software?"
3. If yes, construct a `preferred-citation` block:

```yaml
preferred-citation:
  type: article
  authors:
    - family-names: "Doe"
      given-names: "Jane"
  title: "Paper Title"
  journal: "Journal Name"
  year: 2024
  doi: "10.1234/example"
```

### Identifiers

Build the `identifiers` array from all discovered identifiers:

```yaml
identifiers:
  - type: doi
    value: "10.5281/zenodo.1234567"
    description: "Zenodo archive DOI"
  - type: url
    value: "https://github.com/owner/repo"
    description: "GitHub repository"
```

### Zenodo Reconciliation

If `.zenodo.json` exists, cross-reference fields:
- Compare `creators` with `authors` (warn on mismatches)
- Compare `keywords` with `keywords`
- Compare `license` with `license`
- Suggest reconciliation for any discrepancies

### Output

Display all proposed enrichments as a diff, then apply with `--apply` or show only with `--dry-run`.

## Step 7: GitHub Actions Workflow (`--workflow`)

Generate a GitHub Actions workflow for automated CFF validation.

### Workflow Generation

Before writing, verify the latest version of the validator action:
```bash
gh api repos/dieghernan/cff-validator/releases/latest --jq '.tag_name'
```

Generate the workflow file at `.github/workflows/cff-validate.yml`:

```yaml
name: Validate CITATION.cff
on:
  push:
    branches: [main]
    paths: ['CITATION.cff']
  pull_request:
    paths: ['CITATION.cff']

jobs:
  validate:
    runs-on: ubuntu-latest
    name: Validate CITATION.cff
    steps:
      - name: Checkout
        uses: actions/checkout@v4

      - name: Validate CITATION.cff
        uses: dieghernan/cff-validator@<latest-version>

      - name: Convert to BibTeX
        if: success()
        uses: dieghernan/cff-validator@<latest-version>
        with:
          output-format: bibtex
          output-file: CITATION.bib

      - name: Upload BibTeX
        if: success()
        uses: actions/upload-artifact@v4
        with:
          name: citation-bibtex
          path: CITATION.bib
```

Replace `<latest-version>` with the actual latest release tag discovered above.

### gh-aw Compatibility

If the project uses gh-aw for workflow management, instead generate a gh-aw-compatible workflow manifest (`.md` file) following the project's existing patterns. Check for the presence of `.github/workflows/*.md` or gh-aw configuration to determine this.

### Output

**`--dry-run`:** Display the workflow YAML.

**`--apply`:** Write to `.github/workflows/cff-validate.yml` (or the gh-aw manifest path).

## Error Handling

**No project metadata files found:**
Ask user to provide project name, version, and author information manually, then generate from that input.

**CITATION.cff does not exist (for validate/sync/export/enrich):**
Inform user and suggest running with `--init` first.

**`gh` CLI not available:**
Skip repository topic fetching and remote URL discovery via `gh`. Fall back to `git remote get-url origin` for URL and omit topics.

**`cffconvert` not available:**
Skip external validation step. Report that only schema-based validation was performed and suggest installing cffconvert for comprehensive checking:
```
pip install cffconvert
```

**YAML parse error in existing CITATION.cff:**
Report the parse error with line number. Do not attempt to modify a file with invalid YAML — user must fix syntax first.

**Git repository not found:**
Warn that author discovery from git history and remote URL detection are unavailable. Proceed with manifest-only data.

**Version not found in any source:**
Prompt user for version string. Do not default to `0.0.0` or omit the field.

**Author name splitting ambiguous:**
When a full name cannot be reliably split into family/given names (e.g., "Madonna", "Li Wei", organizational names), use the `name` field instead and add a comment suggesting the user verify.

## Flags Reference

| Flag | Description | Default |
|------|-------------|---------|
| `--init` | Create a new CITATION.cff from project metadata | - |
| `--validate` | Validate existing CITATION.cff | - |
| `--sync` | Sync version, authors, keywords from project sources | - |
| `--export <fmt>` | Export as bibtex, apa, ris, codemeta, or endnote | - |
| `--enrich` | Add ORCIDs, DOIs, identifiers, preferred-citation | - |
| `--workflow` | Generate GitHub Actions validation workflow | - |
| `--dry-run` | Show changes without writing (default for all operations) | `true` |
| `--apply` | Write changes to disk | `false` |
| `--output <file>` | Write export output to file instead of stdout | - |

## Examples

### Example 1: Create for a Node.js Project

**Detected ecosystem:** Node.js (package.json)

```yaml
cff-version: 1.2.0
message: "If you use this software, please cite it as below."
title: "github-social"
type: software
authors:
  - family-names: "Zircote"
    given-names: "Robert"
    email: "robert@zircote.com"
version: "1.0.0"
date-released: "2024-06-15"
url: "https://github.com/zircote/github-social"
repository-code: "https://github.com/zircote/github-social"
license: "MIT"
keywords:
  - github
  - social-preview
  - citation
  - metadata
abstract: "Generate social preview images for GitHub repositories by analyzing project intent."
```

### Example 2: BibTeX Export

```bibtex
@software{zircote_2024_githubsocial,
  author       = {Zircote, Robert},
  title        = {github-social},
  version      = {1.0.0},
  date         = {2024-06-15},
  url          = {https://github.com/zircote/github-social},
  license      = {MIT}
}
```

### Example 3: APA Export

```
Zircote, R. (2024). github-social (Version 1.0.0) [Computer software]. https://github.com/zircote/github-social
```

### Example 4: Validation Report

```markdown
## CITATION.cff Validation Report

### Errors (must fix)
- `date-released` format invalid — got "June 15, 2024", expected "2024-06-15"

### Warnings (should fix)
- `version` in CITATION.cff ("1.0.0") differs from package.json ("1.1.0")
- 0 of 1 authors have ORCID iDs

### Info
- 1 author listed
- DOI: not set
- License: MIT (valid SPDX)

### Result: FAIL (1 error, 2 warnings)
```
