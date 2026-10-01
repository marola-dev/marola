// REQUIRES JDK 25: Kyo 1.0.0-RC5's artifacts are compiled for class-file version 69, so both the
// JVM running sbt and the runtime executing the jar must be 25+ (flake.nix and the Dockerfile pin
// it). An older JVM fails with `UnsupportedClassVersionError: kyo/Frame$package$Frame$`.
//
// Modules: core (pure pipeline), local (Ollama path), cli (wires them together).

ThisBuild / scalaVersion := "3.9.0"
ThisBuild / version      := "0.1.0-SNAPSHOT"
ThisBuild / organization := "com.marola"

// scalafix's semantic rules (RemoveUnused, OrganizeImports) need SemanticDB.
ThisBuild / semanticdbEnabled := true
ThisBuild / semanticdbVersion := scalafixSemanticdb.revision

val kyoVersion = "1.0.0-RC5"

val munitFramework = new TestFramework("munit.Framework")

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

    // --- Logging ---
    "ch.qos.logback" % "logback-classic" % "1.5.13",

    // --- Test ---
    "org.scalameta" %% "munit" % "1.0.2" % Test
  ),

  testFrameworks += munitFramework,

  // E2E suites hit live services; `just e2e` overrides this setting rather than adding
  // --include-tags, because munit lets an exclude win over a same-tag include.
  Test / testOptions += Tests.Argument(munitFramework, "--exclude-tags=E2E"),

  // `Http.withTransport` is a process-wide switch (core/.../Http.scala), so two suites replaying
  // fixtures at the same time see each other's transport. sbt runs a project's suites in parallel
  // by default — confirmed the hard way: BoardSpec's call-count assertion failed only when it ran
  // next to PipelineGoldenSpec. Sequential suites cost nothing here (the whole run is seconds).
  Test / parallelExecution := false,

  assembly / assemblyMergeStrategy := {
    // Discarding these broke the MCP SDK's ServiceLoader lookup in the fat jar only
    // (`No JsonSchemaValidatorSupplier available`).
    case PathList("META-INF", "services", xs @ _*) => MergeStrategy.concat
    // GraalVM reachability metadata (ours under com.marola/marola-cli, and every dependency's) must
    // survive into the fat jar too: the Dockerfile's `native-image -jar marola.jar` reads it from
    // there. Paths are per artifact, so nothing collides; `first` is only for a duplicate jar.
    case PathList("META-INF", "native-image", xs @ _*) => MergeStrategy.first
    case PathList("META-INF", xs @ _*)                 => MergeStrategy.discard
    case _                                         => MergeStrategy.first
  }
)

lazy val core = (project in file("core"))
  .settings(baseSettings)
  .settings(name := "marola-core")

val OpenTelemetryVersion = "1.65.0"

val PdfboxVersion = "3.0.8"

lazy val local = (project in file("local"))
  .dependsOn(core)
  .settings(baseSettings)
  .settings(
    name := "marola-local",
    // OpenTelemetry: MLflow ingests traces over OTLP/HTTP only (MIP-0010). PDFBox: the agencies publish bulletins only as PDFs, and a
    // pure-JVM parser avoids bundling `pdftotext` into the image (MIP-0031 §4.3).
    libraryDependencies ++= Seq(
      "io.opentelemetry" % "opentelemetry-sdk" % OpenTelemetryVersion,
      "io.opentelemetry" % "opentelemetry-exporter-otlp" % OpenTelemetryVersion,
      "io.opentelemetry" % "opentelemetry-sdk-testing" % OpenTelemetryVersion % Test,
      "org.apache.pdfbox" % "pdfbox" % PdfboxVersion
    )
  )

lazy val cli = (project in file("cli"))
  .dependsOn(core, local)
  .enablePlugins(NativeImagePlugin)
  .settings(baseSettings)
  .settings(
    name := "marola-cli",
    // GraalVM native-image (MIP-0008): `nativeImageInstalled` uses $GRAALVM_HOME's native-image
    // (`just native-image` provides one) instead of downloading a GraalVM. Its arguments and
    // reachability metadata live under cli/src/main/resources/META-INF/native-image/, so the
    // Dockerfile's `native-image -jar marola.jar` builds the same binary.
    Compile / mainClass := Some("marola.Main"),
    nativeImageInstalled := true,
    nativeImageOutput := target.value / "marola",
    // --- MCP (agent tool wiring — see agent/SwimConditionsMcpServer.scala) ---
    libraryDependencies += "io.modelcontextprotocol.sdk" % "mcp" % "2.0.0",
    assembly / mainClass := Some("marola.Main"),
    // SwimConditionsMcpServer has a `main` too; unpinned, `sbt run` prompts and hangs in batch mode.
    Compile / run / mainClass := Some("marola.Main"),
    // Fork with sbt's stdin: the MCP server's `main` returns once the stdio transport is up and
    // lives on its SDK's non-daemon reader thread, which an in-process run kills on return.
    // `StdoutOutput` stops sbt re-logging child stdout/stderr, which corrupted the MCP transport.
    Compile / run / fork := true,
    // Silences JEP 498's sun.misc.Unsafe warning from scala-library 3.9.0's LazyVals. Expires:
    // once Unsafe is removed the JVM rejects this flag, so re-check on every Scala bump.
    Compile / run / javaOptions += "--sun-misc-unsafe-memory-access=allow",
    Compile / run / connectInput := true,
    Compile / run / outputStrategy := Some(StdoutOutput),
    // A forked run's cwd defaults to `cli/`; every relative path (`site/`, `knowledge/`, `data/`)
    // assumes the repo root.
    Compile / run / baseDirectory := (ThisBuild / baseDirectory).value
  )

// An sbt task so CI's sbt-only gate (the devkit's scala-ci) can fetch before `test`.
lazy val corpusFetch =
  taskKey[Unit]("scripts/corpus-fetch.sh: corpus.version's release into .tmp/knowledge")

lazy val root = (project in file("."))
  .aggregate(core, local, cli)
  .settings(
    name := "marola",
    publish / skip := true,
    corpusFetch := {
      val rc = scala.sys.process.Process(Seq("scripts/corpus-fetch.sh"), baseDirectory.value).!
      if (rc != 0) sys.error(s"scripts/corpus-fetch.sh exited $rc")
    }
  )
