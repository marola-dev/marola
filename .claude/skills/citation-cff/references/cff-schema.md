# CFF 1.2.0 Schema Reference

Complete field reference for the Citation File Format (CFF) version 1.2.0. Use this to generate valid `CITATION.cff` files.

## File Structure

Every `CITATION.cff` file is a YAML document with these top-level fields:

```yaml
cff-version: "1.2.0"
message: "If you use this software, please cite it as below."
title: "My Software"
type: software
authors:
  - family-names: "Doe"
    given-names: "Jane"
date-released: "2024-01-15"
version: "1.0.0"
```

---

## Top-Level Fields

| Field | Type | Required | Description | Example |
|-------|------|----------|-------------|---------|
| `cff-version` | string | **Yes** | CFF schema version. Must be `"1.2.0"` | `"1.2.0"` |
| `message` | string | **Yes** | Instructions for citing the work | `"If you use this software, please cite it as below."` |
| `title` | string | **Yes** | Name of the software or dataset | `"My Research Tool"` |
| `type` | enum | **Yes** | Either `software` or `dataset` | `software` |
| `authors` | list\<person\|entity\> | **Yes** | List of author objects | See Author Fields |
| `date-released` | date | **Yes** | Release date in `YYYY-MM-DD` format | `"2024-01-15"` |
| `version` | string | **Yes** | Version string (semver recommended) | `"2.1.0"` |
| `doi` | string | No | Digital Object Identifier | `"10.5281/zenodo.1234567"` |
| `url` | url | No | Homepage or landing page URL | `"https://example.com/tool"` |
| `repository-code` | url | No | URL of the source code repository | `"https://github.com/org/repo"` |
| `repository` | url | No | URL of the repository (non-code, e.g., artifact repo) | `"https://repo.example.com/tool"` |
| `repository-artifact` | url | No | URL of the archived artifact | `"https://archive.org/tool-v1"` |
| `license` | string | No | SPDX license identifier | `"MIT"` |
| `license-url` | url | No | URL of the license text | `"https://opensource.org/licenses/MIT"` |
| `keywords` | list\<string\> | No | Descriptive keywords | `["simulation", "physics"]` |
| `abstract` | string | No | Description of the software/dataset | `"A tool for simulating..."` |
| `identifiers` | list\<identifier\> | No | Additional identifiers (DOIs, URLs, SWH IDs) | See Identifier Fields |
| `preferred-citation` | reference | No | Citation for the related paper/article | See Reference Fields |
| `references` | list\<reference\> | No | Other works referenced by this software | See Reference Fields |
| `contact` | list\<person\|entity\> | No | Contact person(s) for the software | Same as author objects |
| `commit` | string | No | Commit hash for this version | `"abc123def456"` |

---

## Author Fields (Person)

Each author in the `authors` list can be a person or an entity.

### Person Object

| Field | Type | Required | Description | Example |
|-------|------|----------|-------------|---------|
| `family-names` | string | **Yes*** | Surname / family name | `"van Rossum"` |
| `given-names` | string | **Yes*** | Given / first name(s) | `"Guido"` |
| `email` | string | No | Email address | `"guido@python.org"` |
| `affiliation` | string | No | Organizational affiliation | `"Python Software Foundation"` |
| `orcid` | url | No | ORCID URL (full URL, not bare ID) | `"https://orcid.org/0000-0001-2345-6789"` |
| `name-particle` | string | No | Name particle (e.g., "von", "de") | `"van"` |
| `name-suffix` | string | No | Name suffix (e.g., "Jr.", "III") | `"Jr."` |
| `alias` | string | No | Pseudonym or username | `"gvanrossum"` |
| `city` | string | No | City | `"Amsterdam"` |
| `country` | string | No | ISO 3166-1 alpha-2 country code | `"NL"` |
| `fax` | string | No | Fax number | `"+1-234-567-8900"` |
| `post-code` | string | No | Postal code | `"1012"` |
| `region` | string | No | Region or state | `"North Holland"` |
| `tel` | string | No | Telephone number | `"+1-234-567-8901"` |
| `website` | url | No | Personal website | `"https://gvanrossum.github.io"` |

*Required when representing a person (not an entity).

### Entity Object

