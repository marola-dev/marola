---
paths: ["**/*.scala", "build.sbt"]
---

# Scala style and testing

Full detail excerpted from `AGENTS.md`'s "Code style" section — it lives here so it loads only
while you're actually touching Scala; `AGENTS.md` keeps a one-line pointer for non-Claude tools
that don't support path-scoped rules.

- Scala 3.9, direct style preferred over deeply nested combinator chains. Kyo's own docs recommend
  enabling these compiler flags (already set in `build.sbt` — don't remove them): `-Wvalue-discard`,
  `-Wnonunit-statement`, the matching `-Wconf` promotion to error, and `-language:strictEquality`.
- Keep pure business logic (e.g. `Swimability`'s scoring) as plain functional Scala with no effect
  type. Reserve Kyo (`Sync`, `Abort`, `Env`) for the I/O boundary — HTTP calls to
  Foundry/Overpass/Open-Meteo, file/database reads, the Telegram polling loop. This keeps the
  decision logic trivially testable without a Kyo runtime.
- Prefer `enum` + exhaustive pattern matching over exceptions for expected failure modes. Reserve
  thrown exceptions for genuinely unexpected faults.
- **Testing discipline** (adapted from [Kyo's own `AGENTS.md`](https://github.com/getkyo/kyo) —
  worth matching since this repo depends on Kyo directly): reproduce a bug with a failing test
  before fixing it, and keep that test afterward as a regression guard; when a test and the code
  disagree, diagnose which one is actually wrong before changing either — don't reflexively "fix"
  the test to match broken code; assert concrete expected values on behavior, not on
  implementation details, and cover edge cases deterministically rather than relying on one happy
  path. (Unlike Kyo's guide, this repo does track deferred work explicitly, in
  `docs/FUTURE-WORK.md` — that's a real, load-bearing doc here, not a banned excuse.)
- **Kyo is pre-1.0** (currently `1.0.0-RC5`) with no version-specific published docs — when unsure
  of an API, verify against the actual jar (`javap` on the decompiled class) rather than guessing
  from `getkyo.io`'s latest-version docs, which can silently drift from what's pinned. See
  `docs/FUTURE-WORK.md` §2-3 and `docs/EFFECTS-MAP.md` for examples of this verification approach.
- **JDK 25 is required, not just "17+".** Scala 3.9 itself only needs JDK 17+, but Kyo 1.0.0-RC5
  compiles with `-release 25` and its artifacts won't load on an older JVM — this already broke a
  real build with `UnsupportedClassVersionError` on a JDK-24 runtime. `flake.nix` pins JDK 25. If
  you're compiling from an IDE (IntelliJ, etc.) rather than a terminal, check the IDE's own Project
  SDK setting separately — it does not automatically follow the Nix devShell's JDK, and a mismatch
  there produces the same class-file-version error even when `nix develop` itself is correctly
  configured.
