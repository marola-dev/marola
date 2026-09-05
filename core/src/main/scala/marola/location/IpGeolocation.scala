package marola.location

import kyo.*

import marola.http.Http
import marola.json.JsonValue
import marola.model.Coordinates

/**
 * Where this machine's public IP geolocates to, plus how much to trust it: `agreeingSources` are
 * the providers whose answer landed within `IpGeolocation.AgreementRadiusKm` of the chosen point,
 * out of `totalSources` that answered at all. `2/3` or `3/3` is a normal, trustworthy result; `1/3`
 * means the providers disagreed and the pick is a coin toss between cities — `Main` prints this so
 * a wrong city is obvious at a glance rather than silently producing beaches 300km away.
 */
final case class IpLocation(
    coordinates: Coordinates,
    city: Option[String],
    agreeingSources: List[String],
    totalSources: Int
)

/**
 * Coarse "where am I" for the CLI, from the machine's public IP — the zero-setup fallback when
 * neither `--lat/--lon` nor `MAROLA_ORIGIN_LAT/LON` is given (see `Main.resolveOrigin`). The real
 * location source is the Telegram bot's native location share (`ARCHITECTURE.md` §11 Phase 1); this
 * exists so `just run` on a laptop/desktop points at the right city without flags.
 *
 * Accuracy is **city-level at best**, and worse than that in Brazil specifically: many Brazilian
 * ISPs' address blocks geolocate to the ISP's head-office city or to São Paulo regardless of where
 * the customer actually is, and a single provider's database can be off by hundreds of km. Two
 * mitigations, both confirmed against real responses for a Florianópolis IP while building this
 * (all three providers below answered "Florianópolis"; two agreed to within ~300m, the third was
 * ~5km east):
 *
 * First, ask several free, keyless providers and take the **medoid** — the answer closest to all
 * the others — so one provider mapping the IP to the ISP's HQ is outvoted rather than trusted.
 *
 * Second, report how many providers agreed, and have the caller widen the beach-search radius
 * (`Main` uses at least 20km instead of the 15km default) to absorb the residual error.
 *
 * Every provider call is wrapped in `Abort.catching`: an unreachable/rate-limited/reshaped provider
 * is dropped from the vote, never a crash. All three are free for non-commercial use with no key or
 * signup, same footing as Overpass/Open-Meteo (`ARCHITECTURE.md` §7); `ip-api.com`'s free tier is
 * HTTP-only, which is acceptable here since the request carries nothing but the caller's IP.
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
   * wins, and every answer within `AgreementRadiusKm` of it is counted as agreeing. With three
   * providers this means "two close together outvote one outlier"; with one answer it's just that
   * answer, flagged as `1/1`. Public so it's unit-testable without network (`IpGeolocationSpec`).
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
