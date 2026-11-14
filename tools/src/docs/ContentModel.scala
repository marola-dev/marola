package marola.tools.docs

import marola.tools.shared.{ReposDoc, Util}

import java.util.Locale
import java.util.regex.Pattern
import scala.jdk.CollectionConverters.*

// The content checks' domain: a README's reading time and its badge, an Archify figure's boxes,
// the Mermaid font pin, and the findings of the AGENTS.md and CLAUDE_PROJECT.config.md checks.
// No IO here: the commands read and write the files.

// ---- reading time ------------------------------------------------------------------------------

/** Brysbaert (2019)'s reading speeds, and how each language names its badge. */
enum ReadingLang(
    val wordsPerMinute: Double,
    val label: String,
    val alt: String,
    val file: String,
    val anchor: String,
    val wordsLabel: String,
    val blocksLabel: String
):
  case PtBr
      extends ReadingLang(
        181.0,
        "tempo%20de%20leitura",
        "tempo de leitura",
        "README.md",
        "tempo-de-leitura",
        "palavras",
        "blocos de código"
      )
  case En
      extends ReadingLang(
        238.0,
        "reading%20time",
        "reading time",
        "README.en.md",
        "reading-time",
        "words",
        "code blocks"
      )

object ReadingLang:
  private val EnglishWords = Set("the", "and", "of", "is", "to", "with")
  private val PortugueseWords = Set("de", "que", "não", "um", "uma", "com", "para")

  /** English when the name ends in `.en.md` or the text has more English than Portuguese function
    * words.
    */
  def of(path: String, text: String): ReadingLang =
    if path.endsWith(".en.md") then En
    else
      val normalized = Prose.of(text).words.map { w =>
        val cps = w.toLowerCase(Locale.ROOT).codePoints.toArray
        val from = cps.indexWhere(Prose.isAlphanumeric)
        val to = cps.lastIndexWhere(Prose.isAlphanumeric)
        if from < 0 then "" else String(cps, from, to - from + 1)
      }
      val english = normalized.count(EnglishWords)
      val portuguese = normalized.count(PortugueseWords)
      if english > portuguese then En else PtBr

/** Whole minutes of reading, never under one. */
opaque type Minutes = Int

object Minutes:
  def from(n: Int): Either[String, Minutes] =
    if n >= 1 then Right(n) else Left(s"a reading time is at least 1 minute, not $n")

  /** `minutes` rounded up, at least one. */
  def ceil(minutes: Double): Minutes = math.ceil(minutes).max(1.0).toInt

  extension (m: Minutes) def value: Int = m

/** The prose of a Markdown file, and how many fenced code blocks it had. */
final case class Prose(text: String, codeBlocks: Int):
  /** The words a person reads: runs of non-space holding a letter or a number. */
  def words: Seq[String] =
    Prose.Whitespace.split(text).toSeq.filter(w => w.codePoints.anyMatch(Prose.isAlphanumeric))

object Prose:
  private val Comment = "(?s)<!--.*?-->".r
  private val Image = raw"!\[[^\]]*\]\([^)]*\)".r
  private val Link = raw"\[([^\]]*)\]\([^)]*\)".r
  private val Tag = "<[^>\n]+>".r
  private val Url = raw"(?U)https?://\S+".r
  private val Whitespace = raw"(?U)\s+".r
  private val LeadingSpace = raw"(?U)^\s+".r
  private val TrailingSpace = raw"(?U)\s+$$".r

  /** A word character: Unicode Alphabetic or any Number category. */
  def isAlphanumeric(codePoint: Int): Boolean =
    Character.isAlphabetic(codePoint) || (Character.getType(codePoint) match
      case Character.DECIMAL_DIGIT_NUMBER | Character.LETTER_NUMBER | Character.OTHER_NUMBER => true
      case _ => false)

  private def isFenceChar(c: Char) = c == '`' || c == '~'

  /** The lines outside fenced code, the number of fences, and the fence still open. */
  private type Scan = (Vector[String], Int, Option[String])

  private def scan(state: Scan, line: String): Scan =
    val (prose, blocks, fence) = state
    val trimmed = LeadingSpace.replaceFirstIn(line, "")
    val marker = trimmed.takeWhile(isFenceChar)
    fence match
      case Some(open) =>
        val closes = trimmed
          .startsWith(open) && TrailingSpace.replaceFirstIn(trimmed, "").forall(isFenceChar)
        if closes then (prose, blocks, None) else state
      case None if marker.length >= 3 && marker.forall(_ == marker.head) =>
        (prose, blocks + 1, Some(marker))
      case None => (prose :+ line, blocks, None)

  def of(markdown: String): Prose =
    val (prose, blocks, _) =
      ReadingText.lines(markdown).foldLeft[Scan]((Vector.empty, 0, None))(scan)
    val stripped = Seq(Comment -> " ", Image -> " ", Link -> "$1", Tag -> " ", Url -> " ")
      .foldLeft(prose.map(_ + "\n").mkString)((text, rule) => rule._1.replaceAllIn(text, rule._2))
    Prose(stripped, blocks)

