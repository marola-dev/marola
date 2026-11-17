package marola.tools.submodules

import marola.tools.shared.Conclusion

// The decoders PointerSyncMergeTest does not reach: the commit statuses, a comment's body, and the
// `jq -er` reading of a required field.
class GitHubTest extends munit.FunSuite:
  test("a commit status is a Check whose context is its name"):
    val page = ujson.read("""{"statuses": [{"context": "ci-ok", "state": "pending"}]}""")
    val checks = GitHub.statuses(Seq(page))
    assertEquals(
      checks,
      Vector(Check("ci-ok", CheckStatus.Unfinished("pending"), Conclusion.Unknown("pending")))
    )
    val done = ujson.read("""{"statuses": [{"context": "docs", "state": "failure"}]}""")
    assertEquals(
      GitHub.statuses(Seq(done)),
      Vector(Check("docs", CheckStatus.Completed, Conclusion.Failure))
    )

  test("hasComment looks for one comment whose body is exactly the text"):
    val view =
      ujson.read("""{"comments": [{"body": "held by the hold label"}, {"body": "other"}]}""")
    assertEquals(GitHub.hasComment(view.render(), "held by the hold label"), true)
    assertEquals(GitHub.hasComment(view.render(), "held"), false)
    assertEquals(GitHub.hasComment("""{"comments": []}""", "held"), false)

  test("requiredField joins every page's value, or none when jq would have failed"):
    val out = """{"headRefName": "chore/pointer-sync"} {"headRefName": "other"}"""
    assertEquals(GitHub.requiredField(0, out, "headRefName"), Some("chore/pointer-sync\nother"))
    assertEquals(GitHub.requiredField(4, out, "headRefName"), None)
    assertEquals(GitHub.requiredField(0, """{"headRefName": null}""", "headRefName"), None)
    assertEquals(GitHub.requiredField(0, out, "gone"), None)
