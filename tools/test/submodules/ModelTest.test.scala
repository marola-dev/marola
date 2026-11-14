package marola.tools.submodules

class ModelTest extends munit.FunSuite:
  private val head = Sha("abcdef0123456789")

  test("a GitHub remote's repo, .git or not"):
    assertEquals(
      RepoSlug.fromGitHubUrl("https://github.com/someone/thing.git"),
      Right(RepoSlug("someone/thing"))
    )
    assert(RepoSlug.fromGitHubUrl("/tmp/app").isLeft)
    assert(RepoSlug.fromGitHubUrl("https://github.com/a/b/c").isLeft, "a deeper path is not a repo")

  test("only a marola-dev remote is a marola-dev repo"):
    assertEquals(
      RepoSlug.marolaDev("https://github.com/marola-dev/marola-app.git"),
      Right(RepoSlug("marola-dev/marola-app"))
    )
    assert(RepoSlug.marolaDev("https://github.com/someone/other").isLeft)
    assert(RepoSlug.marolaDev("https://github.com/marola-dev/a b").isLeft)

  test("a submodule's name comes from its config key"):
    assertEquals(
      Submodule.fromConfig("submodule.marola-app.path", "app"),
      Submodule("marola-app", "app")
    )

  test("moved.tsv row"):
    assertEquals(Moved("app", Sha("a"), Sha("b"), "u").tsvRow, "app\ta\tb\tu\n")
    assertEquals(Bump(Vector.empty).names, "")
    assert(Bump(Vector.empty).isEmpty)

  test("a held PR carries the hold label"):
    assert(Pr(PrNumber("1"), head, Vector("x", "hold")).held)
    assert(!Pr(PrNumber("1"), head, Vector("holder")).held)

  test("conclusions that pass, and their text"):
    assertEquals(
      Seq("success", "skipped", "neutral", "failure", "").map(Conclusion.parse).map(_.passed),
      Seq(true, true, true, false, false)
    )
    assertEquals(
      Seq("success", "skipped", "neutral", "cancelled").map(c => Conclusion.parse(c).render),
      Seq("success", "skipped", "neutral", "cancelled")
    )
    assertEquals(CheckStatus.parse("completed"), CheckStatus.Completed)
    assertEquals(CheckStatus.parse("queued"), CheckStatus.Unfinished("queued"))

  private def check(name: String, status: String, conclusion: String) =
    Check(name, CheckStatus.parse(status), Conclusion.parse(conclusion))

  test("failed and pending checks"):
    val checks = Seq(
      check("a", "completed", "success"),
      check("b", "completed", "failure"),
      check("c", "in_progress", ""),
      check("d", "completed", "")
    )
    assertEquals(Checks.failed(checks), "`b` (failure), `d` ()")
    assertEquals(Checks.pending(checks), "c")

  test("what the merge waits for"):
    val done = Seq(check("a", "completed", "success"))
    assertEquals(Checks.waitingOn(done, Seq(Conclusion.Success)), None)
    assertEquals(Checks.waitingOn(done, Seq.empty), Some("ci-ok"))
    assertEquals(
      Checks.waitingOn(done, Seq(Conclusion.Success, Conclusion.Success)),
      Some("ci-ok"),
      "two ci-ok pages is not one green ci-ok"
    )
    assertEquals(Checks.waitingOn(Seq.empty, Seq(Conclusion.Success)), Some("ci-ok"))
    assertEquals(
      Checks.waitingOn(done :+ check("b", "queued", ""), Seq(Conclusion.Success)),
      Some("b")
    )

  test("a tree entry and whether it is still a pointer"):
    val entry = TreeEntry.parse("160000 commit 2222222222222222222222222222222222222222\tapp")
    assertEquals(entry, TreeEntry("160000", Sha("2" * 40)))
    assert(TreeEntry.isPointer(Seq(entry)))
    assert(!TreeEntry.isPointer(Seq.empty), "deleted")
    assert(!TreeEntry.isPointer(Seq(TreeEntry("100644", Sha("3" * 40)))), "now a file")

  test("scope: gitlinks and REPOS.md's wiring table only"):
    val links = Set("app", "other")
    def of(changed: Vector[String], pointer: Boolean = true, wiring: Boolean = true) =
      ScopeProblem.of(links, changed, _ => pointer, wiring)
    assertEquals(of(Vector("app", WiringDoc.Path)), None)
    assertEquals(
      of(Vector("app", "README.md")),
      Some(ScopeProblem.NotPointers(Vector("README.md")))
    )
    assertEquals(of(Vector("app"), pointer = false), Some(ScopeProblem.NotPointers(Vector("app"))))
    assertEquals(
      of(Vector("app", WiringDoc.Path, "README.md"), wiring = false),
      Some(ScopeProblem.WiringDocOutsideMarkers),
      "REPOS.md outside its markers is named first"
    )
    var asked = false
    assertEquals(ScopeProblem.of(links, Vector("app"), _ => true, { asked = true; false }), None)
    assert(!asked, "REPOS.md unchanged: not read")
    assertEquals(
      ScopeProblem.NotPointers(Vector("a", "b")).message,
      "it changes more than submodule pointers: `a` `b` "
    )

  test("compare statuses on the default branch"):
    val parsed =
      Seq(Some("ahead"), Some("identical"), Some("behind"), Some("diverged"), Some("x"), None)
        .map(CompareStatus.parse)
    assertEquals(parsed.map(_.onDefaultBranch), Seq(true, true, false, false, false, false))
    assertEquals(
      parsed.map(_.render),
      Seq("ahead", "identical", "behind", "diverged", "x", "unreadable")
    )

  test("pointer problems as a person reads them"):
    assertEquals(
      PointerProblem
        .NotOnDefaultBranch("app", Sha("2" * 40), RepoSlug("marola-dev/app"), CompareStatus.Behind)
        .message,
      "`app` moves to `2222222`, which is not on marola-dev/app's default branch (compare: behind)"
    )
    assertEquals(
      PointerProblem.NotMarolaDev("other", "https://x/y").message,
      "`other` points at `https://x/y`, not a marola-dev repo"
    )

  test("refusals"):
    assertEquals(
      Refusal("why").body(head),
      "Not auto-merged at `abcdef0`: why. Left for a person (#739)."
    )
    assertEquals(Refusal.headMoved(None, head), None)
    assertEquals(Refusal.headMoved(Some(head), head), None)
    assertEquals(
      Refusal.headMoved(Some(Sha("1234567890")), head).map(_.body(head)),
      Some(
        "Not auto-merged at `abcdef0`: the checks ran on `1234567`, but the head has moved. The new head's checks retry it (#739)."
      )
    )

  test("the plan for the keyed job"):
    assertEquals(
      Plan(Action.Merge, PrNumber("7"), head).render,
      s"action=merge\npr=7\nhead=$head\n"
    )
    assertEquals(
      Plan(Action.Comment("a\nb"), PrNumber("7"), head).render,
      s"action=comment\npr=7\nhead=$head\nbody=a b\n"
    )

  test("site-data-push tries"):
    assertEquals(Tries("3").limit, 3)
    assertEquals(Tries("x").limit, 0, "not a number is no tries")
    assert(Tries("3").exhausted(3))
    assert(!Tries("3").exhausted(2))
    assertEquals(Tries(Tries.Default).backoffSeconds(2), 4)
    assertEquals(Seq(ReplayBase.Parent, ReplayBase.Root).map(_.arg), Seq("HEAD~1", "--root"))
