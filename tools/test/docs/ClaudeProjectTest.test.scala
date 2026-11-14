package marola.tools.docs

import marola.tools.shared.Util

class ClaudeProjectTest extends munit.FunSuite:
  import ClaudeProject.*

  private val good =
    val g = s"> An agent $Banner.\n" + Sections.map(s => s"\n## $s\n\nx\n").mkString
    g.replace(
      "## 4. Routines\n\nx\n",
      "## 4. Routines\n\n### A\n\n| **Schedule (cron)** | `5 * * * *` |\n\n```text\nhi\n```\n"
    )

  test("checks"):
    assertEquals(check(good), Vector.empty)
    assertEquals(
      check(good.replace(Banner, "may read it")),
      Vector(s"no banner saying a CLI agent $Banner")
    )
    assertEquals(
      check(good.replace("## 6. Restoring the project", "## 6. Restore")),
      Vector("no section '## 6. Restoring the project'")
    )
    assertEquals(
      check(good.replace("```text\nhi\n```", "hi")),
      Vector("routine 'A': no prompt block")
    )
    val leaked = check(s"${good}ping someone@example.org\n")
    assert(leaked.length == 1 && leaked.head.contains("e-mail"), leaked)
    assertEquals(check(s"${good}wakes session_01ExampleExampleExample00\n").length, 1)

  test("the committed file passes"):
    assertEquals(check(os.read(Util.repoRoot / "CLAUDE_PROJECT.config.md")), Vector.empty)
