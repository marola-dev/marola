package marola.tools.ci

import marola.tools.shared.Util

class DigestTest extends munit.FunSuite:
  import Digest.*

  private val Atom = """<?xml version='1.0' encoding='UTF-8'?>
<feed xmlns:opensearch="http://a9.com/-/spec/opensearch/1.1/" xmlns:arxiv="http://arxiv.org/schemas/atom" xmlns="http://www.w3.org/2005/Atom">
  <entry>
    <id>http://arxiv.org/abs/2609.03658v1</id>
    <title>  A Deep Learning Model for
      Forecasting  Sea Surface Temperature</title>
    <updated>2026-09-03T10:56:02Z</updated>
    <summary>We forecast sea surface temperature anomalies with a transformer.</summary>
    <category term="physics.ao-ph" scheme="http://arxiv.org/schemas/atom"/>
    <published>2026-09-03T10:56:02Z</published>
    <author><name>Ada Lovelace</name></author>
    <author><name>Grace Hopper</name></author>
  </entry>
  <entry>
    <id>http://arxiv.org/abs/2601.00001v2</id>
    <title>Notify About Jellyfish Blooms via Sonar</title>
    <summary>A sonar pipeline to detect jellyfish blooms near beaches.</summary>
    <category term="cs.CV" scheme="http://arxiv.org/schemas/atom"/>
    <published>2026-01-01T00:00:00Z</published>
    <author><name>Marie Curie</name></author>
  </entry>
</feed>"""

  private val sst = Query("sst-forecast", "", 2)

  test("parses atom"):
    val entries = parseAtom(Atom).toOption.get
    assertEquals(entries.length, 2)
    assertEquals(entries(0).arxivId, "2609.03658")
    assertEquals(
      entries(0).title,
      "A Deep Learning Model for Forecasting Sea Surface Temperature"
    )
    assertEquals(entries(0).toJson("authors"), ujson.Arr("Ada Lovelace", "Grace Hopper"))
    assertEquals(entries(0).toJson("categories"), ujson.Arr("physics.ao-ph"))
    assertEquals(entries(1).arxivId, "2601.00001")
    assert(parseAtom("<not-xml").isLeft, "malformed XML is an error, not zero results")
    assertEquals(Query.relevanceScore(Seq(Query("ocean-physics", "", 1))), 1)
    assertEquals(Query.relevanceScore(Seq(Query("ocean-physics", "", 1), sst)), 3)

  test("parses search"):
    val fixture = """{"total_count": 2, "incomplete_results": false, "items": [
      {"full_name": "example-org/agent-critic", "html_url": "https://github.com/example-org/agent-critic",
       "description": "A reviewer/critic pattern for LLM agent pipelines.", "stargazers_count": 4200,
       "pushed_at": "2026-09-01T00:00:00Z", "topics": ["llm-agents", "mcp"]},
      {"full_name": "another-org/dspy-scala", "html_url": "https://github.com/another-org/dspy-scala",
       "description": "DSPy-style prompt compilation, replayed from Scala.", "stargazers_count": 130,
       "pushed_at": "2026-08-15T00:00:00Z", "topics": ["ai-agents"]}]}"""
    val repos = parseSearchResponse(fixture).toOption.get
    assertEquals(repos.length, 2)
    assertEquals(repos(0).fullName, "example-org/agent-critic")
    assertEquals(repos(0).toJson("stars").num, 4200.0)
    assertEquals(RepoCandidate.key("example-org/agent-critic"), "example-org__agent-critic")
    assert(parseSearchResponse("{not json").isLeft)
    assert(parseSearchResponse("""{"total_count": 0}""").isLeft)

  test("fills the fields a search result leaves out"):
    val repo = parseSearchResponse(
      """{"items": [{"full_name": "a/b", "description": "  x  ", "topics": []}, {"full_name": ""}]}"""
    )
    assertEquals(
      repo.map(_.map(r => Util.dumps(r.toJson))),
      Right(
        Seq(
          """{"full_name": "a/b", "html_url": "https://github.com/a/b", "description": "x", "stars": 0, "pushed_at": "", "topics": []}"""
        )
      )
    )

  test("caches and dedups"):
    val root = os.temp.dir()
    val store = arxivStore(root)
    store.init()
    assertEquals(store.cachedIds, Set.empty[String])
    val found = parseAtom(Atom).toOption.get.map(p => Hit(p.arxivId, p.toJson, Seq(sst)))
    assertEquals(store.store(found, () => "T"), Right(2))
    assertEquals(store.cachedIds, Set("2609.03658", "2601.00001"))
    assertEquals(os.read.lines(root / ".tmp" / "arxiv_cache" / "index.jsonl").length, 2)
    assertEquals(
      store.store(found, () => "T"),
      Right(0),
      "an already-cached id is skipped on refetch"
    )

    val repos = awesomeStore(root)
    repos.init()
    val fixture =
      """{"items": [{"full_name": "a/low", "stargazers_count": 1}, {"full_name": "b/high", "stargazers_count": 9}]}"""
    val hits = parseSearchResponse(fixture).toOption.get.map(r =>
      Hit(RepoCandidate.key(r.fullName), r.toJson, Seq(Query("mcp", "", 2)))
    )
    assertEquals(repos.store(hits, () => "T"), Right(2))
    val index = os.read.lines(root / ".tmp" / "awesome_agentic_cache" / "index.jsonl")
    assert(index.head.contains("b/high"), "equal score: more stars first")

  test("writes a record in the published byte layout"):
    val root = os.temp.dir()
    val store = arxivStore(root)
    store.init()
    val hit = Hit(
      "1",
      ujson.Obj("arxiv_id" -> "1", "title" -> "é", "published" -> "p"),
      Seq(sst, Query("llm-ocean", "", 3))
    )
    assertEquals(store.store(Seq(hit), () => "T"), Right(1))
    assertEquals(
      os.read(store.itemsDir / "1.json"),
      "{\n  \"arxiv_id\": \"1\",\n  \"title\": \"é\",\n  \"published\": \"p\",\n  \"matched_queries\": [\n    \"sst-forecast\",\n    \"llm-ocean\"\n  ],\n  \"relevance_score\": 5,\n  \"fetched_at\": \"T\"\n}"
    )
    assertEquals(
      os.read(store.indexPath),
      "{\"arxiv_id\": \"1\", \"title\": \"é\", \"published\": \"p\", \"relevance_score\": 5, \"fetched_at\": \"T\"}\n"
    )

  test("collects hits in first-seen order"):
    val a = ujson.Obj("x" -> 1)
    val b = ujson.Obj("x" -> 2)
    val q1 = Query("q1", "", 1)
    val q2 = Query("q2", "", 2)
    assertEquals(
      Hit.collect(Seq(Match("b", b, q1), Match("a", a, q1), Match("b", b, q2))),
      Seq(Hit("b", b, Seq(q1, q2)), Hit("a", a, Seq(q1)))
    )

  test("renders the summary"):
    val s = DigestSummary(
      DigestKind.Papers,
      queriesRun = 6,
      queriesTotal = 7,
      queriesFailed = Seq("x: boom"),
      fresh = 2,
      totalCached = 9,
      indexPath = "/i"
    )
    assertEquals(
      s.render(asJson = false),
      "queries run: 6/7\nqueries failed: [\"x: boom\"]\nnew papers cached: 2\ntotal cached: 9\nindex: /i\n"
    )
    assertEquals(
      s.render(asJson = true),
      "{\n  \"queries_run\": 6,\n  \"queries_failed\": [\n    \"x: boom\"\n  ],\n  \"new_papers\": 2,\n  \"total_cached\": 9,\n  \"index_path\": \"/i\"\n}\n"
    )

  test("ids and timestamps"):
    assertEquals(Paper.idFromUrl("http://arxiv.org/abs/2609.03658v12"), "2609.03658")
    assertEquals(Paper.idFromUrl("http://arxiv.org/abs/2609.03658"), "2609.03658")
    val fixed = java.time.Clock
      .fixed(java.time.Instant.parse("2026-09-06T01:02:03.000004Z"), java.time.ZoneOffset.UTC)
    assertEquals(DigestConfig(os.pwd, 1, false, fixed).now(), "2026-09-06T01:02:03.000004+00:00")
    val now = DigestConfig(os.pwd, 1, false, java.time.Clock.systemUTC()).now()
    assert(now.matches("""\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d\.\d{6}\+00:00"""))
