<h1 align="center">🌊 marola</h1>

<p align="center">
<a href="https://github.com/marola-dev/marola/actions/workflows/ci.yml"><img src="https://github.com/marola-dev/marola/actions/workflows/ci.yml/badge.svg" alt="CI" /></a>
<a href="https://github.com/marola-dev/marola-site/actions/workflows/site.yml"><img src="https://github.com/marola-dev/marola-site/actions/workflows/site.yml/badge.svg" alt="site (build + deploy)" /></a>
<!-- Python statement coverage of scripts/**/*.py while each script's own --self-test runs, the only way marola tests Python (there is no pytest suite). ci.yml's repo-stats job (scripts/repo_stats.py) writes it and the badges below as shields.io endpoint JSON to marola-site's site-data branch, which marola.dev serves. The Scala badges are marola-app's README's. -->
<a href="https://github.com/marola-dev/marola/actions/workflows/ci.yml"><img src="https://img.shields.io/endpoint?url=https%3A%2F%2Fmarola.dev%2Fstats%2Fpython-coverage.json" alt="Python statement coverage under the scripts' own --self-tests" /></a>
<!-- How many of the last main run's steps went green out of the steps that actually ran (skipped ones excluded), and cloc's code-line count for the Python trees (this repo's scripts/ and marola-app's). -->
<a href="https://github.com/marola-dev/marola/actions/workflows/ci.yml"><img src="https://img.shields.io/endpoint?url=https%3A%2F%2Fmarola.dev%2Fstats%2Fci.json" alt="CI steps green on the last main run" /></a>
<img src="https://img.shields.io/endpoint?url=https%3A%2F%2Fmarola.dev%2Fstats%2Fpython-loc.json" alt="Python lines of code" />
<a href="https://marola.dev/"><img src="https://img.shields.io/badge/live_map-marola.dev-0b6e99?logo=mapbox&logoColor=white" alt="live map" /></a>
<a href="https://doi.org/10.5281/zenodo.23224155"><img src="https://img.shields.io/badge/DOI-10.5281%2Fzenodo.23224155-blue" alt="DOI" /></a>
<a href="./LICENSE"><img src="https://img.shields.io/badge/license-CC_BY_4.0_%2B_AGPL--3.0-green" alt="CC BY 4.0 for the docs, AGPL-3.0-only for the code" /></a>
<a href="https://github.com/h0ffmann/ptbr-writer"><img src="https://img.shields.io/badge/built_with-ptbr--writer-1682D4?logo=data:image/svg%2Bxml;base64,PHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIHZpZXdCb3g9IjAgMCA2NCA2NCIgZmlsbD0ibm9uZSIgc3Ryb2tlPSIjMWQzZmQwIiBzdHJva2Utd2lkdGg9IjIuNiIgc3Ryb2tlLWxpbmVjYXA9InJvdW5kIiBzdHJva2UtbGluZWpvaW49InJvdW5kIj48dGl0bGU+dW1hIHBlbmEgZGUgZXNjcmV2ZXIsIG51bWEgbGluaGEgc8OzPC90aXRsZT48cGF0aCBkPSJNMTAgNTZjOC0zIDE0LTkgMjAtMTZNMzAgNDBjLTQtMTAgMC0yNCAyMi0zMi0yIDE0LTggMjYtMjIgMzJ6TTMwIDQwbDEwLTE2TTE4IDU0YzEwIDIgMjIgMCAzNC00Ii8+PC9zdmc+Cg==" alt="built with ptbr-writer" /></a>
</p>

<p align="center"><b>The water super app: everything about the sea near you, built in the open, as citizen science.</b></p>

<p align="center"><a href="./README.md">🇧🇷 Leia em português (the full version)</a> · <a href="https://marola.dev/">🗺️ Open the live map</a></p>

## What is marola?

Imagine a friend who knows the sea really well. You ask *"can I swim tomorrow, and when?"*. Your
friend looks at the waves, the wind, the water temperature and the tide. Your friend checks if the
water is clean, and if jellyfish or whales are near. Then your friend tells you the best hour, and
**why**.

