# Scala 3 and JDK review

What the codebase already uses well, what it should adopt, and what to leave alone, judged
against *this* code (Scala 3.9 LTS, Kyo 1.0.0-RC5, JDK 25), not a generic feature list. Each row
names where it would land. "Adopt" items are ordered by payoff-for-effort; the first three are
worth a PR each.

## 1. Scala 3 — in use today

| Feature | Where | Verdict |
|---|---|---|
| Significant-indentation syntax, `then`/`do` | everywhere | Keep. Consistent across all modules. |
| `enum` + `derives CanEqual` | `JellyfishRisk`, `BathingCondition`, `Provider`, `LoreKind`, `Fallback` | Keep. Multiversal equality (`-language:strictEquality`) has already caught two real test bugs this week. |
| Union/intersection types via Kyo's `A < (S1 & S2)` | every effectful signature | Keep — the effect set *is* the type. |
| `given`/`using` (`AllowUnsafe`) | MCP server, tests | Keep, but see §2.5 for the bigger use. |
| Optional braces, `end` markers on long specs | tests | Keep. |
| Strict compiler flags (`-Wvalue-discard`, `-Wnonunit-statement` promoted to errors) | `build.sbt` | Keep; add `-Wunused:all` (§2.7). |

## 2. Scala 3 — adopt

### 2.1 Opaque types for units and ranges (highest payoff)

`Coordinates(lat, lon)` takes any two `Double`s; `Beach.distanceKm`, `HourlyConditions.seaTempC`,
`WaterVerdict.delta`, `score` are all bare numbers. Swapping `lat`/`lon` or passing metres where km
are expected compiles today. Opaque types are zero-cost and make it not compile:

```scala
object units:
  opaque type Km = Double
  object Km:
    def apply(d: Double): Km = d
    extension (k: Km) def value: Double = k
  opaque type Celsius = Double
  opaque type Score = Int   // 0..100, constructed only through Swimability.clamp
```

Land in `core/model/Units.scala`; migrate `Beach`, `HourlyConditions`, `BestHour.score`,
`Recommender.radiusKm`. Pair with Iron refinements (`FUTURE-WORK.md` §6) for the 0-100 and
lat/lon ranges once the aliases exist.

### 2.2 Sum types instead of flag-bags

`WaterVerdict(delta, veto, note, summary)` is four fields encoding five states (MIP-0001 §6). An
`enum` with payloads says which state you're in and makes `match` exhaustive:

```scala
enum WaterVerdict:
  case NoData
  case Stale(newest: LocalDate)
  case Proper(points: Int, sampledOn: LocalDate)
  case Mixed(proper: Int, total: Int, avoid: List[SamplingPoint], sampledOn: LocalDate)
  case Unfit(worst: SamplingPoint, sample: WaterSample, sampledOn: LocalDate)
  def delta: Int = this match ...
```

Same shape applies to `Main`'s `Origin` (flags / env / IP / default) and to `Http.Response`
(success / http-error). `Report` then renders by pattern match rather than reading booleans.

### 2.3 Typed error channels with `Abort[E]`

Every I/O function throws a specific exception and every caller catches `Throwable`
(`FABLE_REVIEW.md`, `SKILLS.md` Stage 6). Scala 3's union types make Kyo's typed errors cheap:

```scala
def getString(url: String): String < (Sync & Abort[HttpError])
def samplingPoints: List[SamplingPoint] < (Sync & Abort[HttpError | JsonParseException])
```

`Recommender.fetchWaterQuality` then handles `HttpError` explicitly and lets a `JsonParseException`
(a real bug) surface. Start with `Http` and `ImaScWaterQualityClient`; the golden test suite makes
the refactor safe.

### 2.4 Named tuples for the `(Int, List[String])` returns

`Swimability.score` and every `*Delta` return `(Int, Option[String])`. Scala 3.7+ named tuples
(standard in 3.9) name the positions without a case class per function:

```scala
def score(hour: HourlyConditions, water: WaterVerdict): (score: Int, notes: List[String])
val r = Swimability.score(h); r.score; r.notes
```

Small, mechanical, and removes every `._1`/`._2` in `Recommender` and the specs.

### 2.5 A `Clock` capability via `using`, instead of a function parameter

`Recommender.bestPerBeachTomorrow(..., today: ZoneId => LocalDate)` was the minimum change to make
the pipeline testable. The idiomatic Scala 3 shape is a contextual capability:

```scala
trait Clock:
  def today(zone: ZoneId): LocalDate
given Clock = zone => LocalDate.now(zone)          // production, in Main
def bestPerBeachTomorrow(...)(using clock: Clock): ...
```

Tests supply `given Clock = _ => LocalDate.of(2026, 9, 5)`. The same pattern replaces
`AppConfig.fromEnv` being called ad hoc (`EFFECTS-MAP.md` §2): `using config: AppConfig`, or Kyo's
`Env[AppConfig]`, read once at the `KyoApp` boundary.

### 2.6 Extension methods on foreign types

`JsonValue` navigation (`json("hourly")("time").arr.flatMap(_.str)`) repeats across five clients.
Extensions keep the hand-rolled JSON but make call sites read like the data:

```scala
extension (j: JsonValue)
  def doubles(key: String): Vector[Option[Double]] = j(key).arr.map(_.num)
  def strings(key: String): Vector[String] = j(key).arr.flatMap(_.str)
```

