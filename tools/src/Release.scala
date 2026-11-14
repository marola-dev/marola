// Tag main as vX.Y.Z and push the tag; release.yml publishes the GitHub release and Zenodo mints
// the version DOI (MIP-0079). A DOI cannot be deleted, so this refuses anything but a clean main
// level with origin and valid citation metadata.
//
//     marola-tools release 0.2.0 [--dry-run]
//     marola-tools release --guard <ref>   # exit 1 if <ref> only moves pointers since the last v* tag
//
// Hard rule: a submodule pointer move never makes a Zenodo version. release.yml runs the same
// --guard, so a tag pushed by hand around this command still gets no release.
object Release:
  private val Gitlink = "160000"

  def validTag(tag: String): Boolean = tag.matches(raw"v[0-9]+\.[0-9]+\.[0-9]+")

  /** The `git diff --raw` lines that are not a gitlink (mode 160000) on either side. */
  def contentLines(diffRaw: String): Vector[String] =
    Proc.lines(diffRaw).filter { line =>
      val fields = line.trim.split("\\s+")
      val source = fields.head.drop(1)
      val destination = fields.lift(1).getOrElse("")
      source != Gitlink && destination != Gitlink
    }

  /** The newest v* tag that is an ancestor of `ref` (other than `ref`'s own) is the base. */
  def guard(ref: String, cwd: os.Path, proc: Proc): Int = Proc.exitCode {
    val own = proc.captureQuiet(cwd, "git", "describe", "--tags", "--exact-match", ref) match
      case Proc.Result(0, tag) => tag
      case _                   => "none"
    val describe =
      Seq("git", "describe", "--tags", "--abbrev=0", "--match", "v*", "--exclude", own, ref)
    val base = proc.captureQuiet(cwd, describe*).out
    if base.isEmpty then
      Proc.err(s"release: no earlier v* tag before $ref; nothing to compare")
      0
    else
      val changes =
        contentLines(proc.mustCapture(cwd, "git", "diff", "--raw", "--no-renames", base, ref))
      if changes.isEmpty then
        Proc.err(
          s"release: $base..$ref only moves submodule pointers; a pointer move never makes a Zenodo version"
        )
        1
      else
        Proc.err(
          s"release: ${changes.length} file(s) changed outside the submodule pointers since $base"
        )
        0
  }

  private def refuse(message: String): Int =
    Proc.err(s"release: $message")
    1

  /** Tag and push `version`, from `cwd`; with `dryRun`, print the two commands instead. */
  def release(version: String, dryRun: Boolean, cwd: os.Path, proc: Proc): Int = Proc.exitCode {
    val tag = "v" + version.stripPrefix("v")
    if version.isEmpty then refuse("usage: just release <X.Y.Z> [--dry-run]")
    else if !validTag(tag) then refuse(s"'$version' is not MAJOR.MINOR.PATCH")
    else
      val top = proc.toplevel(cwd)
      def git(args: String*) = proc.capture(top, "git" +: args*).out
      if git("branch", "--show-current") != "main" then refuse("check out main first")
      else if git(
          "status",
          "--porcelain",
          "--untracked-files=no",
          "--ignore-submodules=all"
        ).nonEmpty
      then refuse("the working tree is dirty; commit or stash first")
      else
        proc.must(top, "git", "fetch", "-q", "--tags", "origin", "main")
        if git("rev-parse", "HEAD") != git("rev-parse", "origin/main") then
          refuse("main is not level with origin/main; pull or push first")
        else if proc
            .capture(top, "git", "rev-parse", "-q", "--verify", s"refs/tags/$tag")
            .exitCode == 0
        then refuse(s"tag $tag already exists")
        else
          proc.must(top, "scripts/marola-tools", "citation", "--check")
          val guarded = guard("HEAD", top, proc)
          if guarded != 0 then guarded else tagAndPush(tag, dryRun, top, proc)
  }

  private def tagAndPush(tag: String, dryRun: Boolean, top: os.Path, proc: Proc): Int =
    val previous =
      Proc.lines(proc.capture(top, "git", "tag", "-l", "v*", "--sort=-v:refname").out).headOption
    val head = proc.capture(top, "git", "log", "-1", "--format=%h %s").out
    Proc.err(s"release: $tag at $head (previous: ${previous.filter(_.nonEmpty).getOrElse("none")})")
    if dryRun then
      Proc.err(s"+ git tag -a $tag -m 'marola $tag'")
      Proc.err(s"+ git push origin $tag")
    else
      proc.must(top, "git", "tag", "-a", tag, "-m", s"marola $tag")
      proc.must(top, "git", "push", "origin", tag)
      Proc.err(
        s"release: pushed $tag; release.yml publishes it and Zenodo mints the DOI a few minutes later."
      )
    0

  private val Usage =
    "marola-tools release <X.Y.Z> [--dry-run]\nmarola-tools release --guard <ref>"

  // Parsed by hand, not by mainargs: --dry-run anywhere, the last other argument is the version.
  def cli(args: Seq[String]): Int = args match
    case "--guard" +: rest =>
      rest.headOption.filter(_.nonEmpty) match
        case Some(ref) => guard(ref, os.pwd, Proc())
        case None      => refuse("usage: marola-tools release --guard <ref>")
    case _ if args.contains("--help") || args.contains("-h") =>
      Proc.err(Usage)
      0
    case _ =>
      val version = args.filterNot(_ == "--dry-run").lastOption.getOrElse("")
      release(version, args.contains("--dry-run"), os.pwd, Proc())
