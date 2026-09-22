package marola.water

import java.text.Normalizer

import marola.model.Beach

/** Assigns each provider sampling point to (at most) one OSM beach. */
object WaterQualityMatcher:

  val MaxDistanceKm = 2.5

  private def foldAccents(s: String): String =
    Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase

  def normalise(name: String): String =
    foldAccents(name)
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
    val n = foldAccents(providerBeachName).trim + " "
    InlandWaterPrefixes.exists(n.startsWith)

  def namesMatch(a: String, b: String): Boolean =
    val (na, nb) = (normalise(a), normalise(b))
    na.nonEmpty && nb.nonEmpty && (na == nb || na.startsWith(nb + " ") || nb.startsWith(na + " "))

  /** Beach name → its matched points. */
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