/** Words at the language's speed, plus 15 s per code block, rounded up. */
final case class ReadingEstimate(words: Int, codeBlocks: Int, minutes: Minutes):
  def summary(path: String, lang: ReadingLang): String =
    s"$path: ~${minutes.value} min ($words ${lang.wordsLabel}, $codeBlocks ${lang.blocksLabel})"

object ReadingEstimate:
  private val CodeBlockMinutes = 0.25

  def of(markdown: String, lang: ReadingLang): ReadingEstimate =
    val prose = Prose.of(markdown)
    val count = prose.words.length
    val minutes =
      Minutes.ceil(count / lang.wordsPerMinute + prose.codeBlocks * CodeBlockMinutes)
    ReadingEstimate(count, prose.codeBlocks, minutes)

/** The badge line of the README at `path` (relative to the repo root), linking its language's
  * landing README.
  */
final case class ReadingBadge(path: String, lang: ReadingLang, minutes: Minutes):
  def line: String =
    val dir = Util.posix.dirname(path)
    val target = s"${lang.file}#${lang.anchor}"
    val href =
      if dir.isEmpty && target.startsWith(path) then s"#${lang.anchor}"
      else s"${Util.posix.relpath(lang.file, dir)}#${lang.anchor}"
    val m = minutes.value
    s"""<a href="$href"><img src="https://${ReadingBadge.Shields}/${lang.label}-~$m%20min-${ReadingBadge.Color}" alt="${lang.alt}: ~$m min" /></a>"""

object ReadingBadge:
  private val Shields = "img.shields.io/badge"
  private val Color = "1682D4"
  private val LicenseBadge = s"$Shields/license-"
  private val Existing =
    """^<a href="[^"]*"><img src="[^"]*" alt="(tempo de leitura|reading time): """.r

  /** `text` with its badge line set to `line`: replaced in place, or put after the license badge,
    * or else after the first heading.
    */
  def place(text: String, line: String): String =
    val current = ReadingText.lines(text)
    val existing = current.indexWhere(l => Existing.findPrefixOf(l).isDefined)
    val license = current.indexWhere(_.contains(LicenseBadge))
    val updated =
      if existing >= 0 then current.updated(existing, line)
      else if license >= 0 then current.patch(license + 1, Seq(line), 0)
      else
        val heading = current.indexWhere(_.startsWith("# "))
        current.patch(if heading >= 0 then heading + 1 else 0, Seq("", line), 0)
    updated.mkString("\n") + (if text.endsWith("\n") then "\n" else "")

object ReadingText:
  /** Split at `\n`, a `\r` before it dropped, no empty last line. */
  def lines(text: String): Vector[String] =
    val parts = text.split("\n", -1).toVector
    val complete = parts.init.map(_.stripSuffix("\r"))
    if parts.last.isEmpty then complete else complete :+ parts.last

// ---- figures -----------------------------------------------------------------------------------

/** The size of Archify's drawing, as its `viewBox="0 0 W H"` writes it (decimals kept). */
final case class DrawingSize(width: String, height: String)

/** What the measuring page saw: the visible drawing from (x0, y0) to (x1, y1), viewBox units. */
final case class BBox(x0: Int, y0: Int, x1: Int, y1: Int):
  /** The box grown by `pad` on every side. */
  def grow(pad: Int): CropBox =
    val (left, top) = (x0 - pad, y0 - pad)
    CropBox(left, top, x1 - left + pad, y1 - top + pad)

/** The part of the drawing the picture keeps. */
final case class CropBox(left: Int, top: Int, width: Int, height: Int):
  def viewBox: String = s"$left $top $width $height"

/** An Archify source, as far as drawing it needs: its `diagram_type`, verbatim. */
final case class ArchifySource(diagramType: String)

/** What `archify deliver --json` reported: its diagnostics' messages, and its `error`. */
final case class DeliverReport(messages: Seq[String], error: String):
  def errors: Seq[String] = if messages.nonEmpty then messages else Seq(error)

