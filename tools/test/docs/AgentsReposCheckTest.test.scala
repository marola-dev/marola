package marola.tools.docs

import marola.tools.shared.Util

class AgentsReposCheckTest extends munit.FunSuite:
  import AgentsReposCheck.*

  private val link = s"The repos, their artifacts and pins: [REPOS.md]($ReposPage)."

  private def exitCode(text: String): Int =
    val file = os.temp.dir() / "AGENTS.md"
    os.write(file, text)
    check(file.toString)

  test("table row fails: a table row naming a repo"):
    val text =
      s"# AGENTS.md\n\n$link\n\n| Repo | Holds |\n|---|---|\n| [marola-app](https://github.com/marola-dev/marola-app) | the app |\n"
    assertEquals(exitCode(text), 1)
    assertEquals(
      findings("A", text),
      Seq(s"agents_repos_check: A:7: a repo table row; it belongs in $ReposPage")
    )

  test("pin name fails: a pin file named"):
    val text = s"# AGENTS.md\n\n$link\n\nBump corpus.version after a corpus release.\n"
    assertEquals(exitCode(text), 1)
    assertEquals(
      findings("A", text),
      Seq(s"agents_repos_check: A: names a pin file (5:corpus.version); it belongs in $ReposPage")
    )

  test("link only ok: one link, repos named in prose"):
    assertEquals(
      exitCode(
        s"# AGENTS.md\n\n## The repos\n\n$link\n\nRead marola-app's own AGENTS.md before changing it.\n"
      ),
      0
    )

  test("missing link fails: no link to the routing table"):
    assertEquals(
      exitCode("# AGENTS.md\n\nRead marola-app's own AGENTS.md before changing it.\n"),
      1
    )

  test("mention only fails: the path named, not linked"):
    assertEquals(exitCode(s"# AGENTS.md\n\nThe routing table is $ReposPage, see there.\n"), 1)

  test("every match of every line is listed"):
    val text = s"$link\nmarola-image and resources.version\n| marola-ml | x |\n| [marola-oods] |\n"
    assertEquals(
      findings("A", text),
      Seq(
        s"agents_repos_check: A:3,4: a repo table row; it belongs in $ReposPage",
        s"agents_repos_check: A: names a pin file (2:marola-image 2:resources.version); it belongs in $ReposPage"
      )
    )

  test("a missing file is exit 2"):
    assertEquals(check((os.temp.dir() / "nope.md").toString), 2)

  test("the committed AGENTS.md passes"):
    assertEquals(findings("AGENTS.md", os.read(Util.repoRoot / "AGENTS.md")), Nil)
