package marola.tools.publishing

import marola.tools.shared.Invocation

class CitationTest extends munit.FunSuite:
  import Citation.*
  import CitationText.*

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

  private def typed(meta: ujson.Value): ZenodoMetadata = ZenodoJson.decode(meta).toOption.get
  private def doi(s: String): Option[Doi] = Doi.parse(s).toOption
  private def contributor(name: String, kind: String) =
    PersonRequest(name, None, None, Role.Contributor(ContributorType.parse(kind).toOption.get))

  test("validates and renders"):
    val meta = good()
    assertEquals(validate(meta), Vector.empty)
    assert(Orcid.valid("0000-0002-1825-0097") && !Orcid.valid("0000-0002-1825-0098"))
    val bad = good()
    bad("version") = "v1"
    bad("creators")(0)("name") = "Matheus Santos"
    bad("related_identifiers")(1)("relation") = "isRelatedTo"
    assertEquals(validate(bad).length, 3, validate(bad))
    val cff = renderCff(typed(meta), None)
    assert(
      cff.contains("family-names: \"Santos\"") && cff.contains("orcid.org/0009-0009-1056-7661")
    )
    assert(cff.contains("  One & two.\n\n  Three.\n") && !cff.contains("doi:"))
    assert(renderCff(typed(meta), doi("10.5281/zenodo.1")).contains("doi: 10.5281/zenodo.1\n"))
    assertEquals(
      ZenodoJson.addPerson(meta, contributor("Valério, Bruno", "ProjectMember")),
      Right(())
    )
    assertEquals(
      meta("contributors"),
      ujson.Arr(ujson.Obj("name" -> "Valério, Bruno", "type" -> "ProjectMember"))
    )
    assertEquals(validate(meta), Vector.empty)
    assert(
      ZenodoJson.addPerson(meta, PersonRequest("Valério, Bruno", None, None, Role.Creator)).isLeft
    )
    val refs = renderRefs(typed(meta), None)
    assert(
      refs.contains("author    = {Santos, Matheus Hoffmann Fernandes}") && !refs.contains("doi")
    )
    assert(
      refs.contains("> Santos, M. H. F. (2026). *t* [Computer software]. https://github.com/x/y")
    )
    meta("creators").arr += ujson.Obj("name" -> "Valério, Bruno")
    meta("title") = "m: a sub"
    val withDoi = renderRefs(typed(meta), doi("10.5281/zenodo.1"))
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

  test("decodes the metadata into the model"):
    val meta = typed(good())
    assertEquals(meta.uploadType, UploadType.Software)
    assertEquals(meta.license, License.Mit)
    assertEquals(meta.language.value, "eng")
    assertEquals(meta.creators.map(_.name), Seq(PersonName("Santos", "Matheus Hoffmann Fernandes")))
    assertEquals(meta.creators.head.orcid.map(_.url), Some("https://orcid.org/0009-0009-1056-7661"))
    assertEquals(meta.creators.head.role, Role.Creator)
    assertEquals(
      meta.relatedIdentifiers.map(_.identifier.value),
      Seq("https://github.com/x/y", "10.5281/zenodo.23221352")
    )
    assertEquals(meta.repositoryCode, "https://github.com/x/y")
    val noRepo = meta.copy(relatedIdentifiers = meta.relatedIdentifiers.drop(1))
    intercept[IllegalStateException](noRepo.repositoryCode)
    val bad = good()
    bad("license") = "gpl"
    bad("contributors") = ujson.Arr(ujson.Obj("name" -> "A, B", "type" -> "Boss"))
    assertEquals(
      ZenodoJson.decode(bad),
      Left(
        Vector(
          "license 'gpl' has no SPDX mapping here",
          "contributors[0]: type 'Boss' is not Zenodo's"
        )
      )
    )

  test("value objects refuse what Zenodo would"):
    assertEquals(PersonName.parse("Valério, Bruno"), Right(PersonName("Valério", "Bruno")))
    assertEquals(PersonName.parse("Valério, Bruno").map(_.value), Right("Valério, Bruno"))
    for name <- Seq("Bruno Valério", "A, B, C", ", B", "A,B") do
      assert(PersonName.parse(name).isLeft, name)
    assertEquals(Orcid.parse("0000-0002-1825-0097").map(_.value), Right("0000-0002-1825-0097"))
    assert(Orcid.parse("0000-0002-1825-009").isLeft)
    assert(Orcid.valid("0000-0002-9079-593X"))
    assertEquals(
      Doi.parse("10.5281/zenodo.1").map(_.url),
      Right("https://doi.org/10.5281/zenodo.1")
    )
    for bad <- Seq("10.12/x", "doi:10.5281/zenodo.1", "10.5281/a b") do
      assert(Doi.parse(bad).isLeft, bad)
    assert(LanguageCode.parse("eng").isRight && LanguageCode.parse("en").isLeft)
    assertEquals(License.fromZenodo("cc-by-4.0").map(_.spdx), Some("CC-BY-4.0"))
    assertEquals(License.fromZenodo("MIT"), None)
    assertEquals(UploadType.fromId("dataset"), Some(UploadType.Dataset))
    assert(Relation.parse("isSupplementTo").isRight && Relation.parse("isRelatedTo").isLeft)
    assert(ContributorType.parse("Editor").isRight && ContributorType.parse("editor").isLeft)
    assertEquals(ContributorType.All.head, "ContactPerson")
    assert(Identifier.url("https://x").isRight && Identifier.url("http://x").isLeft)
    assertEquals(ConceptDoi.map(_.value), Some("10.5281/zenodo.23224155"))

  test("run round trip"):
    val meta = good()
    assertEquals(
      ZenodoJson.addPerson(meta, contributor("Valério, Bruno", "ProjectMember")),
      Right(())
    )
    val dir = os.temp.dir()
    val (zenodo, cff, readme) = (dir / ".zenodo.json", dir / "CITATION.cff", dir / "README.md")
    writeJson(zenodo, meta)
    os.write(readme, s"# x\n\n$Start\n$End\n\n## y\n")
    assertEquals(run(Config(zenodo, cff, Seq(readme)), true), 1)
    assertEquals(run(Config(zenodo, cff, Seq(readme)), false), 0)
    assertEquals(run(Config(zenodo, cff, Seq(readme)), true), 0)
    val text = os.read(readme)
    assert(text.contains("BibTeX:") && text.endsWith("\n\n## y\n"))
    os.write.over(readme, "no markers\n")
    assertEquals(run(Config(zenodo, cff, Seq(readme)), true), 1)

  test("the committed files are current"):
    val root = Invocation.current().repoRoot
    assertEquals(
      run(
        Config(
          root / ".zenodo.json",
          root / "CITATION.cff",
          Seq(root / "README.md", root / "README.en.md")
        ),
        true
      ),
      0
    )