/** The tools a figure is drawn with, from ARCHIFY, CHROME and CHROME_FLAGS. */
final case class FigureTools(archify: String, chrome: String, chromeFlags: Seq[String])

object FigureTools:
  val ArchifyEnvVar = "ARCHIFY"
  val ChromeEnvVar = "CHROME"
  val ChromeFlagsEnvVar = "CHROME_FLAGS"
  val DefaultArchify = ".tmp/archify"
  val DefaultChrome = "google-chrome"

  def from(env: Map[String, String]): FigureTools =
    FigureTools(
      env.get(ArchifyEnvVar).filter(_.nonEmpty).getOrElse(DefaultArchify),
      env.get(ChromeEnvVar).filter(_.nonEmpty).getOrElse(DefaultChrome),
      env.get(ChromeFlagsEnvVar).toSeq.flatMap(_.split("""\s+""")).filter(_.nonEmpty)
    )

/** The work directory of one PNG, named after it: `.tmp/figure/<name>`. */
final case class FigureWork(name: String):
  def dir: String = s"${FigureWork.Root}/$name"

object FigureWork:
  val Root = ".tmp/figure"

  /** The files a draw leaves in it. */
  val Deliver = "deliver.json"
  val Page = "page.html"
  val Measure = "measure.html"
  val Frame = "frame.html"

  /** `basename PNG .png`. */
  def of(png: String): FigureWork =
    val name = png.split('/').filter(_.nonEmpty).lastOption.getOrElse(png)
    FigureWork(if name != ".png" && name.endsWith(".png") then name.dropRight(4) else name)

// ---- the Mermaid font pin (#511) ---------------------------------------------------------------

/** A fenced block: the indent of its fence, its info string's language, and its body. */
final case class MermaidFence(indent: String, lang: String, code: String):
  def isMermaid: Boolean = lang.trim.toLowerCase(Locale.ROOT) == "mermaid"
  def needsPin: Boolean = isMermaid && !code.startsWith(MermaidFence.InitLine)
  def pinnedCode: String = indent + MermaidFence.InitLine + "\n" + code

object MermaidFence:
  val FontFamily = """"Open Sans", "trebuchet ms", verdana, arial, sans-serif"""

  val InitLine: String = s"%%{init: {\"fontFamily\": ${Util.dumps(ujson.Str(FontFamily))}}}%%"

  // Mirrors mkdocs_kroki_plugin's own kroki/parsing.py _FENCE_RE (1.7.0): requiring the closing
  // fence to repeat the opening's exact indent and backtick/tilde run is what keeps a ```mermaid```
  // example quoted inside a longer fence (DIAGRAMS.md's "Source" blocks) from being rewritten too.
  private val FenceRe = Pattern.compile(
    raw"(?ism)(?<fence>^(?<indent>[ ]*)(?:````*|~~~~*))[ ]*(?<lang>[\w#.+-]*)[^\n]*\n(?<code>.*?)(?<=\n)\k<fence>[ ]*$$",
    Pattern.UNICODE_CHARACTER_CLASS | Pattern.UNIX_LINES
  )

  /** `markdown` with the init line prepended to every top-level ```mermaid fence. */
  def pin(markdown: String): String =
    val fences = FenceRe.matcher(markdown).results().toList.asScala
    val (pieces, last) = fences.foldLeft((Vector.empty[String], 0)) { case ((pieces, last), m) =>
      val fence = MermaidFence(m.group("indent"), m.group("lang"), m.group("code"))
      val written =
        if !fence.needsPin then m.group
        else
          markdown.substring(m.start, m.start("code")) + fence.pinnedCode +
            markdown.substring(m.end("code"), m.end)
      (pieces :+ markdown.substring(last, m.start) :+ written, m.end)
    }
    (pieces :+ markdown.substring(last)).mkString

/** How many pages a pin run changed. */
final case class PinnedPages(count: Int):
  def +(other: PinnedPages): PinnedPages = PinnedPages(count + other.count)
  def summary: String = s"mermaid-font: pinned the Mermaid font in $count page(s)"

// ---- AGENTS.md and the routing table (MIP-0074 §7) ---------------------------------------------

/** A pin file named on a line of AGENTS.md. */
final case class PinHit(line: Int, name: String):
  def render: String = s"$line:$name"

