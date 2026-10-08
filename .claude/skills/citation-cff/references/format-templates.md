# Citation Export Format Templates

Templates for converting CFF metadata into standard citation formats. Each section includes a template with placeholders, a field mapping table, and a complete example.

---

## BibTeX

### Template

```bibtex
@software{${citekey},
  author       = {${authors}},
  title        = {${title}},
  version      = {${version}},
  date         = {${date-released}},
  year         = {${year}},
  month        = {${month}},
  doi          = {${doi}},
  url          = {${url}},
  license      = {${license}},
  abstract     = {${abstract}}
}
```

### Field Mapping

| CFF Field | BibTeX Field | Notes |
|-----------|-------------|-------|
| `title` | `title` | Direct mapping |
| `authors[].family-names` + `given-names` | `author` | Format: `{Family}, Given and {Family2}, Given2` |
| `authors[].name` (entity) | `author` | Use entity name directly: `{Entity Name}` |
| `version` | `version` | Direct mapping |
| `date-released` | `date`, `year`, `month` | Split: `year` = YYYY, `month` = numeric month |
| `doi` | `doi` | Direct mapping (bare DOI, no URL prefix) |
| `url` | `url` | Falls back to `repository-code` if `url` is absent |
| `repository-code` | `url` | Used when `url` is not set |
| `license` | `license` | SPDX identifier |
| `abstract` | `abstract` | Direct mapping |
| `keywords` | — | No standard BibTeX field; use `note` or `keywords` with biblatex |

### Citekey Generation

Format: `{first-author-family}{year}{first-word-of-title}`

- Lowercase, ASCII-only (strip accents)
- Example: `doe2024research` for Jane Doe, 2024, "Research Analysis Toolkit"

### Complete Example

```bibtex
@software{doe2024research,
  author       = {Doe, Jane and Smith, John and {Example Research Group}},
  title        = {Research Analysis Toolkit},
  version      = {3.2.1},
  date         = {2024-06-15},
  year         = {2024},
  month        = {6},
  doi          = {10.5281/zenodo.1234567},
  url          = {https://rat.example.com},
  license      = {Apache-2.0},
  abstract     = {A comprehensive toolkit for automated research data analysis.}
}
```

### Preferred-Citation as BibTeX Article

When `preferred-citation` exists with `type: article`:

```bibtex
@article{doe2024rat,
  author       = {Doe, Jane and Smith, John},
  title        = {RAT: A Toolkit for Research Analysis},
  journal      = {Journal of Open Source Software},
  year         = {2024},
  volume       = {9},
  number       = {42},
  pages        = {1234},
  doi          = {10.21105/joss.01234}
}
```

---

## APA (7th Edition)

### Template (Software)

```
${authors} (${year}). ${title} (Version ${version}) [Computer software]. ${url-or-doi}
```

### Template (Article via preferred-citation)

```
${authors} (${year}). ${title}. ${journal}, ${volume}(${issue}), ${start}–${end}. ${doi-url}
```

### Field Mapping

| CFF Field | APA Element | Notes |
|-----------|-------------|-------|
| `authors[].family-names` + `given-names` | Author list | Format: `Family, G. I.` (initials with periods) |
| `authors[].name` (entity) | Author list | Entity name as-is |
| `date-released` | `(year)` | Extract year only |
| `title` | Title | Italicized in rendered output |
| `version` | `(Version X.Y.Z)` | In parentheses after title |
| `doi` | DOI URL | Format: `https://doi.org/10.xxxx/xxxxx` |
| `url` | URL | Used when DOI is absent |
| `repository-code` | URL | Fallback when both `doi` and `url` are absent |

### Author Formatting Rules

- **1 author**: `Doe, J.`
- **2 authors**: `Doe, J., & Smith, A.`
- **3-20 authors**: `Doe, J., Smith, A., & Jones, B.` (list all, `&` before last)
- **21+ authors**: First 19 authors, `...`, then last author
- **Entity**: Use name directly: `Example Research Group.`

### Complete Example (Software)

```
Doe, J., Smith, J., & Example Research Group. (2024). Research Analysis Toolkit (Version 3.2.1) [Computer software]. https://doi.org/10.5281/zenodo.1234567
```

### Complete Example (Article via preferred-citation)

```
Doe, J., & Smith, J. (2024). RAT: A Toolkit for Research Analysis. Journal of Open Source Software, 9(42), 1234. https://doi.org/10.21105/joss.01234
```

---

## RIS (Research Information Systems)

### Template

