package marola.tools.docs

import marola.tools.shared.Cli
import marola.tools.shared.Proc

import mainargs.{arg, main, ParserForMethods}
import SiteModel.*

// Unpack a repo's `api-docs` branch, the generated API docs its CI force-pushes (MIP-0074 §5.2),
// into a directory; a repo without the branch is a notice. prepare-docs calls `fetch` per repo.
//
//     marola-tools fetch-api-docs REPO DEST   # API_DOCS_REMOTE=<url template>, <repo> replaced
object FetchApiDocs:
  /** Prints one line on stdout either way; nonzero only when unpacking a fetched branch fails. */
  def fetch(repo: String, dest: os.Path, config: ApiDocsConfig): Int =
    val outcome = unpack(repo, dest, config)
    outcome.notice(repo).foreach(println)
    outcome.exitCode

  private def unpack(repo: String, dest: os.Path, config: ApiDocsConfig): ApiDocsFetch =
    val git = Proc.exact(config.gitEnv)
    val tmp = os.temp.dir(prefix = "fetch-api-docs")
    def inTmp(args: String*) = Seq("git", "-C", tmp.toString) ++ args
    val remote = config.remote.url(repo)
    try
      val fetched =
        git.status(os.pwd, "git", "init", "-q", tmp.toString) == 0 &&
          git.quiet(os.pwd, inTmp("fetch", "--depth", "1", "-q", remote, Names.ApiDocs)*) == 0
      if !fetched then ApiDocsFetch.NoBranch
      else
        os.makeDir.all(dest)
        val archive = tmp / s"${Names.ApiDocs}.tar"
        val steps = Seq(
          inTmp("archive", "-o", archive.toString, "FETCH_HEAD"),
          Seq("tar", "-x", "-f", archive.toString, "-C", dest.toString)
        )
        steps.iterator.map(git.status(os.pwd, _*)).find(_ != 0) match
          case Some(code) => ApiDocsFetch.Failed(code)
          case None       =>
            // The subject is api-docs-push's `api-docs: <source sha>`.
            val subject = git.capture(os.pwd, inTmp("log", "-1", "--format=%s", "FETCH_HEAD")*).out
            ApiDocsFetch.Unpacked(dest, subject)
    finally os.remove.all(tmp)

  @main(doc = "Unpack a repo's api-docs branch into a directory (MIP-0074 §5.2)")
  def run(
      @arg(positional = true, doc = "the repo, e.g. marola-app") repo: String,
      @arg(positional = true, doc = "the directory to unpack into") dest: String
  ): Int = fetch(repo, os.Path(dest, os.pwd), ApiDocsConfig.from(sys.env))

  def cli(args: Seq[String]): Int = Cli.run(ParserForMethods(this), "fetch-api-docs", args)
