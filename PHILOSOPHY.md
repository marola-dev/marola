# PHILOSOPHY.md — why marola is built the way it is

`AGENTS.md` holds the rules; this file holds the reasons. It exists because the choices below
look unrelated from the outside — a beach app, LLMs, a Scala 3 build, Nix, a `justfile`, a
sandbox for the coding agent, design docs before code — and they are one decision made six times.

## The one decision

**Put the model inside a platform that pushes back.** A language model is a very good writer and a
very poor witness: it does not know what the sea is doing right now, it will state a wave height
with the same confidence whether it read it or invented it, and when it grades its own work it
grades generously. Every piece of marola, and every piece of the tooling around marola, is a
constraint that turns the model's output into something that can be checked before it is trusted.

That is the unpopular part, stated plainly: the best use of an LLM today is not to loosen the
platform so the model can do more, it is to **tighten the platform so the model's mistakes have
somewhere to fail**. A compiler, an exhaustive `match`, a recorded fixture, a deterministic
scoring function, a sandbox, a written design with dated sources — each is a place where a wrong
answer stops instead of shipping. The model then does the part only it is good at: language.

## Why marola

The question "what is the best hour tomorrow to swim nearby?" is small enough to finish and hard
enough to be honest about. It needs live data (Overpass, Open-Meteo, a bathing-water agency), a
decision that can get someone hurt if it is wrong (rough sea, contaminated water, darkness), and a
sentence a person will actually read. That mix is exactly where an LLM alone fails and where an
LLM inside a constrained pipeline is genuinely better than either alone. It also happens to
exercise every AI-103 domain and most of AI-500 (`docs/AI-103-MAPPING.md`, `docs/AI-500-MAPPING.md`)
without inventing a use case for the sake of an exam.

## Why LLMs, and where they are not allowed

Two jobs, and only two: turn a ranked row of numbers into one or two sentences, and answer ocean
questions from a sourced corpus with citations. Everything safety-relevant — the score, the water
veto, the darkness rule, the rough-sea deduction — is plain Scala in `scoring/`, unit-tested,
outside the prompt (`docs/ARCHITECTURE.md` §5a, §8). A second, independently compiled reviewer
pass grades the first model's sentence and can rewrite it. Lore shown to a user is a curated file
with a source per entry, shown verbatim; the model never gets to invent a fact about the sea.
The rule in the `mip` skill says it shortest: *no unsourced text reaches a user*.

## Why Scala 3 on the JVM

Because the compiler is the cheapest reviewer an agent will ever have, and Scala 3 lets that
reviewer be strict without being verbose:

- `-language:strictEquality`, `-Wvalue-discard`, `-Wnonunit-statement` promoted to errors
  (`build.sbt`): a comparison between unrelated types, a dropped result, a statement whose value
  was meant to be used — all compile errors. An agent that writes such code finds out in seconds,
  not in production.
- `enum` + exhaustive `match` for every expected failure mode; exceptions only for the genuinely
  unexpected. A new case the model forgot to handle is a warning-as-error, not a runtime surprise.
- Direct-style Kyo effects (`Sync`, `Abort`, `Async`) only at the I/O boundary, so the decision
  logic is ordinary functions any test can call without a runtime (`docs/EFFECTS-MAP.md`).
