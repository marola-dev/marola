class RoutingBundleTest extends munit.FunSuite:
  import RoutingBundle.*

  private val graph = ujson.read(
    """{"nodes": [
      {"label": "Swimability", "source_file": "marola-app/core/Swimability.scala", "source_location": "L12", "file_type": "code"},
      {"label": "score", "source_file": "marola-app/core/Swimability.scala", "source_location": "L30", "file_type": "code"},
      {"label": "Swimability.scala", "source_file": "marola-app/core/Swimability.scala", "file_type": "code"},
      {"label": "release", "source_file": "scripts/release.sh", "source_location": "L4", "file_type": "code"},
      {"label": "Overview", "source_file": "docs/README.md", "file_type": "document"}
    ], "links": []}"""
  )

  test("assembles"):
    val d = os.temp.dir()
    val (src, out) = (d / "graph", d / "out")
    os.makeDir(src)
    os.write(src / "graph.json", graph.render())
    os.write(src / "wiring.json", "{}")
    val head = "a" * 40
    val build = ujson.Obj("head" -> head, "submodules" -> s" ${"b" * 40} marola-app (heads/main)\n")
    os.write(src / "build.json", build.render())
    os.write(d / "REPOS.md", s"intro\n$Start\n| artifact | by |\n$End\nrest\n")
    val prev = d / "prev.json"
    os.write(prev, """{"bench": {"right": 3, "total": 4}}""")
    val questions = d / "bench.yaml"
    os.write(
      questions,
      """{"questions": [
        {"id": 1, "via": "query", "ask": "A", "expect": ["a"]},
        {"id": 2, "via": "query", "ask": "B", "expect": ["b"]},
        {"id": 3, "via": "wiring", "ask": "C", "expect": ["c"]},
        {"id": 4, "type": "decision", "via": "why", "ask": "D", "expect": ["d"]}
      ]}"""
    )

    def benchOut(results: String*): os.Path =
      val lines = "answers from main@abc (bundle)" +: results.zipWithIndex.map((r, i) =>
        f"$r%-5s  ${i + 1}  q 'x'"
      )
      val f = d / "bench.txt"
      os.write.over(
        f,
        (lines :+ s"right first: ${results.count(_ == "right")}/${results.length}")
          .mkString("\n") + "\n"
      )
      f
    def run(bench: os.Path, previous: Option[os.Path]): Int =
      val args = Args(
        Given(src.toString),
        Given(out.toString),
        Given(bench.toString),
        Given(questions.toString),
        previous.map(p => Given(p.toString)),
        "0.9.84",
        Given((d / "REPOS.md").toString)
      )
      assemble(args).fold(m => fail(m), identity)

    val score =
      scores(os.read(benchOut("right", "MISS", "right", "right")), questions, "q").toOption.get
    assertEquals((score.right, score.total), (3, 4))
    assertEquals(
      score.toJson("by_type"),
      ujson.Obj(
        "symbol" -> ujson.Obj("right" -> 1, "total" -> 2),
        "wiring" -> ujson.Obj("right" -> 1, "total" -> 1),
        "decision" -> ujson.Obj("right" -> 1, "total" -> 1)
      )
    )
    assertEquals(run(benchOut("right", "MISS", "MISS", "right"), Some(prev)), 1)
    assert(!os.exists(out), "a lower score keeps the previous bundle")
    assertEquals(run(benchOut("right", "MISS", "right", "right"), Some(prev)), 0)
    val b = ujson.read(os.read(out / "build.json"))
    assertEquals(b("head").str, head)
    assertEquals(b("bench")("right").num, 3.0)
    assertEquals(b("bench")("by_type")("symbol"), ujson.Obj("right" -> 1, "total" -> 2))
    assertEquals(b("graphify").str, "0.9.84")
    os.copy.over(out / "build.json", prev)
    assertEquals(run(benchOut("MISS", "right", "right", "right"), Some(prev)), 0)
    // The total holds at 3, symbol rises to 2 and wiring falls to 0: one type cannot pay for another.
    assertEquals(run(benchOut("right", "right", "MISS", "right"), Some(prev)), 1)
    // session-check.sh reads build.json with grep, not a JSON parser.
    val raw = os.read(out / "build.json")
    assert(raw.contains(s"\"head\": \"$head\""))
    assert(raw.contains(s"${"b" * 40} marola-app "))
    val manifest = ujson.read(os.read(out / "manifest.json"))
    assertEquals(manifest("schema").num, Schema.toDouble)
    assertEquals(
      manifest("files").obj.keys.toSeq,
      Seq("build.json", "graph.json", "routing-digest.md", "wiring.json")
    )
    val dg = os.read(out / "routing-digest.md")
    assert(dg.contains("| artifact | by |"))
    assert(
      dg.contains("## marola-app") && dg.contains(
        "`marola-app/core/Swimability.scala`: Swimability, score"
      ),
      dg
    )
    assert(dg.contains("## marola\n") && dg.contains("scripts/release.sh"))
    assert(!dg.contains("Overview") && !dg.contains("Swimability.scala`: Swimability.scala"))
    assertEquals(run(benchOut("right", "right", "right", "right"), Some(d / "absent.json")), 0)
    os.write(src / "decisions.json", """{"rows": []}""")
    assertEquals(run(benchOut("right", "right", "right", "right"), None), 0)
    val files = ujson.read(os.read(out / "manifest.json"))("files").obj
    assert(files.contains("decisions.json") && !files.contains("meaning.json"))
    assert(!files.contains("manifest.json"))
    assertEquals(os.read(out / "decisions.json"), """{"rows": []}""")
    assert(scores("no score\n", questions, "q").isLeft)

  test("the digest is capped"):
    val nodes = ujson.Arr.from(
      (0 until 9000).map(i =>
        ujson.Obj("label" -> s"s$i", "source_file" -> s"f$i.rs", "file_type" -> "code")
      )
    )
    val text = digest(ujson.Obj("nodes" -> nodes), "")
    assert(text.codePointCount(0, text.length) <= DigestChars + 100)
