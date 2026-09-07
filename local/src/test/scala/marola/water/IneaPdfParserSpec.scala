package marola.water

/**
 * Parses the real INEA bulletin captured live 2026-09-07
 * (`inea-boletim-zona-sudoeste-sul-2026-06-17.pdf` — Boletim N°24, 17/06/2026, zones Sudoeste e
 * Sul; MIP-0031 §11).
 */
class IneaPdfParserSpec extends munit.FunSuite:

  private val fixtureBytes =
    val stream = getClass.getClassLoader.getResourceAsStream(
      "inea-boletim-zona-sudoeste-sul-2026-06-17.pdf"
    )
    try stream.readAllBytes()
    finally stream.close()

  private val rows = IneaPdfParser.parseTable(fixtureBytes)

  test("parses every one of the bulletin's 39 rows") {
    assertEquals(rows.size, 39)
  }

  test("point code and category on a single-row (no rowspan) beach") {
    val bg = rows.find(_.pointCode == "BG00").getOrElse(fail("no BG00"))
    assertEquals(bg.beachName, "Barra de Guaratiba")
    assertEquals(bg.location, "Em frente à Escola Ana Neri")
    assertEquals(bg.category, BathingCondition.Improper)
  }

  test("PRÓPRIA parses correctly, accents handled") {
    val ip10 = rows.find(_.pointCode == "IP10").getOrElse(fail("no IP10"))
    assertEquals(ip10.beachName, "Ipanema")
  }

  test(
    "the rowspan (merged PRAIAS cell) case: a beach name announced on one row is carried " +
      "to every other point of the same beach, even when the label sits on a different line " +
      "than the row it labels"
  ) {
    // Recreio: BD00 has no attached name (the label "Recreio" is its own standalone line,
    // positioned between BD00 and BD02) — verified live against the real fixture.
    val bd00 = rows.find(_.pointCode == "BD00").getOrElse(fail("no BD00"))
    assertEquals(bd00.beachName, "Recreio")
    assertEquals(bd00.location, "Em frente à Rua Manuel Jorge Lydia")
    val bd02 = rows.find(_.pointCode == "BD02").getOrElse(fail("no BD02"))
    assertEquals(bd02.beachName, "Recreio")
  }

  test("a beach that shares its point-code prefix (BD) with siblings is still split correctly") {
    // BD00/BD02 -> Recreio, BD03/BD011 -> Recreio/Reserva, BD05/BD07 -> Barra da Tijuca,
    // BD09/BD10 -> Barra da Tijuca II — four distinct beaches sharing one code prefix.
    assertEquals(rows.find(_.pointCode == "BD03").map(_.beachName), Some("Recreio/Reserva"))
    assertEquals(rows.find(_.pointCode == "BD011").map(_.beachName), Some("Recreio/Reserva"))
    assertEquals(rows.find(_.pointCode == "BD07").map(_.beachName), Some("Barra da Tijuca"))
    assertEquals(rows.find(_.pointCode == "BD09").map(_.beachName), Some("Barra da Tijuca II"))
    assertEquals(rows.find(_.pointCode == "BD10").map(_.beachName), Some("Barra da Tijuca II"))
  }

  test("a beach with three points under one merged label is fully and correctly resolved") {
    // Ipanema: IP03, IP10 (label attached here), IP06 — all three must resolve to Ipanema, not
    // to the neighbouring single-row "Arpoador" group that immediately follows.
    assertEquals(rows.find(_.pointCode == "IP03").map(_.beachName), Some("Ipanema"))
    assertEquals(rows.find(_.pointCode == "IP06").map(_.beachName), Some("Ipanema"))
    assertEquals(rows.find(_.pointCode == "AR00").map(_.beachName), Some("Arpoador"))
  }

  test("no duplicate rows from the badge/plain-text double-drawn CONAMA verdict") {
    assertEquals(rows.count(_.pointCode == "FL008"), 1)
  }

  test("header and footer prose never become spurious rows or beach names") {
    assert(rows.forall(r => !r.beachName.contains(":")))
    assert(!rows.exists(_.pointCode == "BD010")) // only mentioned in footer prose, not a real row
  }

end IneaPdfParserSpec
