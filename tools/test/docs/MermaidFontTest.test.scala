package marola.tools.docs

class MermaidFontTest extends munit.FunSuite:
  import MermaidFont.*

  private val sample = """# Sample
    |
    |```mermaid
    |flowchart TD
    |  a --> b
    |```
    |
    |````markdown
    |```mermaid
    |flowchart TD
    |  a --> b
    |```
    |````
    |
    |```mermaid
    |%%{init: {"themeVariables": {"git0": "#1ac5da"}}}%%
    |gitGraph
    |  commit
    |```
    |""".stripMargin

  private def count(text: String, part: String) = text.sliding(part.length).count(_ == part)

  test("pins each real fence once"):
    val out = pinMermaidFont(sample)
    val lines = out.linesIterator.toVector
    assertEquals(count(out, "fontFamily"), 2, out)
    assertEquals(lines.count(_ == "```"), 3)
    assertEquals(count(out, "````markdown"), 1)
    assertEquals(count(out, "git0"), 1)
    val afterOpen = lines.sliding(2).collect { case Seq("```mermaid", next) => next }.toVector
    assertEquals(afterOpen.length, 3)
    assertEquals(afterOpen.count(_.contains("Open Sans")), 2)
    assertEquals(
      InitLine,
      """%%{init: {"fontFamily": "\"Open Sans\", \"trebuchet ms\", verdana, arial, sans-serif"}}%%"""
    )

  test("idempotent"):
    val once = pinMermaidFont(sample)
    assertEquals(pinMermaidFont(once), once)