```ris
TY  - COMP
AU  - ${author-family}, ${author-given}
TI  - ${title}
ET  - ${version}
DA  - ${date-released}
PY  - ${year}
DO  - ${doi}
UR  - ${url}
AB  - ${abstract}
KW  - ${keyword}
ER  -
```

### Field Mapping

| CFF Field | RIS Tag | Notes |
|-----------|---------|-------|
| (type: software) | `TY  - COMP` | Use `COMP` for software, `DATA` for dataset |
| `authors[].family-names`, `given-names` | `AU` | Format: `Family, Given`. One `AU` line per author |
| `authors[].name` (entity) | `AU` | Entity name on single `AU` line |
| `title` | `TI` | Direct mapping |
| `version` | `ET` | Edition/version field |
| `date-released` | `DA` | Format: `YYYY/MM/DD/` (trailing slash) |
| `date-released` (year) | `PY` | Format: `YYYY///` |
| `doi` | `DO` | Bare DOI |
| `url` | `UR` | Falls back to `repository-code` |
| `abstract` | `AB` | Direct mapping |
| `keywords[]` | `KW` | One `KW` line per keyword |
| `license` | — | No standard RIS tag; use `N1` (notes) |

### Complete Example

```ris
TY  - COMP
AU  - Doe, Jane
AU  - Smith, John
AU  - Example Research Group
TI  - Research Analysis Toolkit
ET  - 3.2.1
DA  - 2024/06/15/
PY  - 2024///
DO  - 10.5281/zenodo.1234567
UR  - https://rat.example.com
AB  - A comprehensive toolkit for automated research data analysis.
KW  - research
KW  - data-analysis
KW  - automation
KW  - python
ER  -
```

### Preferred-Citation as RIS Article

```ris
TY  - JOUR
AU  - Doe, Jane
AU  - Smith, John
TI  - RAT: A Toolkit for Research Analysis
JO  - Journal of Open Source Software
VL  - 9
IS  - 42
SP  - 1234
PY  - 2024///
DO  - 10.21105/joss.01234
ER  -
```

---

## CodeMeta (codemeta.json)

### Template

```json
{
  "@context": "https://doi.org/10.5063/schema/codemeta-2.0",
  "@type": "SoftwareSourceCode",
  "name": "${title}",
  "description": "${abstract}",
  "version": "${version}",
  "datePublished": "${date-released}",
  "license": "https://spdx.org/licenses/${license}",
  "codeRepository": "${repository-code}",
  "url": "${url}",
  "identifier": "${doi}",
  "author": [
    {
      "@type": "Person",
      "givenName": "${given-names}",
      "familyName": "${family-names}",
      "email": "${email}",
      "affiliation": {
        "@type": "Organization",
        "name": "${affiliation}"
      },
      "@id": "${orcid}"
    }
  ],
  "keywords": ${keywords},
  "programmingLanguage": [],
  "developmentStatus": "active"
}
```

### Field Mapping

| CFF Field | CodeMeta Field | Notes |
|-----------|---------------|-------|
| `title` | `name` | Direct mapping |
| `abstract` | `description` | Direct mapping |
| `version` | `version` | Direct mapping |
| `date-released` | `datePublished` | Direct mapping (ISO 8601) |
| `license` | `license` | Prefix with `https://spdx.org/licenses/` |
| `repository-code` | `codeRepository` | Direct mapping |
| `url` | `url` | Direct mapping |
| `doi` | `identifier` | Prefix with `https://doi.org/` |
| `authors[].given-names` | `author[].givenName` | Direct mapping |
| `authors[].family-names` | `author[].familyName` | Direct mapping |
| `authors[].email` | `author[].email` | Direct mapping |
| `authors[].affiliation` | `author[].affiliation.name` | Wrap in Organization object |
| `authors[].orcid` | `author[].@id` | Direct mapping (full URL) |
| `authors[].name` (entity) | `author[].name` with `@type: Organization` | Use Organization type |
| `keywords` | `keywords` | Direct mapping (array) |
| `commit` | — | No direct CodeMeta mapping |
| `preferred-citation` | `citation` | Map to nested `ScholarlyArticle` or `CreativeWork` |

### Complete Example

