package marola.tools.docs

class ReadingTimeTest extends munit.FunSuite:
  private def minutes(n: Int): Minutes = Minutes.from(n).fold(sys.error, identity)
  private def estimate(text: String, lang: ReadingLang) = ReadingEstimate.of(text, lang)
  private def badge(path: String, lang: ReadingLang, n: Int) =
    ReadingBadge(path, lang, minutes(n)).line
  private def lines(text: String) = ReadingText.lines(text)

  test("counts only prose"):
    val md =
      "# Título\n\n<!-- não conta -->\n![badge](https://x/y.svg) [o mapa](https://marola.dev/) é <b>bom</b>\n\n```sh\nnão conta nada\n```\n\nVeja https://marola.dev — fim.\n"
    assertEquals(estimate(md, ReadingLang.PtBr), ReadingEstimate(7, 1, minutes(1)))
    assertEquals(estimate("palavra " * 363, ReadingLang.PtBr).minutes.value, 3)
    assertEquals(estimate("word " * 476, ReadingLang.En).minutes.value, 2)
    assertEquals(estimate("", ReadingLang.En).minutes.value, 1)

  test("tells the language"):
    assertEquals(ReadingLang.of("README.en.md", "o mar de que"), ReadingLang.En)
    assertEquals(
      ReadingLang.of("README.md", "O mar de Floripa é bom para nadar com a família."),
      ReadingLang.PtBr
    )
    assertEquals(
      ReadingLang.of("docs/X/README.md", "The index of the design docs, with the status of each."),
      ReadingLang.En
    )

  test("links each badge to the discipline"):
    assertEquals(
      badge("README.md", ReadingLang.PtBr, 7),
      """<a href="#tempo-de-leitura"><img src="https://img.shields.io/badge/tempo%20de%20leitura-~7%20min-1682D4" alt="tempo de leitura: ~7 min" /></a>"""
    )
    assert(badge("README.en.md", ReadingLang.En, 2).startsWith("""<a href="#reading-time">"""))
    assert(
      badge("docs/MIPs/README.md", ReadingLang.En, 2).startsWith(
        """<a href="../../README.en.md#reading-time">"""
      )
    )

  test("places and replaces the badge"):
    val line = badge("README.md", ReadingLang.PtBr, 3)
    val top =
      "<p>\n<a href=\"./LICENSE\"><img src=\"https://img.shields.io/badge/license-CC\" /></a>\n</p>\n"
    val placed = ReadingBadge.place(top, line)
    assertEquals(lines(placed)(2), line)
    assertEquals(ReadingBadge.place(placed, line), placed)
    val newer = badge("README.md", ReadingLang.PtBr, 4)
    assertEquals("tempo de leitura".r.findAllIn(ReadingBadge.place(placed, newer)).length, 1)
    assertEquals(
      ReadingBadge.place("# MIP index\n\nDesign docs.\n", "B"),
      "# MIP index\n\nB\n\nDesign docs.\n"
    )

  test("splits lines at LF, a CR before it dropped, with no empty last line"):
    assertEquals(lines("a\r\nb\n\n"), Vector("a", "b", ""))
    assertEquals(lines(""), Vector.empty)
    assertEquals(lines("a\r"), Vector("a\r"))

  test("a reading time is a whole minute or more"):
    assertEquals(Minutes.from(1).map(_.value), Right(1))
    assertEquals(Minutes.from(0), Left("a reading time is at least 1 minute, not 0"))
    assertEquals(Minutes.ceil(0.0).value, 1)
    assertEquals(Minutes.ceil(2.01).value, 3)

  test("prose keeps the text outside fences and counts the fences"):
    val prose = Prose.of("a b\n~~~\nx\n~~~\n  ```py\ny\n  ```\nc 1 —\n")
    assertEquals(prose, Prose("a b\nc 1 —\n", 2))
    assertEquals(prose.words, Seq("a", "b", "c", "1"))

  test("the summary line names the counts in the README's language"):
    val e = ReadingEstimate(10, 2, minutes(3))
    assertEquals(
      e.summary("README.md", ReadingLang.PtBr),
      "README.md: ~3 min (10 palavras, 2 blocos de código)"
    )
    assertEquals(
      e.summary("README.en.md", ReadingLang.En),
      "README.en.md: ~3 min (10 words, 2 code blocks)"
    )
