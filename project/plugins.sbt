// Fat-jar packaging (`sbt cli/assembly`) — see build.sbt's assemblyMergeStrategy.
addSbtPlugin("com.eed3si9n" % "sbt-assembly" % "2.3.0")

// `sbt scalafmtAll` / `scalafmtCheckAll` — used by `just fmt` / `just lint`.
addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.5.2")

// `sbt scalafixAll --check` — semantic lint (unused, import order, banned syntax); see .scalafix.conf
// and `just quality`. Free, runs in ci.yml.
addSbtPlugin("ch.epfl.scala" % "sbt-scalafix" % "0.14.9")

// `sbt cli/nativeImage` — GraalVM native-image of the CLI (MIP-0008 task 3, `just native-image`).
// The native-image arguments and reachability metadata live in cli/src/main/resources/META-INF/
// native-image/com.marola/marola-cli/, read from the classpath, so the Dockerfile's
// `native-image -jar marola.jar` builds the same binary.
addSbtPlugin("org.scalameta" % "sbt-native-image" % "0.5.0")

// `sbt coverage test coverageReport coverageAggregate` — statement coverage, published to the
// site's coverage badge (`just coverage`; see .github/workflows/ci.yml and site.yml). Latest
// release confirmed via GitHub as of 2026-09-05 (github.com/scoverage/sbt-scoverage/releases).
addSbtPlugin("org.scoverage" % "sbt-scoverage" % "2.4.4")
