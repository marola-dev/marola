package marola.tools.ci

class ModelTest extends munit.FunSuite:

  test("a conclusion counts as run only when the step executed"):
    val ran = Seq("success", "failure", "cancelled", "timed_out").map(Conclusion.parse)
    val notRan = Seq("skipped", "neutral", "action_required").map(Conclusion.parse)
    assert(ran.forall(_.ran))
    assert(!notRan.exists(_.ran))
    assertEquals(Conclusion.parse("timed_out"), Conclusion.TimedOut)
    assertEquals(Conclusion.parse("stale"), Conclusion.Unknown("stale"))
    for c <- Seq(Conclusion.Success, Conclusion.Neutral, Conclusion.Unknown("x")) do
      assertEquals(Conclusion.parse(c.wire), c)

  test("the step tally leaves out the reporting job and unfinished steps"):
    val jobs = Seq(
      Job(Some("a"), None, Seq(Step(Some(Conclusion.Success)), Step(None))),
      Job(Some("self"), None, Seq(Step(Some(Conclusion.Failure)))),
      Job(None, None, Seq(Step(Some(Conclusion.Failure)), Step(Some(Conclusion.Skipped))))
    )
    assertEquals(StepTally.of(jobs, Some("self")), StepTally(1, 2))
    assertEquals(StepTally.of(jobs, None), StepTally(1, 3))

  test("the tests badge reads the named job's conclusion"):
    val jobs = Seq(
      Job(Some("other"), Some(Conclusion.Success), Nil),
      Job(Some("tools-ci"), Some(Conclusion.TimedOut), Nil)
    )
    assertEquals(Badge.tests(jobs, "tools-ci"), Badge("tests", "failing", "red"))
    assertEquals(Badge.tests(jobs, "missing"), Badge.noData("tests"))

  test("a badge file's shown path"):
    assertEquals(BadgeFile("a.json", Badge.noData("x")).shownIn("out/"), "out/a.json")

  test("plans and their included minutes"):
    assertEquals(Plan.parse("free"), Plan.Free)
    assertEquals(Plan.parse("pro").includedMinutes, Some(3000))
    assertEquals(Plan.parse("team"), Plan.Other("team"))
    assertEquals(Plan.parse("team").name, "team")
    assertEquals(Plan.parse("team").includedMinutes, None)

  test("a billing period as the URL and the header show it"):
    assertEquals(BillingPeriod("2026", "8").query, "year=2026&month=8")
    assertEquals(BillingPeriod("2026", "8").shown, "2026-08")
    assertEquals(BillingPeriod("2026", "12").shown, "2026-12")
    assertEquals(BillingPeriod("2026", "x").shown, "2026-x")
    assertEquals(
      BillingPeriod("2026", "8").usagePath("m"),
      "/users/m/settings/billing/usage?year=2026&month=8"
    )

  test("token scopes"):
    assertEquals(TokenScopes.Listed(Seq("repo", "user")).shown, "repo, user")
    assert(TokenScopes.Listed(Seq("repo", "user")).has("user"))
    assert(!TokenScopes.Listed(Seq("read:user")).has("user"))
    assertEquals(TokenScopes.NoScopes.shown, "none")
    assert(!TokenScopes.NoScopes.has("none"))
    assertEquals(TokenScopes.Absent.shown, "")

  test("jq's order and @tsv of a JSON value"):
    val values = Seq(ujson.Str("b"), ujson.Num(2), ujson.Null, ujson.True, ujson.Str("a"))
    assertEquals(
      values.map(JqValue(_)).sorted.map(_.json),
      Seq(ujson.Null, ujson.True, ujson.Num(2), ujson.Str("a"), ujson.Str("b"))
    )
    assertEquals(JqValue(ujson.Str("a\tb")).tsv, "a\\tb")
    assertEquals(JqValue(ujson.Null).tsv, "")
    assertEquals(JqValue(ujson.Num(3)).tsv, "3")

  test("usage items group by product and SKU, net descending"):
    def item(product: String, sku: String, net: Double) =
      UsageItem(
        JqValue(ujson.Str(product)),
        JqValue(ujson.Str(sku)),
        JqValue(ujson.Str("u")),
        1,
        net,
        0,
        net
      )
    assertEquals(Usage.of(Nil), Usage.Empty)
    Usage.of(Seq(item("b", "x", 1), item("a", "y", 2), item("b", "x", 3))) match
      case Usage.Report(rows, total) =>
        assertEquals(
          rows.map(r => (r.product.tsv, r.sku.tsv, r.net)),
          Seq(("b", "x", 4.0), ("a", "y", 2.0))
        )
        assertEquals(rows.head.quantity, 2.0)
        assertEquals(total, 6.0)
      case Usage.Empty => fail("three items are usage")

  test("an arXiv id from its abstract URL"):
    assertEquals(Paper.idFromUrl("http://arxiv.org/abs/2609.03658v3/"), "2609.03658")
    assertEquals(Paper.idFromUrl("http://arxiv.org/abs/hep-th/9901001"), "9901001")

  test("a repo candidate's id and record"):
    val repo = RepoCandidate("a/b", "https://github.com/a/b", "d", 7, "t", Seq("mcp"))
    assertEquals(repo.id, "a__b")
    assertEquals(
      repo.toJson.render(),
      """{"full_name":"a/b","html_url":"https://github.com/a/b","description":"d","stars":7,"pushed_at":"t","topics":["mcp"]}"""
    )

  test("a hit's score and its mapped item"):
    val hit = Hit("x", 1, Seq(Query("a", "", 2), Query("b", "", 3)))
    assertEquals(hit.relevanceScore, 5)
    assertEquals(hit.map(_.toString), Hit("x", "1", hit.matched))

  test("the repo digest's summary names its new candidates"):
    val s = DigestSummary(DigestKind.Repos, 10, 10, Nil, 0, 3, "/i")
    assertEquals(
      s.render(asJson = false),
      "queries run: 10/10\nnew candidates cached: 0\ntotal cached: 3\nindex: /i\n"
    )
    assertEquals(s.toJson.value.keys.toSeq(2), "new_candidates")

  test("each digest kind keeps its own cache and index fields"):
    val root = os.temp.dir()
    assertEquals(
      Digest.storeOf(DigestKind.Papers, root).itemsDir,
      root / ".tmp" / "arxiv_cache" / "papers"
    )
    assertEquals(
      Digest.storeOf(DigestKind.Repos, root).indexPath,
      root / ".tmp" / "awesome_agentic_cache" / "index.jsonl"
    )
    assert(DigestKind.values.forall(_.indexFields.contains(RecordField.RelevanceScore)))
