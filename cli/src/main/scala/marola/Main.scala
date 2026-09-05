package marola

import kyo.*
import marola.llm.{CompiledPrompt, LlmClient, Reviewer}
import marola.location.IpGeolocation
import marola.model.{BestHour, Coordinates, WhaleSightingLikelihood}
import marola.observability.Telemetry
import marola.sightings.{Sighting, SightingKind}
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * POC entry point for "what's the best hour tomorrow to swim nearby?".
 *
 * Phase 0 of marola (see docs/ARCHITECTURE.md): this runs the real pipeline (nearby beaches via
 * Overpass, forecasts via Open-Meteo, heuristic scoring) end to end from the command line, with no
 * Azure/Telegram setup required — the origin comes from `--lat`/`--lon`, else
 * `MAROLA_ORIGIN_LAT/LON`, else the machine's IP geolocation (see `resolveOrigin`), standing in for
 * "the user's shared location", which the real Telegram bot will supply once it exists.
 *
 * `--summarize` additionally calls `AppConfig.llmClient` (local Ollama-compatible by default, no
 * Azure needed — see ARCHITECTURE.md §5/§6) to turn the #1 result into a natural-language summary,
 * replaying the DSPy-compiled prompt (`CompiledPrompt`). Off by default: a local CPU-served model
 * genuinely takes tens of seconds (confirmed against a real Ollama install), so this is opt-in
 * rather than adding that latency to every run.
 */
