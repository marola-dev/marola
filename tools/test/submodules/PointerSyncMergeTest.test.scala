package marola.tools.submodules

import marola.tools.shared.Proc

import java.io.{ByteArrayOutputStream, PrintStream}

class PointerSyncMergeTest extends munit.FunSuite:
  import PointerSyncMerge.*

  // ci-ok is ci.yml's aggregate job; app 15368 is GitHub Actions.
  private val green =
    """{"check_runs":[{"name":"changes","status":"completed","conclusion":"success"},{"name":"rust-ci","status":"completed","conclusion":"skipped"},{"name":"gemini","status":"completed","conclusion":"neutral"},{"name":"ci-ok","status":"completed","conclusion":"success","app":{"id":15368}}]}"""
  private val failedRun =
    """{"check_runs":[{"name":"changes","status":"completed","conclusion":"success"},{"name":"docs-build","status":"completed","conclusion":"failure"}]}"""
  private val noCiOk =
    """{"check_runs":[{"name":"changes","status":"completed","conclusion":"success"},{"name":"PR body","status":"completed","conclusion":"success","app":{"id":15368}}]}"""
  private val otherApp =
    """{"check_runs":[{"name":"changes","status":"completed","conclusion":"success"},{"name":"ci-ok","status":"completed","conclusion":"success","app":{"id":99}}]}"""
  private val ciOkFailed =
    """{"check_runs":[{"name":"ci-ok","status":"completed","conclusion":"failure","app":{"id":15368}}]}"""

  // A fake gh on PATH that answers from files in $FAKE_GH and logs what it was asked to do.
  private val fakeGh = """#!/usr/bin/env bash
set -euo pipefail
d="$FAKE_GH"
case "$*" in
  "pr list "*) cat "$d/prs.json" ;;
  "pr view "*) cat "$d/comments.json" ;;
  "pr comment "*) printf '%s' "${*: -1}" >"$d/last-comment"; echo "comment $3" >>"$d/calls" ;;
  "pr merge "*) echo "merge ${*:3}" >>"$d/calls" ;;
  "api "*/check-runs*) cat "$d/check-runs.json" ;;
  "api "*/status*) cat "$d/status.json" ;;
  # Answered only with the read token: the App token cannot see the submodule repos.
  "api repos/marola-dev/app/compare/"*) [ "$GH_TOKEN" = read ] && [ -s "$d/compare.json" ] && cat "$d/compare.json" ;;
  "api repos/marola-dev/app") [ "$GH_TOKEN" = read ] && echo '{"default_branch":"trunk"}' ;;
  *) echo "fake gh: unexpected: $*" >&2; exit 1 ;;
esac
"""

  private final class Fixture(val t: os.Path):
    val gh = t / "gh"
    val origin = t / "origin"
    val work = t / "work"
    os.makeDir.all(t / "bin")
    os.makeDir.all(gh)
    os.write(t / "bin" / "gh", fakeGh, perms = "rwxr-xr-x")
    os.write(t / "gitconfig", "[user]\n\tname = t\n\temail = t@t\n")
    // A pre-push hook exports GIT_DIR and friends; the fixtures would otherwise commit into the caller's repo.
    val gitEnv: Map[String, String] =
      Seq("GIT_DIR", "GIT_WORK_TREE", "GIT_INDEX_FILE", "GIT_PREFIX", "GIT_COMMON_DIR")
        .map(_ -> null)
        .toMap ++
        Map("GIT_CONFIG_GLOBAL" -> (t / "gitconfig").toString, "GIT_CONFIG_NOSYSTEM" -> "1")
    val proc = Proc(
      gitEnv ++ Map(
        "PATH" -> s"${t / "bin"}:${sys.env("PATH")}",
        "FAKE_GH" -> gh.toString,
        "GH_TOKEN" -> "app",
        "GH_READ_TOKEN" -> "read"
      )
    )

    def git(cwd: os.Path, args: String*): String =
      os.proc("git" +: args).call(cwd = cwd, env = gitEnv, stderr = os.Pipe).out.text().stripLineEnd

    def gitlink(fill: Char, path: String): Unit =
      val _ =
        git(origin, "update-index", "--add", "--cacheinfo", s"160000,${fill.toString * 40},$path")

    os.makeDir.all(origin)
    git(origin, "init", "-q", "-b", "main")
    os.write(
      origin / ".gitmodules",
      "[submodule \"app\"]\n\tpath = app\n\turl = https://github.com/marola-dev/app.git\n" +
        "[submodule \"other\"]\n\tpath = other\n\turl = https://github.com/someone/other\n"
    )
    os.write(
      origin / os.RelPath(ReposMd),
      "intro\n<!-- wiring:start -->\n| a | b |\n<!-- wiring:end -->\noutro\n",
      createFolders = true
    )
    os.write(origin / "README.md", "readme\n")
    git(origin, "add", "-A")
    // An empty dir is an unpopulated submodule; without it, `commit -a` would delete the gitlink.
    os.makeDir(origin / "app")
    os.makeDir(origin / "other")
    gitlink('1', "app")
    gitlink('1', "other")
    git(origin, "commit", "-q", "-m", "base")

    /** A commit off main moving that gitlink, plus the edit. */
    def head(name: String, link: String)(edit: => Unit): String =
      git(origin, "checkout", "-q", "-b", name, "main")
      gitlink('2', link)
      edit
      git(origin, "commit", "-qam", name)
      val sha = git(origin, "rev-parse", "HEAD")
      git(origin, "checkout", "-q", "main")
      sha

    private def edit(file: os.Path, from: String, to: String): Unit =
      os.write.over(file, os.read(file).replace(from, to))
    val ok = head("ok", "app")(edit(origin / os.RelPath(ReposMd), "| a | b |", "| a | c |"))
    val outside = head("out", "app")(os.write.append(origin / "README.md", "more\n"))
    val markers = head("md", "app")(edit(origin / os.RelPath(ReposMd), "outro", "outro, edited"))
    val other = head("other", "other")(())
    git(t, "clone", "-q", origin.toString, work.toString)

    /** Open PR #7 at `sha` with these check runs, no statuses, no comments. */
    def pr(sha: String, checkRuns: String, labels: String = "[]"): Unit =
      git(origin, "update-ref", "refs/pull/7/head", sha)
      os.write.over(
        gh / "prs.json",
        s"""[{"number":7,"headRefOid":"$sha","isCrossRepository":false,"labels":$labels}]"""
      )
      os.write.over(gh / "check-runs.json", checkRuns)
      // Zero statuses still reads "pending", as GitHub's combined status does.
      os.write.over(gh / "status.json", """{"state":"pending","statuses":[]}""")
      os.write.over(gh / "comments.json", """{"comments":[]}""")
      os.write.over(gh / "compare.json", """{"status":"ahead"}""")

    var out = ""

    /** One run in the clone: what it did to the fake gh, plus a non-zero exit. */
    def go(sha: String = "", dryRun: Boolean = false, plan: Option[os.Path] = None): String =
      os.write.over(gh / "calls", "")
      os.remove(gh / "last-comment")
      val bytes = ByteArrayOutputStream()
      val saved = System.out
      System.setOut(PrintStream(bytes, true))
      val code = try runIn("marola-dev/marola", sha, dryRun, work, proc, plan)
      finally System.setOut(saved)
      out = bytes.toString
      val calls = os.read(gh / "calls").stripLineEnd
      if code == 0 then calls else s"$calls\nexit $code".stripLeading

    def lastComment: String =
      if os.exists(gh / "last-comment") then os.read(gh / "last-comment") else ""

  private val fixture = FunFixture[Fixture](_ => Fixture(os.temp.dir()), f => os.remove.all(f.t))
  private def merged(sha: String) =
    s"merge 7 --squash --admin --match-head-commit $sha -R marola-dev/marola"

  fixture.test("merges green in scope"): f =>
    f.pr(f.ok, green)
    assertEquals(f.go(f.ok), merged(f.ok), "squash-merges #7 pinned to the head")
    assertEquals(f.go(f.ok, dryRun = true), "", "--dry-run calls nothing")
    assertEquals(
      f.out.linesIterator.count(_.contains("merge 7 --squash")),
      1,
      "--dry-run prints the merge command"
    )

  fixture.test("--plan writes the merge and calls nothing"): f =>
    f.pr(f.ok, green)
    val plan = f.t / "plan"
    assertEquals(f.go(f.ok, plan = Some(plan)), "", "--plan calls nothing")
    assertEquals(os.read(plan), s"action=merge\npr=7\nhead=${f.ok}\n")

  fixture.test("--plan writes a refusal as a comment"): f =>
    f.pr(f.ok, failedRun)
    val plan = f.t / "plan"
    assertEquals(f.go(f.ok, plan = Some(plan)), "", "--plan calls nothing")
    val lines = os.read.lines(plan)
    assertEquals(lines.take(3), Seq("action=comment", "pr=7", s"head=${f.ok}"))
    assert(lines(3).startsWith("body=") && lines(3).contains("docs-build"), lines(3))

  fixture.test("--plan writes nothing while a check is pending"): f =>
    f.pr(
      f.ok,
      """{"check_runs":[{"name":"docs-build","status":"in_progress","conclusion":null},{"name":"ci-ok","status":"completed","conclusion":"success","app":{"id":15368}}]}"""
    )
    val plan = f.t / "plan"
    assertEquals(f.go(f.ok, plan = Some(plan)), "")
    assert(!os.exists(plan), "no action")

  fixture.test("refuses a pending check"): f =>
    f.pr(
      f.ok,
      """{"check_runs":[{"name":"changes","status":"completed","conclusion":"success"},{"name":"docs-build","status":"in_progress","conclusion":null},{"name":"ci-ok","status":"completed","conclusion":"success","app":{"id":15368}}]}"""
    )
    assertEquals(f.go(f.ok), "", "no merge, no comment")

  fixture.test("refuses a failed check, once"): f =>
    f.pr(f.ok, failedRun)
    assertEquals(f.go(f.ok), "comment 7", "one comment, no merge")
    assert(f.lastComment.contains("docs-build"), "the comment names the failed check")
    os.write.over(
      f.gh / "comments.json",
      ujson.write(ujson.Obj("comments" -> ujson.Arr(ujson.Obj("body" -> f.lastComment))))
    )
    assertEquals(f.go(f.ok), "", "the same refusal is not commented twice")
    f.pr(f.ok, green)
    os.write.over(
      f.gh / "status.json",
      """{"state":"failure","statuses":[{"context":"ext/lint","state":"failure"}]}"""
    )
    assertEquals(f.go(), "comment 7", "a failed commit status refuses too")

  fixture.test("refuses a moved head"): f =>
    f.pr(f.ok, green)
    assertEquals(f.go(f.outside), "comment 7")

  fixture.test("refuses a file outside scope"): f =>
    f.pr(f.outside, green)
    assertEquals(f.go(f.outside), "comment 7")
    assert(f.lastComment.contains("README.md"), "the comment names the file")
    assertEquals(
      f.lastComment,
      s"Not auto-merged at `${f.outside.take(7)}`: it changes more than submodule pointers: `README.md` . Left for a person (#739)."
    )

  fixture.test("refuses REPOS.md outside its markers"): f =>
    f.pr(f.markers, green)
    assertEquals(f.go(f.markers), "comment 7")
    assert(f.lastComment.contains("wiring:start"), "the comment names the markers")

  fixture.test("refuses a gitlink not on its default branch"): f =>
    f.pr(f.ok, green)
    os.write.over(f.gh / "compare.json", """{"status":"diverged"}""")
    assertEquals(f.go(f.ok), "comment 7", "a fork commit (diverged): one comment, no merge")
    assert(f.lastComment.contains("`app`"), "the comment names the gitlink")
    f.pr(f.ok, green)
    os.write.over(f.gh / "compare.json", "")
    assertEquals(f.go(f.ok), "comment 7", "an unreadable compare fails closed")
    assert(f.lastComment.contains("(compare: unreadable)"))
    f.pr(f.other, green)
    assertEquals(f.go(f.other), "comment 7", "a gitlink outside marola-dev is refused")
    assert(f.lastComment.contains("not a marola-dev repo"))

  fixture.test("refuses a commit built on the default branch"): f =>
    f.pr(f.ok, green)
    os.write.over(f.gh / "compare.json", """{"status":"behind"}""")
    assertEquals(
      f.go(f.ok),
      "comment 7",
      "a commit on top of the default branch, never merged into it"
    )

  fixture.test("merges a gitlink at its default branch's tip"): f =>
    f.pr(f.ok, green)
    os.write.over(f.gh / "compare.json", """{"status":"identical"}""")
    assertEquals(f.go(f.ok), merged(f.ok))

  fixture.test("skips a held PR"): f =>
    f.pr(f.ok, green, """[{"name":"hold"}]""")
    assertEquals(f.go(f.ok), "", "a held PR: no merge, no comment")
    assertEquals(f.out.linesIterator.count(_.contains("hold")), 1, "it says why")

  fixture.test("waits for ci-ok"): f =>
    f.pr(f.ok, noCiOk)
    assertEquals(f.go(f.ok), "", "every check present is green but ci-ok has not reported")
    f.pr(
      f.ok,
      """{"check_runs":[{"name":"ci-ok","status":"in_progress","conclusion":null,"app":{"id":15368}}]}"""
    )
    assertEquals(f.go(f.ok), "", "ci-ok still running")

  fixture.test("ignores a ci-ok from another app"): f =>
    f.pr(f.ok, otherApp)
    assertEquals(f.go(f.ok), "")

  fixture.test("refuses a failed ci-ok"): f =>
    f.pr(f.ok, ciOkFailed)
    assertEquals(f.go(f.ok), "comment 7")

  fixture.test("no open PR is quiet"): f =>
    os.write.over(f.gh / "prs.json", "[]")
    assertEquals(f.go(), "")

  test("a fork's PR with the same branch is not ours"):
    val list =
      """[{"number":3,"headRefOid":"abc","isCrossRepository":true,"labels":[]},{"number":4,"headRefOid":"def","isCrossRepository":false,"labels":[{"name":"x"}]}]"""
    assertEquals(openPr(list), Some(Pr("4", "def", held = false)))
    assertEquals(openPr("[]"), None)
    assertEquals(openPr("not json"), None)

  test("checks from several pages, ci-ok on the second"):
    val pages = """{"check_runs":[{"name":"a\tb","status":"completed","conclusion":null}]}
{"check_runs":[{"name":"ci-ok","status":"completed","conclusion":"success","app":{"id":15368}}]}"""
    val runs = Json.values(pages)
    assert(runs.complete)
    assertEquals(checkRuns(runs.documents).map(_.name), Vector("a\\tb", "ci-ok"))
    assertEquals(failed(checkRuns(runs.documents)), "`a\\tb` ()")
    assertEquals(ciOk(runs.documents), "success")

  test("the wiring block is the only part of REPOS.md that may change"):
    val text = "intro\n<!-- wiring:start -->\n| a | b |\n<!-- wiring:end -->\noutro"
    assertEquals(outsideMarkers(text), "intro\n<!-- wiring:start -->\n<!-- wiring:end -->\noutro\n")
    assertEquals(outsideMarkers(text.replace("| a | b |", "| x |")), outsideMarkers(text))
    assertNotEquals(
      outsideMarkers(text.replace("intro\n", "intro\r\n")),
      outsideMarkers(text),
      "a CRLF is a change"
    )
