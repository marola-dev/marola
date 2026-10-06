# MIP-0077: Kyo modules at 1.0.0-RC7 — kyo-schema-json replaces the hand-rolled JSON, kyo-mcp replaces the MCP Java SDK, kyo-case-app parses `oods`

| | |
|---|---|
| **Status** | Draft — `Tasks: docs/MIPs/MIP-0077.tasks.md` ([`MIP-0077.tasks.md`](./MIP-0077.tasks.md)) |
| **Author** | Claude (Opus 5.5), from marola-dev/marola-app#55's module review (2026-10-06) |
| **Created** | 2026-10-06 |
| **Phase** | None: library choices inside marola-app, outside `docs/PHASES.md`'s sequence; no paid resource, no user-visible change. Task 5 lands inside MIP-0075's `oods` module and follows that MIP's gating |
| **Related** | marola-dev/marola-app#55 (the review; its verdict table is marola-app's [`docs/2-libraries.md`](https://docs.marola.dev/5-Repos/marola-app/2-libraries/#kyo-modules-at-100-rc7)), MIP-0075 (the `oods` module, §4.5 and §4.6's library picks), MIP-0008 (the native `cli` image), MIP-0033 (the chat server), marola-app `docs/1-design_effects.md` §3 (the MCP server's unsafe boundary) |
| **Effort** | M — five marola-app PRs: one new dependency per module (kyo-schema-json, kyo-mcp, kyo-case-app), 375 `JsonValue` references across 29 files moved to derived codecs, one 264-line server rewritten; no new trait, no new module |
| **Gain** | `infra/dev-loop` — typed models instead of `JsonValue` navigation, the 200-line parser and the MCP SDK's dependency tree (Jackson 3, Reactor, a JSON-schema validator, snakeyaml-engine) go, and the last `AllowUnsafe.embrace.danger` in the repo goes with them |
| **Effort vs Gain** | `cheap win` — each task ships alone behind golden tests already in place; task 5 is `do when X lands` (MIP-0075 task 1, `marola.oods.Main`) |
| **Depends on** | Kyo 1.0.0-RC7, already pinned (`build.sbt`). Task 5: MIP-0075 task 1. No Phase 1 gate. No paid resource |
| **Blocked by** | none |
| **Risk** | A derived codec changes a persisted or published JSON format silently (`BeachSnapshot` v1, the water cache v1, the board's JSON). Task 3 keeps every writer byte-identical against fixtures before `marola.json` goes |
| **Cost so far** | — |

## 1. Summary

marola-dev/marola-app#55 reviewed every Kyo module published at 1.0.0-RC7 against marola-app's
code and the Scala MIP-0075 is about to add, checking each API in the RC7 jar. Three modules
remove real code or a real dependency and fit the effect-boundary rule: **kyo-schema-json** (with
kyo-schema) for every JSON shape marola reads or writes, **kyo-mcp** (with kyo-jsonrpc) for the MCP
tool server, and **kyo-case-app** for `marola.oods.Main`'s subcommands. This MIP plans them as five
stacked marola-app PRs. Everything else was deferred or rejected; the table lives in marola-app's
`docs/2-libraries.md`.

## 2. Motivation

- **JSON is hand-navigated everywhere.** `core/src/main/scala/marola/json/Json.scala` is a 200-line
  recursive-descent parser and writer, and `JsonValue` is referenced 375 times across 29 main
  source files (Overpass, Open-Meteo, Ollama, MLflow, IMA/SC, the snapshot and cache files, the
  board, the MCP and chat servers). Every reader is a chain of `("key").arr.headOption.flatMap(…)`
  whose shape lives only in the code. `docs/2-libraries.md` already recommended the move
  ("migrate as a dedicated task, one integration at a time"); this MIP is that task.
- **The MCP server is the repo's one deliberate unsafe boundary.** `SwimConditionsMcpServer`
  runs every Kyo effect through `AllowUnsafe.embrace.danger` and `Sync.Unsafe.evalOrThrow`,
  because the MCP Java SDK calls back on its own threads (`docs/1-design_effects.md` §3). The SDK
  2.0.0 pulls Jackson 3.0.3, Reactor 3.7.0, `json-schema-validator` 3.0.0 and `snakeyaml-engine`,
  needs `META-INF/services` concatenated in the fat jar, and takes its tool arguments as
  `java.util.Map[String, Object]`.
- **`oods` needs a command line.** MIP-0075 §5.4 gives `marola.oods.Main` six subcommands with
  repeated (`--state UF…`), enumerated (`--mode incremental|backfill`) and numeric flags. `cli`'s
  `Main` parses its flags by `args.indexOf`; copying that into `oods` would be the third parser.

## 3. User-visible change

None. The CLI, the board, the MCP tools' names, inputs and outputs, and every file format stay as
they are; the goldens and `PipelineGoldenSpec` say so. For a contributor:

```scala
// before (OpenMeteoClient)
val hourly = json("hourly"); val times = hourly("time").arr.flatMap(_.str)
// after
final case class Hourly(time: Chunk[String], @rename("wave_height") waveHeight: Chunk[Option[Double]]) derives Schema
Json.decode[Forecast](body)   // Result[DecodeException, Forecast]
```

## 4. Data sources and dependencies reviewed

All read from Maven Central's `io.getkyo` RC7 jars and sources jars on 2026-10-06 (JDK 25 `javap`,
class-file version 69, like the rest of Kyo RC7). Transitive sets are `cs resolve` diffs against
marola's current `kyo-core` + `kyo-direct` + `kyo-combinators`.