That friend is marola. You can use it today at **[marola.dev](https://marola.dev/)**. It is a map of
the beaches around Florianópolis, Rio de Janeiro and Salvador, and every beach gets a score for
today and tomorrow.

<p align="center"><a href="https://marola.dev/"><img src="./docs/img/marola-web-view.png" alt="marola.dev over Rio de Janeiro: Ipanema and Copacabana scored by best hour, water-quality drops from INEA, and Praia do Forte's card with sea, tide, jellyfish and whale odds" width="860" /></a></p>

marola keeps three promises:

- **It never makes things up.** Every number comes from real, public data. Plain rules decide what
  can hurt you (dirty water, a rough sea), not an AI's guess. The AI only puts the answer into words.
- **It is free, and it runs on your own computer.** You need no account and no paid service.
- **It tells you when it does not know.** "No data" shows as "no data".

## The map

> ⚠️ **Frontend v2 in progress.** The map's frontend v2 is being written with
> [MapLibre](https://maplibre.org/) and Rust, and it should be ready before 2027. The page on
> marola.dev today is v1, on Mapbox GL JS. The v2 seed lives in 🔒 `h0ffmann/smart-alerts`, a closed
> repository for now, under MPL-2.0.
>
> <a href="https://github.com/h0ffmann/smart-alerts"><img src="https://img.shields.io/badge/h0ffmann%2Fsmart--alerts-private-555?logo=github&logoColor=white" alt="h0ffmann/smart-alerts: private repository" /></a>
> <a href="https://www.mozilla.org/MPL/2.0/"><img src="https://img.shields.io/badge/license-MPL--2.0-1682D4?logo=mozilla&logoColor=white" alt="license: MPL-2.0" /></a>

Two layers are live on the map today:

- 🏖️ **Beaches**: every beach, with the best hour to swim.
- 💧 **Water quality**: the official test points of the state agencies, coloured by their last test.

More layers wait their turn: wind and waves, satellite pictures, sea heat, depth, coastal trails,
storm warnings and live cameras. Each one goes on only after a person checks its data. The
Portuguese [README](./README.md#o-mapa-o-que-já-está-no-ar-e-o-que-vem-aí) has the full list.

## Try it on your computer

```bash
git clone --recurse-submodules https://github.com/marola-dev/marola && cd marola/marola-app
```

```bash
# in a marola-app checkout
nix develop                                          # gets every tool you need
just run -- --brief --lat -27.6733 --lon -48.4700    # the beaches near Florianópolis, best first
```

You need no cloud account and no API key. The full walkthrough is
[RUN-LOCALLY](https://docs.marola.dev/1-Using-marola/RUN-LOCALLY/).

## Why it is open: citizen science 🔬

The sea belongs to everyone, so the knowledge about it should too. Public agencies measure a lot,
but the data is scattered and hard to read at the beach. marola collects it, checks it, explains it
in plain words, and gives it back to everyone. All of it is open: the code, the data sources, the
rules and every design decision.

## Where it is going

The goal is a water super app, and swimming is the first question. Next come warnings for *ressaca* (the storm surf that eats Brazil's
beaches), wave forecasts from several models side by side, an open archive of Brazil's beach water
tests, and reports from people at the beach. There is also an idea to compute the score with open decision models,
served by [Ollaya](https://github.com/h0ffmann/ptbr-writer#ollaya). It is only an idea for now, with
no MIP yet. Each idea is written down first as a public design
doc, a "MIP", in [`docs/MIPs/`](./docs/MIPs/README.md).

## How you can help (no coding needed)

- **Use the map** at [marola.dev](https://marola.dev/), and tell us when it is wrong.
- **Tell us what you see in the water**, like jellyfish or whales, or a missing beach.
- **Share what you know** about sea safety, sea life or your beach. marola explains only facts with
  a source, kept in [marola-corpus](https://github.com/marola-dev/marola-corpus).
- **Open an issue**, in English or Portuguese:
  [github.com/marola-dev/marola/issues](https://github.com/marola-dev/marola/issues).

## For developers

marola is a family of small repos, and each one does one job. This repo is the **umbrella**: it
holds the design docs, the plans and the docs site, and it links every code repo as a git
submodule. It holds no code. The map of the repos is in
[REPOS](docs/2-Building-marola/REPOS.md), and all the docs are at
**[docs.marola.dev](https://docs.marola.dev/)**. AI coding agents read [`AGENTS.md`](./AGENTS.md)
first.

PR descriptions here come out in two languages, Portuguese first, from the `bipr` skill of
[ptbr-writer](https://github.com/h0ffmann/ptbr-writer), the maintainer's writing plugin. The full
guide is [Contributing](https://docs.marola.dev/3-Ways-of-working/CONTRIBUTING/). Please read the
[Code of Conduct](https://github.com/marola-dev/.github/blob/main/CODE_OF_CONDUCT.md) too.

## Every repo in the org

The [marola-dev](https://github.com/marola-dev) org has 14 public repos, forks included. "Commits"
counts the commits on each repo's default branch (`git rev-list --count HEAD`, counted on
2026-10-26). A fork's count includes the history of the project it copies. One private repo is
left out.

| Repo | Kind | Commits | Last commit |
|---|---|---:|---|
| [marola](https://github.com/marola-dev/marola) | umbrella | 594 | 2026-10-26 |
| [marola-devkit](https://github.com/marola-dev/marola-devkit) | code | 223 | 2026-10-20 |
| [marola-app](https://github.com/marola-dev/marola-app) | code | 197 | 2026-10-20 |
| [marola-site](https://github.com/marola-dev/marola-site) | code | 140 | 2026-10-26 |
| [marola-ml](https://github.com/marola-dev/marola-ml) | code | 116 | 2026-10-20 |
| [marola-skills](https://github.com/marola-dev/marola-skills) | catalogue | 48 | 2026-10-20 |
| [marola-corpus](https://github.com/marola-dev/marola-corpus) | data | 45 | 2026-10-20 |
| [marola-research](https://github.com/marola-dev/marola-research) | science | 42 | 2026-10-20 |
| [marola-oods](https://github.com/marola-dev/marola-oods) | data | 34 | 2026-10-20 |
| [.github](https://github.com/marola-dev/.github) | org profile | 15 | 2026-10-25 |
| [marola-agents](https://github.com/marola-dev/marola-agents) | catalogue | 4 | 2026-10-10 |
| [awesome-ocean-sciences](https://github.com/marola-dev/awesome-ocean-sciences) | list | 1 | 2026-10-19 |
| [open-sustainable-technology](https://github.com/marola-dev/open-sustainable-technology) | list fork | 4089 | 2026-09-25 |
| [awesome-open-climate-science](https://github.com/marola-dev/awesome-open-climate-science) | list fork | 77 | 2025-11-12 |

## How to cite

Every release is kept on [Zenodo](https://zenodo.org/) with a DOI. Cite
[10.5281/zenodo.23224155](https://doi.org/10.5281/zenodo.23224155): it always points to the latest
version. GitHub's **Cite this repository** button exports it from [`CITATION.cff`](./CITATION.cff).

<!-- citation:start -->

BibTeX:

```bibtex
@software{hoffmann_2026_marola,
  author    = {Hoffmann, Matheus and Valério, Bruno and Soares da Silva Junior, Rob Kler and Oliveira, Elisa and Almeida, Leonardo Ramos and Ribeiro, Pablo},
  title     = {{marola: an open, non-profit platform for sea conditions and bathing-water quality at Brazilian beaches, built on public data}},
  year      = {2026},
  publisher = {Zenodo},
  doi       = {10.5281/zenodo.23224155},
  url       = {https://github.com/marola-dev/marola}
}
```

APA:

> Hoffmann, M., Valério, B., Soares da Silva Junior, R. K., Oliveira, E., Almeida, L. R., & Ribeiro, P. (2026). *marola: an open, non-profit platform for sea conditions and bathing-water quality at Brazilian beaches, built on public data* [Computer software]. Zenodo. https://doi.org/10.5281/zenodo.23224155

ABNT (NBR 6023):

> HOFFMANN, Matheus; VALÉRIO, Bruno; SOARES DA SILVA JUNIOR, Rob Kler; OLIVEIRA, Elisa; ALMEIDA, Leonardo Ramos; RIBEIRO, Pablo. **marola**: an open, non-profit platform for sea conditions and bathing-water quality at Brazilian beaches, built on public data. [S. l.]: Zenodo, 2026. DOI 10.5281/zenodo.23224155.

<!-- citation:end -->

## Thanks

marola is built on other people's work: [OpenStreetMap](https://www.openstreetmap.org/copyright)
contributors (every beach on the map), [Open-Meteo](https://open-meteo.com/) (the sea and weather
forecasts), [Mapbox GL JS](https://github.com/mapbox/mapbox-gl-js) (the map),
[Ollama](https://ollama.com/) (the models on your computer) and [Kyo](https://getkyo.io/). The
water tests come from INEA (Rio de Janeiro), INEMA (Bahia) and IMA/SC (Santa Catarina).

## Contact

Write to [admin@marola.dev](mailto:admin@marola.dev). Report a security problem through the
private channel in the [security policy](https://github.com/marola-dev/.github/blob/main/SECURITY.md).

## License

The docs are [CC BY 4.0](./LICENSE), and the code is
[AGPL-3.0-only](./LICENSES/AGPL-3.0-only.txt). [`REUSE.toml`](./REUSE.toml) records each file's
licence. © 2026 marola contributors.
