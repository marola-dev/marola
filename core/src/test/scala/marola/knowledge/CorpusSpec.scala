package marola.knowledge

class CorpusSpec extends munit.FunSuite:

  private val doc =
    """# Rip currents
      |Source: https://www.weather.gov/safety/ripcurrent
      |
      |First paragraph about rip currents.
      |
      |Second paragraph, still short.
      |
      |""".stripMargin + ("x" * 690) + "\n\nLast paragraph.\n"

  test("chunkDocument extracts title and source and merges short paragraphs up to the limit") {
    val chunks = Corpus.chunkDocument(doc)
    assert(chunks.nonEmpty)
    chunks.foreach { c =>
      assertEquals(c.docTitle, "Rip currents")
      assertEquals(c.source, "https://www.weather.gov/safety/ripcurrent")
      assert(c.text.length <= Corpus.MaxChunkChars, s"chunk too long: ${c.text.length}")
      assert(!c.text.contains("# Rip currents") && !c.text.toLowerCase.contains("source:"))
    }
    // the two short paragraphs merge; the 690-char one stands alone; the last one follows
    assertEquals(
      chunks.head.text,
      "First paragraph about rip currents.\n\nSecond paragraph, still short."
    )
    assertEquals(chunks.size, 3)
  }

  test("cosine similarity: identical → 1, orthogonal → 0, zero vector → 0 not NaN") {
    val a = Vector(1.0, 2.0, 3.0)
    assertEqualsDouble(Corpus.cosine(a, a), 1.0, 1e-9)
    assertEqualsDouble(Corpus.cosine(Vector(1.0, 0.0), Vector(0.0, 1.0)), 0.0, 1e-9)
    assertEqualsDouble(Corpus.cosine(a, Vector(0.0, 0.0, 0.0)), 0.0, 1e-9)
  }

  test("OceanQa.buildMessages numbers passages and puts the grounding rule in the system prompt") {
    val ps = List(Passage("Rip currents", "https://x", "swim parallel to the shore", 0.9))
    val msgs = OceanQa.buildMessages("what do I do in a rip?", ps)
    assertEquals(msgs.map(_.role), List("system", "user"))
    assert(msgs.head.content.contains("ONLY the numbered passages"))
    assert(msgs(1).content.contains("[1] (Rip currents — https://x)"))
    assert(msgs(1).content.endsWith("Question: what do I do in a rip?"))
  }

end CorpusSpec