| Field | Type | Required | Description | Example |
|-------|------|----------|-------------|---------|
| `name` | string | **Yes** | Organization or entity name | `"Python Software Foundation"` |
| `address` | string | No | Street address | `"9450 SW Gemini Dr"` |
| `city` | string | No | City | `"Beaverton"` |
| `country` | string | No | ISO 3166-1 alpha-2 country code | `"US"` |
| `email` | string | No | Contact email | `"psf@python.org"` |
| `date-start` | date | No | Start date of involvement | `"2001-03-01"` |
| `date-end` | date | No | End date of involvement | `"2024-12-31"` |
| `fax` | string | No | Fax number | `"+1-234-567-8900"` |
| `location` | string | No | Descriptive location | `"Building A, Floor 3"` |
| `post-code` | string | No | Postal code | `"97008"` |
| `region` | string | No | Region or state | `"Oregon"` |
| `tel` | string | No | Telephone number | `"+1-234-567-8901"` |
| `website` | url | No | Organization website | `"https://www.python.org/psf/"` |

### Author Examples

**Person with ORCID:**

```yaml
authors:
  - family-names: "Druskat"
    given-names: "Stephan"
    email: "stephan.druskat@example.com"
    affiliation: "German Aerospace Center (DLR)"
    orcid: "https://orcid.org/0000-0003-4925-7248"
```

**Entity as author:**

```yaml
authors:
  - name: "The Research Software Engineering Group"
    city: "Berlin"
    country: "DE"
    website: "https://rse.example.com"
```

**Multiple authors:**

```yaml
authors:
  - family-names: "Doe"
    given-names: "Jane"
    orcid: "https://orcid.org/0000-0001-2345-6789"
  - family-names: "Smith"
    given-names: "John"
    affiliation: "University of Example"
  - name: "Example Corporation"
```

---

## Identifier Fields

The `identifiers` list provides additional ways to reference the software.

| Field | Type | Required | Description | Example |
|-------|------|----------|-------------|---------|
| `type` | enum | **Yes** | One of: `doi`, `url`, `swh`, `other` | `"doi"` |
| `value` | string | **Yes** | The identifier value | `"10.5281/zenodo.1234567"` |
| `description` | string | No | What this identifier represents | `"The concept DOI for all versions"` |

### Identifier Examples

```yaml
identifiers:
  - type: doi
    value: "10.5281/zenodo.1234567"
    description: "The concept DOI for all versions"
  - type: doi
    value: "10.5281/zenodo.7654321"
    description: "The versioned DOI for version 1.0.0"
  - type: url
    value: "https://example.com/tool/v1.0.0"
    description: "The release page for version 1.0.0"
  - type: swh
    value: "swh:1:dir:abcdef1234567890abcdef1234567890abcdef12"
    description: "Software Heritage identifier"
```

---

## Reference / Preferred-Citation Fields

Used in `preferred-citation` and items in the `references` list.

| Field | Type | Required | Description | Example |
|-------|------|----------|-------------|---------|
| `type` | enum | **Yes** | Reference type (see types below) | `"article"` |
| `title` | string | **Yes** | Title of the referenced work | `"My Research Paper"` |
| `authors` | list\<person\|entity\> | **Yes** | Authors (same schema as top-level) | See Author Fields |
| `year` | integer | No | Publication year | `2024` |
| `month` | integer | No | Publication month (1-12) | `6` |
| `journal` | string | No | Journal name | `"Journal of Open Source Software"` |
| `volume` | string | No | Volume number | `"9"` |
| `issue` | string | No | Issue number | `"42"` |
| `start` | string | No | Start page | `"123"` |
| `end` | string | No | End page | `"145"` |
| `doi` | string | No | DOI of the referenced work | `"10.21105/joss.01234"` |
| `url` | url | No | URL of the referenced work | `"https://doi.org/10.21105/joss.01234"` |
| `abstract` | string | No | Abstract of the referenced work | `"We present..."` |
| `keywords` | list\<string\> | No | Keywords | `["citation", "metadata"]` |
| `date-released` | date | No | Release/publication date | `"2024-06-15"` |
| `date-published` | date | No | Publication date | `"2024-06-15"` |
| `isbn` | string | No | ISBN | `"978-0-13-468599-1"` |
| `issn` | string | No | ISSN | `"2475-9066"` |
| `publisher` | entity | No | Publisher as entity object | `{name: "Springer"}` |
| `conference` | entity | No | Conference as entity object | `{name: "RSE Con"}` |
| `editors` | list\<person\|entity\> | No | Editors | Same as author objects |
| `collection-title` | string | No | Title of collection / proceedings | `"Proceedings of RSE Con"` |
| `collection-doi` | string | No | DOI of the collection | `"10.1234/proceedings"` |
| `collection-type` | string | No | Type of collection | `"proceedings"` |
| `edition` | string | No | Edition | `"2nd"` |
| `institution` | entity | No | Institution | `{name: "MIT"}` |
| `pages` | string | No | Total pages | `"22"` |
| `notes` | string | No | Additional notes | `"Preprint"` |
| `scope` | string | No | What part of the software is cited | `"Visualization module"` |
| `section` | string | No | Section title | `"Methods"` |
| `thesis-type` | string | No | Type of thesis | `"PhD thesis"` |
| `number` | string | No | Report/tech number | `"TR-2024-001"` |
| `number-volumes` | string | No | Number of volumes | `"3"` |
| `status` | enum | No | `advance-online`, `in-preparation`, `in-press`, `pre-print`, `submitted` | `"in-press"` |
| `copyright` | string | No | Copyright notice | `"Copyright 2024 Author"` |