enum AgentsFinding:
  case RepoTableRows(lines: Seq[Int])
  case PinFiles(hits: Seq[PinHit])
  case NoReposLink

  /** The finding as printed for `file`. */
  def render(file: String): String =
    import AgentsFinding.ReposPage
    this match
      case RepoTableRows(lines) =>
        s"agents_repos_check: $file:${lines.mkString(",")}: a repo table row; it belongs in $ReposPage"
      case PinFiles(hits) =>
        s"agents_repos_check: $file: names a pin file (${hits.map(_.render).mkString(" ")}); it belongs in $ReposPage"
      case NoReposLink => s"agents_repos_check: $file does not link $ReposPage"

object AgentsFinding:
  val ReposPage = ReposDoc.Path
  val File = "AGENTS.md"

  private val TableRow = """^\| \[?marola-(app|site|corpus|ml|oods|devkit)""".r
  private val PinFile = """marola-image|corpus\.version|resources\.version""".r

  /** What is wrong with an AGENTS.md: rows, then pins, then the link, each at most once. */
  def of(text: String): Seq[AgentsFinding] =
    val lines = text.split("\n", -1).toSeq.zipWithIndex.map((line, i) => (i + 1, line))
    val rows = lines.collect { case (n, line) if TableRow.findFirstIn(line).isDefined => n }
    val pins = lines.flatMap((n, line) => PinFile.findAllIn(line).map(PinHit(n, _)))
    Seq(
      Option.when(rows.nonEmpty)(RepoTableRows(rows)),
      Option.when(pins.nonEmpty)(PinFiles(pins)),
      Option.when(!text.contains(s"]($ReposPage"))(NoReposLink)
    ).flatten

// ---- CLAUDE_PROJECT.config.md ------------------------------------------------------------------

/** A `### <name>` block of the Routines section, and what it carries. */
final case class ProjectRoutine(name: String, hasCron: Boolean, hasPrompt: Boolean):
  def errors: Vector[ProjectConfigError] =
    (Option.when(!hasCron)(ProjectConfigError.NoCron(name)) ++
      Option.when(!hasPrompt)(ProjectConfigError.NoPromptBlock(name))).toVector

object ProjectRoutine:
  private val CronField = "**Schedule (cron)**"
  private val PromptFence = "```text\n"

  def of(block: String): ProjectRoutine =
    ProjectRoutine(
      block.linesIterator.nextOption().getOrElse(""),
      block.contains(CronField),
      block.contains(PromptFence)
    )

enum ProjectConfigError:
  case NoBanner
  case MissingSection(section: String)
  case NoCron(routine: String)
  case NoPromptBlock(routine: String)
  case Leak(found: String)

  def message: String = this match
    case NoBanner                => s"no banner saying a CLI agent ${ProjectConfig.Banner}"
    case MissingSection(section) => s"no section '## $section'"
    case NoCron(routine)         => s"routine '$routine': no cron"
    case NoPromptBlock(routine)  => s"routine '$routine': no prompt block"
    case Leak(found)             => s"${Util.pyreprStr(found)} looks like an e-mail or an id"

object ProjectConfig:
  val File = "CLAUDE_PROJECT.config.md"
  val Routines = "4. Routines"
  private val AfterRoutines = "5."
  private val RoutineHeading = "\n### "

  val Sections = Seq(
    "1. Project",
    "2. Cloud environment",
    "3. Plugins, skills and connectors",
    Routines,
    "5. Joining as a new member",
    "6. Restoring the project",
    "7. Not in this file",
    "8. Keeping it current"
  )
  // AGENTS.md points CLI agents away from this file; the banner says the same to whoever opens it.
  val Banner = "must not read it"

  // The service returns the owner's e-mail, account UUIDs and session ids; none is committed.
  private val Leaks = Seq(
    raw"[\w.+-]+@[\w-]+\.[\w.]+",
    raw"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}",
    raw"\b(?:session|cse|cmsg|env|chan|user)_0[0-9A-Za-z]{20,}"
  ).mkString("|").r

  def routines(text: String): Vector[ProjectRoutine] =
    val section = text.split(s"\n## $Routines\n", 2).last.split(s"\n## $AfterRoutines", 2).head
    section.split(RoutineHeading).toVector.drop(1).map(ProjectRoutine.of)

  /** Banner, sections, routines, then the first leak. */
  def check(text: String): Vector[ProjectConfigError] =
    val banner = Option.when(!text.contains(Banner))(ProjectConfigError.NoBanner)
    val sections = Sections
      .filterNot(s => text.contains(s"\n## $s\n"))
      .map(ProjectConfigError.MissingSection(_))
    val leak = Leaks.findFirstIn(text).map(ProjectConfigError.Leak(_))
    (banner ++ sections ++ routines(text).flatMap(_.errors) ++ leak).toVector
