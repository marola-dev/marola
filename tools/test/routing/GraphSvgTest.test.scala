package marola.tools.routing

class GraphSvgTest extends munit.FunSuite:
  import GraphSvg.load
  import Figure.{fixed1, layout, render}

  private val sample = ujson.read(
    """{"built_at_commit": "0123456789", "nodes": [
      {"id": "b", "source_file": "marola-app/core/A.scala"},
      {"id": "a", "source_file": "tools/src/main.rs"},
      {"id": "c", "source_file": "scripts/gone.py"},
      {"id": "a", "source_file": "tools/src/main.rs"}
    ], "links": [
      {"source": "a", "target": "b"}, {"source": "b", "target": "a"},
      {"source": "a", "target": "c"}, {"source": "a", "target": "a"}
    ]}"""
  )

  test("assigns repos"):
    assertEquals(Repo.ofSource("marola-ml/x.py").slug, "marola-ml")
    assertEquals(Repo.ofSource("tools/src/main.rs").slug, "marola")
    assertEquals(Repo.ofSource("marola-appx/y").slug, "marola")

  test("loads kept nodes and their links once"):
    val Graph(nodes, links) = load(sample, f => !f.endsWith(".py"))
    assertEquals(nodes.map(_.id), Vector("a", "b"))
    assertEquals(nodes(1).repo.slug, "marola-app")
    assertEquals(links, Vector(Edge(0, 1)))

  test("draws the same bytes every time"):
    val Graph(nodes, links) = load(sample, _ => true)
    val one = render(nodes, links, layout(nodes, links), "t")
    val two = render(nodes, links, layout(nodes, links), "t")
    assertEquals(one, two)
    assertEquals(one.split("<circle", -1).length - 1, 3 + 2)
    assert(one.contains(">marola-app 1</text>"))

  test("rounds to one decimal from the exact binary value, half to even"):
    assertEquals(fixed1(0.25), "0.2")
    assertEquals(fixed1(0.15), "0.1")
    assertEquals(fixed1(1.05), "1.1")
    assertEquals(fixed1(-0.04), "-0.0")
    assertEquals(fixed1(1600.0), "1600.0")
