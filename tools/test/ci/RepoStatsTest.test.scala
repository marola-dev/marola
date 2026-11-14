package marola.tools.ci

import marola.tools.shared.Invocation

class RepoStatsTest extends munit.FunSuite:
  import RepoStats.*

  test("counts steps"):
    val jobs = Seq(
      ujson.read("""{"name": "build-test", "steps": [
        {"name": "checkout", "conclusion": "success"},
        {"name": "compile+test", "conclusion": "success"},
        {"name": "coverage", "conclusion": "skipped"}]}"""),
      ujson.read("""{"name": "quality-other", "steps": [
        {"name": "fmt", "conclusion": "success"},
        {"name": "actionlint", "conclusion": "failure"},
        {"name": "hadolint", "conclusion": "skipped"},
        {"name": "docker compose config", "conclusion": null}]}"""),
      ujson.read("""{"name": "repo-stats", "steps": [
        {"name": "checkout", "conclusion": "success"},
        {"name": "badges", "conclusion": null}]}""")
    ).map(decodeJob)
    assertEquals(StepTally.of(jobs, Some("repo-stats")), StepTally(3, 4))
    assertEquals(StepTally.of(jobs, None), StepTally(4, 5))
    assertEquals(
      StepTally.of(
        Seq(ujson.read("""{"name": "changes"}"""), ujson.read("""{"name": "x", "steps": null}"""))
          .map(decodeJob),
        None
      ),
      StepTally(0, 0)
    )
    val steps = Seq.fill(2)(ujson.Obj("conclusion" -> "success")) ++ Seq.fill(7)(
      ujson.Obj("conclusion" -> "skipped")
    )
    assertEquals(
      StepTally.of(Seq(decodeJob(ujson.Obj("name" -> "quality-other", "steps" -> steps))), None),
      StepTally(2, 2)
    )
    assertEquals(Badge.ci(StepTally(2, 2)).message, "2/2 green")

  test("shapes badges"):
    assertEquals(
      Badge.ci(StepTally(20, 20)).toJson: ujson.Value,
      ujson.read(
        """{"schemaVersion": 1, "label": "ci steps", "message": "20/20 green", "color": "brightgreen"}"""
      )
    )
    assertEquals(Badge.ci(StepTally(19, 20)).color, "yellow")
    assertEquals(Badge.ci(StepTally(17, 20)).color, "red")
    assertEquals(
      Badge.ci(StepTally(0, 0)).toJson: ujson.Value,
      ujson.read(
        """{"schemaVersion": 1, "label": "ci steps", "message": "no data", "color": "lightgrey"}"""
      )
    )
    assertEquals(Badge.loc("scala", 6865, BadgeColor.Scala).message, "6,865 LOC")
    assertEquals(Badge.loc("scala", 1234567, BadgeColor.Scala).message, "1,234,567 LOC")
    assertEquals(Badge.loc("scala", 0, BadgeColor.Scala).message, "0 LOC")
    val cloc =
      """{"header": {"cloc_version": "2.10"}, "Scala": {"nFiles": 84, "blank": 1050, "comment": 1673, "code": 6865}}"""
    assertEquals(parseCloc(cloc, "Scala"), Right(6865L))
    assertEquals(parseCloc(cloc, "Rust"), Right(0L))

  test("reads the tests job"):
    def jobs(conclusion: String) =
      Seq(decodeJob(ujson.read(s"""{"name": "tools-ci", "conclusion": $conclusion}""")))
    assertEquals(Badge.tests(jobs("\"success\""), "tools-ci").message, "passing")
    assertEquals(Badge.tests(jobs("\"failure\""), "tools-ci").color, "red")
    assertEquals(Badge.tests(jobs("\"skipped\""), "tools-ci").message, "no data")
    assertEquals(Badge.tests(jobs("null"), "tools-ci").message, "no data")
    assertEquals(Badge.tests(Nil, "tools-ci").message, "no data")

  test("reads the pinned versions"):
    val project =
      "//> using scala 3.9.0\n//> using jvm 21\n//> using dep com.lihaoyi::os-lib:0.11.10\n"
    assertEquals(ProjectFile.directive(project, "scala"), Some("3.9.0"))
    assertEquals(ProjectFile.directive(project, "jvm"), Some("21"))
    assertEquals(ProjectFile.directive(project, "options"), None)
    val badges = Badge.versions(project).map(f => f.name -> f.badge).toMap
    assertEquals(badges("scala-version.json").message, "3.9.0")
    assertEquals(badges("jdk-version.json").message, "21")
    assertEquals(
      Badge.versions("").find(_.name == "jdk-version.json").map(_.badge.message),
      Some("no data")
    )
    val pinned = os.read(Invocation.current().repoRoot / "tools" / "project.scala")
    assert(
      ProjectFile.directive(pinned, "scala").exists(_.startsWith("3.")),
      "tools/project.scala pins Scala 3"
    )
    assert(ProjectFile.directive(pinned, "jvm").nonEmpty, "tools/project.scala pins a JDK")

  test("writes whole documents"):
    val out = os.temp.dir() / "stats"
    val paths =
      write(
        out,
        Seq(
          BadgeFile("ci.json", Badge.ci(StepTally(20, 20))),
          BadgeFile("scala-loc.json", Badge.loc("s", 1, "x"))
        )
      )
    assertEquals(paths.length, 2)
    assertEquals(
      os.read(out / "ci.json"),
      "{\"schemaVersion\": 1, \"label\": \"ci steps\", \"message\": \"20/20 green\", \"color\": \"brightgreen\"}\n"
    )
    write(out, Seq(BadgeFile("ci.json", Badge.ci(StepTally(1, 2)))))
    assert(os.read(out / "ci.json").contains("1/2 green"))

  test("prints the --out-dir as given"):
    assertEquals(BadgeFile("ci.json", Badge.noData("x")).shownIn("stats"), "stats/ci.json")
    assertEquals(BadgeFile("ci.json", Badge.noData("x")).shownIn("stats/"), "stats/ci.json")
    assertEquals(BadgeFile("ci.json", Badge.noData("x")).shownIn("./a/b"), "./a/b/ci.json")
