# marola — FP safety map: what's pure, what's effectful, what's hidden

A layer-by-layer map of every module's actual purity/effect status, done as part of a Scala 3/
ergonomics review (see git history around this file's addition). The goal isn't "everything should
be maximally effect-tracked"; it's finding the places where the *type signature lies about what
the function actually does*, since those are the ones worth fixing regardless of how much of the
rest gets migrated to richer Kyo effects.

## How to read the table

- **Pure**: deterministic, no I/O, referentially transparent. Safe to call anywhere, test without
  a runtime.
- **`< Sync`**: Kyo-tracked, genuinely blocking I/O. The type signature is honest: a caller sees
  `< Sync` and knows this does real work.
- **Unsafe boundary**: deliberately escapes Kyo's effect tracking (`Sync.Unsafe.evalOrThrow`,
  `AllowUnsafe`), for a specific, documented, contained reason.
- **Hidden effect** ⚠️: the type signature says "pure" (no effect type at all) but the function
  actually does something effectful (reads mutable external state, can throw on bad input).
  **This is the category worth fixing**; see §2.

| Module | Classification | Notes |
|---|---|---|
| `scoring/Swimability` | Pure | No I/O, fully deterministic, the reason it's the only module with real unit tests (`SwimabilitySpec`) |
| `model/Models` | Pure (data only) | Plain case classes/enums, no logic |
| `json/Json.scala`'s `render`/builders | Pure | `JsonValue.obj`/`arr`/`str`/`render` — deterministic, total (never throws) |
| `json/Json.scala`'s `parse` | Hidden effect ⚠️ (partial) | Throws `JsonParseException` on malformed input — see §2 |
| `http/Http.scala` (all 4 methods) | `< Sync` | Honest — every caller sees `< Sync` and knows real network I/O happens |
| `beaches/BeachFinder` | `< Sync` | Composed from `Http`, correctly propagates |
| `conditions/OpenMeteoClient` | `< Sync` | Same |
| `water/WaterQualityMatcher`, `scoring/Swimability.waterVerdict`, `conditions/Tides`, `lore/SeaLore.pick` | Pure | MIP-0001's logic; all unit-tested |
| `water/ImaScWaterQualityClient.samplingPoints`, `knowledge/OllamaEmbedder.embed` | `< Sync` | HTTP via `Http` |
| `knowledge/FileKnowledgeStore` | `< Sync` | File I/O and embedding wrapped in `Sync.defer`/`Embedder`; `Corpus.chunkDocument`/`cosine` are pure |
| `lore/SeaLore.loadDefault` | Hidden effect ⚠️ (minor) | Classpath read with no effect type — same class as `CompiledPrompt.loadFromFile` above |
| `location/IpGeolocation.locate` | `< Sync` | Same; each provider call is individually `Abort.catching`-wrapped so a dead provider drops out of the vote. `consensus` (the vote itself) is pure and unit-tested |
| `llm/LocalLlmClient`, `vision/*Client` | `< Sync` | Same |
| `llm/CompiledPrompt.loadFromFile`/`loadFromString` | Hidden effect ⚠️ (I/O + partial) | `loadFromFile` reads a file with **no effect type at all** — not even `< Sync`. `loadFromString` throws on malformed JSON. See §2 |
| `llm/Reviewer.review` | `< Sync` | Correctly tracked; the `JsonValue.parse` it calls internally is where a hidden partiality lives (see above) |
| `sightings/LocalFileSightingStore` | `< Sync` | Correctly tracked, including proper `try/finally` resource cleanup for file handles |
| `observability/Telemetry` | `< Sync` (shallow) | `withSpan` is honest about being `< Sync`, but see `ARCHITECTURE.md` §5f's own documented gap: no try/finally around the wrapped effect, so a thrown exception mid-span leaves it unclosed |
| `AppConfig.fromEnv` | **Hidden effect ⚠️** | See §2 — the single most consequential finding here |
| `agent/SwimConditionsMcpServer` | Unsafe boundary (contained) | See §3 |
| `Recommender.traverse`/`traverseSingle`, `Main.printLines` | Pure control flow over `< Sync` values | Hand-rolled recursion, not stack-safe for very large lists — a non-issue at marola's actual list sizes (a handful of beaches), worth knowing if that ever changes |

## 2. The real finding: `AppConfig.fromEnv` is a hidden effect

```scala
object AppConfig:
  def fromEnv: AppConfig = AppConfig(
    telegramBotToken = sys.env.get("MAROLA_TELEGRAM_BOT_TOKEN"),
    ...
  )
```

