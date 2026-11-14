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
    )
    assertEquals(countSteps(jobs, Some("repo-stats")), (3, 4))
    assertEquals(countSteps(jobs, None), (4, 5))
    assertEquals(
      countSteps(
        Seq(ujson.read("""{"name": "changes"}"""), ujson.read("""{"name": "x", "steps": null}""")),
        None
      ),
      (0, 0)
    )
    val steps = Seq.fill(2)(ujson.Obj("conclusion" -> "success")) ++ Seq.fill(7)(
      ujson.Obj("conclusion" -> "skipped")
    )
    assertEquals(
      countSteps(Seq(ujson.Obj("name" -> "quality-other", "steps" -> steps)), None),
      (2, 2)
    )
    assertEquals(ciBadge(2, 2)("message").str, "2/2 green")

  test("shapes badges"):
    assertEquals(
      ciBadge(20, 20): ujson.Value,
      ujson.read(
        """{"schemaVersion": 1, "label": "ci steps", "message": "20/20 green", "color": "brightgreen"}"""
      )
    )
    assertEquals(ciBadge(19, 20)("color").str, "yellow")
    assertEquals(ciBadge(17, 20)("color").str, "red")
    assertEquals(
      ciBadge(0, 0): ujson.Value,
      ujson.read(
        """{"schemaVersion": 1, "label": "ci steps", "message": "no data", "color": "lightgrey"}"""
      )
    )
    assertEquals(locBadge("scala", 6865, ScalaColor)("message").str, "6,865 LOC")
    assertEquals(locBadge("scala", 1234567, ScalaColor)("message").str, "1,234,567 LOC")
    assertEquals(locBadge("scala", 0, ScalaColor)("message").str, "0 LOC")
    val cloc =
      """{"header": {"cloc_version": "2.10"}, "Scala": {"nFiles": 84, "blank": 1050, "comment": 1673, "code": 6865}}"""
    assertEquals(parseCloc(cloc, "Scala"), Right(6865L))
    assertEquals(parseCloc(cloc, "Rust"), Right(0L))

  test("writes whole documents"):
    val out = os.temp.dir() / "stats"
    val paths =
      write(out, Seq("ci.json" -> ciBadge(20, 20), "scala-loc.json" -> locBadge("s", 1, "x")))
    assertEquals(paths.length, 2)
    assertEquals(
      os.read(out / "ci.json"),
      "{\"schemaVersion\": 1, \"label\": \"ci steps\", \"message\": \"20/20 green\", \"color\": \"brightgreen\"}\n"
    )
    write(out, Seq("ci.json" -> ciBadge(1, 2)))
    assert(os.read(out / "ci.json").contains("1/2 green"))

  test("prints the --out-dir as given"):
    assertEquals(shownPath("stats", "ci.json"), "stats/ci.json")
    assertEquals(shownPath("stats/", "ci.json"), "stats/ci.json")
    assertEquals(shownPath("./a/b", "ci.json"), "./a/b/ci.json")
