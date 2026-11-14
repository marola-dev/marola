package marola.tools.docs

import marola.tools.shared.Proc

object FetchApiDocsTest:
  /** A bare repo at `bare` whose api-docs branch holds scala/index.html, committed as `subject`. */
  def seedApiDocs(bare: os.Path, subject: String, env: Map[String, String]): Unit =
    def git(args: String*) =
      os.proc(Seq("git", "-c", "user.email=t@t", "-c", "user.name=t") ++ args)
        .call(env = env, propagateEnv = false, stderr = os.Pipe): Unit
    os.makeDir.all(bare)
    git("init", "-q", "--bare", bare.toString)
    val seed = os.temp.dir(prefix = "seed")
    try
      git("init", "-q", seed.toString)
      git("-C", seed.toString, "checkout", "-q", "--orphan", "api-docs")
      os.write(seed / "scala" / "index.html", "hi\n", createFolders = true)
      git("-C", seed.toString, "add", "-A")
      git("-C", seed.toString, "commit", "-q", "-m", subject)
      git("-C", seed.toString, "push", "-q", bare.toString, "api-docs")
    finally os.remove.all(seed)

class FetchApiDocsTest extends munit.FunSuite:
  import FetchApiDocs.*

  private val remote = os.temp.dir(prefix = "api-docs-remote")
  private val env = gitEnv(sys.env) ++ Map(
    "GIT_CONFIG_GLOBAL" -> "/dev/null",
    "GIT_CONFIG_NOSYSTEM" -> "1",
    "API_DOCS_REMOTE" -> s"$remote/<repo>.git"
  )
  FetchApiDocsTest.seedApiDocs(remote / "with-branch.git", "api-docs: 1234567", env)
  os.proc("git", "init", "-q", "--bare", (remote / "no-branch.git").toString)
    .call(env = env, propagateEnv = false): Unit

  private def fetched(
      repo: String,
      dest: os.Path,
      extra: Map[String, String] = Map.empty
  ): (Int, String) =
    val buffer = java.io.ByteArrayOutputStream()
    val stream = java.io.PrintStream(buffer, true, "UTF-8")
    val code = Console.withOut(stream)(fetch(repo, dest, env ++ extra))
    (code, buffer.toString("UTF-8"))

  test("a present api-docs branch is unpacked, its notice naming the commit"):
    val t = os.temp.dir()
    val (code, out) = fetched("with-branch", t / "dest")
    assertEquals(code, 0)
    assert(os.isFile(t / "dest" / "scala" / "index.html"))
    assertEquals(out, s"fetch-api-docs: with-branch -> ${t / "dest"} (api-docs: 1234567)\n")

  test("a missing api-docs branch is a notice, not a failure, and writes nothing"):
    val t = os.temp.dir()
    val (code, out) = fetched("no-branch", t / "dest")
    assertEquals(code, 0)
    assert(!os.exists(t / "dest"))
    assertEquals(out, "fetch-api-docs: no-branch has no api-docs branch yet — skipping\n")

  test("an inherited GIT_DIR (a hook's) neither redirects the fetch nor gets written to"):
    val t = os.temp.dir()
    val (code, _) = fetched("with-branch", t / "dest", Map("GIT_DIR" -> (t / "elsewhere").toString))
    assertEquals(code, 0)
    assert(os.isFile(t / "dest" / "scala" / "index.html"))
    assert(!os.exists(t / "elsewhere"))

  test("the CLI takes REPO DEST; a missing DEST is a usage error"):
    assertEquals(cli(Seq("with-branch")), 2)

  test("the remote template replaces <repo>"):
    assertEquals(
      FetchApiDocs.remote(Map.empty, "marola-app"),
      "https://github.com/marola-dev/marola-app"
    )
    assertEquals(
      FetchApiDocs.remote(Map("API_DOCS_REMOTE" -> ""), "x"),
      "https://github.com/marola-dev/x"
    )

  test("Proc.which follows the given PATH, not the JVM's"):
    val bin = os.temp.dir()
    os.write(bin / "fake-tool", "#!/bin/sh\necho hi\n", perms = "rwxr-xr-x")
    val found = Proc.exact(Map("PATH" -> bin.toString))
    val missing = Proc.exact(Map("PATH" -> "/nonexistent"))
    assertEquals(found.which("fake-tool", os.pwd), Some(bin / "fake-tool"))
    assertEquals(missing.which("fake-tool", os.pwd), None)
    assertEquals(found.call(os.pwd, Seq("fake-tool"), os.Pipe), Proc.Result(0, "hi\n"))
    assertEquals(missing.call(os.pwd, Seq("fake-tool")).exitCode, 127)
