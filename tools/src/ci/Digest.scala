package marola.tools.ci

import marola.tools.shared.Cli
import marola.tools.shared.Util

import mainargs.{arg, main, Flag, ParserForMethods}

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.{Duration, Instant, ZoneOffset}
import java.time.format.DateTimeFormatter
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.{Element, Node}

// Research digests: arXiv papers for marola's forecasting domain (MIP-0019) and GitHub repo
// candidates for AWESOME-AGENTIC-ENGINEERING.md (MIP-0043), cached one JSON file per item under .tmp/.
//
//     marola-tools arxiv-digest [--max-results 5] [--json]
//     marola-tools awesome-agentic-digest [--max-results 5] [--json]
object Digest:
  private val AtomNs = "http://www.w3.org/2005/Atom"
  private val ArxivApi = "https://export.arxiv.org/api/query"
  private val GithubApi = "https://api.github.com/search/repositories"

  /** One search: its label, the query string and the weight a match adds to an item's score. */
  case class Query(label: String, query: String, weight: Int)

  // Verified against the live API on 2026-09-06; cross-domain noise is scored down, not filtered
  // out, per MIP-0019's honesty about query precision.
  val ArxivQueries = Seq(
    Query("ocean-physics", "cat:physics.ao-ph AND abs:ocean", 1),
    Query("sst-forecast", """abs:"sea surface temperature" AND abs:forecast""", 2),
    Query("llm-ocean", """abs:"large language model" AND abs:ocean""", 3),
    Query(
      "llm-weather-forecast",
      """abs:"large language model" AND abs:"weather forecasting"""",
      3
    ),
    Query("jellyfish-prediction", "abs:jellyfish AND abs:prediction", 2),
    Query("marine-heatwave", """abs:"marine heatwave"""", 2),
    Query("ao-ph-ml", """cat:physics.ao-ph AND abs:"machine learning"""", 1)
  )

  // Verified live on 2026-09-07 (MIP-0043 §4.2). Two sort orders per label feed one cache.
  val GithubQueries = Seq(
    Query("agents", "topic:agents", 1),
    Query("llm-agents", "topic:llm-agents", 2),
    Query("ai-agents", "topic:ai-agents", 1),
    Query("mcp", "topic:mcp", 2),
    Query("multi-agent-systems", "topic:multi-agent-systems", 2)
  )
  private val Sorts = Seq("stars", "updated")

  /** One found item: its id, its fields and the queries that found it. */
  case class Hit(id: String, item: ujson.Obj, matched: Seq[Query])

  /** One kind of cache: where it lives, what its items are called, which fields its index keeps. */
  case class Store(dir: os.Path, items: String, indexFields: Seq[String]):
    def itemsDir: os.Path = dir / items
    def indexPath: os.Path = dir / "index.jsonl"

    def init(): Unit = os.makeDir.all(itemsDir)

    def itemFiles: Seq[os.Path] =
      if !os.isDir(itemsDir) then Nil
      else os.list(itemsDir).filter(_.ext == "json").sortBy(_.toString)

    def cachedIds: Set[String] = itemFiles.map(_.baseName).toSet

    /** Rewrite index.jsonl from the per-item files, highest relevance first (then most stars). */
    def writeIndex(): Either[String, Unit] =
      val rows = itemFiles.map { file =>
        Util.readJson(os.read(file)).left.map(e => s"$file: $e").flatMap { d =>
          val fields =
            indexFields.map(k => k -> d.objOpt.flatMap(_.get(k)).toRight(s"$file: no $k"))
          fields
            .collectFirst { case (_, Left(e)) => e }
            .toLeft(ujson.Obj.from(fields.collect { case (k, Right(v)) => k -> v }))
        }
      }
      rows.collectFirst { case Left(e) => e }.toLeft(rows.collect { case Right(r) => r }).map {
        found =>
          def number(row: ujson.Obj, key: String) =
            row.value.get(key).flatMap(_.numOpt).getOrElse(0.0)
          val sorted = found.sortBy(row => (-number(row, "relevance_score"), -number(row, "stars")))
          os.write.over(
            indexPath,
            sorted.map(row => Util.dumps(row, ensureAscii = false) + "\n").mkString
          )
      }

    /** Cache every item not seen before, then rewrite the index; returns how many were new. */
    def store(found: Seq[Hit], now: () => String = nowIso): Either[String, Int] =
      val already = cachedIds
      val fresh = found.filterNot(hit => already(hit.id))
      for hit <- fresh do
        val record = ujson.Obj.from(hit.item.value)
        record("matched_queries") = hit.matched.map(_.label)
        record("relevance_score") = relevanceScore(hit.matched)
        record("fetched_at") = now()
        os.write.over(
          itemsDir / s"${hit.id}.json",
          Util.dumps(record, indent = Some(2), ensureAscii = false)
        )
      writeIndex().map(_ => fresh.length)

  def arxivStore(root: os.Path): Store =
    Store(
      root / ".tmp" / "arxiv_cache",
      "papers",
      Seq("arxiv_id", "title", "published", "relevance_score", "fetched_at")
    )

  def awesomeStore(root: os.Path): Store =
    Store(
      root / ".tmp" / "awesome_agentic_cache",
      "repos",
      Seq("full_name", "html_url", "stars", "description", "relevance_score", "fetched_at")
    )

  private val IsoMicros =
    DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'+00:00'").withZone(ZoneOffset.UTC)

  /** Now in UTC as ISO 8601 with microseconds and `+00:00`, the format the cached records carry. */
  def nowIso(): String = IsoMicros.format(Instant.now())

  /** Sum of the weights of every query that matched: two narrow hits outscore one broad hit. */
  def relevanceScore(matched: Seq[Query]): Int = matched.map(_.weight).sum

  /** http://arxiv.org/abs/2609.03658v1 -> 2609.03658 (version-stripped, safe as a filename). */
  def arxivIdFromUrl(idUrl: String): String =
    val tail = idUrl.reverse.dropWhile(_ == '/').reverse.split('/').lastOption.getOrElse("")
    val withoutDigits = tail.reverse.dropWhile(_.isDigit).reverse
    if withoutDigits.length < tail.length && withoutDigits.endsWith("v") then
      withoutDigits.dropRight(1)
    else tail

  /** Every run of Unicode White_Space as one space, none at the ends. */
  private def squash(s: String): String =
    Util.trimUnicode(s).split("""(?U)\s+""").filter(_.nonEmpty).mkString(" ")

  private def children(node: Node, tag: String): Seq[Element] =
    val nodes = node.getChildNodes
    (0 until nodes.getLength).map(nodes.item).collect {
      case e: Element if e.getNamespaceURI == AtomNs && e.getLocalName == tag => e
    }

  /** The element's first text child, as roxmltree's `Node::text` read it. */
  private def text(element: Option[Element]): String =
    val first = element.flatMap(e => Option(e.getFirstChild))
    Util.trimUnicode(
      first.filter(_.getNodeType == Node.TEXT_NODE).map(_.getNodeValue).getOrElse("")
    )

  /** Atom feed text -> paper records. Malformed XML is an error, never "no results". */
  def parseAtom(xml: String): Either[String, Seq[ujson.Obj]] =
    val factory = DocumentBuilderFactory.newInstance()
    factory.setNamespaceAware(true)
    factory.setCoalescing(true)
    factory.setExpandEntityReferences(true)
    val builder = factory.newDocumentBuilder()
    // The default handler prints "[Fatal Error]" to stderr before throwing.
    builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler)
    val parsed =
      try Right(builder.parse(org.xml.sax.InputSource(java.io.StringReader(xml))))
      catch case e: org.xml.sax.SAXException => Left(s"malformed Atom XML: ${e.getMessage}")
    parsed.map { doc =>
      for
        entry <- children(doc.getDocumentElement, "entry")
        idUrl = text(children(entry, "id").headOption)
        if idUrl.nonEmpty
      yield
        val authors = children(entry, "author")
          .flatMap(a => children(a, "name").headOption)
          .map(n => text(Some(n)))
        val categories = children(entry, "category").map(_.getAttribute("term")).filter(_.nonEmpty)
        ujson.Obj(
          "arxiv_id" -> arxivIdFromUrl(idUrl),
          "abs_url" -> idUrl,
          "title" -> squash(text(children(entry, "title").headOption)),
          "summary" -> squash(text(children(entry, "summary").headOption)),
          "authors" -> authors,
          "categories" -> categories,
          "published" -> text(children(entry, "published").headOption)
        )
    }

  /** 'owner/repo' -> 'owner__repo', safe as a filename. */
  def repoKey(fullName: String): String = fullName.replace("/", "__")

  /** GitHub Search API JSON text -> repo records. Malformed JSON, or no `items`, is an error. */
  def parseSearchResponse(text: String): Either[String, Seq[ujson.Obj]] =
    Util
      .readJson(text)
      .left
      .map(e => s"malformed JSON: $e")
      .flatMap(
        _.objOpt
          .flatMap(_.get("items"))
          .flatMap(_.arrOpt)
          .toRight("GitHub Search API response missing 'items'")
      )
      .map { items =>
        for
          item <- items.toSeq
          fields = item.objOpt
            .map(_.value)
            .getOrElse(collection.mutable.LinkedHashMap.empty[String, ujson.Value])
          fullName = fields.get("full_name").flatMap(_.strOpt).getOrElse("")
          if fullName.nonEmpty
        yield ujson.Obj(
          "full_name" -> fullName,
          "html_url" -> fields.getOrElse("html_url", ujson.Str(s"https://github.com/$fullName")),
          "description" -> Util
            .trimUnicode(fields.get("description").flatMap(_.strOpt).getOrElse("")),
          "stars" -> fields.getOrElse("stargazers_count", ujson.Num(0)),
          "pushed_at" -> fields.getOrElse("pushed_at", ujson.Str("")),
          "topics" -> fields
            .get("topics")
            .filter(_.arrOpt.exists(_.nonEmpty))
            .getOrElse(ujson.Arr())
        )
      }

  private lazy val http = HttpClient
    .newBuilder()
    .connectTimeout(Duration.ofSeconds(20))
    .followRedirects(HttpClient.Redirect.NORMAL)
    .build()

  /** A GET that fails on any non-2xx status, as ureq did; the error is ureq's wording. */
  private def get(url: String, userAgent: String, accept: Option[String]): Either[String, String] =
    val request = HttpRequest
      .newBuilder(URI.create(url))
      .timeout(Duration.ofSeconds(20))
      .header("User-Agent", userAgent)
    accept.foreach(a => request.header("Accept", a))
    try
      val response = http.send(request.build(), HttpResponse.BodyHandlers.ofString())
      if response.statusCode / 100 == 2 then Right(response.body)
      else Left(s"$url: status code ${response.statusCode}")
    catch
      case e: java.io.IOException =>
        Left(
          s"$url: Connection Failed: ${Option(e.getMessage).getOrElse(e.getClass.getSimpleName)}"
        )

  /** Collects hits in first-seen order, one per id, with every query that found it. */
  def collect(found: Seq[(String, ujson.Obj, Query)]): Seq[Hit] =
    val byId = found.groupBy(_._1)
    found.map(_._1).distinct.map { id =>
      val group = byId(id)
      Hit(id, group.head._2, group.map(_._3))
    }

  private def summary(
      store: Store,
      run: Int,
      failed: Seq[String],
      newKey: String,
      fresh: Int
  ): ujson.Obj =
    ujson.Obj(
      "queries_run" -> run,
      "queries_failed" -> failed,
      newKey -> fresh,
      "total_cached" -> store.cachedIds.size,
      "index_path" -> store.indexPath.toString
    )

  /** The summary as `--json` prints it, or as the plain lines. */
  def render(s: ujson.Obj, asJson: Boolean, total: Int, newKey: String, newLabel: String): String =
    if asJson then Util.dumps(s, indent = Some(2)) + "\n"
    else
      val failed = s("queries_failed").arr
      Seq(
        Some(s"queries run: ${s("queries_run").render()}/$total"),
        Option.when(failed.nonEmpty)(s"queries failed: ${s("queries_failed").render()}"),
        Some(s"$newLabel: ${s(newKey).render()}"),
        Some(s"total cached: ${s("total_cached").render()}"),
        Some(s"index: ${s("index_path").str}")
      ).flatten.map(_ + "\n").mkString

  def arxiv(maxResults: Int, asJson: Boolean): Either[String, Unit] =
    val store = arxivStore(Util.repoRoot)
    store.init()
    val fetched = ArxivQueries.map { q =>
      val params = Util.urlencode(
        Seq(
          "search_query" -> q.query,
          "start" -> "0",
          "max_results" -> maxResults.toString,
          "sortBy" -> "submittedDate",
          "sortOrder" -> "descending"
        )
      )
      q -> get(s"$ArxivApi?$params", "marola-arxiv-digest/1", None).left.map(e => s"${q.label}: $e")
    }
    val errors = fetched.collect { case (_, Left(e)) => e }
    val parsed = fetched.collect { case (q, Right(xml)) =>
      parseAtom(xml).map(_.map(p => (p("arxiv_id").str, p, q)))
    }
    for
      papers <- parsed
        .collectFirst { case Left(e) => e }
        .toLeft(parsed.collect { case Right(p) => p }.flatten)
      fresh <- store.store(collect(papers))
    yield
      val s = summary(store, ArxivQueries.length - errors.length, errors, "new_papers", fresh)
      print(render(s, asJson, ArxivQueries.length, "new_papers", "new papers cached"))

  def awesome(maxResults: Int, asJson: Boolean): Either[String, Unit] =
    val store = awesomeStore(Util.repoRoot)
    store.init()
    val fetched =
      for
        q <- GithubQueries
        sort <- Sorts
      yield
        val params = Util.urlencode(
          Seq("q" -> q.query, "sort" -> sort, "order" -> "desc", "per_page" -> maxResults.toString)
        )
        val text = get(
          s"$GithubApi?$params",
          "marola-awesome-agentic-digest/1",
          Some("application/vnd.github+json")
        )
        q -> text.left.map(e => s"${q.label} ($sort): $e")
    val errors = fetched.collect { case (_, Left(e)) => e }
    val parsed = fetched.collect { case (q, Right(text)) =>
      parseSearchResponse(text).map(_.map(r => (repoKey(r("full_name").str), r, q)))
    }
    val total = GithubQueries.length * Sorts.length
    for
      repos <- parsed
        .collectFirst { case Left(e) => e }
        .toLeft(parsed.collect { case Right(r) => r }.flatten)
      fresh <- store.store(collect(repos))
    yield
      val s = summary(store, total - errors.length, errors, "new_candidates", fresh)
      print(render(s, asJson, total, "new_candidates", "new candidates cached"))

  object ArxivCommand:
    @main(doc = "Cache arXiv papers relevant to marola's forecasting domain (MIP-0019)")
    def run(
        @arg(doc = "Papers per query") maxResults: Int = 5,
        @arg(doc = "Print the summary as JSON") json: Flag
    ): Int =
      Cli.report(arxiv(maxResults, json.value))

  object AwesomeCommand:
    @main(doc = "Cache GitHub repo candidates for AWESOME-AGENTIC-ENGINEERING.md (MIP-0043)")
    def run(
        @arg(doc = "Repos per query and sort") maxResults: Int = 5,
        @arg(doc = "Print the summary as JSON") json: Flag
    ): Int =
      Cli.report(awesome(maxResults, json.value))

  def arxivCli(args: Seq[String]): Int =
    Cli.run(ParserForMethods(ArxivCommand), "arxiv-digest", args)
  def awesomeCli(args: Seq[String]): Int =
    Cli.run(ParserForMethods(AwesomeCommand), "awesome-agentic-digest", args)
