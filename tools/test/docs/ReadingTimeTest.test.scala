package marola.tools.docs

class ReadingTimeTest extends munit.FunSuite:
  import ReadingTime.*

  test("counts only prose"):
    val md =
      "# Título\n\n<!-- não conta -->\n![badge](https://x/y.svg) [o mapa](https://marola.dev/) é <b>bom</b>\n\n```sh\nnão conta nada\n```\n\nVeja https://marola.dev — fim.\n"
    assertEquals(estimate(md, Lang.PtBr), Estimate(7, 1, 1))
    assertEquals(estimate("palavra " * 363, Lang.PtBr).minutes, 3)
    assertEquals(estimate("word " * 476, Lang.En).minutes, 2)
    assertEquals(estimate("", Lang.En).minutes, 1)

  test("tells the language"):
    assertEquals(langOf("README.en.md", "o mar de que"), Lang.En)
    assertEquals(langOf("README.md", "O mar de Floripa é bom para nadar com a família."), Lang.PtBr)
    assertEquals(
      langOf("docs/X/README.md", "The index of the design docs, with the status of each."),
      Lang.En
    )

  test("links each badge to the discipline"):
    assertEquals(
      badge("README.md", Lang.PtBr, 7),
      """<a href="#tempo-de-leitura"><img src="https://img.shields.io/badge/tempo%20de%20leitura-~7%20min-1682D4" alt="tempo de leitura: ~7 min" /></a>"""
    )
    assert(badge("README.en.md", Lang.En, 2).startsWith("""<a href="#reading-time">"""))
    assert(
      badge("docs/MIPs/README.md", Lang.En, 2).startsWith(
        """<a href="../../README.en.md#reading-time">"""
      )
    )

  test("places and replaces the badge"):
    val line = badge("README.md", Lang.PtBr, 3)
    val top =
      "<p>\n<a href=\"./LICENSE\"><img src=\"https://img.shields.io/badge/license-CC\" /></a>\n</p>\n"
    val placed = withBadge(top, line)
    assertEquals(lines(placed)(2), line)
    assertEquals(withBadge(placed, line), placed)
    val newer = badge("README.md", Lang.PtBr, 4)
    assertEquals("tempo de leitura".r.findAllIn(withBadge(placed, newer)).length, 1)
    assertEquals(
      withBadge("# MIP index\n\nDesign docs.\n", "B"),
      "# MIP index\n\nB\n\nDesign docs.\n"
    )

  test("splits lines at LF, a CR before it dropped, with no empty last line"):
    assertEquals(lines("a\r\nb\n\n"), Vector("a", "b", ""))
    assertEquals(lines(""), Vector.empty)
    assertEquals(lines("a\r"), Vector("a\r"))