- The JVM ecosystem underneath: one build tool, one dependency resolver with pinned versions,
  `javap` on the actual jar when the docs and the code disagree (`AGENTS.md`'s "verify against the
  jar" rule exists because Kyo is pre-1.0 and its docs drift), GraalVM native images, a container
  that is a fat jar and nothing else.

**The Python question.** Python is not short of types: gradual typing since 3.5, mypy and
pyright, and typed code is the norm in modern libraries. The point is where the gate lives. In
Python the annotations are optional and unenforced by the language — the `typing` module's own
documentation opens with "The Python runtime does not enforce function and variable type
annotations. They can be used by third party tools such as type checkers, IDEs, linters, etc."
(docs.python.org, fetched 2026-09-05) — so the check is there by discipline: every contributor,
including the agent, installs the checker, configures it, and never reaches for `Any` to make it
pass; a `match` has no exhaustiveness check; a wrong type surfaces when that line runs. On the JVM
the gate is the build — there by construction — and Scala 3 makes it strict without ceremony
(strict equality, warnings as errors, `enum` with exhaustive `match`, effects at the boundary).
For a backend system that will live for years and be written largely by agents, that is the bet
this repo makes: compile-time safety, one build tool with pinned resolution, and ergonomics that
hold up as the codebase grows beat Python's faster start over the long term. Python keeps the
places where its libraries are the only ones — the offline steps (`dspy/`, `finetune/`) — and
stays out of the runtime path on purpose.

## Why Nix

A dev shell that is the same on every machine is the first constraint an agent meets. `flake.nix`
pins JDK 25 (Kyo's artifacts will not load on 24 — a real `UnsupportedClassVersionError`, not a
hypothetical), sbt on that JDK, scala-cli, coursier, `just`, Python for the offline steps, `az`,
`gh`, hadolint. `nix develop` is the whole setup; there is no "works on my machine" left for the
model to reason about, and no page of install instructions for it to skip. CI, the Docker `dev`
image and the laptop run the same shell.

## Why a `justfile`

Recipes are the agent's vocabulary. `just build`, `just test`, `just quality`, `just benchmark`,
`just e2e`, `just site-build`, `just uprd`, `just cost-split` — each is a short, discoverable
name for a command that would otherwise be reconstructed from memory, slightly differently each
time. `just --list` is documentation that cannot go stale, and a recipe is where the environment
quirks live once (the `XDG_RUNTIME_DIR` override sbt needs inside the sandbox is at the top of the
file, with the reason). When the agent must run something, the question is "which recipe", not
"which flags".

## Why ai-jail

The agent runs in a sandbox (`just jail-claude`, `just jcf`, `just jcs`; bubblewrap/Landlock/
seccomp; policy in `.ai-jail`, which can only tighten). It is containment for the filesystem and
process blast radius: the agent can build, test and push; it cannot read `.env` or a key, and
cannot touch the machine outside the repository. It does not replace the rules that live above it
— no unattended `azd up`, every paid resource behind a human go-ahead — and it does not stop bad
code or spent budget. It is the same idea one layer down: give the mistake a wall to hit.

## Why design docs before code, and a cost line on every PR

A Marola Improvement Proposal (`docs/mips/`) is written before a non-trivial change is built, with
every external claim fetched and dated and every unverified one parked in "Open questions". The
agent is a fast writer of plausible designs; the MIP template forces the plausible to become the
checked. The `Cost:` trailer on every commit and PR (`AGENTS.md`) exists for the same reason at
the meta level: an agent's work is cheap to ask for and not free to run, and a repository that
records what a feature cost in tokens learns what to ask for next. `docs/DEV-FLOW.md` is the loop
end to end: idea → MIP → acceptance → tasks → small stacked PRs, each green on the same gates CI
runs → review only when asked → merge.

## What this is not

- Not a claim that a typed platform makes the model right. It makes the model *checkable*; the
  checks still have to be written (`scoring/`'s tests, the golden pipeline fixtures, the benchmark
  under `docs/benchmarks/`).
- Not anti-Python, anti-cloud or anti-anything. Azure is opt-in per integration and every path has
  a free local default (`docs/ARCHITECTURE.md` §5), because "runs entirely locally with a free
  model" is also a constraint: it keeps the thing testable by anyone, including the agent, without
  a bill.
- Not finished. The honest status vocabulary used everywhere here — *verified live*, *confirmed
  against the jar*, *written, not run*, *not checked* — is the last constraint: the docs are not
  allowed to sound more certain than the code.
