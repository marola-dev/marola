package marola.tools.submodules

import marola.tools.shared.Proc

class PointerSyncTest extends munit.FunSuite:
  import PointerSync.*

  // Fake submodules made with `git init`, a fake `wiring` on PATH, no network.
  private final class Fixture(val t: os.Path):
    os.write(
      t / "gitconfig",
      // Local paths as submodules are refused by default (CVE-2022-39253). A global config file,
      // not GIT_CONFIG_COUNT: git strips that from a submodule's fetch.
      "[protocol \"file\"]\n\tallow = always\n[user]\n\tname = t\n\temail = t@t\n"
    )
    os.makeDir.all(t / "bin")
    os.makeDir.all(t / "none")
    // Rewrites REPOS.md's block with app's checked-out commit: a block that names the new tip
    // proves it ran after the submodules moved.
    os.write(
      t / "bin" / "wiring",
      "#!/usr/bin/env bash\nset -euo pipefail\n" +
        "sed \"s/^app at .*/app at $(git -C app rev-parse HEAD)/\" \"$1\" >\"$1.new\"\nmv \"$1.new\" \"$1\"\n",
      perms = "rwxr-xr-x"
    )
    // A pre-push hook exports GIT_DIR and friends; the fixtures would otherwise commit into the caller's repo.
    val gitEnv: Map[String, String] =
      Seq("GIT_DIR", "GIT_WORK_TREE", "GIT_INDEX_FILE", "GIT_PREFIX", "GIT_COMMON_DIR")
        .map(_ -> null)
        .toMap ++
        Map("GIT_CONFIG_GLOBAL" -> (t / "gitconfig").toString, "GIT_CONFIG_NOSYSTEM" -> "1")
    val proc = Proc(gitEnv + ("PATH" -> s"${t / "bin"}:${sys.env("PATH")}"))

    def git(cwd: os.Path, args: String*): String =
      os.proc("git" +: args).call(cwd = cwd, env = gitEnv, stderr = os.Pipe).out.text().stripLineEnd

    val umbrella = t / "umbrella"
    for repo <- Seq("app", "site") do
      git(t, "init", "-q", "-b", "trunk", repo)
      git(t / repo, "commit", "-q", "--allow-empty", "-m", s"$repo 1")
    git(t, "init", "-q", "-b", "main", "umbrella")
    git(umbrella, "submodule", "add", "-q", (t / "app").toString, "app")
    git(umbrella, "submodule", "add", "-q", (t / "site").toString, "site")
    os.write(
      umbrella / os.RelPath(ReposMd),
      s"<!-- wiring:start -->\napp at ${tip("app")}\n<!-- wiring:end -->\n",
      createFolders = true
    )
    git(umbrella, "add", "docs")
    git(umbrella, "commit", "-q", "-m", "two submodules")

    def tip(repo: String): String = git(t / repo, "rev-parse", "HEAD")
    def head: String = git(umbrella, "rev-parse", "HEAD")
    def advance(repo: String, message: String): Unit =
      val _ = git(t / repo, "commit", "-q", "--allow-empty", "-m", message)
    def bumpTo(out: String, runner: Proc = proc): Int = bump(umbrella, t / out, runner)

  private val fixture = FunFixture[Fixture](_ => Fixture(os.temp.dir()), f => os.remove.all(f.t))

  fixture.test("nothing upstream moved: no commit, empty moved.tsv"): f =>
    val base = f.head
    assertEquals(f.bumpTo("out0"), 0)
    assertEquals(f.head, base)
    assertEquals(os.read(f.t / "out0" / "moved.tsv"), "")

  fixture.test("app's default branch (trunk, not master) moved twice, site not"): f =>
    val base = f.head
    val app1 = f.tip("app")
    f.advance("app", "app 2")
    f.advance("app", "app 3")
    val app3 = f.tip("app")
    assertEquals(f.bumpTo("out1"), 0)
    assertEquals(f.git(f.umbrella, "rev-parse", "HEAD:app"), app3)
    assertEquals(f.git(f.umbrella, "rev-parse", "HEAD:site"), f.tip("site"))
    assertEquals(f.git(f.umbrella, "rev-list", "--count", s"$base..HEAD"), "1")
    val moved = os.read.lines(f.t / "out1" / "moved.tsv").map(_.split('\t').take(3).mkString("\t"))
    assertEquals(moved, Seq(s"app\t$app1\t$app3"))
    assertEquals(
      f.git(f.umbrella, "log", "-1", "--format=%s"),
      "chore: move submodule pointers (app)"
    )
    val trailers = f
      .git(f.umbrella, "log", "-1", "--format=%(trailers:only,unfold)")
      .linesIterator
      .filter(_.nonEmpty)
    assertEquals(trailers.map(_.takeWhile(_ != ':')).toSeq, Seq("Tested", "Co-Authored-By"))
    val row = s"| `app` | `${app1.take(7)}` | `${app3.take(7)}` |"
    assertEquals(os.read.lines(f.t / "out1" / "pr-body.md").count(_.contains(row)), 1)
    assertEquals(f.git(f.umbrella, "status", "--porcelain"), "")

  fixture.test("both moved: one commit names both"): f =>
    f.advance("app", "app 2")
    f.advance("site", "site 2")
    assertEquals(f.bumpTo("out2"), 0)
    assertEquals(
      f.git(f.umbrella, "log", "-1", "--format=%s"),
      "chore: move submodule pointers (app, site)"
    )
    assertEquals(os.read.lines(f.t / "out2" / "moved.tsv").length, 2)

  fixture.test("the pointer commit carries the wiring block, regenerated from the new tip"): f =>
    f.advance("app", "app 5")
    val app5 = f.tip("app")
    val before = f.head
    assertEquals(f.bumpTo("out3"), 0)
    assertEquals(f.git(f.umbrella, "rev-list", "--count", s"$before..HEAD"), "1")
    assertEquals(
      f.git(f.umbrella, "diff-tree", "--no-commit-id", "--name-only", "-r", "HEAD")
        .linesIterator
        .toSeq,
      Seq("app", ReposMd)
    )
    val block = f.git(f.umbrella, "show", s"HEAD:$ReposMd").linesIterator.collectFirst {
      case line if line.startsWith("app at ") => line.stripPrefix("app at ")
    }
    assertEquals(block, Some(app5))
    assertEquals(f.git(f.umbrella, "status", "--porcelain"), "")
    // Nothing moved since: no commit, the block as it was.
    val after = f.head
    assertEquals(f.bumpTo("out4"), 0)
    assertEquals(f.head, after)
    assertEquals(f.git(f.umbrella, "status", "--porcelain"), "")

  fixture.test("wiring missing from PATH: fails before moving anything"): f =>
    f.advance("app", "app 2")
    val before = f.head
    assertEquals(
      f.bumpTo("out5", f.proc.copy(env = f.proc.env + ("PATH" -> (f.t / "none").toString))),
      1
    )
    assertEquals(f.head, before)

  fixture.test("--out is required"): f =>
    assertEquals(runIn(f.umbrella, "", f.proc), 2)

  test("compare links"):
    assertEquals(
      compareUrl("https://github.com/marola-dev/marola-app.git", "aaa", "bbb"),
      Some("https://github.com/marola-dev/marola-app/compare/aaa...bbb")
    )
    assertEquals(
      compareUrl("https://github.com/marola-dev/marola-site", "aaa", "bbb"),
      Some("https://github.com/marola-dev/marola-site/compare/aaa...bbb")
    )
    assertEquals(compareUrl("/tmp/app", "aaa", "bbb"), None)

  test("commit message and PR body"):
    val moved = Seq(
      Moved("app", "a" * 40, "b" * 40, "https://github.com/marola-dev/app.git"),
      Moved("x", "c" * 40, "d" * 40, "/x")
    )
    assertEquals(
      commitMessage(moved),
      "chore: move submodule pointers (app, x)\n\nEach submodule's default-branch tip, as git submodule update --remote found it:\n\n" +
        "- app: aaaaaaa -> bbbbbbb\n- x: ccccccc -> ddddddd\n\n" + Trailers
    )
    val body = prBody(moved)
    assert(
      body.contains(
        s"| `app` | `aaaaaaa` | `bbbbbbb` | [compare](https://github.com/marola-dev/app/compare/${"a" * 40}...${"b" * 40}) |\n"
      )
    )
    assert(body.endsWith("| `x` | `ccccccc` | `ddddddd` |  |\n"))
