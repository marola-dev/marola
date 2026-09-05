// NOTE: this bootstrap was written without network access to Maven Central,
// so nothing here has been compiled. Versions below were the latest
// confirmed as of early September 2026 — check for newer ones,
// especially Kyo (still pre-1.0, currently 1.0.0-RC5) and the Azure SDKs,
// before trusting them long-term. `just build` is the real check.
//
// REQUIRES JDK 25. Kyo 1.0.0-RC5 compiles with -release 25 (confirmed via
// Kyo's own release notes: "RC5's artifacts already could not run on JDK
// 17"), so the JVM running sbt/scalac — and the runtime executing the
// packaged jar — must both be JDK 25 or newer, regardless of what Scala
// 3.9 itself requires (17+). This bit a real build with a JDK-24 error:
// `UnsupportedClassVersionError: kyo/Frame$package$Frame$ ... class file
// version 69.0 ... this version of the Java Runtime only recognizes class
// file versions up to 68.0`. See flake.nix, which pins 25 (there is no Dockerfile yet — Phase 3).
//
// This repo is entirely marola — "best hour tomorrow to swim nearby" — split into four sbt
// modules (core/local/azure/cli) at the repo root (FUTURE-WORK.md §7.3's module-split proposal,
// implemented, then hoisted out of a marola/ subdirectory once this repo became marola's own
// repo rather than a shared monorepo). core carries the pure pipeline and shared HTTP/JSON
// helpers; local is the always-needed Ollama path with ZERO Azure SDK dependency; azure holds
// the optional Azure integrations; cli (Main, AppConfig, the MCP server) depends on all three
// since it's the one place that has to pick a backend per integration.

ThisBuild / scalaVersion := "3.9.0" // Scala 3.9 LTS itself needs JDK 17+, but Kyo 1.0.0-RC5 requires JDK 25 (see build note above) — the JVM running sbt/scalac must be 25+
ThisBuild / version      := "0.1.0-SNAPSHOT"
ThisBuild / organization := "com.marola"

// scalafix's semantic rules (RemoveUnused, OrganizeImports) need SemanticDB.
ThisBuild / semanticdbEnabled := true
ThisBuild / semanticdbVersion := scalafixSemanticdb.revision

val kyoVersion = "1.0.0-RC5"

// Shared so `testFrameworks +=` and the E2E-tag-exclusion `Tests.Argument` below (see
// baseSettings) both reference the exact same TestFramework value, rather than risk two
// separately-constructed `new TestFramework("munit.Framework")` values behaving inconsistently.
val munitFramework = new TestFramework("munit.Framework")

