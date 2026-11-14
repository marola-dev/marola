package marola.tools.publishing

import marola.tools.shared.Util

class CitationTest extends munit.FunSuite:
  import Citation.*

  private def good() = ujson.read(
    """{
      "title": "t",
      "upload_type": "software",
      "description": "<p>One &amp; two.</p><p>Three.</p>",
      "creators": [{"name": "Santos, Matheus Hoffmann Fernandes", "orcid": "0009-0009-1056-7661"}],
      "license": "mit",
      "access_right": "open",
      "language": "eng",
      "keywords": ["ocean"],
      "related_identifiers": [
        {"identifier": "https://github.com/x/y", "relation": "isSupplementTo", "scheme": "url"},
        {"identifier": "10.5281/zenodo.23221352", "relation": "references", "scheme": "doi"}
      ]
    }"""
  )

  test("validates and renders"):
    val meta = good()
    assertEquals(validate(meta), Vector.empty)
    assert(orcidOk("0000-0002-1825-0097") && !orcidOk("0000-0002-1825-0098"))
    val bad = good()
    bad("version") = "v1"
    bad("creators")(0)("name") = "Matheus Santos"
    bad("related_identifiers")(1)("relation") = "isRelatedTo"
    assertEquals(validate(bad).length, 3, validate(bad))
    val cff = renderCff(meta, "")
    assert(
      cff.contains("family-names: \"Santos\"") && cff.contains("orcid.org/0009-0009-1056-7661")
    )
    assert(cff.contains("  One & two.\n\n  Three.\n") && !cff.contains("doi:"))
    assert(renderCff(meta, "10.5281/zenodo.1").contains("doi: 10.5281/zenodo.1\n"))
    assertEquals(addPerson(meta, "Valério, Bruno", None, None, "ProjectMember", false), Right(()))
    assertEquals(
      meta("contributors"),
      ujson.Arr(ujson.Obj("name" -> "Valério, Bruno", "type" -> "ProjectMember"))
    )
    assertEquals(validate(meta), Vector.empty)
    assert(addPerson(meta, "Valério, Bruno", None, None, "Other", true).isLeft)
    val refs = renderRefs(meta, "")
    assert(
      refs.contains("author    = {Santos, Matheus Hoffmann Fernandes}") && !refs.contains("doi")
    )
    assert(
      refs.contains("> Santos, M. H. F. (2026). *t* [Computer software]. https://github.com/x/y")
    )
    meta("creators").arr += ujson.Obj("name" -> "Valério, Bruno")
    meta("title") = "m: a sub"
    val withDoi = renderRefs(meta, "10.5281/zenodo.1")
    assert(withDoi.contains("Santos, M. H. F., & Valério, B. (2026)"))
    assert(withDoi.contains("doi       = {10.5281/zenodo.1}"))
    assert(withDoi.contains("SANTOS, Matheus Hoffmann Fernandes; VALÉRIO, Bruno. **m**: a sub."))
    assert(
      withDoi.contains("DOI 10.5281/zenodo.1.") && withDoi.contains("@software{santos_2026_m,")
    )
    assertEquals(splice(s"a\n$Start\nold\n$End\nb", "NEW"), Right("a\nNEW\nb"))

  test("wraps and decodes entities"):
    assertEquals(wrap("aa bb cc", 5), Vector("aa bb", "cc"))
    assertEquals(decodeEntities("&lt;&#233;&#xE9;&bogus;"), "<éé&bogus;")

  test("run round trip"):
    val meta = good()
    assertEquals(addPerson(meta, "Valério, Bruno", None, None, "ProjectMember", false), Right(()))
    val dir = os.temp.dir()
    val (zenodo, cff, readme) = (dir / ".zenodo.json", dir / "CITATION.cff", dir / "README.md")
    writeJson(zenodo, meta)
    os.write(readme, s"# x\n\n$Start\n$End\n\n## y\n")
    assertEquals(run(zenodo, cff, true, Seq(readme)), 1)
    assertEquals(run(zenodo, cff, false, Seq(readme)), 0)
    assertEquals(run(zenodo, cff, true, Seq(readme)), 0)
    val text = os.read(readme)
    assert(text.contains("BibTeX:") && text.endsWith("\n\n## y\n"))
    os.write.over(readme, "no markers\n")
    assertEquals(run(zenodo, cff, true, Seq(readme)), 1)

  test("the committed files are current"):
    val root = Util.repoRoot
    assertEquals(
      run(
        root / ".zenodo.json",
        root / "CITATION.cff",
        true,
        Seq(root / "README.md", root / "README.en.md")
      ),
      0
    )
