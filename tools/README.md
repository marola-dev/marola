# marola-tools

The umbrella's repo tooling: one Scala 3 project in [`tools/`](.), one command per job. Every
command runs as `scripts/marola-tools <command> [args]`, which sets `MAROLA_ROOT` and lets
scala-cli compile on first use (`--help` lists them). CI runs the gates on every PR:
`scala-cli test tools` (compiled with `-Werror`) and `scala-cli fmt --check tools`
([`.scalafmt.conf`](.scalafmt.conf)).

| Command | What it does |
|---|---|
| `citation` | Validate `.zenodo.json` and regenerate `CITATION.cff` and the READMEs' citation block (MIP-0079) |
| `mip-graph` | Write the MIP dependency graph into `docs/MIPs/README.md` |
| `doc-links` | Rewrite one repo's README and `docs/**` links for its mount on the docs site (MIP-0074) |
| `site-links-check` | Every docs.marola.dev link in a built site resolves, and no old sitemap URL was dropped |
| `strip-external-scripts` | Drop third-party `<script src>` from generated API docs (MIP-0044 §5.6) |
| `mermaid-font` | Pin the font Kroki measures Mermaid labels in, in every `.md` under each `DIR` (#511) |
| `routing-bundle` | Assemble the files ci.yml's graph job publishes to `routing-bundle` (MIP-0084 §5.3) |
| `claude-project-check` | `CLAUDE_PROJECT.config.md` has its sections and routines, and leaks no id |
| `repo-stats` | The README badges as shields.io endpoint JSON |
| `reading-time` | Each README's reading time: print it, `--write` the badge line, or `--check` it is current |
| `graph-svg` | Draw the routing-bundle `graph.json` as the README's graphify figure (an SVG) |
| `arxiv-digest` | Cache arXiv papers relevant to marola's forecasting domain (MIP-0019) |
| `awesome-agentic-digest` | Cache GitHub repo candidates for AWESOME-AGENTIC-ENGINEERING.md (MIP-0043) |
| `agents-repos-check` | AGENTS.md links the routing table instead of restating it (MIP-0074 §7) |
| `prepare-docs` | Build docs.marola.dev's source tree in `.tmp/docs-aggregated` (MIP-0074 §5.3) |
| `fetch-api-docs` | Unpack a repo's `api-docs` branch into a directory (MIP-0074 §5.2) |
| `mkdocs` | Build or serve the documentation site in its container (MIP-0064) |
| `figure` | Draw one Archify source as a PNG in picasso's look |
| `pointer-sync` | Move every submodule pointer to its default branch's tip, for pointer-sync.yml (MIP-0070 §5.6) |
| `pointer-sync-merge` | Squash-merge pointer-sync.yml's rolling PR once it is green and in scope (#739) |
| `release` | Tag `main` as `vX.Y.Z` for a Zenodo version, or `--guard` a tag (MIP-0079) |
| `site-data-push` | Push the commit a workflow made on marola-site's `site-data` branch, retrying on a race |
| `gh-billing` | This GitHub account's metered usage: Actions, Packages, Copilot |

## Why Scala 3

[AGENTS.md](../../AGENTS.md) makes it a hard rule: *the umbrella's own code is Scala 3, and only
Scala 3 — no Python, no Rust* (the owner, 2026-11-14, [#942](https://github.com/marola-dev/marola/issues/942)).
Before that rule the tooling was a Rust crate plus a few dozen shell scripts, three languages with
three toolchains and three sets of failure modes; [#943](https://github.com/marola-dev/marola/pull/943)
replaced them with this one project. One language means one way to read every step of a workflow,
one test framework, one formatter, and gates that CI can run in one command. scala-cli keeps the
feedback loop of a script — `scripts/marola-tools <command>` compiles what changed and runs it —
while the compiler's `-Werror`, `-Wunused` and `-Wvalue-discard` turn the carelessness a shell
never notices into a build failure. The shell stays only for one-line glue: a workflow `run:`, a
justfile recipe, the [`scripts/marola-tools`](../scripts/marola-tools) wrapper.

## The style: FP principles, as this code practices them

Scala was built to put functional programming and object-oriented programming in one language, and
the rules in [AGENTS.md](../../AGENTS.md#scala-3-tooling-hard-rule) are that idea, applied to repo
tooling rather than a server:

- **Immutable values, pure logic.** The model files (`ci/Model.scala`, `docs/SiteModel.scala`,
  `submodules/Model.scala`, `routing/Model.scala`, `publishing/Model.scala`) hold the domain as
  case classes, enums and opaque types, and their functions take values in and return values out —
  no `var`, no `return`, no mutation in logic. A test can call any of them with a literal and know
  the answer before it runs.
- **The domain in types.** A commit id is `Sha`, not a string that happens to be a sha
  (`shared/Sha.scala`); a repo is `RepoSlug`; a PR number is `PrNumber`; a check's outcome is the
  enum `Conclusion`, whose `passed` and `ran` are the only places that know what the Actions API's
  words mean. Illegal states — a malformed sha, a non-GitHub remote asked for a compare link — are
  `Either`/`Option` values, not crashes downstream.
- **Total functions over sum types.** Every `match` over an enum covers its cases; adding a
  `Conclusion` case breaks the build where the meaning is decided, not where it is read.
- **IO only at the edge.** Each command is one object with one `@main`-shaped entry point that
  reads the process (env, cwd, the clock, the command line) once into a config value —
  `shared/Invocation.scala` — runs its pure core, and lets `shared/Proc.scala` talk to git, gh and
  the filesystem. Nothing in the middle reaches for `sys.env` or `os.pwd` on its own.
- **Direct style, no effect system.** Composition is `map`, `flatMap` and `for` over `Either` and
  `Option`, on os-lib, upickle and mainargs. Effects are real but rare and pushed to the edge, so
  the code reads top to bottom; a monad transformer to save one `Either` would cost more than it
  saves.
- **No magic strings, no implicit configuration.** A name another file depends on is a constant
  (`shared/GitHubNames.scala`, `ReposDoc.WiringStart`, `RollingPr.Branch`); env, defaults and the
  cwd are read once at the edge into the config, never deep in a helper.

The layout follows [MIP-0076](../docs/MIPs/MIP-0076-code-graph-and-routing.md): one bounded context
per directory (`ci`, `docs`, `publishing`, `routing`, `submodules`, and the `shared` kernel they all
read), a model file with its pure logic, adapters that decode gh, git and files into the model, one
command object per command, and **its munit suite at the same path under [`tools/test/`](test/)** —
that mirror is the rule; a command without a suite beside it is not done.

## Scala, and where it came from

Scala is Martin Odersky's language, and its design is his career's argument in one place. His PhD
at ETH Zürich was under Niklaus Wirth — Pascal's creator — which is where the conviction comes from
that a language should be small, principled and grow by libraries rather than special cases. As a
researcher in the 1990s he co-designed **Pizza** (with Philip Wadler) and then **GJ**, Generic
Java; GJ is what Java 5's generics shipped as in 2004, and Odersky wrote the javac implementation
of it. Scala, first released from EPFL in 2003–2004, was the next step of the same question: if
generics had to be bolted onto Java, what would a language look like with them, and the rest of
functional programming, designed in from the start? The name says the ambition — a *scalable*
language, one small core that grows into whatever a domain needs, userland libraries first.

The two halves of Scala are the two halves Odersky kept saying belong together: objects for
composition of large systems, functions for composition of logic — "objects meet functions", as
[the language's own account](https://www.scala-lang.org/history/) puts it. Teaching is part of the
story too: his Coursera course *Functional Programming Principles in Scala*, opened in 2012, was
one of the first MOOCs and walked hundreds of thousands of students through exactly the style this
repository asks for — immutable values, first-class functions, pattern matching over sum types.
He co-founded Typesafe (now Lightbend) in 2011 to make the ecosystem sustainable, and led the
ten-year rewrite that became **Scala 3** (2021), built on the DOT calculus — the first version of
Scala with a logical foundation proved rather than patched — with enums, opaque types, `given`s and
optional braces, several of which this project leans on. The languages that "won" the argument
borrowed from it: Java's own later evolution (lambdas, records, pattern matching) is the mainstream
reaching the place Scala had already built, which is Odersky's quiet point when he says Scala's job
was to push the ideas over the wall.

That is why the umbrella's tooling reads the way it does: a language designed for exactly this mix
— types you can shape to the domain, pure functions you can reason about, scripts that still run
like scripts — from the person who spent a decade putting functional programming into Java and then
asked what it would be without the compromise.

## Adding a command

1. Pick the context it belongs to, or `shared` if every context would read it.
2. A model file for the types and pure logic, an adapter that decodes the process output into the
   model, one command object whose entry point takes an `Invocation` and returns the exit code.
3. Its suite under `tools/test/<context>/`, named after it.
4. The row in [`Main.scala`](src/Main.scala)'s `commands` — its one-line help is `--help`'s.
5. `scala-cli test tools && scala-cli fmt --check tools`, then the usual gates in a PR.
