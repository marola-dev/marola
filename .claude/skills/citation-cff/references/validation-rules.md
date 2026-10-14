# CITATION.cff Validation Rules

Complete validation ruleset for CITATION.cff files. Use these checks to validate generated files before writing.

---

## Required Fields

| Error Code | Check | Severity | Message | Fix Suggestion |
|------------|-------|----------|---------|----------------|
| CFF-001 | `cff-version` field exists | **Error** | Missing required field `cff-version` | Add `cff-version: "1.2.0"` |
| CFF-002 | `message` field exists | **Error** | Missing required field `message` | Add `message: "If you use this software, please cite it as below."` |
| CFF-003 | `title` field exists | **Error** | Missing required field `title` | Add `title:` with the software/dataset name |
| CFF-004 | `type` field exists | **Error** | Missing required field `type` | Add `type: software` or `type: dataset` |
| CFF-005 | `authors` field exists | **Error** | Missing required field `authors` | Add `authors:` list with at least one person or entity |
| CFF-006 | `date-released` field exists | **Error** | Missing required field `date-released` | Add `date-released:` in `YYYY-MM-DD` format |
| CFF-007 | `version` field exists | **Error** | Missing required field `version` | Add `version:` with the current version string |

---

## Field Format Validation

| Error Code | Check | Severity | Message | Fix Suggestion |
|------------|-------|----------|---------|----------------|
| CFF-010 | `date-released` matches `YYYY-MM-DD` | **Error** | Invalid date format: `${value}`. Expected `YYYY-MM-DD` | Use ISO 8601 date format, e.g., `2024-01-15` |
| CFF-011 | `orcid` matches `https://orcid.org/XXXX-XXXX-XXXX-XXXX` | **Error** | Invalid ORCID format: `${value}`. Must be full URL | Use full URL format: `https://orcid.org/0000-0001-2345-6789` |
| CFF-012 | `license` is a valid SPDX identifier | **Error** | Invalid SPDX license identifier: `${value}` | Use a valid SPDX ID from https://spdx.org/licenses/ (e.g., `MIT`, `Apache-2.0`, `GPL-3.0-only`) |
| CFF-013 | `doi` matches DOI pattern `10.XXXX/XXXXX` | **Error** | Invalid DOI format: `${value}`. Must start with `10.` | Use bare DOI format: `10.5281/zenodo.1234567` (no URL prefix) |
| CFF-014 | `url`, `repository-code`, `repository`, `repository-artifact` are valid URLs | **Error** | Invalid URL format: `${value}` | Use full URL with scheme: `https://example.com` |
| CFF-015 | `email` matches standard email pattern | **Error** | Invalid email format: `${value}` | Use standard email format: `user@domain.com` |
| CFF-016 | `country` is ISO 3166-1 alpha-2 | **Error** | Invalid country code: `${value}`. Must be 2-letter ISO code | Use 2-letter country code: `US`, `DE`, `GB`, etc. |
| CFF-017 | `version` is non-empty string | **Error** | Version must be a non-empty string | Provide a version string, e.g., `"1.0.0"` |
| CFF-018 | `date-released` is a valid calendar date | **Error** | Date `${value}` does not exist (e.g., Feb 30) | Use a valid calendar date |

---

## Author Structure Validation

| Error Code | Check | Severity | Message | Fix Suggestion |
|------------|-------|----------|---------|----------------|
| CFF-020 | Person authors have both `family-names` and `given-names` | **Error** | Author at index `${idx}` is missing `family-names` or `given-names` | Person authors require both `family-names` and `given-names`. For organizations, use `name` instead |
| CFF-021 | `authors` list has at least one entry | **Error** | `authors` list is empty | Add at least one author (person or entity) |
| CFF-022 | Each author is either person (has `family-names`) or entity (has `name`), not both | **Error** | Author at index `${idx}` has both `family-names` and `name` | Use `family-names`/`given-names` for people, `name` for organizations. Do not mix both |
| CFF-023 | Entity authors have `name` field | **Error** | Entity author at index `${idx}` is missing `name` | Entity authors require the `name` field |
| CFF-024 | ORCID is only on person authors, not entities | **Error** | Entity author at index `${idx}` has `orcid` field | ORCID is for individual researchers only. Remove `orcid` from entity authors |

---

## Schema Validation