- **kyo-schema + kyo-schema-json** (`io.getkyo:kyo-schema-json_3:1.0.0-RC7`). Adds `kyo-schema`
  and `kyo-system`, nothing else. `Json.decode[A](String, Int, Int)(using Json, Schema[A], Frame)`
  returns `Result[DecodeException, A]`, a pure value: no effect, so decoding stays outside the
  Kyo boundary. `@rename(wireName)`, `Schema.renameAllFields(NameCase)`, `Option`/`Map[String, V]`
  schemas; unknown fields are skipped unless `denyUnknownFields` is set. Derivation is macros, with
  no `java.lang.reflect`, `Class.forName`, `ServiceLoader` or `java.lang.foreign` in its sources,
  so the native `cli` image should need no metadata for it (task 1 proves it with `just native-image`).
- **kyo-mcp + kyo-jsonrpc**. Adds `kyo-jsonrpc`, `kyo-schema(-json)`, `kyo-system`, `kyo-net`,
  `kyo-ffi`. `McpHandler.tool[In](name, description)(handler: In => Out < (Async & Abort[…]))`
  derives the input and output JSON Schema from `Schema[In]`/`Schema[Out]`;
  `McpServer.init(transport, handlers*)`; `JsonRpcTransport.stdioWith()` runs the server with
  `Console` output diverted to stderr, the problem marola's `logback.xml` solves by hand today.
  Protocol versions `2025-06-18` and `2025-11-25`. The line-delimited stdio wire runs on Kyo's
  `Console`, not on `kyo-net`, so kyo-net's Panama (FFM) backends load but are not driven. The
  MCP server is a JVM-only main class (`Dockerfile`: `java -cp marola.jar …`), never in the native
  binary.
- **kyo-case-app** (`kyo-case-app_3`, on `com.github.alexarchambault:case-app_3:2.1.0`). Adds
  case-app, `-util`, `-annotations` (class-file 52). `KyoCommand[T]` is a case-app `Command[T]`
  whose `run` takes `T => A < (Async & Scope & Abort[Throwable])`; `CommandsEntryPoint` dispatches
  subcommands and prints help. `oods` is JVM-only (DuckDB's native libraries), so native-image
  does not apply.
- **Not taken**, with the reason in marola-app's table: kyo-http (no HTTP proxy, which MIP-0075
  §4.6 needs; its own HTTP/1.1 stack on Panama io_uring/epoll and BoringSSL is unproven under
  native-image), kyo-stats-otlp (OTLP/JSON over kyo-http), kyo-ai (pulls kyo-http into the native
  binary for a 60-line client), kyo-sql (no JDBC or DuckDB backend), kyo-config and kyo-system
  (global or effectful config for no code removed), kyo-test-* (munit works; revisit with the
  first property test), and the rest rejected.
- **Already pinned, no new module**: kyo-core RC7 has `Meter.initRateLimiter`/`initSemaphore`,
  `Retry[E](Schedule)` and `kyo.Cache`. MIP-0075's `Throttle` (task 13 there) can build on them;
  that is MIP-0075's call, noted here, not a task of this MIP.

## 5. Design

