package marola.location

import kyo.*

import marola.http.Http
import marola.json.JsonValue
import marola.model.Coordinates

/**
 * Where this machine's public IP geolocates to, plus how much to trust it: `agreeingSources` are
 * the providers whose answer landed within `IpGeolocation.AgreementRadiusKm` of the chosen point,
 * out of `totalSources` that answered at all.
 */
final case class IpLocation(
    coordinates: Coordinates,
    city: Option[String],
    agreeingSources: List[String],
    totalSources: Int
)

/**
 * Coarse "where am I" for the CLI, from the machine's public IP — the zero-setup fallback when
 * neither `--lat/--lon` nor `MAROLA_ORIGIN_LAT/LON` is given (see `Main.resolveOrigin`).
 */
object IpGeolocation:

  final case class Provider(
      name: String,
      url: String,
      parse: JsonValue => Option[(Coordinates, Option[String])]
  )

  /** Answers within this distance of the medoid count as agreeing with it. */
  val AgreementRadiusKm = 25.0

  val Providers: List[Provider] = List(
    Provider(
      "ipinfo.io",
      "https://ipinfo.io/json",
      json =>
        // `"loc": "-27.5967,-48.5492"` — a single comma-separated string, not two numbers.
        json("loc").str.flatMap { loc =>
          loc.split(',') match
            case Array(la, lo) =>
              for
                lat <- la.trim.toDoubleOption
                lon <- lo.trim.toDoubleOption
              yield (Coordinates(lat, lon), json("city").str)
            case _ => None
        }
    ),
    Provider(
      "ipwho.is",
      "https://ipwho.is/",
      json =>
        for
          lat <- json("latitude").num
          lon <- json("longitude").num
        yield (Coordinates(lat, lon), json("city").str)
    ),
    Provider(
      "ip-api.com",
      "http://ip-api.com/json/?fields=status,city,lat,lon",
      json =>
        for
          lat <- json("lat").num
          lon <- json("lon").num
        yield (Coordinates(lat, lon), json("city").str)
    )
  )

  /** `None` only when no provider answered usably (offline, all rate-limited, ...). */
  def locate: Option[IpLocation] < Sync =
    query(Providers).map(consensus)

  private def query(
      providers: List[Provider]
  ): List[(String, Coordinates, Option[String])] < Sync =
    providers match
      case Nil => Nil
      case provider :: rest =>
        for
          outcome <- Abort.run(
            Abort.catching[Throwable](
              Http.getString(provider.url).map(body => provider.parse(JsonValue.parse(body)))
            )
          )
          tail <- query(rest)
        yield outcome match
          case Result.Success(Some((coords, city))) => (provider.name, coords, city) :: tail
          case _                                    => tail

  /**
   * Pure vote over whatever answered: the medoid (smallest summed distance to every other answer)
   * wins, and every answer within `AgreementRadiusKm` of it is counted as agreeing.
   */
  def consensus(candidates: List[(String, Coordinates, Option[String])]): Option[IpLocation] =
    if candidates.isEmpty then None
    else
      val (_, best, bestCity) = candidates.minBy {
        case (_, c, _) =>
          candidates.map { case (_, other, _) => c.distanceKm(other) }.sum
      }
      val agreeing = candidates.filter { case (_, c, _) => c.distanceKm(best) <= AgreementRadiusKm }
      val city = bestCity.orElse(agreeing.flatMap { case (_, _, c) => c }.headOption)
      Some(IpLocation(best, city, agreeing.map { case (name, _, _) => name }, candidates.size))
