package marola.water

/**
 * Parses the real INEMA (Bahia) bulletin fetched live 2026-09-07 from the exact `idcampanha=83453`
 * endpoint MIP-0031 §4.3 verified — `curl
 * http://balneabilidade.inema.ba.gov.br/index.php/relatoriodebalneabilidade/geraBoletim?idcampanha=83453`,
 * saved unmodified as `inema-boletim-salvador-13-2025.pdf` (Boletim N°13/2025, "Costa: Litoral de
 * Salvador", 04/04/2025). Real bulletin bytes, not a synthesized fixture.
 */
class InemaPdfParserSpec extends munit.FunSuite:

  private val fixture: Array[Byte] =
    val stream = getClass.getClassLoader.getResourceAsStream("inema-boletim-salvador-13-2025.pdf")
    try stream.readAllBytes()
    finally stream.close()

  private val rows = InemaPdfParser.parseTable(fixture)

  test("parses every real row in the fixture bulletin") {
    assertEquals(rows.size, 38)
  }

  test("point code and category are extracted for a straightforward one-line-description row") {
    val saoTome = rows.find(_.code == "SSA IN 100").getOrElse(fail("no SSA IN 100"))
    assertEquals(saoTome.pointName, "São Tomé de Paripe")
    assertEquals(saoTome.category, "Própria")
    assert(saoTome.description.contains("Vila Maria"))
  }

  test("an IMPRÓPRIA point keeps its exact category label") {
    val tubarao = rows.find(_.code == "SSA PR 200").getOrElse(fail("no SSA PR 200"))
    assertEquals(tubarao.category, "Imprópria")
  }

  test("a wrapped multi-line description is joined into one string, not truncated") {
    val boaViagem = rows.find(_.code == "SSA BV 100").getOrElse(fail("no SSA BV 100"))
    assertEquals(boaViagem.pointName, "Boa Viagem")
    assertEquals(boaViagem.category, "Própria")
    // The bulletin wraps this description across two source lines ("...Fundação Luís Eduardo" /
    // "Magalhães, junto à rampa..."); both halves must survive the join.
    assert(boaViagem.description.contains("Fundação Luís Eduardo"))
    assert(boaViagem.description.contains("Magalhães"))
    assert(boaViagem.description.contains("rampa de acesso à praia"))
  }

  test("every parsed code is unique and non-empty") {
    val codes = rows.map(_.code)
    assertEquals(codes.distinct.size, codes.size)
    assert(codes.forall(_.nonEmpty))
  }

end InemaPdfParserSpec
