package marola.knowledge

import java.nio.file.Files

class CorpusSpec extends munit.FunSuite:

  private given unsafe: kyo.AllowUnsafe = kyo.AllowUnsafe.embrace.danger

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

  test("chunkDocument stamps safety onto every chunk it produces") {
    val plain = Corpus.chunkDocument(doc)
    assert(plain.forall(!_.safety))
    val safe = Corpus.chunkDocument(doc, safety = true)
    assert(safe.forall(_.safety))
  }

  test("listFiles: top-level .md files plus one level into safety/, not deeper, sorted") {
    val tmp = Files.createTempDirectory("marola-corpus-listfiles")
    Files.writeString(tmp.resolve("b.md"), "# B")
    Files.writeString(tmp.resolve("a.md"), "# A")
    Files.writeString(tmp.resolve("not-markdown.txt"), "ignore me")
    val safetyDir = tmp.resolve("safety")
    Files.createDirectory(safetyDir)
    Files.writeString(safetyDir.resolve("rip.md"), "# Rip")
    val nested = safetyDir.resolve("nested")
    Files.createDirectory(nested)
    Files.writeString(nested.resolve("deep.md"), "# Deep")

    val files = Corpus.listFiles(tmp).map(_.getFileName.toString)
    assertEquals(files, List("a.md", "b.md", "rip.md"))
  }

  test("listFiles on a missing directory is empty, not an error") {
    assertEquals(Corpus.listFiles(java.nio.file.Paths.get("/no/such/dir/at/all")), Nil)
  }

  test("load stamps safety = true only for chunks from files under safety/") {
    val tmp = Files.createTempDirectory("marola-corpus-load")
    Files.writeString(tmp.resolve("plain.md"), "# Plain\nSource: https://example.com\n\nbody text")
    val safetyDir = tmp.resolve("safety")
    Files.createDirectory(safetyDir)
    Files.writeString(
      safetyDir.resolve("rip.md"),
      "# Rip\nSource: https://example.com\n\ndanger text"
    )

    val chunks = kyo.Sync.Unsafe.evalOrThrow(Corpus.load(tmp))
    val bySafety = chunks.groupBy(_.safety).view.mapValues(_.map(_.docTitle)).toMap
    assertEquals(bySafety.getOrElse(false, Nil), List("Plain"))
    assertEquals(bySafety.getOrElse(true, Nil), List("Rip"))
  }

end CorpusSpec
