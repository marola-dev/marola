package marola.tools.docs

import marola.tools.shared.Cli
import marola.tools.shared.Util

import mainargs.{arg, main, Flag, ParserForMethods}

// Every tracked README*.md carries its own reading time, computed here and never by hand: words a
// person reads at Brysbaert (2019)'s 238 wpm (English) or 181 wpm (Portuguese), plus 15 s per code
// block, rounded up. `marola-tools reading-time [--write | --check]` (AGENTS.md).
object ReadingTime:
  private def readmes(root: os.Path): Vector[String] =
    val listed = os
      .proc("git", "ls-files", "--", "README*.md", "**/README*.md")
      .call(cwd = root, check = false, stderr = os.Pipe)
    ReadingText.lines(listed.out.text()).distinct.sorted

  def run(root: os.Path, write: Boolean, check: Boolean): Either[String, Unit] =
    val stale = Vector.newBuilder[String]
    for rel <- readmes(root) do
      val path = root / os.RelPath(rel)
      val text = os.read(path)
      val lang = ReadingLang.of(rel, text)
      val estimate = ReadingEstimate.of(text, lang)
      val want = ReadingBadge.place(text, ReadingBadge(rel, lang, estimate.minutes).line)
      if want != text then if write then os.write.over(path, want) else stale += rel
      println(estimate.summary(rel, lang))
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
