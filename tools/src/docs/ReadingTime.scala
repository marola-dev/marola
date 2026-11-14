package marola.tools.docs

import marola.tools.shared.Cli
import marola.tools.shared.Util

import mainargs.{arg, main, Flag, ParserForMethods}

// Every tracked README*.md carries its own reading time, computed here and never by hand: words a
// person reads at Brysbaert (2019)'s 238 wpm (English) or 181 wpm (Portuguese), plus 15 s per code
// block, rounded up. `marola-tools reading-time [--write | --check]` (AGENTS.md).
object ReadingTime:
  enum Lang:
    case PtBr, En

  case class Estimate(words: Int, codeBlocks: Int, minutes: Int)

  private val WpmPt = 181.0
  private val WpmEn = 238.0
  private val CodeBlockMinutes = 0.25
  private val Color = "1682D4"

  private val Comment = "(?s)<!--.*?-->".r
  private val Image = raw"!\[[^\]]*\]\([^)]*\)".r
  private val Link = raw"\[([^\]]*)\]\([^)]*\)".r
  private val Tag = "<[^>\n]+>".r
  private val Url = raw"(?U)https?://\S+".r
  private val Badge =
    """^<a href="[^"]*"><img src="[^"]*" alt="(tempo de leitura|reading time): """.r
  private val Whitespace = raw"(?U)\s+".r
  private val LeadingSpace = raw"(?U)^\s+".r
  private val TrailingSpace = raw"(?U)\s+$$".r

  /** Split at `\n`, a `\r` before it dropped, no empty last line. */
  def lines(text: String): Vector[String] =
    val parts = text.split("\n", -1).toVector
    val complete = parts.init.map(_.stripSuffix("\r"))
    if parts.last.isEmpty then complete else complete :+ parts.last

  /** A word character: Unicode Alphabetic or any Number category. */
  private def isAlphanumeric(codePoint: Int): Boolean =
    Character.isAlphabetic(codePoint) || (Character.getType(codePoint) match
      case Character.DECIMAL_DIGIT_NUMBER | Character.LETTER_NUMBER | Character.OTHER_NUMBER => true
      case _ => false)

  private def isFenceChar(c: Char) = c == '`' || c == '~'

  /** The prose of a Markdown file, and how many fenced code blocks it had. */
  def prose(text: String): (String, Int) =
    val out = StringBuilder()
    var blocks = 0
    var fence: Option[String] = None
    for line <- lines(text) do
      val trimmed = LeadingSpace.replaceFirstIn(line, "")
      val marker = trimmed.takeWhile(isFenceChar)
      fence match
        case Some(open) =>
          val closes = trimmed
            .startsWith(open) && TrailingSpace.replaceFirstIn(trimmed, "").forall(isFenceChar)
          if closes then fence = None
        case None if marker.length >= 3 && marker.forall(_ == marker.head) =>
          blocks += 1
          fence = Some(marker)
        case None =>
          out ++= line
          out += '\n'
    val stripped = Seq(Comment -> " ", Image -> " ", Link -> "$1", Tag -> " ", Url -> " ")
      .foldLeft(out.toString)((text, rule) => rule._1.replaceAllIn(text, rule._2))
    (stripped, blocks)

  private def words(prose: String): Seq[String] =
    Whitespace.split(prose).toSeq.filter(w => w.codePoints.anyMatch(isAlphanumeric))

  private val EnglishWords = Set("the", "and", "of", "is", "to", "with")
  private val PortugueseWords = Set("de", "que", "não", "um", "uma", "com", "para")

  /** English when the name ends in `.en.md` or the text has more English than Portuguese function
    * words.
    */
  def langOf(path: String, text: String): Lang =
    if path.endsWith(".en.md") then Lang.En
    else
      val normalized = words(prose(text)._1).map { w =>
        val cps = w.toLowerCase(java.util.Locale.ROOT).codePoints.toArray
        val from = cps.indexWhere(isAlphanumeric)
        val to = cps.lastIndexWhere(isAlphanumeric)
        if from < 0 then "" else String(cps, from, to - from + 1)
      }
      val english = normalized.count(EnglishWords)
      val portuguese = normalized.count(PortugueseWords)
      if english > portuguese then Lang.En else Lang.PtBr

  def estimate(text: String, lang: Lang): Estimate =
    val (p, codeBlocks) = prose(text)
    val count = words(p).length
    val wpm = if lang == Lang.En then WpmEn else WpmPt
    val minutes = math.ceil(count / wpm + codeBlocks * CodeBlockMinutes).max(1.0).toInt
    Estimate(count, codeBlocks, minutes)

  /** The badge line for the README at `path` (relative to the repo root). */
  def badge(path: String, lang: Lang, minutes: Int): String =
    val (label, alt, file, anchor) = lang match
      case Lang.PtBr =>
        ("tempo%20de%20leitura", "tempo de leitura", "README.md", "tempo-de-leitura")
      case Lang.En => ("reading%20time", "reading time", "README.en.md", "reading-time")
    val dir = Util.posix.dirname(path)
    val href =
      if dir.isEmpty && s"$file#$anchor".startsWith(path) then s"#$anchor"
      else s"${Util.posix.relpath(file, dir)}#$anchor"
    s"""<a href="$href"><img src="https://img.shields.io/badge/$label-~$minutes%20min-$Color" alt="$alt: ~$minutes min" /></a>"""

  /** `text` with its badge line set to `line`: replaced in place, or put after the license badge,
    * or else after the first heading.
    */
  def withBadge(text: String, line: String): String =
    val current = lines(text)
    val existing = current.indexWhere(l => Badge.findPrefixOf(l).isDefined)
    val license = current.indexWhere(_.contains("img.shields.io/badge/license-"))
    val updated =
      if existing >= 0 then current.updated(existing, line)
      else if license >= 0 then current.patch(license + 1, Seq(line), 0)
      else
        val heading = current.indexWhere(_.startsWith("# "))
        current.patch(if heading >= 0 then heading + 1 else 0, Seq("", line), 0)
    updated.mkString("\n") + (if text.endsWith("\n") then "\n" else "")

  private def readmes(root: os.Path): Vector[String] =
    val listed = os
      .proc("git", "ls-files", "--", "README*.md", "**/README*.md")
      .call(cwd = root, check = false, stderr = os.Pipe)
    lines(listed.out.text()).distinct.sorted

  def run(root: os.Path, write: Boolean, check: Boolean): Either[String, Unit] =
    val stale = Vector.newBuilder[String]
    for rel <- readmes(root) do
      val path = root / os.RelPath(rel)
      val text = os.read(path)
      val lang = langOf(rel, text)
      val e = estimate(text, lang)
      val want = withBadge(text, badge(rel, lang, e.minutes))
      if want != text then if write then os.write.over(path, want) else stale += rel
      val (wordsLabel, blocksLabel) = lang match
        case Lang.En   => ("words", "code blocks")
        case Lang.PtBr => ("palavras", "blocos de código")
      println(s"$rel: ~${e.minutes} min (${e.words} $wordsLabel, ${e.codeBlocks} $blocksLabel)")
    val staleFiles = stale.result()
    if check && staleFiles.nonEmpty then
      Left(
        s"reading-time: the badge of ${staleFiles.mkString(", ")} is missing or stale; run just reading-time"
      )
    else Right(())

  @main(doc =
    "Each README's reading time: print it, --write the badge line, or --check it is current"
  )
  def readingTime(
      @arg(doc = "Write the badge line into each README") write: Flag,
      @arg(doc = "Exit 1 if a README's badge line is missing or stale") check: Flag
  ): Int =
    if write.value && check.value then
      System.err.println(
        "marola-tools reading-time: the argument '--check' cannot be used with '--write'"
      )
      2
    else Cli.report(run(Util.repoRoot, write.value, check.value))

  def cli(args: Seq[String]): Int = Cli.run(ParserForMethods(this), "reading-time", args)
