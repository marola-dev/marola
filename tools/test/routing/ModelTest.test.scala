package marola.tools.routing

class ModelTest extends munit.FunSuite:

  test("a question's type: declared, else wiring by its via, else symbol"):
    assertEquals(QuestionType.of(Some("decision"), Some("wiring")), QuestionType("decision"))
    assertEquals(QuestionType.of(Some(""), Some("wiring")), QuestionType.Wiring)
    assertEquals(QuestionType.of(None, Some("query")), QuestionType.Symbol)
    assertEquals(QuestionType.of(None, None).name, "symbol")

  test("verdicts parse from route bench's words only"):
    assertEquals(Verdict.parse("right"), Some(Verdict.Hit))
    assertEquals(Verdict.parse("MISS"), Some(Verdict.Miss))
    assertEquals(Verdict.parse("miss"), None)

  test("a score counts every verdict and the right ones"):
    assertEquals(Score(0, 0).add(Verdict.Hit).add(Verdict.Miss), Score(1, 2))

  test("the tally keeps the types in the order they first appear"):
    val questions = Seq(Question("1", QuestionType.Wiring), Question("2", QuestionType("decision")))
    val bench = Bench.tally(
      Seq(
        BenchLine(Verdict.Hit, "2"),
        BenchLine(Verdict.Miss, "9"),
        BenchLine(Verdict.Hit, "1"),
        BenchLine(Verdict.Hit, "9")
      ),
      questions
    )
    assertEquals((bench.right, bench.total), (3, 4))
    assertEquals(bench.byType.map(_.questionType.name), Seq("decision", "symbol", "wiring"))
    assertEquals(bench.rightOf(QuestionType.Symbol), Some(1))
    assertEquals(bench.rightOf(QuestionType("meaning")), None)
    assertEquals(
      bench.toJson,
      ujson.Obj(
        "right" -> 3,
        "total" -> 4,
        "by_type" -> ujson.Obj(
          "decision" -> ujson.Obj("right" -> 1, "total" -> 1),
          "symbol" -> ujson.Obj("right" -> 1, "total" -> 2),
          "wiring" -> ujson.Obj("right" -> 1, "total" -> 1)
        )
      )
    )

  test("drops: by type when the published score has types, else the total"):
    val bench = Bench(2, 3, Seq(TypeScore(QuestionType.Symbol, Score(2, 2))))
    assertEquals(Drop.between(bench, PublishedBench(2, None)), Nil)
    assertEquals(Drop.between(bench, PublishedBench(3, None)), Seq(Drop.Total))
    val previous = PublishedBench(0, Some(Seq("wiring" -> 1L, "symbol" -> 3L, "zeta" -> 0L)))
    val drops = Drop.between(bench, previous)
    assertEquals(drops, Seq(Drop.Type("symbol", 2, 3), Drop.Type("wiring", 0, 1)))
    assertEquals(drops.map(_.render), Seq("symbol 2 < 3", "wiring 0 < 1"))
    assertEquals(Drop.Total.render, "total")

  test("build.json is stamped in place, and passes through when it is not an object"):
    val bench = Bench(1, 1, Seq(TypeScore(QuestionType.Symbol, Score(1, 1))))
    val build = ujson.Obj("graphify" -> "old", "head" -> "h")
    val stamped = BuildInfo(build).stamped("0.9", bench).json
    assertEquals(stamped.obj.keys.toSeq, Seq("graphify", "head", "bench"))
    assertEquals(stamped("graphify").str, "0.9")
    assertEquals(build("graphify").str, "old")
    assertEquals(BuildInfo(ujson.Arr(1)).stamped("0.9", bench).json, ujson.Arr(1))

  test("a source location's line is its digits, 0 past u64"):
    assertEquals(DigestNode.line("L12"), BigInt(12))
    assertEquals(DigestNode.line(""), BigInt(0))
    assertEquals(DigestNode.line("True"), BigInt(0))
    assertEquals(DigestNode.line("L" + "9" * 30), BigInt(0))

  test("the digest's repo is any marola-* directory, and the basename ignores trailing slashes"):
    assertEquals(Digest.repoOf("marola-x/a.rs"), "marola-x")
    assertEquals(Digest.repoOf("marola-x"), "marola")
    assertEquals(Digest.repoOf("tools/a.rs"), "marola")
    assertEquals(Digest.basename("a/b/c.md//"), "c.md")

  test("the digest lists code symbols by repo and file, in line order"):
    val text = Digest.render(
      Seq(
        DigestNode("marola-app/A.scala", "late", true, 9),
        DigestNode("marola-app/A.scala", "early", true, 1),
        DigestNode("marola-app/A.scala", "A.scala", true, 0),
        DigestNode("docs/x.md", "Doc", false, 0),
        DigestNode("tools/B.scala", "b", true, 0)
      ),
      "  | w |  \n"
    )
    assertEquals(
      text,
      "# marola routing digest\n\n## Wiring\n\n| w |\n\n## marola\n\n- `tools/B.scala`: b\n\n" +
        "## marola-app\n\n- `marola-app/A.scala`: early, late\n\n"
    )
    assert(Digest.capped("x\n" * Digest.MaxChars).endsWith("has the rest._"))

  test("the wiring block is what lies between the markers"):
    import WiringBlock.*
    assertEquals(extract(s"a$Start b $End c"), " b ")
    assertEquals(extract(s"a$Start b"), "_No wiring block found._")
    assertEquals(extract("nothing"), "_No wiring block found._")

  test("the manifest carries its schema and each file's size"):
    assertEquals(
      Manifest(Seq(FileSize("a.json", 3))).toJson,
      ujson.Obj("schema" -> 1, "files" -> ujson.Obj("a.json" -> 3))
    )

  test("a given path joins names as the caller would have written them"):
    assertEquals((Given("out") / "a").shown, "out/a")
    assertEquals((Given("out/") / "a").shown, "out/a")
    assertEquals((Given("") / "a").shown, "a")

  test("the palette's repos, and the umbrella for anything else"):
    assertEquals(Repo.ofSource("marola-corpus/k.md"), Repo.Corpus)
    assertEquals(Repo.ofSource("marola-x/k.md"), Repo.Umbrella)
    assertEquals(
      Repo.values.map(_.slug).take(4).toSeq,
      Seq("marola-app", "marola-site", "marola-ml", "marola")
    )

  test("an edge is ordered and never a loop"):
    assertEquals(Edge.between(3, 1), Some(Edge(1, 3)))
    assertEquals(Edge.between(2, 2), None)
    assertEquals(
      Vector(Edge(1, 3), Edge(0, 5), Edge(1, 2)).sorted,
      Vector(Edge(0, 5), Edge(1, 2), Edge(1, 3))
    )

  test("a graph keeps the first of each id, and links between kept nodes once"):
    val graph = Graph.of(
      Seq(
        RawNode("b", "marola-ml/b.py"),
        RawNode("a", "x"),
        RawNode("a", "marola-ml/y"),
        RawNode("c", "gone")
      ),
      Seq(
        RawLink(Some("b"), Some("a")),
        RawLink(Some("a"), Some("b")),
        RawLink(Some("a"), Some("c")),
        RawLink(Some("a"), None)
      ),
      _ != "gone"
    )
    assertEquals(graph.nodes, Vector(Node("a", Repo.Umbrella), Node("b", Repo.Ml)))
    assertEquals(graph.edges, Vector(Edge(0, 1)))
    assertEquals(
      Figure.caption(graph, "0123456789"),
      "graphify: the marola code graph, 2 nodes and 1 links (marola@0123456)"
    )