### 5.1 kyo-schema-json in `core` (tasks 1–3)

`kyoVersion` stays; `"io.getkyo" %% "kyo-schema-json" % kyoVersion` joins `baseSettings`. Each
integration gets private wire case classes beside its client (`OpenMeteoClient.Forecast`, …)
with `derives Schema`, decoded once at the boundary into the existing domain types: the domain
model (`Models.scala`) does not derive anything, so a wire rename never reaches scoring. Parse
failures keep today's recovery points (`Recommender.fetchWaterQuality`'s "no data" mapping); a
`DecodeException` becomes the failure those `Abort.catching` sites already narrow to.

- Task 1: the dependency, `OpenMeteoClient` and `IpGeolocation` (read-only, simplest), and a
  `just native-image` run proving the native binary still builds and answers `--brief`.
- Task 2: every other reader (Overpass in `BeachFinder`, `OverpassAccessibilityClient`,
  `TrailFinder`; Ollama in `LlmClient`, `OllamaEmbedder`, `LocalVisionClient`; `MlflowApi`;
  `ImaScWaterQualityClient`; `Reviewer`'s JSON reply).
- Task 3: every writer and file format (`BeachSnapshot`, `CachedWaterQualityClient`,
  `LocalFileSightingStore`, `FileKnowledgeStore`'s index, `Board`/`SiteBuilder`, `ChatServer`,
  `MlflowRunLedger`, `OceanBenchmark`), byte-identical against today's fixtures, then
  `marola.json` deleted. `SightingKind` gets the `label`/`fromLabel` pair `.claude/rules/scala.md`
  asks for, since its codec is rewritten anyway.

### 5.2 kyo-mcp in `cli` (task 4)

```scala
object SwimConditionsMcpServer:
  final case class FindBeachesIn(lat: Double, lon: Double, @rename("radius_km") radiusKm: Option[Double]) derives Schema
  val findBeaches = McpHandler.tool[FindBeachesIn]("find_beaches", "…")(in => BeachFinder.nearby(…).map(toOut))
  def main(args: Array[String]): Unit =
    KyoApp.run(JsonRpcTransport.stdioWith()(t => McpServer.init(t, findBeaches, recommendation, waterQuality, ask).andThen(Async.never)))
```

The four tools keep their names, input fields and output fields (snake_case via `@rename`). The
MCP SDK dependency, its `--initialize-at-build-time=com.fasterxml.jackson` entry and the
`META-INF/services` comment about its validator go; `docs/1-design_effects.md` §3 is rewritten,
since no unsafe boundary is left. The exact `KyoApp` entry and effect rows are checked against the
RC7 jar when written (`.claude/rules/scala.md`).

### 5.3 kyo-case-app in `oods` (task 5)

After MIP-0075 task 1 lands `marola.oods.Main` with its usage and exit code 2, this task turns it
into a `CommandsEntryPoint` with one `KyoCommand` per subcommand, each options type a case class
(`LoadOptions(state: List[String], source: List[String], mode: Mode = Mode.Incremental,
maxMinutes: Option[Int], dryRun: Boolean)`), so MIP-0075 tasks 9, 10 and 16 add commands instead
of parsing. Usage on stderr and exit 2 stay MIP-0075's contract. `cli`'s `Main` is not touched:
its grammar (`--site [area]`, `--report-sighting kind beach [note]`) is positional-optional, and
rewriting it is not needed by anything.

Nothing in this MIP goes through an LLM, and nothing deterministic moves out of `scoring/`.

## 6. Scoring / safety impact

None. `Swimability.score` and its thresholds are not touched; `SwimabilitySpec` and the goldens
pin it.

## 7. Verification plan

- **Unit** (`just test`): the existing `PipelineGoldenSpec`, `BoardSpec`, `BeachSnapshotSpec`,
  `CachedWaterQualityClientSpec`, `HttpSpec` and every client spec pass unchanged, replaying the
  same fixtures. New: `OpenMeteoWireSpec` (a captured forecast decodes; a missing `hourly` is a
  `DecodeException`, not a crash), `JsonFormatsSpec` (every writer's output equals the committed
  v1 fixture byte for byte), `McpToolsSpec` (`tools/list` returns the four tools with today's input
  schemas' required fields; `find_beaches` over a recorded transport returns today's fields), and
  `OodsMainSpec` (MIP-0075's `MainSpec` cases plus `oods load --state SC --state RJ` parsing to two
  states and `--mode nonsense` exiting 2).
- **Native**: `just native-image`, then the binary's `--brief --lat -27.6 --lon -48.5` (tasks 1, 3).
- **Live**: `just e2e` (tasks 2, 3); an MCP client (Claude Desktop or `npx
  @modelcontextprotocol/inspector`) lists and calls the four tools against the task 4 jar.
- **Done**: no `marola.json` import, no `io.modelcontextprotocol` dependency, no
  `AllowUnsafe.embrace` in `main`, and `oods`' commands declared as case classes.

## 8. Risks, limitations, and honest caveats

- **Pre-1.0.** Kyo has no version-specific docs; every signature above was read from the RC7 jar
  and sources jar, but behaviour (decode errors, number formatting on encode) was not run. Task 3's
  byte-identical fixtures are the guard; a `Double` written as `1.0` instead of `1` would fail them.
- **kyo-mcp brings kyo-net and kyo-ffi onto the classpath.** They are not driven by the stdio
  wire, but JDK 25 may print a restricted-method warning on stderr if the posix backend probes
  run; stderr is safe for MCP. Not observed, because nothing was run.
- **Native image.** kyo-schema-json lands in the native binary; its sources have no reflection,
  but only task 1's `just native-image` proves it builds.
- **MCP client compatibility.** kyo-mcp speaks protocol 2025-06-18 and 2025-11-25; a client that
  only speaks an older revision would fail the handshake. Checked by task 4's live run only.

## 9. Alternatives considered

- **Do nothing.** The JSON code works and is tested; it stays readable but untyped, and every
  MIP-0075 export and adapter would add more of it.
- **kyo-http at the same time.** Rejected for now: no proxy support (MIP-0075 §4.6), the
  `Http.withTransport` seam has no counterpart, and its Panama transport in the native binary is
  unproven. Revisit conditions are in marola-app's table.
- **A non-Kyo codec (jsoniter-scala, circe, upickle).** Each works; none shares a `Schema` with
  kyo-mcp's tool definitions, so the MCP server would need a second derivation.
- **scopt / decline for `oods`.** Both fine; kyo-case-app runs Kyo effects at the entry point
  without a hand-written runner, for the same three small jars case-app brings anyway.

## 10. Exam-coverage mapping

None.

## 11. Open questions

- Whether task 5 should instead be folded into MIP-0075 task 1 (the maintainer's call; the tasks
  file orders it right after).
- Whether kyo-test-prop or ScalaCheck carries the first property tests (`.claude/rules/scala.md`):
  deferred in marola-app's table, not part of this MIP.

## Appendix

The jar evidence for each promoted module, `javap` on the RC7 jars (Temurin/OpenJDK 25.0.4):

```text
kyo.Json$:            public <A> java.lang.Object decode(java.lang.String, int, int, kyo.Json, kyo.Schema<A>, java.lang.String);
kyo.schema.rename:    public kyo.schema.rename(java.lang.String);
kyo.McpServer$package$McpServer$: public java.lang.Object init(kyo.JsonRpcTransport, scala.collection.immutable.Seq<kyo.McpHandler<?, ?, ?>>, java.lang.String);
kyo.McpHandler$:      public <In> java.lang.String tool$default$2();   (tool is an inline def; its default proves the overload)
kyo.JsonRpcTransport$: public <A, S> java.lang.Object stdioWith(kyo.JsonRpcFramer, kyo.Schema<kyo.JsonRpcEnvelope>, scala.Function1<kyo.JsonRpcTransport, java.lang.Object>, java.lang.String);
kyo.KyoCommand:       public abstract class kyo.KyoCommand<T> extends caseapp.core.app.Command<T> …
caseapp.core.app.CommandsEntryPoint: public abstract scala.collection.immutable.Seq<caseapp.core.app.Command<?>> commands();
```

`cs resolve io.modelcontextprotocol.sdk:mcp:2.0.0`: `mcp-core`, `mcp-json-jackson3`,
`jackson-core`/`-databind`/`-dataformat-yaml` 3.0.3, `jackson-annotations` 2.20,
`json-schema-validator` 3.0.0, `itu` 1.14.0, `reactor-core` 3.7.0, `reactive-streams` 1.0.4,
`snakeyaml-engine` 2.10, `slf4j-api` 2.0.17: what task 4 removes.
