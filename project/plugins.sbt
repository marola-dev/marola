// Fat-jar packaging (`sbt cli/assembly`) — see build.sbt's assemblyMergeStrategy.
addSbtPlugin("com.eed3si9n" % "sbt-assembly" % "2.3.0")

// `sbt scalafmtAll` / `scalafmtCheckAll` — used by `just fmt` / `just lint`.
addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.5.2")

// `sbt scalafixAll --check` — semantic lint (unused, import order, banned syntax); see .scalafix.conf
// and `just quality`. Free, runs in ci.yml.
addSbtPlugin("ch.epfl.scala" % "sbt-scalafix" % "0.14.3")