```json
{
  "@context": "https://doi.org/10.5063/schema/codemeta-2.0",
  "@type": "SoftwareSourceCode",
  "name": "Research Analysis Toolkit",
  "description": "A comprehensive toolkit for automated research data analysis.",
  "version": "3.2.1",
  "datePublished": "2024-06-15",
  "license": "https://spdx.org/licenses/Apache-2.0",
  "codeRepository": "https://github.com/example/rat",
  "url": "https://rat.example.com",
  "identifier": "https://doi.org/10.5281/zenodo.1234567",
  "author": [
    {
      "@type": "Person",
      "givenName": "Jane",
      "familyName": "Doe",
      "email": "jane.doe@example.com",
      "affiliation": {
        "@type": "Organization",
        "name": "University of Example"
      },
      "@id": "https://orcid.org/0000-0001-2345-6789"
    },
    {
      "@type": "Person",
      "givenName": "John",
      "familyName": "Smith",
      "affiliation": {
        "@type": "Organization",
        "name": "Example Corp"
      }
    },
    {
      "@type": "Organization",
      "name": "Example Research Group",
      "url": "https://research.example.com"
    }
  ],
  "keywords": ["research", "data-analysis", "automation", "python"]
}
```

---

## EndNote (XML)

### Template

```xml
<?xml version="1.0" encoding="UTF-8"?>
<xml>
  <records>
    <record>
      <ref-type name="Computer Program">9</ref-type>
      <contributors>
        <authors>
          <author>${family-names}, ${given-names}</author>
        </authors>
      </contributors>
      <titles>
        <title>${title}</title>
      </titles>
      <edition>${version}</edition>
      <dates>
        <year>${year}</year>
        <pub-dates>
          <date>${date-released}</date>
        </pub-dates>
      </dates>
      <electronic-resource-num>${doi}</electronic-resource-num>
      <urls>
        <related-urls>
          <url>${url}</url>
        </related-urls>
      </urls>
      <abstract>${abstract}</abstract>
      <keywords>
        <keyword>${keyword}</keyword>
      </keywords>
    </record>
  </records>
</xml>
```

### Field Mapping

| CFF Field | EndNote XML Element | Notes |
|-----------|-------------------|-------|
| (type: software) | `<ref-type name="Computer Program">9</ref-type>` | Ref type 9 = Computer Program |
| (type: dataset) | `<ref-type name="Dataset">59</ref-type>` | Ref type 59 = Dataset |
| `title` | `<titles><title>` | Direct mapping |
| `authors[].family-names`, `given-names` | `<author>` | Format: `Family, Given`. One `<author>` per person |
| `authors[].name` (entity) | `<author>` | Entity name directly |
| `version` | `<edition>` | Direct mapping |
| `date-released` (year) | `<year>` | Extract year |
| `date-released` | `<date>` | Full date string |
| `doi` | `<electronic-resource-num>` | Bare DOI |
| `url` | `<url>` | Falls back to `repository-code` |
| `abstract` | `<abstract>` | Direct mapping |
| `keywords[]` | `<keyword>` | One `<keyword>` per keyword |
| `license` | `<custom1>` | No native field; use custom |
| `repository-code` | `<custom2>` | No native field; use custom |

### Complete Example

```xml
<?xml version="1.0" encoding="UTF-8"?>
<xml>
  <records>
    <record>
      <ref-type name="Computer Program">9</ref-type>
      <contributors>
        <authors>
          <author>Doe, Jane</author>
          <author>Smith, John</author>
          <author>Example Research Group</author>
        </authors>
      </contributors>
      <titles>
        <title>Research Analysis Toolkit</title>
      </titles>
      <edition>3.2.1</edition>
      <dates>
        <year>2024</year>
        <pub-dates>
          <date>2024-06-15</date>
        </pub-dates>
      </dates>
      <electronic-resource-num>10.5281/zenodo.1234567</electronic-resource-num>
      <urls>
        <related-urls>
          <url>https://rat.example.com</url>
        </related-urls>
      </urls>
      <abstract>A comprehensive toolkit for automated research data analysis.</abstract>
      <keywords>
        <keyword>research</keyword>
        <keyword>data-analysis</keyword>
        <keyword>automation</keyword>
        <keyword>python</keyword>
      </keywords>
      <custom1>Apache-2.0</custom1>
      <custom2>https://github.com/example/rat</custom2>
    </record>
  </records>
</xml>
```

---

## Format Selection Guide

| Use Case | Recommended Format |
|----------|-------------------|
| LaTeX / academic papers | BibTeX |
| General academic citation | APA |
| Reference managers (Zotero, Mendeley) | RIS |
| Linked data / metadata registries | CodeMeta |
| EndNote users | EndNote XML |
| Machine-readable metadata | CodeMeta |
| README citation block | APA or BibTeX |
