package marola.water

import marola.model.{Beach, Coordinates}
import java.time.LocalDate

class WaterQualityMatcherSpec extends munit.FunSuite:

  // Real IMA/SC points (2026-09-05 feed) around Campeche, Florianópolis — see MIP-0001 appendix.
  private def point(
      id: String,
      beach: String,
      name: String,
      loc: String,
      lat: Double,
      lon: Double,
      cond: BathingCondition,
      count: Int
  ) =
    SamplingPoint(
      id,
      beach,
      name,
      loc,
      Coordinates(lat, lon),
      List(WaterSample(LocalDate.of(2026, 8, 25), cond, Some("Ausente"), Some(count), Some(16.0)))
    )

  private val campeche89 = point(
    "1",
    "PRAIA DO CAMPECHE",
    "Ponto 89",
    "Av. Jerônimo Venâncio Chagas, 113",
    -27.6660,
    -48.4720,
    BathingCondition.Proper,
    10
  )
  private val campeche73 = point(
    "2",
    "PRAIA DO CAMPECHE",
    "Ponto 73",
    "Av. Campeche, 300, no Riozinho",
    -27.6595,
    -48.4655,
    BathingCondition.Improper,
    749
  )
  private val campeche35 = point(
    "3",
    "PRAIA DO CAMPECHE",
    "Ponto 35",
    "Av. Pequeno Príncipe, 3348",
    -27.6900,
    -48.4780,
    BathingCondition.Proper,
    10
  )
  private val lagoa72 = point(
    "4",
    "LAGOA DA CONCEIÇÃO",
    "Ponto 72",
    "Rua Canto da Amizade",
    -27.6280,
    -48.4610,
    BathingCondition.Improper,
    573
  )
  private val armacao44 = point(
    "5",
    "PRAIA DA ARMAÇÃO DO PÂNTANO DO SUL",
    "Ponto 44",
    "Av. Antônio Borges dos Santos, 1772",
    -27.7420,
    -48.5060,
    BathingCondition.Proper,
    10
  )
  private val unnamedNear = point(
    "6",
    "PONTO SEM PRAIA",
    "Ponto 99",
    "somewhere",
    -27.6740,
    -48.4710,
    BathingCondition.Proper,
    10
  )

  private val campeche = Beach("Praia do Campeche", Coordinates(-27.6733, -48.4700), 2.1)
  private val armacao = Beach("Praia da Armação", Coordinates(-27.7450, -48.5040), 7.9)
  private val joaquina = Beach("Praia da Joaquina", Coordinates(-27.6290, -48.4480), 4.6)
  private val beaches = List(campeche, armacao, joaquina)

  test("normalise strips accents, the 'Praia do/da' prefix and parentheticals") {
    assertEquals(WaterQualityMatcher.normalise("PRAIA DO CAMPECHE"), "campeche")
    assertEquals(WaterQualityMatcher.normalise("Praia da Armação"), "armacao")
    assertEquals(
      WaterQualityMatcher.normalise("Praia de Pedras Altas (Área Nudista)"),
      "pedras altas"
    )
    assertEquals(WaterQualityMatcher.normalise("Prainha da Fortaleza"), "fortaleza")
  }

  test("name match wins, including the word-prefix rule for IMA's longer names") {
    assert(WaterQualityMatcher.namesMatch("Praia da Armação", "PRAIA DA ARMAÇÃO DO PÂNTANO DO SUL"))
    assert(WaterQualityMatcher.namesMatch("Praia do Campeche", "PRAIA DO CAMPECHE"))
    assert(!WaterQualityMatcher.namesMatch("Praia do Campeche", "PRAIA DA JOAQUINA"))
    assert(!WaterQualityMatcher.namesMatch("Praia do Meio", "PRAIA DO MEIO NORTE X".take(0)))
  }

  test("assign gives Campeche its own points and Armação the long-named one; Joaquina none") {
    val assigned = WaterQualityMatcher.assign(
      beaches,
      List(campeche89, campeche73, campeche35, lagoa72, armacao44),
      "IMA/SC"
    )
    assertEquals(
      assigned(campeche.name).points.map(_.pointName).toSet,
      Set("Ponto 89", "Ponto 73", "Ponto 35")
    )
    assertEquals(assigned(armacao.name).points.map(_.pointName), List("Ponto 44"))
    assertEquals(assigned.get(joaquina.name), None)
    assertEquals(assigned(campeche.name).source, "IMA/SC")
  }

  test("a lagoon point never lands on a sea beach, even 1.3km from Joaquina's centroid") {
    assert(lagoa72.coordinates.distanceKm(joaquina.coordinates) < WaterQualityMatcher.MaxDistanceKm)
    assert(WaterQualityMatcher.isInlandWater("LAGOA DA CONCEIÇÃO"))
    assert(WaterQualityMatcher.isInlandWater("CANAL DO LINGUADO"))
    assert(!WaterQualityMatcher.isInlandWater("ARROIO DA PRAIA DAS GAIVOTAS"))
    val assigned = WaterQualityMatcher.assign(beaches, List(lagoa72), "IMA/SC")
    assertEquals(assigned, Map.empty[String, WaterQuality])
  }

  test(
    "distance fallback attaches a point with an unknown beach name to the nearest beach within 2.5km"
  ) {
    val assigned = WaterQualityMatcher.assign(beaches, List(unnamedNear), "IMA/SC")
    assertEquals(assigned.keySet, Set(campeche.name))
  }

  test("WaterQuality freshness: samples older than 45 days are not fresh") {
    val wq = WaterQuality(List(campeche89, campeche73), "IMA/SC")
    assertEquals(wq.fresh(LocalDate.of(2026, 9, 5)).size, 2)
    assertEquals(wq.fresh(LocalDate.of(2026, 10, 10)).size, 0)
    assertEquals(wq.improper(LocalDate.of(2026, 9, 5)).map(_._1.pointName), List("Ponto 73"))
    assertEquals(wq.newestSampleDate, Some(LocalDate.of(2026, 8, 25)))
  }

end WaterQualityMatcherSpec
