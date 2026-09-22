---
paths: ["**/*.scala", "build.sbt"]
---

# Scala style and testing

Full detail excerpted from `AGENTS.md`'s "Code style" section: it lives here so it loads only
while you're actually touching Scala; `AGENTS.md` keeps a one-line pointer for non-Claude tools
that don't support path-scoped rules.

A few of the rules below are cross-checked against a reference tagless-final Cats Effect 3
backend (not a dependency, not tracked in this repo); its effect *mechanism* doesn't transfer to
Kyo's direct style, but its layering, construction, failure-typing and test discipline do. Import
style: `.scalafix.conf`'s `groupedImports = Merge` is this repo's own convention, followed as
written.

## Effects and purity

- Scala 3.9, direct style preferred over deeply nested combinator chains. Kyo's own docs recommend
  enabling these compiler flags (already set in `build.sbt`; don't remove them): `-Wvalue-discard`,
  `-Wnonunit-statement`, the matching `-Wconf` promotion to error, and `-language:strictEquality`.
- Keep pure business logic (e.g. `Swimability`'s scoring) as plain functional Scala with no effect
  type. Reserve Kyo (`Sync`, `Abort`, `Env`) for the I/O boundary: HTTP calls to
  Foundry/Overpass/Open-Meteo, file/database reads, the Telegram polling loop. This keeps the
  decision logic trivially testable without a Kyo runtime.
- **One trait per pluggable capability; the implementation class is never the type a caller
  depends on.** `trait X[F[_]]` (or, in marola's direct style, no `F[_]`) holds the methods, a
  companion `object X: def apply(...) = XImpl(...)` is the single entry point, a case class does
  the work, never a second overload. marola already follows this for `LlmClient`, `VisionClient`,
  `SightingStore`, `Tracing` and `RunLedger`; keep it when adding an integration, and keep the
  *caller* typed to the trait: `AppConfig.llmClient` returns `Option[LlmClient]`, never
  `Option[LocalLlmClient]`.
- **Inject the clock, the exporter and the transport; don't reach for a global.** The repo's own
  precedent: `MlflowRunLedger`'s `now: () => Long = () => System.currentTimeMillis()`
  (`local/src/main/scala/marola/ledger/MlflowRunLedger.scala:52`) and `Recommender`'s
  `today: ZoneId => LocalDate = LocalDate.now(_)` (`core/src/main/scala/marola/Recommender.scala:38`).
  A new component that reads the wall clock, the filesystem or an env var takes it as a parameter
  with a real default, never a bare `System.currentTimeMillis()`/`LocalDate.now()` inline.

## Expected failures

- Prefer `enum` + exhaustive pattern matching over exceptions for expected failure modes. Reserve
  thrown exceptions for genuinely unexpected faults.
- **Until the `Abort[E]` migration lands, a thrown domain failure still needs a root type and a
  message.** marola throws today (`.scalafix.conf` keeps `noThrows = false` for exactly this
  reason), but its six domain exceptions share no ancestor and no convention: `Http.HttpError`
  (`core/src/main/scala/marola/http/Http.scala:24`), `JsonValue.JsonParseException`
  (`core/src/main/scala/marola/json/Json.scala:89`), `LlmClient.NoCompletionException`
  (`core/src/main/scala/marola/llm/LlmClient.scala:21`), `Reviewer.MalformedReviewException`
  (`core/src/main/scala/marola/llm/Reviewer.scala:27`), `AzureVisionClient.NoCaptionException`
  (`azure/src/main/scala/marola/vision/AzureVisionClient.scala:23`),
  `RouteFinder.RouteNotFoundException`
  (`azure/src/main/scala/marola/beaches/RouteFinder.scala:26`), plus a bare `RuntimeException` at
  `local/src/main/scala/marola/ledger/MlflowApi.scala:51`. The fix is one root:
  `trait MarolaException extends Throwable with NoStackTrace: def message: String;
  override def getMessage() = message`, with a per-layer family extending it (one sealed trait per
  layer: HTTP, LLM, vision, routing), each case a `case object`/`case class` carrying its own
  `message`. Give a new marola failure that shape: name it for the
  layer it comes from, extend a common root (introduce a `MarolaException` when you add the next
  one rather than growing a seventh orphan), and mix in `NoStackTrace`: these are control flow, not
  crashes, and nothing ever reads the stack trace.
- **Never catch `Throwable` at a boundary without saying what that boundary does with each case.**
  marola wraps in `Abort.catching[Throwable]` in 15+ places (`cli/src/main/scala/marola/Main.scala`
  lines 176/215/258/322/338/364/380, `core/src/main/scala/marola/Recommender.scala:60` and `:83`,
  `core/src/main/scala/marola/location/IpGeolocation.scala:105`,
  `cli/src/main/scala/marola/bench/BenchmarkLedger.scala:80`/`:88`/`:100`) and then throws the value
  away with a single `case _ =>`. Narrow explicitly and *totally*, once per boundary: list every
  domain failure a boundary expects in a `given`/total `match`, `case e => ...` as the last arm, not
  the only one, and log the original before mapping it. When you add an `Abort.catching` site, write
  the arms for the failures you actually expect *first*; a catch-all is the last case, not the only
  one.
- **Recover at the layer that knows what "no data" means, and leave the reason in the code.** Narrow
  to exactly the failure you understand and rethrow everything else, rather than swallowing at the
  call site. marola's good examples already read this way:
  `Recommender.fetchWaterQuality` (`core/src/main/scala/marola/Recommender.scala:53`) maps any
  failure to "no water data for every beach" *and documents that decision in the scaladoc*. Keep the
  comment with the recovery.

## Constructors

- **One constructor per type. No auxiliary `def this(...)`, no second private factory that builds
  the same object a different way.** Scala inherits Java's several-constructors idiom, and it loses
  the answer to "which one is *the* one": a reader has to diff parameter lists to learn which fields
  a given path leaves defaulted, and a test that reaches for the short constructor quietly stops
  exercising what production actually builds. Verified across `core/`, `local/`, `azure/`, `cli/`
  (2026-09-07): marola has **zero** `def this(...)` constructors, zero `apply` overloads and zero
  private companion factories; keep it that way. The two classes with a `private` primary
  constructor are the shape to copy, not to avoid:
  - `MlflowTracing` (`local/src/main/scala/marola/observability/MlflowTracing.scala:38`):
    `final class MlflowTracing private (tracer, current)` plus two *named* companion factories that
    say what each is for: `apply(trackingUri, experimentPrefix): MlflowTracing < Sync` (`:100`, the
    production path, effectful because it resolves the MLflow experiment id over REST first) and
    `withExporter(exporter: SpanExporter)` (`:111`, the pure seam `MlflowTracingSpec` injects an
    in-memory exporter through). `apply` *delegates to* `withExporter` (`:108`), so exactly one line
    in the repo calls `new MlflowTracing`; that delegation is what makes two factories fine rather
    than two constructors.
  - `AzureMonitorTracing` (`azure/src/main/scala/marola/observability/AzureMonitorTracing.scala:36`):
    private primary constructor, one `apply(connectionString)` (`:55`) doing the
    `AutoConfiguredOpenTelemetrySdk` wiring.

  So: when an invariant must hold before the object exists (an exporter is configured, an id was
  resolved), make the primary constructor `private` and expose named factories on the companion,
  each delegating to the one construction site. When there is no invariant, leave the single public
  constructor alone and don't add a companion `apply` that merely forwards to it. Every module here
  has exactly one `object X: def apply(...)` entry point and one impl class, never a second
  overload.
- **The rule applies to entry-point methods too, and marola does break it there.**
  `Recommender.bestHoursTomorrow` (`core/src/main/scala/marola/Recommender.scala:32`),
  `bestPerBeachTomorrow` (`:96`) and `scoreDays` (`:120`) each repeat the same five defaulted
  parameters (`radiusKm = 15.0, beachLimit = 6, distanceRefiner = None, waterQuality = None,
  today = LocalDate.now(_)`), with `scoreDays` adding `days = 2`. `bestPerBeachTomorrow` is
  `bestHoursTomorrow` plus a regroup; `scoreDays` re-implements its body. Three near-identical
  parameter lists is the multiple-constructor problem in method form: change a default in one and
  not the others and you get a silent behaviour split between the CLI, the site builder and the MCP
  server. **Known example, deliberately left as-is**: all three are public API called from
  `Main`, `SiteBuilder` and `SwimConditionsMcpServer`, so collapsing them into one options case
  class plus thin wrappers is a real refactor with its own tests, not a rules-file drive-by. Don't
  add a fourth overload to this family; extend the existing one or propose the collapse.

## Types

- **Wrap domain scalars in a type instead of passing bare `Double`/`Int`/`String`.** A newtype per
  domain value (a smart constructor rejecting invalid values, arithmetic defined as an extension so
  it stays in the type) means two values of the same underlying primitive can't be swapped at a call
  site. marola's zero-dependency equivalent is Scala 3 `opaque type`,
  already specified in `docs/SCALA3-JDK-REVIEW.md` §2.1 (`Km`, `Celsius`, `Score`, landing in
  `core/model/Units.scala`) and still unbuilt: `Coordinates(lat, lon)`
  (`core/src/main/scala/marola/model/Models.scala:5`) takes any two `Double`s, so swapping lat and
  lon compiles. When you touch `Models.scala` or add a numeric field, prefer the opaque alias over a
  bare primitive and put the smart constructor beside it.
- **A secret's type should refuse to print it.** A newtype overriding `toString()` to a fixed
  redacted string means no logging path can leak it. marola redacts at one call site instead:
  `AppConfig.redacted`
  (`cli/src/main/scala/marola/AppConfig.scala:129`), added after `FABLE_REVIEW` C1 caught the raw
  `toString` echoing every key to stdout. That fix is correct but not structural: a new
  `Option[String]` secret added to `AppConfig`'s 32 fields prints in the clear until someone
  remembers `redacted`. A `Secret` opaque alias with a redacting `toString` is the cheap upgrade
  once the opaque-type work above lands.
- **An enum that crosses a wire or a disk gets an explicit label, never `toString`/`ordinal`.** Pair
  it with `val label: String` and a total `fromLabel: String => Option[T]`, and build the wire/disk
  codec from that pair; renaming a case can't silently change the persisted value. Both of marola's
  sighting stores do the opposite: they write
  `sighting.kind.toString` and read it back with `SightingKind.values.find(_.toString == kindStr)`
  (`azure/src/main/scala/marola/sightings/CosmosDbSightingStore.scala:52`/`:79` and
  `local/src/main/scala/marola/sightings/LocalFileSightingStore.scala:52`/`:66`), so renaming a
  `SightingKind` case orphans every stored row in both backends. Add the `label`/`fromLabel` pair
  when you next touch a persisted enum.

## Modules

- **`local/` having zero Azure SDK dependency is an invariant that today only a comment enforces**
  (`build.sbt`, the `lazy val local` block). The mechanical version is cheap: the sbt plugin
  `sbt-explicit-dependencies` supplies `undeclaredCompileDependenciesTest` and
  `unusedCompileDependenciesTest`, which fail the build when a module compiles against something it
  doesn't declare. Re-read that block before adding any dependency to `core/` or `local/`; wiring
  that plugin into `just quality` is a legitimate small PR, not scope creep.

## Testing

- **Testing discipline** (adapted from [Kyo's own `AGENTS.md`](https://github.com/getkyo/kyo);
  worth matching since this repo depends on Kyo directly): reproduce a bug with a failing test
  before fixing it, and keep that test afterward as a regression guard; when a test and the code
  disagree, diagnose which one is actually wrong before changing either; don't reflexively "fix"
  the test to match broken code; assert concrete expected values on behavior, not on
  implementation details, and cover edge cases deterministically rather than relying on one happy
  path. (Unlike Kyo's guide, this repo does track deferred work explicitly, in
  `docs/FUTURE-WORK.md`; that's a real, load-bearing doc here, not a banned excuse.)
- **Hand-write test doubles as instances of the trait; no mocking library.** A double that's just an
  instance of the trait (a no-op, a switchable stub, a recorder) beats a mocking framework every
  time. marola already has `Recording extends Tracing` and `Inner extends LlmClient`
  (`core/src/test/scala/marola/llm/TracedLlmClientSpec.scala:22` and `:39`) and
  `Flaky extends Http.Transport` (`core/src/test/scala/marola/http/HttpSpec.scala:61`). Make the
  double *record* what it saw (`spans`, `seen`, `sent`) so the test can assert on the interaction,
  not only the return value.
- **Assert the exact failure, never just "it failed."** Match the specific expected case and fail
  loudly on anything else (`case Left(SpecificError(_, _)) => success` /
  `case e => failure(s"Unexpected: $e")`), and split near-identical failure modes ("wrong
  credentials" / "wrong role" / "no such user") into separate tests with distinct expected errors,
  which is what actually proves a "doesn't leak which part was wrong" property, not just "it
  failed." munit's spelling is `intercept[...]`, already used at
  `core/src/test/scala/marola/llm/SummarizeFlowSpec.scala:83` and `:92`; prefer it to
  `assert(result.isLeft)`.
- **One named fixture per suite, not setup copy-pasted per test.** One `type Env` plus a single
  builder that assembles the whole object graph, reused identically across every suite in the same
  shape, with real per-test isolation rather than shared mutable state. marola's analog is the
  `private def run[A](effect: A < Sync) = Sync.Unsafe.evalOrThrow(effect)` helper each spec defines
  (`TracedLlmClientSpec.scala:19`) plus `Http.withTransport` for fixture replay. Keep new suites to
  that one-helper shape, and remember `Test / parallelExecution := false` in `build.sbt` exists
  because `Http.withTransport` is process-wide.
- **Property tests where a real invariant exists, not everywhere.** Keep them in separate
  `*Props.scala` files, each with a named generator and one stated invariant: a state machine that
  must reach its terminal state, an arithmetic relationship that must hold across every generated
  input. marola has none, and
  `docs/SKILLS.md` already names the obvious target: `Swimability.score` never leaves [0,100], and a
  strictly worse wave height never raises the score. ScalaCheck would be a new dependency; propose
  it, don't slip it in.

## Kyo and the JDK

- **Kyo is pre-1.0** (currently `1.0.0-RC5`) with no version-specific published docs; when unsure
  of an API, verify against the actual jar (`javap` on the decompiled class) rather than guessing
  from `getkyo.io`'s latest-version docs, which can silently drift from what's pinned. See
  `docs/FUTURE-WORK.md` §2-3 and `docs/EFFECTS-MAP.md` for examples of this verification approach.
- **JDK 25 is required, not just "17+".** Scala 3.9 itself only needs JDK 17+, but Kyo 1.0.0-RC5
  compiles with `-release 25` and its artifacts won't load on an older JVM. This already broke a
  real build with `UnsupportedClassVersionError` on a JDK-24 runtime. `flake.nix` pins JDK 25. If
  you're compiling from an IDE (IntelliJ, etc.) rather than a terminal, check the IDE's own Project
  SDK setting separately; it does not automatically follow the Nix devShell's JDK, and a mismatch
  there produces the same class-file-version error even when `nix develop` itself is correctly
  configured.
</content>
