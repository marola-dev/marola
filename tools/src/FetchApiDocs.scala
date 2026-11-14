import mainargs.{arg, main, ParserForMethods}

// Unpack a repo's `api-docs` branch, the generated API docs its CI force-pushes (MIP-0074 §5.2),
// into a directory; a repo without the branch is a notice. prepare-docs calls `fetch` per repo.
//
//     marola-tools fetch-api-docs REPO DEST   # API_DOCS_REMOTE=<url template>, <repo> replaced
object FetchApiDocs:
  val Branch = "api-docs"
  val DefaultRemote = "https://github.com/marola-dev/<repo>"

  /** `env` for every git call: a hook's GIT_DIR would point it at the caller's repo, and a missing
    * or private repo must not stop a local build at a credential prompt.
    */
  def gitEnv(env: Map[String, String]): Map[String, String] =
    env -- Seq("GIT_DIR", "GIT_WORK_TREE", "GIT_INDEX_FILE", "GIT_PREFIX", "GIT_COMMON_DIR") +
      ("GIT_TERMINAL_PROMPT" -> "0")

  def remote(env: Map[String, String], repo: String): String =
    env.get("API_DOCS_REMOTE").filter(_.nonEmpty).getOrElse(DefaultRemote).replace("<repo>", repo)

  /** Prints one line on stdout either way; nonzero only when unpacking a fetched branch fails. */
  def fetch(repo: String, dest: os.Path, env: Map[String, String]): Int =
    val git = Proc.exact(gitEnv(env))
    val tmp = os.temp.dir(prefix = "fetch-api-docs")
    def inTmp(args: String*) = Seq("git", "-C", tmp.toString) ++ args
    try
      val fetched =
        git.status(os.pwd, "git", "init", "-q", tmp.toString) == 0 &&
          git.quiet(os.pwd, inTmp("fetch", "--depth", "1", "-q", remote(env, repo), Branch)*) == 0
      if !fetched then
        println(s"fetch-api-docs: $repo has no $Branch branch yet — skipping")
        0
      else
        os.makeDir.all(dest)
        val archive = tmp / "api-docs.tar"
        val unpack = Seq(
          inTmp("archive", "-o", archive.toString, "FETCH_HEAD"),
          Seq("tar", "-x", "-f", archive.toString, "-C", dest.toString)
        )
        unpack.iterator.map(git.status(os.pwd, _*)).find(_ != 0) match
          case Some(code) => code
          case None       =>
            // The subject is api-docs-push's `api-docs: <source sha>`.
            val subject = git.capture(os.pwd, inTmp("log", "-1", "--format=%s", "FETCH_HEAD")*).out
            println(s"fetch-api-docs: $repo -> $dest ($subject)")
            0
    finally os.remove.all(tmp)

  @main(doc = "Unpack a repo's api-docs branch into a directory (MIP-0074 §5.2)")
  def run(
      @arg(positional = true, doc = "the repo, e.g. marola-app") repo: String,
      @arg(positional = true, doc = "the directory to unpack into") dest: String
  ): Int = fetch(repo, os.Path(dest, os.pwd), sys.env)

  def cli(args: Seq[String]): Int = Cli.run(ParserForMethods(this), "fetch-api-docs", args)