| Error Code | Check | Severity | Message | Fix Suggestion |
|------------|-------|----------|---------|----------------|
| CFF-030 | `cff-version` value is `"1.2.0"` | **Error** | Unsupported CFF version: `${value}`. Only `1.2.0` is supported | Set `cff-version: "1.2.0"` |
| CFF-031 | `type` is either `software` or `dataset` | **Error** | Invalid type: `${value}`. Must be `software` or `dataset` | Set `type: software` or `type: dataset` |
| CFF-032 | `identifiers[].type` is one of: `doi`, `url`, `swh`, `other` | **Error** | Invalid identifier type: `${value}` at index `${idx}` | Use one of: `doi`, `url`, `swh`, `other` |
| CFF-033 | `preferred-citation.type` is a valid reference type | **Error** | Invalid preferred-citation type: `${value}` | Use a valid type: `article`, `book`, `conference-paper`, `software`, etc. |
| CFF-034 | `preferred-citation` has required fields (`type`, `title`, `authors`) | **Error** | `preferred-citation` is missing required field: `${field}` | Preferred citations require `type`, `title`, and `authors` fields |
| CFF-035 | `references[]` entries have required fields (`type`, `title`, `authors`) | **Error** | Reference at index `${idx}` is missing required field: `${field}` | Each reference requires `type`, `title`, and `authors` fields |
| CFF-036 | No unknown top-level keys | **Error** | Unknown field: `${key}`. Not part of CFF 1.2.0 schema | Remove or rename the field. Check spelling against the CFF 1.2.0 specification |
| CFF-037 | YAML is valid and parseable | **Error** | YAML parse error: `${error}` | Fix YAML syntax. Common issues: incorrect indentation, missing quotes around special characters |
| CFF-038 | File is valid UTF-8 encoding | **Error** | File contains invalid UTF-8 characters | Ensure the file is saved with UTF-8 encoding |

---

## Warnings (Optional but Recommended)

| Error Code | Check | Severity | Message | Fix Suggestion |
|------------|-------|----------|---------|----------------|
| CFF-W001 | `doi` field is present | **Warning** | No DOI specified. Consider registering one via Zenodo or similar | Register a DOI via Zenodo (https://zenodo.org) for better citability |
| CFF-W002 | `license` field is present | **Warning** | No license specified. This limits reusability | Add a `license` field with a valid SPDX identifier |
| CFF-W003 | `url` or `repository-code` is present | **Warning** | No URL or repository link. Readers cannot find the software | Add `url` or `repository-code` so readers can locate the software |
| CFF-W004 | `abstract` field is present | **Warning** | No abstract provided. Consider adding a brief description | Add an `abstract` field describing the software/dataset |
| CFF-W005 | `keywords` field is present | **Warning** | No keywords specified. Keywords improve discoverability | Add `keywords` as a list of relevant terms |
| CFF-W006 | At least one author has `orcid` | **Warning** | No author has an ORCID. ORCIDs improve author disambiguation | Add ORCID URLs for authors (register at https://orcid.org) |
| CFF-W007 | At least one author has `email` | **Warning** | No contact email on any author | Add an `email` to at least one author or use the `contact` field |
| CFF-W008 | `preferred-citation` is present when an associated paper exists | **Warning** | Consider adding `preferred-citation` if a related paper exists | Add `preferred-citation` to direct users to the canonical academic citation |
| CFF-W009 | `commit` field is present for pinned versions | **Warning** | No commit hash specified for this version | Add `commit` with the Git SHA for reproducibility |
| CFF-W010 | `identifiers` includes a concept DOI (for versioned software) | **Warning** | Consider adding a concept DOI in `identifiers` for all-version citation | Add an identifier with `description: "The concept DOI for all versions"` |

---

## Validation Order

Run checks in this sequence for best user experience:

1. **CFF-037**: YAML parse check (abort if invalid)
2. **CFF-038**: UTF-8 encoding check
3. **CFF-001 through CFF-007**: Required fields
4. **CFF-030, CFF-031**: Schema value checks
5. **CFF-036**: Unknown fields
6. **CFF-010 through CFF-018**: Field format checks
7. **CFF-020 through CFF-024**: Author structure checks
8. **CFF-032 through CFF-035**: Nested structure checks
9. **CFF-W001 through CFF-W010**: Warnings

---

## Severity Definitions

| Severity | Meaning | Action |
|----------|---------|--------|
| **Error** | File is invalid per CFF 1.2.0 spec | Must fix before writing file |
| **Warning** | File is valid but could be improved | Report to user; do not block file creation |

---

## Example Validation Output

```
Validating CITATION.cff...

ERRORS:
  CFF-011: Invalid ORCID format: "0000-0001-2345-6789". Must be full URL
    Fix: Use full URL format: https://orcid.org/0000-0001-2345-6789
  CFF-012: Invalid SPDX license identifier: "MIT License"
    Fix: Use a valid SPDX ID: "MIT"

WARNINGS:
  CFF-W001: No DOI specified. Consider registering one via Zenodo
  CFF-W004: No abstract provided. Consider adding a brief description

Result: 2 errors, 2 warnings. Fix errors before writing.
```