// Every subproject in this build shares this: same Scala version, same scalac flags, same
// effects library, same test setup. Azure-SDK dependencies are deliberately NOT here — see
// the `azure` project below for why that module declares its own.
lazy val baseSettings = Seq(
  // Kyo's own docs recommend these flags to catch common effect-handling
  // mistakes (unused/discarded Kyo computations, unsafe equality).
  // Do not remove without reading getkyo.io first — see AGENTS.md.
  scalacOptions ++= Seq(
    "-Wvalue-discard",
    "-Wnonunit-statement",
    "-Wconf:msg=(unused.*value|discarded.*value|pure.*statement|unused import|is never used):error",
    "-language:strictEquality",
    "-deprecation",
    "-unchecked",
    "-feature",
    // Unused imports/locals/privates are errors (via the -Wconf rule below); `params` is left out on
    // purpose — the MCP SDK's BiFunction handlers take an `exchange` they don't use.
    "-Wunused:imports,locals,privates,implicits"
  ),

  libraryDependencies ++= Seq(
    // --- Effects ---
    "io.getkyo" %% "kyo-core"        % kyoVersion,
    "io.getkyo" %% "kyo-direct"      % kyoVersion, // direct-style (.now / defer) syntax
    "io.getkyo" %% "kyo-combinators" % kyoVersion,
    // NOTE: kyo-sttp existed pre-1.0 but was never published in the 1.0.x
    // line (last release May 2025) — Kyo replaced the sttp integration
    // with its own in-house transport, published as kyo-http. Its
    // getJson/postJson/Schema-derivation API (getkyo.io's docs describe
    // this for 1.0.0-RC6) is confirmed REAL and present at this exact
    // 1.0.0-RC5 version too — verified directly by decompiling the jar
    // (kyo.HttpClient$package$HttpClient$'s real method list includes
    // getJson/postJson/getText/postBinary/... all taking a `kyo.Schema[A]`
    // — the codec typeclass lives in a separate `kyo-schema` artifact,
    // already resolved transitively). Kept as a declared dependency for
    // future direct use; marola's HTTP calls go through
    // `java.net.http.HttpClient` wrapped in `Sync.defer` instead
    // (`core`'s `Http.scala`), which is simpler and already
    // live-verified against real APIs. Migrating is real, tracked future
    // work — see `docs/FUTURE-WORK.md` §2.
    "io.getkyo" %% "kyo-http"        % kyoVersion,

    // --- Logging ---
    "ch.qos.logback" % "logback-classic" % "1.5.13",

    // --- Test ---
    "org.scalameta" %% "munit" % "1.0.2" % Test
  ),

  testFrameworks += munitFramework,

  // Excludes tests tagged "E2E" (cli/src/test/scala/marola/E2ESpec.scala) from the default
  // `sbt test`/`just test` run — those hit live Overpass/Open-Meteo/Ollama, which would make the
  // normal fast unit-test suite flaky and slow. Run them explicitly with `just e2e`, which
  // overrides this setting for that one invocation rather than layering an --include-tags on top
  // of it — confirmed necessary: munit applies an exclude over a same-tag include when both are
  // passed together, so simple layering doesn't work.
  Test / testOptions += Tests.Argument(munitFramework, "--exclude-tags=E2E"),

  // `Http.withTransport` is a process-wide switch (core/.../Http.scala), so two suites replaying
  // fixtures at the same time see each other's transport. sbt runs a project's suites in parallel
  // by default — confirmed the hard way: BoardSpec's call-count assertion failed only when it ran
  // next to PipelineGoldenSpec. Sequential suites cost nothing here (the whole run is seconds).
  Test / parallelExecution := false,

  assembly / assemblyMergeStrategy := {
    // ServiceLoader registrations (e.g. the MCP Java SDK's JsonSchemaValidatorSupplier — see
    // cli/src/main/scala/marola/agent/SwimConditionsMcpServer.scala) live under
    // META-INF/services/* and must be concatenated, not discarded: confirmed the hard way —
    // blanket-discarding all of META-INF (the line below this comment used to do that) produced a
    // real `ServiceConfigurationError: No JsonSchemaValidatorSupplier available` at runtime in the
    // assembled jar, even though the same code ran fine under `sbt run`'s unmerged classpath.
    case PathList("META-INF", "services", xs @ _*) => MergeStrategy.concat
    case PathList("META-INF", xs @ _*)             => MergeStrategy.discard
    case _                                         => MergeStrategy.first
  }
)

lazy val core = (project in file("core"))
  .settings(baseSettings)
  .settings(name := "marola-core")

lazy val local = (project in file("local"))
  .dependsOn(core)
  .settings(baseSettings)
  .settings(name := "marola-local") // deliberately no extra dependencies: java.net.http (JDK) only

lazy val azure = (project in file("azure"))
  .dependsOn(core)
  .settings(baseSettings)
  .settings(
    name := "marola-azure",
    libraryDependencies ++= Seq(
      // --- managed identity everywhere, no API keys ---
      "com.azure" % "azure-identity" % "1.18.1",
      // NOTE: `com.azure:azure-ai-agents` was declared here from the bootstrap onward but nothing
      // imports it (`AzureFoundryLlmClient` is plain REST) — removed (FABLE_REVIEW C5). Add it back
      // when a Foundry Agent Service integration actually uses it (AI-500-MAPPING.md §2).
      // --- Cosmos DB (optional sighting-report store) ---
      "com.azure" % "azure-cosmos" % "4.71.0",
      // --- Monitor / Application Insights (optional observability backend, no-op by default) ---
      "com.azure" % "azure-monitor-opentelemetry-autoconfigure" % "1.4.0"
    )
  )

lazy val cli = (project in file("cli"))
  .dependsOn(core, local, azure)
  .settings(baseSettings)
  .settings(
    name := "marola-cli",
    // --- MCP (agent tool wiring — see agent/SwimConditionsMcpServer.scala) ---
    libraryDependencies += "io.modelcontextprotocol.sdk" % "mcp" % "2.0.0",
    assembly / mainClass := Some("marola.Main"),
    // marola.agent.SwimConditionsMcpServer also has a `main` (see that file) — without pinning
    // this, `sbt run` prompts interactively to pick one, which hangs in batch mode (confirmed:
    // `No main class detected` under a non-interactive run). Use
    // `sbt cli/runMain marola.agent.SwimConditionsMcpServer` to run the MCP server instead.
    Compile / run / mainClass := Some("marola.Main")
  )

lazy val root = (project in file("."))
  .aggregate(core, local, azure, cli)
  .settings(
    name := "marola",
    publish / skip := true
  )