object Main extends KyoApp:

  // Arpoador, Rio de Janeiro — a real marola swim spot, used as the default "current location"
  // until location comes from the Telegram bot's native location-sharing (see ARCHITECTURE.md).
  private val DefaultOrigin = Coordinates(lat = -22.9878, lon = -43.1913)

  /**
   * IP geolocation is city-level at best (`IpGeolocation`'s doc comment on why, and why Brazil in
   * particular): when the origin comes from it, search at least this far so the residual error
   * doesn't push real nearby beaches outside the radius. Never *narrows* a larger configured
   * `MAROLA_BEACH_SEARCH_RADIUS_KM`.
   */
  private val IpGeolocationMinRadiusKm = 20.0

  /** Where to search from, how far, and a human-readable note on where that answer came from. */
  final private case class Origin(coordinates: Coordinates, radiusKm: Double, source: String)

  /**
   * Precedence: explicit `--lat`/`--lon` flags, then `MAROLA_ORIGIN_LAT`/`MAROLA_ORIGIN_LON`, then
   * the machine's public-IP geolocation (widened radius, see above), then the built-in Rio default
   * only if the IP lookup found nothing at all (offline). Half a pair — one flag or one env var
   * without its partner — is ignored, not half-applied; `warnHalfPair` reports it.
   */
  private def resolveOrigin(args: Array[String], config: AppConfig): Origin < Sync =
    (flagOrigin(args), config.origin) match
      case (Some(coords), _) => Origin(coords, config.beachSearchRadiusKm, "--lat/--lon flags")
      case (None, Some(coords)) =>
        Origin(coords, config.beachSearchRadiusKm, "MAROLA_ORIGIN_LAT/MAROLA_ORIGIN_LON")
      case (None, None) =>
        IpGeolocation.locate.map {
          case Some(loc) =>
            val radius = math.max(config.beachSearchRadiusKm, IpGeolocationMinRadiusKm)
            val agreement = s"${loc.agreeingSources.size}/${loc.totalSources} providers agree"
            Origin(
              loc.coordinates,
              radius,
              s"IP geolocation: ${loc.city.getOrElse("unknown city")}, $agreement " +
                s"(${loc.agreeingSources.mkString(", ")}); city-level accuracy, radius widened to " +
                f"$radius%.0fkm — pass --lat/--lon or set MAROLA_ORIGIN_LAT/LON to pin it"
            )
          case None =>
            Origin(
              DefaultOrigin,
              config.beachSearchRadiusKm,
              "built-in default (Arpoador, Rio) — IP geolocation unavailable, pass --lat/--lon"
            )
        }

  private def flagOrigin(args: Array[String]): Option[Coordinates] =
    for
      lat <- argValue(args, "--lat").flatMap(_.toDoubleOption)
      lon <- argValue(args, "--lon").flatMap(_.toDoubleOption)
    yield Coordinates(lat, lon)

  private def warnHalfPair(args: Array[String], config: AppConfig): Unit < Async =
    val flagHalf =
      argValue(args, "--lat").isDefined != argValue(args, "--lon").isDefined
    val envHalf = config.originLat.isDefined != config.originLon.isDefined
    if flagHalf then
      Console.printLine(
        "warning: --lat and --lon must be given together — ignoring the one present"
      )
    else if envHalf then
      Console.printLine(
        "warning: MAROLA_ORIGIN_LAT and MAROLA_ORIGIN_LON must be set together — ignoring the one set"
      )
    else noop

  private def argValue(args: Array[String], flag: String): Option[String] =
    val idx = args.indexOf(flag)
    if idx >= 0 && idx + 1 < args.length then Some(args(idx + 1)) else None

  private val hourFormat = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm")

  private def formatLine(rank: Int, best: BestHour): String =
    val when = best.hour.time.format(hourFormat)
    val dist = f"${best.beach.distanceKm}%.1fkm away"
    val temp = best.hour.seaTempC.map(t => f"$t%.1f°C sea").getOrElse("sea temp n/a")
    val wind = best.hour.windSpeedKmh.map(w => f"$w%.0fkm/h wind").getOrElse("wind n/a")
    val notes = if best.notes.isEmpty then "good conditions" else best.notes.mkString(", ")
    val whale =
      if best.whaleSightingLikelihood == WhaleSightingLikelihood.Low then ""
      else s"  |  whale sighting: ${best.whaleSightingLikelihood}"
    f"${rank}%2d. [${best.score}%3d/100] ${best.beach.name}%-22s ($dist)  best at $when  |  $temp, $wind  |  jellyfish: ${best.jellyfishRisk}$whale  |  $notes"

  private val SummaryPromptResource = "recommendation_prompt.json"
  private val ReviewPromptResource = "review_prompt.json"

  private def noop: Unit < Async = ()

  /**
   * `--report-sighting <jellyfish|whale> <beach name> [note...]` — the stand-in for submitting a
   * sighting via the (not-yet-built) Telegram bot; see `SightingStore`'s own doc comment on the
   * phase-discipline gap this papers over. Returns `None` for any other invocation, including a
   * malformed one, so `bootstrap` falls through to the normal recommendation flow rather than
   * silently swallowing a typo.
   */
  private def parseSightingReport(
      args: Array[String]
  ): Option[(SightingKind, String, Option[String])] =
    val idx = args.indexOf("--report-sighting")
    if idx < 0 || idx + 2 >= args.length then None
    else
      val kind = SightingKind.values.find(_.toString.equalsIgnoreCase(args(idx + 1)))
      val beachName = args(idx + 2)
      val note = Option.when(idx + 3 < args.length)(args(idx + 3))
      kind.map((_, beachName, note))

  private def bootstrap(args: Array[String]): Unit < Async =
    val config = AppConfig.fromEnv
    (parseSightingReport(args), argValue(args, "--analyze-photo")) match
      case (Some((kind, beachName, note)), _) => reportSighting(config, kind, beachName, note)
      case (None, Some(photoPath))            => analyzePhoto(config, photoPath)
      case (None, None)                       => runRecommendation(args, config)

  private def analyzePhoto(config: AppConfig, photoPath: String): Unit < Async =
    config.visionClient match
      case None =>
        Console.printLine(
          s"(--analyze-photo needs AZURE_VISION_ENDPOINT/AZURE_VISION_KEY set for " +
            s"visionProvider=${config.visionProvider})"
        )
      case Some(client) =>
        for
          _ <- Console.printLine(s"Analyzing $photoPath with ${config.visionProvider} vision...")
          outcome <- Abort.run(
            Abort.catching[Throwable] {
              val bytes = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(photoPath))
              client.describe(bytes)
            }
          )
          _ <- outcome match
            case Result.Success(description) => Console.printLine(s"Description: $description")
            case failure                     => Console.printLine(s"(vision call failed: $failure)")
        yield ()

  private def reportSighting(
      config: AppConfig,
      kind: SightingKind,
      beachName: String,
      note: Option[String]
  ): Unit < Async =
    config.sightingStore match
      case None =>
        Console.printLine(
          s"(--report-sighting needs COSMOS_DB_ENDPOINT/COSMOS_DB_KEY set for " +
            s"sightingStoreProvider=${config.sightingStoreProvider})"
        )
      case Some(store) =>
        val sighting = Sighting(beachName, kind, note, Instant.now())
        for
          outcome <- Abort.run(Abort.catching[Throwable](store.record(sighting)))
          _ <- outcome match
            case Result.Success(_) =>
              Console.printLine(
                s"Recorded: $kind sighting at $beachName${note.map(n => s" ($n)").getOrElse("")}"
              )
            case failure => Console.printLine(s"(failed to record sighting: $failure)")
        yield ()

  private def runRecommendation(args: Array[String], config: AppConfig): Unit < Async =
    val summarize = args.contains("--summarize")
    for
      _ <- Console.printLine("marola :: best hour tomorrow to swim nearby (POC)")
      _ <- Console.printLine(s"config -> $config")
      _ <- warnHalfPair(args, config)
      origin <- resolveOrigin(args, config)
      _ <- Console.printLine(
        f"origin -> lat=${origin.coordinates.lat}%.4f, lon=${origin.coordinates.lon}%.4f " +
          f"(radius ${origin.radiusKm}%.0fkm, source: ${origin.source})"
      )
      otel = Telemetry.initialize(config.appInsightsConnectionString)
      results <- Telemetry.withSpan(otel, "bestPerBeachTomorrow") {
        Recommender.bestPerBeachTomorrow(
          origin.coordinates,
          origin.radiusKm,
          distanceRefiner = config.distanceRefiner
        )
      }
      _ <-
        if results.isEmpty then
          Console.printLine("No beaches found nearby, or no forecast data for tomorrow yet.")
        else printLines(results.zipWithIndex.take(15))
      _ <- if summarize then summarizeTop(config, results.headOption) else noop
    yield ()

  private def factInputsFor(best: BestHour): Map[String, String] =
    Map(
      "beach_name" -> best.beach.name,
      "hour_local" -> best.hour.time.format(hourFormat),
      "sea_temp_c" -> best.hour.seaTempC.map(t => f"$t%.1f").getOrElse("unknown"),
      "wind_kmh" -> best.hour.windSpeedKmh.map(w => f"$w%.0f").getOrElse("unknown"),
      "wave_height_m" -> best.hour.waveHeightM.map(h => f"$h%.1f").getOrElse("unknown"),
      "jellyfish_risk" -> best.jellyfishRisk.toString,
      "whale_sighting_likelihood" -> best.whaleSightingLikelihood.toString,
      "score" -> best.score.toString
    )

  /**
   * Generates the draft summary, then hands it to `Reviewer` (`ARCHITECTURE.md` §5a,
   * `FUTURE-WORK.md` §4.2) for a second, independent pass before printing anything to the user.
   * Both `loadCompiledPrompt` calls live inside their respective `Abort.catching` blocks, not just
   * the network call — a missing/malformed resource file threw uncaught past an earlier version of
   * this function, since it happened outside the block that was actually being caught.
   */
  private def summarizeTop(config: AppConfig, top: Option[BestHour]): Unit < Async =
    (top, config.llmClient) match
      case (None, _) => Console.printLine("(nothing to summarize — no results)")
      case (_, None) =>
        Console.printLine(
          s"(--summarize needs FOUNDRY_PROJECT_ENDPOINT set for llmProvider=${config.llmProvider})"
        )
      case (Some(best), Some(client)) =>
        val factInputs = factInputsFor(best)
        for
          _ <- Console.printLine(
            s"\nAsking ${config.llmProvider} LLM to summarize the top pick (this may take a while)..."
          )
          draftOutcome <- Abort.run(Abort.catching[Throwable] {
            val summaryPrompt = loadCompiledPrompt(SummaryPromptResource, outputField = "summary")
            client.complete(summaryPrompt.buildMessages(factInputs))
          })
          _ <- draftOutcome match
            case Result.Success(draft) => reviewAndPrint(client, factInputs, draft)
            case failure =>
              Console.printLine(s"(LLM call failed, showing numbers above only: $failure)")
        yield ()

  private def reviewAndPrint(
      client: LlmClient,
      factInputs: Map[String, String],
      draftSummary: String
  ): Unit < Async =
    for
      _ <- Console.printLine(s"Draft summary: $draftSummary")
      reviewOutcome <- Abort.run(Abort.catching[Throwable] {
        val reviewPrompt = loadCompiledPrompt(ReviewPromptResource, outputField = "review_json")
        Reviewer.review(client, reviewPrompt, factInputs, draftSummary)
      })
      _ <- reviewOutcome match
        case Result.Success(result) =>
          Console.printLine(
            s"Reviewer (score ${result.score}/100, verdict: ${result.verdict}): ${result.finalSummary}"
          )
        case failure =>
          Console.printLine(s"(reviewer call failed, showing draft summary only: $failure)")
    yield ()

  private def loadCompiledPrompt(resourceName: String, outputField: String): CompiledPrompt =
    val stream = getClass.getClassLoader.getResourceAsStream(resourceName)
    if stream == null then
      throw java.io.FileNotFoundException(s"classpath resource not found: $resourceName")
    val json = scala.io.Source.fromInputStream(stream).mkString
    CompiledPrompt.loadFromString(json, outputField)

  // Sequential effect loop, hand-rolled for the same reason as Recommender.traverse: only
  // map/flatMap on `< Async` are confirmed against the pinned Kyo 1.0.0-RC5 build.
  private def printLines(items: List[(BestHour, Int)]): Unit < Async =
    items match
      case Nil => ()
      case (best, i) :: rest =>
        for
          _ <- Console.printLine(formatLine(i + 1, best))
          _ <- printLines(rest)
        yield ()

  run(bootstrap(args.toArray))
