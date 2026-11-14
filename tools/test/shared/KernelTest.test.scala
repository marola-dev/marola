package marola.tools.shared

class KernelTest extends munit.FunSuite:
  private val sha = "a" * 40

  test("Sha.parse takes a full SHA-1 or SHA-256 name and nothing shorter or uppercase"):
    assertEquals(Sha.parse(sha).map(_.value), Right(sha))
    assertEquals(Sha.parse("b" * 64).map(_.short), Right("bbbbbbb"))
    assertEquals(Sha.parse("abc1234"), Left("not a full commit sha: abc1234"))
    assert(Sha.parse("A" * 40).isLeft)
    assert(Sha.parse("").isLeft)
    assertEquals(Sha("abc").value, "abc", "as printed, unchecked")

  test("a repo slug in the org, and its page"):
    assertEquals(RepoSlug.inOrg("marola-app"), RepoSlug("marola-dev/marola-app"))
    assertEquals(RepoSlug("a/b").web, "https://github.com/a/b")

  test("a conclusion that ran, that passes, and its wire name"):
    val all = Seq("success", "failure", "cancelled", "timed_out", "skipped", "neutral", "")
      .map(Conclusion.parse)
    assertEquals(all.map(_.ran), Seq(true, true, true, true, false, false, false))
    assertEquals(all.map(_.passed), Seq(true, false, false, false, true, true, false))
    assertEquals(
      all.map(_.wire),
      Seq("success", "failure", "cancelled", "timed_out", "skipped", "neutral", "")
    )

  test("a submodule from its config key, and its url key"):
    val sub = Submodule.fromConfig("submodule.marola-app.path", "app")
    assertEquals(sub, Submodule("marola-app", "app"))
    assertEquals(sub.urlKey, "submodule.marola-app.url")

  test("jq's stream: documents up to the first that does not parse"):
    val pages = Jq.values("""{"a":[1]} {"a":"x\"}"}  [2] 3""")
    assertEquals(pages.documents.length, 4)
    assert(pages.complete)
    assert(!pages.readable("a"), "the last document has no `a` to iterate")
    val broken = Jq.values("""{"a":[]} {oops}""")
    assertEquals(broken.documents, Vector(ujson.Obj("a" -> ujson.Arr())))
    assert(!broken.complete)
    assertEquals(Jq.values("  "), Jq.Documents(Vector.empty, complete = true))

  test("jq's paths, truth, -r text and @tsv"):
    val doc = ujson.read("""{"a":{"b":7},"s":"t\tu","n":null,"f":false}""")
    assertEquals(Jq.field(Jq.field(doc, "a"), "b"), ujson.Num(7))
    assertEquals(Jq.field(doc, "zz"), ujson.Null)
    assertEquals(Jq.items(ujson.Null), Seq.empty)
    assert(!Jq.truthy(Jq.field(doc, "f")))
    assertEquals(Jq.orElse(Jq.field(doc, "n"), ujson.Str("d")), ujson.Str("d"))
    assertEquals(Jq.text(ujson.Num(7)), "7")
    assertEquals(Jq.tsv(Jq.field(doc, "s")), "t\\tu")
    assertEquals(Jq.tsv(ujson.Null), "")

  test("an invocation finds the umbrella from MAROLA_ROOT or above its cwd"):
    val t = os.temp.dir()
    try
      os.write(t / "tools" / "project.scala", "", createFolders = true)
      os.makeDir.all(t / "a" / "b")
      assertEquals(Invocation(Map.empty, t / "a" / "b").repoRoot, t)
      assertEquals(Invocation(Map(Invocation.RootEnvVar -> "a"), t).repoRoot, t / "a")
      assertEquals(Invocation(Map.empty, t).path("x/y"), t / "x" / "y")
    finally os.remove.all(t)