Also `extension (d: LocalDate) def isWithinDays(other: LocalDate, days: Long)` for the staleness
checks, and an `Ordering[LocalDate]` `given` so `sortBy(_.sampledOn)` works without `toEpochDay`.

### 2.7 Compiler and tooling flags

- `-Wunused:all` and `-Wsafe-init` (3.9): the review found no unused imports by eye. The compiler
  should be doing that. Promote via the existing `-Wconf` rule.
- `-source:future` to get the `for` and given-syntax cleanups early and stop new code using
  deprecated forms.
- Scalafix with `DisableSyntax` (no `var` outside `Json.Parser`, no `null`, no `throw` outside
  `Abort` boundaries): `SKILLS.md` Stage 6 names it; it's a day's work.

### 2.8 `boundary`/`break` for the recursive-descent parser

`Json.Parser` uses `var continue = true; while continue do` loops. Scala 3.3's
`scala.util.boundary` expresses the early exits without the flag variable and stays stack-safe.
Low priority (the parser is tested and small), but it is the one file with mutable control flow.

### 2.9 Leave alone (for now)

- **Macros / `inline`**: nothing here is hot enough; the CLI is bound by Overpass, not the JVM.
- **Match types, dependent function types**: no type-level programming problem exists in marola.
- **`export` clauses**: a `marola.local` facade would save a few imports in `AppConfig`; not
  worth the indirection yet.
- **Capture checking (experimental)**: track when it leaves experimental; the `AllowUnsafe`
  boundary is exactly what it's for, but not on 3.9 LTS.

## 3. JDK — the project is on 25; what 21+ offers this code

| JDK feature | Status | Use in marola | Verdict |
|---|---|---|---|
| **Virtual threads** (21, JEP 444) | final | Every HTTP call blocks a carrier thread inside `Sync.defer`; the six beaches' 12 Open-Meteo calls run *sequentially* (`Recommender.traverse`, hand-rolled). Kyo's `Async` + `Async.foreach` (confirmed present in the pinned jar, `SKILLS.md` Stage 6) run them concurrently; with `-Dkyo.scheduler.virtualizeWorkers=true` (Kyo's virtual-thread worker mode) the blocking `java.net.http` calls stop pinning platform threads. Expected: the 6-beach fan-out drops from ~12× to ~2× one call's latency. | **Adopt** — the biggest single UX win after Overpass caching. |
| **Scoped values** (25, JEP 506, final) | final | `Http.transport` is an `AtomicReference` swapped for tests — a process-wide mutable. `ScopedValue<Transport>` binds the replay transport to the dynamic scope of the test body only, and is inheritable by child virtual threads. | **Adopt** when §3 virtual threads land, so the seam and the fan-out agree. |
| **Structured concurrency** (`StructuredTaskScope`) | still preview in 25 (JEP 505) | Kyo `Async` already gives structured fan-out/cancellation; the JDK version needs `--enable-preview`. | Skip; Kyo covers it. |
| **`HttpClient` is `AutoCloseable`** (21) | final | `Http.Transport.Live` never closes its client; fine for a CLI, wrong for the long-lived Telegram bot (MIP-0002). Wrap in Kyo `Scope`/`Resource`. | Adopt with MIP-0002. |
| **AOT cache / CDS** (24 JEP 483; 25 JEP 514/515) | final | `just run` pays ~1s JVM start + class loading before the first Overpass byte; with the assembled jar, `-XX:AOTCacheOutput`/`-XX:AOTCache` training on one `just run` cuts startup noticeably. Pure ops change, no code. | Adopt for the fat-jar / bot deployment; irrelevant under `sbt run`. |
| **Compact object headers** (25, JEP 519) | final (opt-in) | `JsonValue` trees for a 207KB IMA feed are millions of small objects; `-XX:+UseCompactObjectHeaders` shrinks them ~10-20%. | Nice-to-have flag in the run scripts. |
| **Generational ZGC** (21; default ZGC mode in 23) | final | Low-pause GC for the bot process; not for a CLI. | Later, with MIP-0002. |
| **Sequenced collections** (21) | final | Java interop only — the MCP SDK's `java.util.List`. Scala collections already have `head`/`last`. | No action. |
| **Record patterns / pattern `switch`** (21), **unnamed variables** (22), **primitive patterns** (preview) | final/preview | Java-side language features; Scala has had all of them. | No action. |
| **String templates** | withdrawn (23) | — | Do not use. |
| **Foreign Function & Memory API** (22, JEP 454) | final | The only plausible use: calling llama.cpp in-process for embeddings instead of over HTTP to Ollama. Not worth it while Ollama is the deployment story. | Note for `FUTURE-WORK.md` only. |
| **Stream Gatherers** (24), **Markdown Javadoc** (23), **KDF API** (24), **`synchronized` without pinning** (24, JEP 491) | final | Gatherers/Javadoc: Java-only. JEP 491 matters *for* virtual threads: libraries that `synchronized` (the MCP SDK) no longer pin carriers. | Free benefit once on virtual threads. |

## 4. Suggested order

1. §3 virtual threads + `Async.foreach` fan-out (measure with the golden suite's call count and a
   wall-clock check in `just e2e`).
2. §2.1 opaque units, then Iron.
3. §2.3 `Abort[E]` on `Http` and the two feed clients.
4. §2.2 `WaterVerdict` as an enum; §2.4 named tuples: mechanical, do together.
5. §2.5 `Clock`/`Env[AppConfig]` capabilities.
6. §2.7 flags + scalafix.
7. Scoped values for the transport seam once 1 is in.