This reads **mutable external process state** (environment variables can differ between calls in
principle, and definitely differ between processes/test runs) through a method with no effect type
at all. Its signature (`AppConfig.fromEnv: AppConfig`) looks exactly like a pure function. Called
fresh in multiple places (`Main.bootstrap`, `SwimConditionsMcpServer.recommendationHandler` calls
it per-request), each call is a real, untracked side effect.

**Why this matters more than it looks like it should:** every other I/O boundary in this codebase
(§ table above) correctly wears its effect on its sleeve via `< Sync`. `AppConfig.fromEnv` is the
one place that doesn't, and it's called from nearly everywhere. A reader scanning function
signatures to find "where does this touch the outside world" will miss it entirely.

**The fix, if this gets migrated:** thread `AppConfig` through Kyo's `Env[AppConfig]` effect instead
of calling `fromEnv` ad hoc:

```scala
def runRecommendation(args: Array[String]): Unit < (Async & Env[AppConfig]) =
  for
    config <- Env.get[AppConfig]
    ...
```

with the actual `sys.env` read happening exactly once, at `KyoApp`'s boundary, via
`Env.run(AppConfig.fromEnv)(bootstrap(args))`. This is a real, contained refactor (touches every
function that currently takes `config: AppConfig` as a plain parameter, changing it to read from
`Env` instead), not done here, flagged as the top candidate if/when this codebase adopts more of
Kyo's effect-tracking beyond the I/O boundary.

### The smaller version of the same problem: exceptions as untracked failure channels

`JsonValue.parse`, `CompiledPrompt.loadFromString`, `LlmClient.extractContent`, and several others
throw a specific exception type on bad input rather than returning `Either`/`Abort[E]`. Every call
site currently wraps the *outermost* effectful call in a broad `Abort.catching[Throwable]` (see
`Main.scala`'s `summarizeTop`/`analyzePhoto`/`reportSighting`), which works, and is honest about
"something in here can fail," but loses the specific failure type. A stricter version would have
each function declare its own `Abort[JsonParseException]`/`Abort[NoCompletionException]` and let
Kyo's effect system compose the union automatically. Lower priority than the `AppConfig` finding
above. The current broad-catch pattern is simple and was live-verified working (see `Main.scala`'s
own comment on why `loadCompiledPrompt` had to move inside the `Abort.catching` block after a real
bug), but worth naming as the same class of gap.

## 3. The one deliberate unsafe boundary: `SwimConditionsMcpServer`

```scala
private given unsafe: AllowUnsafe = AllowUnsafe.embrace.danger
private def runSync[A](effect: A < Sync): A = Sync.Unsafe.evalOrThrow(effect)
```

This is Kyo's own documented escape hatch, used for exactly the reason it exists: the MCP Java
SDK's tool-handler API (`BiFunction<Exchange, CallToolRequest, CallToolResult>`) is a plain
synchronous Java callback, not a Kyo-aware one, so there's no way to hand it a `< Sync` value
without unwrapping it first. This is the *correct* place for `AllowUnsafe`: a foreign-callback
boundary, contained to one file, with the reason documented right there in the code and again in
`ARCHITECTURE.md` §5c. Not a finding to fix; a pattern to recognize as legitimate when it shows up
elsewhere for the same reason (a future Telegram SDK's callback API, if it turns out to have the
same shape).

## 4. Resource lifecycle — not yet a problem, but worth naming

A client built on first use and never closed is a non-issue for a CLI process that runs once and
exits: the OS reclaims everything on
exit. It becomes a real question once marola runs as a long-lived service (the Telegram bot, Phase
1) that might reconfigure or reconnect: Kyo's `kyo.Scope` effect (acquire/release, seen in the
`kyo-core` dependency already) is the natural fit for that later, not needed now.

## 5. Net assessment

Nothing here is unsafe in the memory/concurrency sense. This is a Scala/JVM codebase with no raw
mutation escaping module boundaries (the one `var` in `Json.scala`'s recursive-descent parser is
fully encapsulated in a private class). The gap is specifically the FP-purity sense: one function
(`AppConfig.fromEnv`) whose type signature hides that it does I/O, and a handful of functions that
can throw without saying so in their type. Both are real, both are fixable with Kyo's own `Env`/
`Abort` effects, and neither is urgent enough to have blocked shipping the features this review
accompanied. Captured here as the concrete next step for whoever picks up
`FUTURE-WORK.md` §2's kyo-http/kyo-schema migration, since that's the natural moment to also
tighten these two effect boundaries.
