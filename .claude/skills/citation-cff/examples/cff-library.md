# Example: Library/Package CITATION.cff

A complete CITATION.cff for a Python library. This example covers the most common case: an open-source package published to a registry (PyPI, npm, crates.io, etc.).

## Key Choices

- **`type: software`** is the correct type for libraries and packages.
- **`cff-version: 1.2.0`** is the current version of the CFF specification.
- **`date-released`** should match the release date of the version specified in `version`.
- **Authors** can have varying levels of detail. Include ORCID identifiers when available to ensure proper attribution in academic contexts.
- **`repository-code`** points to the source, while `url` can point to documentation or a project homepage.

## CITATION.cff

```yaml
cff-version: 1.2.0
message: "If you use this software, please cite it using the metadata from this file."
type: software
title: "datacheck"
version: 2.4.1
date-released: "2025-11-03"
abstract: >-
  A Python library for declarative data validation with
  support for nested schemas, custom rules, and detailed
  error reporting.
license: MIT
url: "https://datacheck.readthedocs.io"
repository-code: "https://github.com/exampleorg/datacheck"
keywords:
  - data-validation
  - schema
  - python
  - type-checking
  - data-quality
authors:
  - given-names: "Maria"
    family-names: "Chen"
    email: "m.chen@example.edu"
    affiliation: "University of Example"
    orcid: "https://orcid.org/0000-0002-1234-5678"
  - given-names: "James"
    family-names: "Okonkwo"
    affiliation: "ExampleOrg Inc."
```

## Usage

When someone cites this software, their BibTeX entry would look like:

```bibtex
@software{chen_datacheck_2025,
  author       = {Chen, Maria and Okonkwo, James},
  title        = {datacheck},
  version      = {2.4.1},
  date         = {2025-11-03},
  url          = {https://github.com/exampleorg/datacheck},
  license      = {MIT}
}
```

## Validation

```bash
# Install cffconvert
pip install cffconvert

# Validate the file
cffconvert --validate

# Convert to BibTeX
cffconvert -f bibtex
```
