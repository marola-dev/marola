package marola.beaches

import kyo.*

import marola.http.Http
import marola.json.JsonValue
import marola.model.Coordinates

/**
 * Real driving-route distance via Azure Maps' Route Directions API — fixes the haversine "as the
 * crow flies" limitation `Beach.distanceKm` otherwise has (documented in `ARCHITECTURE.md` §9:
 * beaches across a bay from the origin show up as "nearby" despite not being reachable without a
 * boat or a long drive around it). Entirely optional: needs `AZURE_MAPS_SUBSCRIPTION_KEY`; when
 * unset, `Recommender` keeps the haversine distance `BeachFinder` already computed — see that
 * file's own doc comment for the confirmed real-world case this fixes.
 *
 * REST shape (`GET .../route/directions/json?api-version=1.0&query={lat1},{lon1}:{lat2},{lon2}`
 * with the key in a `subscription-key` header, response `routes[0].summary.lengthInMeters`)
 * confirmed against Azure's own published API reference — not exercised against a live Azure Maps
 * account (none provisioned; see `AGENTS.md`'s cost-safety rule).
 */
object RouteFinder:

  private val Endpoint = "https://atlas.microsoft.com/route/directions/json"

  final case class RouteNotFoundException(message: String) extends Exception(message)

  def travelDistanceKm(subscriptionKey: String, origin: Coordinates, dest: Coordinates): Double <
    Sync =
    val query = f"${origin.lat}%.6f,${origin.lon}%.6f:${dest.lat}%.6f,${dest.lon}%.6f"
    // Key in a header, not the query string: `Http.HttpError` embeds the URL in its message and
    // that message gets printed/logged (FABLE_REVIEW C2). Azure Maps accepts either form.
    val url = s"$Endpoint?api-version=1.0&query=$query"
    Http.getString(url, headers = Map("subscription-key" -> subscriptionKey)).map { body =>
      JsonValue
        .parse(body)("routes")
        .arr
        .headOption
        .flatMap(_("summary")("lengthInMeters").num)
        .map(_ / 1000.0)
        .getOrElse(
          throw RouteNotFoundException(s"no routes[0].summary.lengthInMeters in response: $body")
        )
    }
