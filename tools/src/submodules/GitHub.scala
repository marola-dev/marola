package marola.tools.submodules

// gh's output decoded into the model, read as the jq pipelines it replaced read it (Jq, below).
object GitHub:
  private val GitHubActions = 15368.0

  /** The first open PR of `gh pr list --json number,headRefOid,isCrossRepository,labels` that is
    * this repo's own branch (a fork's PR can carry the same branch name).
    */
  def openPr(prList: String): Option[Pr] =
    val own = Jq.values(prList).documents.flatMap { list =>
      Jq.items(list).find(pr => !Jq.truthy(Jq.field(pr, "isCrossRepository")))
    }
    own.collectFirst { case pr: ujson.Obj =>
      val labels = Jq.items(Jq.field(pr, "labels")).map(label => Jq.text(Jq.field(label, "name")))
      Pr(
        PrNumber(Jq.text(Jq.field(pr, "number"))),
        Sha(Jq.text(Jq.field(pr, "headRefOid"))),
        labels.toVector
      )
    }

  /** The rows of the check-runs pages. */
  def checkRuns(pages: Seq[ujson.Value]): Vector[Check] =
    for
      page <- pages.toVector
      run <- Jq.items(Jq.field(page, "check_runs"))
    yield Check(
      Jq.tsv(Jq.field(run, "name")),
      CheckStatus.parse(Jq.tsv(Jq.field(run, "status"))),
      Conclusion.parse(Jq.tsv(Jq.orElse(Jq.field(run, "conclusion"), ujson.Str(""))))
    )

  /** The commit statuses as check rows. */
  def statuses(pages: Seq[ujson.Value]): Vector[Check] =
    for
      page <- pages.toVector
      status <- Jq.items(Jq.field(page, "statuses"))
    yield
      val state = Jq.field(status, "state")
      val progress =
        if state == ujson.Str("pending") then CheckStatus.Unfinished("pending")
        else CheckStatus.Completed
      Check(Jq.tsv(Jq.field(status, "context")), progress, Conclusion.parse(Jq.tsv(state)))

  /** ci.yml's aggregate job, from GitHub Actions only: its conclusion on each page that has it. */
  def ciOk(pages: Seq[ujson.Value]): Vector[Conclusion] =
    pages.toVector
      .flatMap { page =>
        Jq.items(Jq.field(page, "check_runs")).find { run =>
          Jq.field(run, "name") == ujson.Str("ci-ok") &&
          Jq.field(Jq.field(run, "app"), "id") == ujson.Num(GitHubActions) &&
          Jq.field(run, "status") == ujson.Str("completed")
        }
      }
      .map(run => Jq.field(run, "conclusion"))
      .filter(Jq.truthy)
      .map(conclusion => Conclusion.parse(Jq.text(conclusion)))

  /** Whether `gh pr view --json comments` holds a comment with exactly this body. */
  def hasComment(view: String, body: String): Boolean =
    Jq.values(view).documents.lastOption.exists { view =>
      Jq.items(Jq.field(view, "comments")).exists(c => Jq.field(c, "body") == ujson.Str(body))
    }

  /** `jq -er .<field>` over `gh api`'s output: every page's value, or None when jq would fail. */
  def requiredField(exitCode: Int, out: String, field: String): Option[String] =
    val pages = Jq.values(out)
    val values = pages.documents.map(page => Jq.field(page, field))
    val ok = exitCode == 0 && pages.complete && values.lastOption.exists(Jq.truthy)
    Option.when(ok)(values.map(Jq.text).mkString("\n"))

// The jq behaviours these reads keep, exit code included: gh --paginate prints one JSON document
// per page, `.a.b` of a missing key is null, `[]` of null is nothing, and `-r` prints strings bare.
object Jq:
  /** jq's exit code for a parse error, or for an error on its last input. */
  val JqError = 5

  /** The documents read before the end of the text, or before the first one that does not parse.
    */
  final case class Documents(documents: Vector[ujson.Value], complete: Boolean):
    /** `jq '.<key>[]'` exits 0: everything parsed and the last document has `key` to iterate. */
    def readable(key: String): Boolean =
      complete && documents.lastOption.forall(doc => iterable(field(doc, key)))

  /** Every JSON document in `text`, as `jq` reads a stream. */
  def values(text: String): Documents =
    val documents = Vector.newBuilder[ujson.Value]
    var start = skipBlanks(text, 0)
    var complete = true
    while start < text.length && complete do
      val end = documentEnd(text, start)
      scala.util.Try(ujson.read(text.substring(start, end))) match
        case scala.util.Success(document) => documents += document
        case scala.util.Failure(_)        => complete = false
      start = skipBlanks(text, end)
    Documents(documents.result(), complete)

  private def skipBlanks(text: String, from: Int): Int =
    var i = from
    while i < text.length && text(i).isWhitespace do i += 1
    i

  /** Where the document starting at `start` ends: its closing bracket, or the next blank for a
    * scalar.
    */
  private def documentEnd(text: String, start: Int): Int =
    if text(start) != '{' && text(start) != '[' then
      var i = start
      while i < text.length && !text(i).isWhitespace do i += 1
      i
    else
      var depth = 0
      var inString = false
      var i = start
      var end = -1
      while i < text.length && end < 0 do
        val c = text(i)
        if inString then
          if c == '\\' then i += 1
          else if c == '"' then inString = false
        else if c == '"' then inString = true
        else if c == '{' || c == '[' then depth += 1
        else if c == '}' || c == ']' then
          depth -= 1
          if depth == 0 then end = i + 1
        i += 1
      if end < 0 then text.length else end

  def field(value: ujson.Value, key: String): ujson.Value = value match
    case obj: ujson.Obj => obj.value.getOrElse(key, ujson.Null)
    case _              => ujson.Null

  def iterable(value: ujson.Value): Boolean =
    value.isInstanceOf[ujson.Arr] || value.isInstanceOf[ujson.Obj]

  /** `.[]`, and nothing for a value it would refuse. */
  def items(value: ujson.Value): Seq[ujson.Value] = value match
    case arr: ujson.Arr => arr.value.toSeq
    case obj: ujson.Obj => obj.value.values.toSeq
    case _              => Seq.empty

  def truthy(value: ujson.Value): Boolean = value != ujson.Null && value != ujson.False

  /** `a // b` */
  def orElse(value: ujson.Value, fallback: ujson.Value): ujson.Value =
    if truthy(value) then value else fallback

  /** A value as `jq -r` or string interpolation prints it. */
  def text(value: ujson.Value): String = value match
    case ujson.Str(s)                              => s
    case ujson.Num(d) if d.isWhole && d.abs < 1e17 => d.toLong.toString
    case other                                     => ujson.write(other)

  /** A value as one `@tsv` field. */
  def tsv(value: ujson.Value): String = value match
    case ujson.Null   => ""
    case ujson.Str(s) =>
      s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "\\r")
    case other => text(other)
