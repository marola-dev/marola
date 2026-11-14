package marola.tools.docs

import marola.tools.shared.{Cli, Invocation}

import mainargs.{arg, main, Flag, ParserForMethods}

// Every tracked README*.md carries its own reading time, computed here and never by hand: words a
// person reads at Brysbaert (2019)'s 238 wpm (English) or 181 wpm (Portuguese), plus 15 s per code
// block, rounded up. `marola-tools reading-time [--write | --check]` (AGENTS.md).
object ReadingTime:
  private val ReadmePatterns = Seq("README*.md", "**/README*.md")

  private def readmes(root: os.Path): Vector[String] =
    val listed = os
      .proc(Seq("git", "ls-files", "--") ++ ReadmePatterns)
      .call(cwd = root, check = false, stderr = os.Pipe)
    ReadingText.lines(listed.out.text()).distinct.sorted

  /** One README's estimate printed, its badge written with `write`; the README when it is stale. */
  private def update(root: os.Path, rel: String, write: Boolean): Option[String] =
    val path = root / os.RelPath(rel)
    val text = os.read(path)
    val lang = ReadingLang.of(rel, text)
    val estimate = ReadingEstimate.of(text, lang)
    val want = ReadingBadge.place(text, ReadingBadge(rel, lang, estimate.minutes).line)
    val stale = Option.when(want != text && !write)(rel)
    if want != text && write then os.write.over(path, want)
    println(estimate.summary(rel, lang))
    stale

  def run(root: os.Path, write: Boolean, check: Boolean): Either[String, Unit] =
    val staleFiles = readmes(root).flatMap(update(root, _, write))
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
    else Cli.report(run(Invocation.current().repoRoot, write.value, check.value))

  def cli(args: Seq[String]): Int = Cli.run(ParserForMethods(this), "reading-time", args)
