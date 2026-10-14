# Example: Application CITATION.cff

A complete CITATION.cff for a CLI application. This example demonstrates additional metadata fields useful for standalone tools and applications, including identifiers and contact information.

## Key Choices

- **`identifiers`** allows listing DOIs, URLs, and other persistent identifiers. A DOI from Zenodo is common for software that needs a citable persistent identifier.
- **`contact`** specifies who should be contacted about the software. This is separate from authorship.
- **Multiple organizations** are shown across authors to demonstrate affiliation diversity.
- **`type: software`** applies to both libraries and applications.

## CITATION.cff

```yaml
cff-version: 1.2.0
message: "If you use this software, please cite it using the metadata from this file."
type: software
title: "shipctl"
version: 3.1.0
date-released: "2026-01-15"
abstract: >-
  A command-line tool for declarative application deployment
  across cloud providers with rollback support, canary
  releases, and infrastructure-as-code integration.
license: Apache-2.0
url: "https://shipctl.dev"
repository-code: "https://github.com/shipctl-org/shipctl"
identifiers:
  - type: doi
    value: "10.5281/zenodo.9876543"
    description: "Zenodo archive of shipctl v3.1.0"
  - type: url
    value: "https://shipctl.dev/releases/v3.1.0"
    description: "Release page for v3.1.0"
keywords:
  - deployment
  - cli
  - devops
  - cloud
  - infrastructure-as-code
contact:
  - given-names: "Priya"
    family-names: "Sharma"
    email: "priya@shipctl.dev"
authors:
  - given-names: "Priya"
    family-names: "Sharma"
    email: "priya@shipctl.dev"
    affiliation: "Shipctl Open Source Project"
    orcid: "https://orcid.org/0000-0003-8765-4321"
  - given-names: "Lukas"
    family-names: "Weber"
    affiliation: "CloudOps GmbH"
  - given-names: "Aisha"
    family-names: "Fatima"
    affiliation: "National Institute of Standards"
    orcid: "https://orcid.org/0000-0001-5678-9012"
```

## When to Use Identifiers

| Identifier Type | Use Case |
|----------------|----------|
| `doi` | Archived on Zenodo, Figshare, or similar services |
| `url` | Release pages, changelogs, or download links |
| `swh` | Software Heritage archive identifier |

## Validation

```bash
cffconvert --validate
```