### Reference Type Values

```
article, book, book-chapter, conference-paper, dataset, edited-work,
generic, government-document, grant, journal, legal-proceeding,
magazine-article, manual, map, multimedia, music, newspaper-article,
pamphlet, patent, personal-communication, proceedings, report,
serial, slides, software, software-code, software-container,
software-executable, software-virtual-machine, sound-recording,
standard, statute, thesis, unpublished, video, website
```

### Preferred-Citation Example (Journal Article)

```yaml
preferred-citation:
  type: article
  title: "My Research Tool: A Novel Approach"
  authors:
    - family-names: "Doe"
      given-names: "Jane"
      orcid: "https://orcid.org/0000-0001-2345-6789"
    - family-names: "Smith"
      given-names: "John"
  journal: "Journal of Open Source Software"
  volume: "9"
  issue: "42"
  start: "1234"
  year: 2024
  doi: "10.21105/joss.01234"
```

### Preferred-Citation Example (Conference Paper)

```yaml
preferred-citation:
  type: conference-paper
  title: "Efficient Algorithms for Data Processing"
  authors:
    - family-names: "Doe"
      given-names: "Jane"
  collection-title: "Proceedings of the International Conference on Software Engineering"
  conference:
    name: "ICSE 2024"
    city: "Lisbon"
    country: "PT"
  year: 2024
  start: "45"
  end: "56"
  doi: "10.1145/1234567.1234568"
```

---

## Complete CITATION.cff Example

```yaml
cff-version: "1.2.0"
message: "If you use this software, please cite it using the metadata from this file."
title: "Research Analysis Toolkit"
type: software
version: "3.2.1"
date-released: "2024-06-15"
doi: "10.5281/zenodo.1234567"
url: "https://rat.example.com"
repository-code: "https://github.com/example/rat"
license: "Apache-2.0"
abstract: "A comprehensive toolkit for automated research data analysis."
keywords:
  - research
  - data-analysis
  - automation
  - python

authors:
  - family-names: "Doe"
    given-names: "Jane"
    email: "jane.doe@example.com"
    affiliation: "University of Example"
    orcid: "https://orcid.org/0000-0001-2345-6789"
  - family-names: "Smith"
    given-names: "John"
    affiliation: "Example Corp"
  - name: "Example Research Group"
    website: "https://research.example.com"

contact:
  - family-names: "Doe"
    given-names: "Jane"
    email: "jane.doe@example.com"

identifiers:
  - type: doi
    value: "10.5281/zenodo.1234567"
    description: "The concept DOI for all versions"
  - type: swh
    value: "swh:1:dir:abcdef1234567890abcdef1234567890abcdef12"
    description: "Software Heritage identifier"

preferred-citation:
  type: article
  title: "RAT: A Toolkit for Research Analysis"
  authors:
    - family-names: "Doe"
      given-names: "Jane"
      orcid: "https://orcid.org/0000-0001-2345-6789"
    - family-names: "Smith"
      given-names: "John"
  journal: "Journal of Open Source Software"
  volume: "9"
  issue: "42"
  start: "1234"
  year: 2024
  doi: "10.21105/joss.01234"

references:
  - type: software
    title: "NumPy"
    authors:
      - name: "NumPy Developers"
    version: "1.26.0"
    doi: "10.1038/s41586-020-2649-2"
```
