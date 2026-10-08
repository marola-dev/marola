# Example: CITATION.cff with Published Paper

A complete CITATION.cff for research software that has an associated published paper. When your software accompanies a peer-reviewed publication, use `preferred-citation` to direct academic citations to the paper rather than the software itself.

## When to Use preferred-citation

Use `preferred-citation` when:

- Your software has a published paper in a journal or conference proceedings.
- You want academic citations to point to the paper (which is the norm in research).
- The software repository serves as a companion to the publication.

The root-level metadata still describes the **software**. The `preferred-citation` block describes the **paper** that should be cited instead.

## Key Choices

- **`preferred-citation`** with `type: article` directs citation tools to generate references to the journal article.
- **`references`** lists related works that informed or depend on this software.
- The software and the paper may have different author lists; both are specified independently.

## CITATION.cff

```yaml
cff-version: 1.2.0
message: >-
  If you use this software, please cite the associated paper
  listed in the preferred-citation field.
type: software
title: "spectralkit"
version: 1.3.2
date-released: "2025-09-20"
abstract: >-
  A Python toolkit for spectral analysis of time-series data
  with support for wavelet transforms, power spectral density
  estimation, and coherence analysis.
license: BSD-3-Clause
url: "https://spectralkit.readthedocs.io"
repository-code: "https://github.com/signal-lab/spectralkit"
keywords:
  - spectral-analysis
  - time-series
  - signal-processing
  - wavelet
  - python
authors:
  - given-names: "Elena"
    family-names: "Vasquez"
    affiliation: "Signal Processing Laboratory, ETH Example"
    orcid: "https://orcid.org/0000-0002-3456-7890"
  - given-names: "Tomoko"
    family-names: "Nakamura"
    affiliation: "Department of Applied Mathematics, University of Example"
  - given-names: "David"
    family-names: "Osei"
    affiliation: "Signal Processing Laboratory, ETH Example"
    orcid: "https://orcid.org/0000-0001-2345-6789"
preferred-citation:
  type: article
  title: "SpectralKit: A Unified Framework for Multi-Resolution Spectral Analysis"
  authors:
    - given-names: "Elena"
      family-names: "Vasquez"
      orcid: "https://orcid.org/0000-0002-3456-7890"
    - given-names: "Tomoko"
      family-names: "Nakamura"
    - given-names: "David"
      family-names: "Osei"
      orcid: "https://orcid.org/0000-0001-2345-6789"
  journal: "Journal of Computational Signal Processing"
  year: 2025
  volume: 42
  issue: 3
  start: 287
  end: 305
  doi: "10.1234/jcsp.2025.42.3.287"
references:
  - type: software
    title: "SciPy"
    authors:
      - given-names: "Pauli"
        family-names: "Virtanen"
    version: 1.14.0
    url: "https://scipy.org"
    doi: "10.1038/s41592-019-0686-2"
  - type: article
    title: "A Survey of Wavelet Methods for Time-Series Analysis"
    authors:
      - given-names: "R."
        family-names: "Liu"
      - given-names: "S."
        family-names: "Park"
    journal: "Signal Processing Review"
    year: 2023
    volume: 18
    start: 44
    end: 72
    doi: "10.5678/spr.2023.18.44"
```

## How Citation Tools Handle This

When a user runs `cffconvert -f bibtex`, the output targets the **paper**, not the software:

```bibtex
@article{vasquez_spectralkit_2025,
  author    = {Vasquez, Elena and Nakamura, Tomoko and Osei, David},
  title     = {SpectralKit: A Unified Framework for Multi-Resolution
               Spectral Analysis},
  journal   = {Journal of Computational Signal Processing},
  year      = {2025},
  volume    = {42},
  number    = {3},
  pages     = {287--305},
  doi       = {10.1234/jcsp.2025.42.3.287}
}
```

## Validation

```bash
cffconvert --validate
```
