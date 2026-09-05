package marola.water

import marola.model.Beach
import java.text.Normalizer

/**
 * Assigns each provider sampling point to (at most) one OSM beach. Pure, so it's unit-tested on a
 * fixture trimmed from the real IMA feed (`WaterQualityMatcherSpec`).
 *
 * Name match wins: the provider's beach name and OSM's, both normalised (accents stripped, "Praia
 * do/da/de..." prefix dropped, lower-cased), are equal or one is a word-prefix of the other — that
 * second rule is what pairs OSM's "Praia da Armação" with IMA's "PRAIA DA ARMAÇÃO DO PÂNTANO DO
 * SUL". Distance is the fallback only for points whose name matches no beach at all: the nearest
 * beach within `MaxDistanceKm` — *unless* the point is inland water (a name starting LAGOA, CANAL,
 * RIO, ...), which never lands on a sea beach however close it is. Confirmed necessary by test:
 * Lagoa da Conceição's Ponto 72 is ~1.3km from Praia da Joaquina's centroid.
 */
object WaterQualityMatcher:

  val MaxDistanceKm = 2.5

  def normalise(name: String): String =
    val ascii = Normalizer.normalize(name, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
    ascii.toLowerCase
      .replaceAll("\\(.*?\\)", " ")
      .replaceAll("^\\s*(praia|prainha|praias)\\s+(do|da|de|dos|das)\\s+", "")
      .replaceAll("^\\s*(praia|prainha|praias)\\s+", "")
      .replaceAll("[^a-z0-9 ]", " ")
      .replaceAll("\\s+", " ")
      .trim

  private val InlandWaterPrefixes =
    List("lagoa ", "lago ", "canal ", "rio ", "foz ", "represa ", "barragem ")

  /**
   * "LAGOA DA CONCEIÇÃO", "CANAL DO LINGUADO", "RIO ..." — never matched to a sea beach by
   * distance.
   */
  def isInlandWater(providerBeachName: String): Boolean =
    val n = Normalizer
      .normalize(providerBeachName, Normalizer.Form.NFD)
      .replaceAll("\\p{M}", "")
      .toLowerCase
      .trim + " "
    InlandWaterPrefixes.exists(n.startsWith)

  def namesMatch(a: String, b: String): Boolean =
    val (na, nb) = (normalise(a), normalise(b))
    na.nonEmpty && nb.nonEmpty && (na == nb || na.startsWith(nb + " ") || nb.startsWith(na + " "))

  /** Beach name → its matched points. Beaches with no point are absent from the map. */
  def assign(
      beaches: List[Beach],
      points: List[SamplingPoint],
      source: String
  ): Map[String, WaterQuality] =
    val pairs: List[(Beach, SamplingPoint)] = points.flatMap { point =>
      val byName = beaches.filter(b => namesMatch(b.name, point.beachName))
      val target =
        if byName.nonEmpty then Some(byName.minBy(_.coordinates.distanceKm(point.coordinates)))
        else if isInlandWater(point.beachName) then None
        else
          beaches
            .map(b => (b, b.coordinates.distanceKm(point.coordinates)))
            .filter { case (_, km) => km <= MaxDistanceKm }
            .sortBy { case (_, km) => km }
            .headOption
            .map { case (b, _) => b }
      target.map(b => (b, point))
    }
    pairs
      .groupBy { case (beach, _) => beach.name }
      .map { case (name, ps) => name -> WaterQuality(ps.map { case (_, p) => p }, source) }
