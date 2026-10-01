# API reference

Generated from the source on every push to `main`, by
[`api-docs.yml`](https://github.com/marola-dev/marola/blob/main/.github/workflows/api-docs.yml),
and published alongside these pages. The trees are HTML, not Markdown, so they sit beside this
site rather than inside its navigation — this page is the way in.

## Scala

One scaladoc tree per module of the `build.sbt` build. Each link goes to `marola.html`, not the
tree's `index.html`: scaladoc's landing page has one line in its body, `Packages: package marola`,
so linking it lands the reader on a stub.

- [`core`](/api/scala/core/marola.html) — the pure pipeline, the shared HTTP/JSON helpers, and the
  `LlmClient` / `VisionClient` / `SightingStore` traits.
- [`local`](/api/scala/local/marola.html) — the Ollama-backed implementations of those traits, and the
  local-file sighting store.
- [`cli`](/api/scala/cli/marola.html) — `Main`, `AppConfig`, and the MCP tool server.

## Python

- [marola-ml's `finetune/` and `scripts/`](/repos/marola-ml/api/) — pdoc, from `api-docs.tar.gz`
  on marola-ml's latest release. These are commands, not a library: each one's module docstring is
  its usage.

!!! note "These links resolve on the published site, not in a local build"
    `just docs` builds only this site. The API trees come from a separate CI job and are merged
    into the same output there, so on a local build the four links above 404.
