package marola

import java.nio.file.Paths
import java.time.Instant
import java.time.format.DateTimeFormatter

import kyo.*

import marola.bench.{BenchmarkLedger, OceanBenchmark}
import marola.knowledge.OceanQa
import marola.llm.{CompiledPrompt, LlmClient, Reviewer}
import marola.location.IpGeolocation
import marola.model.{BestHour, Coordinates}
import marola.observability.Tracing
import marola.sightings.{Sighting, SightingKind}
import marola.site.SiteBuilder
import marola.trails.{Trail, TrailFinder}

/**
 * POC entry point for marola, the ocean intelligence layer — its first case, "what's the best hour
 * tomorrow to swim nearby?".
 */
object Main extends KyoApp:

  // Arpoador, Rio de Janeiro — a real marola swim spot, used as the default "current location"
  // until location comes from the Telegram bot's native location-sharing (see ARCHITECTURE.md).
  private val DefaultOrigin = Coordinates(lat = -22.9878, lon = -43.1913)

  /**
   * IP geolocation is city-level at best (`IpGeolocation`'s doc comment on why, and why Brazil in
   * particular): when the origin comes from it, search at least this far so the residual error
   * doesn't push real nearby beaches outside the radius.
   */
  private val IpGeolocationMinRadiusKm = 20.0

  /** Where to search from, how far, and a human-readable note on where that answer came from. */
  final private case class Origin(coordinates: Coordinates, radiusKm: Double, source: String)

  /**
   * Precedence: explicit `--lat`/`--lon` flags, then a Google Maps pin given as `--location-url`
   * (MIP-0008 §5.6, `Coordinates.fromMapsUrl`), then `MAROLA_ORIGIN_LAT`/`MAROLA_ORIGIN_LON`, then
   * the machine's public-IP geolocation (widened radius, see above), then the built-in Rio default
   * only if the IP lookup found nothing at all (offline).
   */
  private def resolveOrigin(args: Array[String], config: AppConfig): Origin < Sync =
    (flagOrigin(args).orElse(urlOrigin(args)), config.origin) match
      case (Some(coords), _) if flagOrigin(args).isDefined =>
        Origin(coords, config.beachSearchRadiusKm, "--lat/--lon flags")
      case (Some(coords), _) =>
        Origin(coords, config.beachSearchRadiusKm, "--location-url (Google Maps pin)")
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

  private def urlOrigin(args: Array[String]): Option[Coordinates] =
    argValue(args, "--location-url").flatMap(Coordinates.fromMapsUrl)

  private def warnHalfPair(args: Array[String], config: AppConfig): Unit < Async =
    val flagHalf =
      argValue(args, "--lat").isDefined != argValue(args, "--lon").isDefined
    val envHalf = config.originLat.isDefined != config.originLon.isDefined
    val badUrl = argValue(args, "--location-url").isDefined && urlOrigin(args).isEmpty
    if flagHalf then
      Console.printLine(
        "warning: --lat and --lon must be given together — ignoring the one present"
      )
    else if badUrl then
      Console.printLine(
        "warning: no pin found in --location-url — expected a google.com/maps URL with @lat,lon, " +
          "q=lat,lon or !3dlat!4dlon (expand a maps.app.goo.gl short link first); ignoring it"
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

  private val SummaryPromptResource = "recommendation_prompt.json"
  private val ReviewPromptResource = "review_prompt.json"

  private def noop: Unit < Async = ()

  /**
   * `--report-sighting <jellyfish|whale> <beach name> [note...]` — the stand-in for submitting a
   * sighting via the (not-yet-built) Telegram bot; see `SightingStore`'s own doc comment on the
   * phase-discipline gap this papers over.
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
    (parseSightingReport(args), argValue(args, "--analyze-photo"), argValue(args, "--ask")) match
      case (Some((kind, beachName, note)), _, _) => reportSighting(config, kind, beachName, note)
      case (None, Some(photoPath), _)            => analyzePhoto(config, photoPath)
      case (None, None, Some(question))          => askOcean(config, question)
      case (None, None, None) if args.contains("--reindex")    => reindexKnowledge(config)
      case (None, None, None) if args.contains("--benchmark")  => runBenchmark(config)
      case (None, None, None) if args.contains("--site")       => buildSite(args, config)
      case (None, None, None) if args.contains("--serve-chat") => serveChat(args, config)
      case (None, None, None)                                  => runRecommendation(args, config)

  /**
   * `--ask "<question>"` — local RAG over the Markdown corpus in `knowledge/` (MIP-0001 /
   * `FUTURE-WORK.md` §9.1): retrieve with the Ollama embedder, answer with the local LLM from those
   * passages only, print the passages' sources.
   */
  private def askOcean(config: AppConfig, question: String): Unit < Async =
    config.llmClient match
      case None =>
        Console.printLine(
          s"(--ask needs an LLM: llmProvider=${config.llmProvider} is not configured)"
        )
      case Some(untraced) =>
        for
          tracing <- resolveTracing(config)
          client = config.tracedLlmClient(tracing).getOrElse(untraced)
          _ <- Console.printLine(
            s"Searching ${config.knowledgeDir} (embedder: ${config.localEmbedModel}) and asking " +
              s"${config.localLlmModel}..."
          )
          outcome <- Abort.run(
            Abort.catching[Throwable](
              OceanQa.answer(
                question,
                config.knowledgeStore,
                client,
                fallback = config.askFallback,
                minScore = config.askMinScore
              )
            )
          )
          _ <- outcome match
            case Result.Success(answer) => Console.printLine("\n" + Report.answer(answer))
            case failure                => Console.printLine(s"(ask failed: $failure)")
        yield ()

  /**
   * `--serve-chat [port]` (MIP-0033 §5.2): runs `marola.agent.ChatServer` in the foreground —
   * `/health` and `/ask` over plain HTTP on `localhost:port` (default `ChatServer.DefaultPort`),
   * meant to sit behind a named Cloudflare Tunnel so the static site's chat widget can reach it.
   */
  private def serveChat(args: Array[String], config: AppConfig): Unit < Async =
    val port =
      argValue(args, "--serve-chat")
        .filterNot(_.startsWith("--"))
        .flatMap(_.toIntOption)
        .getOrElse(marola.agent.ChatServer.DefaultPort)
    for
      _ <-
        if config.llmClient.isEmpty then
          Console.printLine(
            s"warning: llmProvider=${config.llmProvider} is not configured — /ask will 503 until it is"
          )
        else noop
      _ <- Sync.defer(marola.agent.ChatServer.start(config, port))
      _ <- Console.printLine(
        s"marola chat server listening on http://localhost:$port (GET /health, POST /ask) — Ctrl+C to stop"
      )
      _ <- Sync.defer(new java.util.concurrent.CountDownLatch(1).await())
    yield ()

  /**
   * `--site [area-id]` (MIP-0005): build the static map's data for one area of `site/areas.json`,
   * or every area when no id is given, into `site/dist/` (`--site-out <dir>` to change it, `--areas
   * <file>` for another areas file).
   */
  private def buildSite(args: Array[String], config: AppConfig): Unit < Async =
    val areasPath = argValue(args, "--areas")
      .map(java.nio.file.Path.of(_))
      .getOrElse(SiteBuilder.Areas.DefaultPath)
    val out =
      argValue(args, "--site-out").map(java.nio.file.Path.of(_)).getOrElse(SiteBuilder.DefaultOut)
    val wanted = argValue(args, "--site").filterNot(_.startsWith("--"))
    val all = SiteBuilder.Areas.load(areasPath)
    val areas = wanted.fold(all)(id => all.filter(_.id == id))
    if areas.isEmpty then
      Console.printLine(
        s"(no area ${wanted.getOrElse("")} in $areasPath — known: ${all.map(_.id).mkString(", ")})"
      )
    else
      for
        _ <- Console.printLine(
          s"marola :: building the site boards for ${areas.map(_.id).mkString(", ")} -> $out"
        )
        outcome <- Abort.run(
          Abort.catching[Throwable](
            SiteBuilder.build(
              areas,
              out,
              SiteBuilder.DefaultStatic,
              water = config.waterQualityClient,
              now = java.time.OffsetDateTime.now(),
              distanceRefiner = config.distanceRefiner,
              accessibility = Some(config.accessibilityClient)
            )
          )
        )
        _ <- outcome match
          case Result.Success(files) =>
            Console.printLine(
              s"wrote ${files.size} files; boards: " +
                files.filter(_.toString.endsWith(".json")).map(_.toString).mkString(", ")
            )
          case failure =>
            // Fail the *process*, not just the line: site.yml deploys whatever this step leaves
            // in site/dist, and on 5 Sep 2026 that was the first area's boards and no index.html
            // — a 404 at the site root — because the second area's Overpass query failed after
            // the first area had been written and the JVM still exited 0.
            for
              _ <- Console.printLine(s"(site build failed: $failure)")
              _ <- Sync.defer {
                import AllowUnsafe.embrace.danger
                exit(1)
              }
            yield ()
      yield ()

  /** `--benchmark` — marola vs. a plain prompt on ocean questions; see `bench/OceanBenchmark`. */
  private def runBenchmark(config: AppConfig): Unit < Async =
    config.llmClient match
      case None => Console.printLine("(--benchmark needs a configured local LLM)")
      case Some(client) =>
        for
          _ <- Console.printLine(
            s"Benchmarking ${OceanBenchmark.load().size} questions x 3 arms on ${config.localLlmModel} " +
              s"(embedder ${config.localEmbedModel}) - a few minutes on CPU..."
          )
          outcome <- Abort.run(
            Abort.catching[Throwable](
              OceanBenchmark.run(config.knowledgeStore, client, config.askMinScore)
            )
          )
          _ <- outcome match
            case Result.Success(report) =>
              for
                path <- Sync.defer(OceanBenchmark.save(report))
                _ <- Console.printLine("\n" + report.markdown)
                _ <- Console.printLine(s"\nSaved to $path")
                _ <- logBenchmarkRun(config, report, Paths.get(path))
              yield ()
            case failure => Console.printLine(s"(benchmark failed: $failure)")
        yield ()

  /**
   * MIP-0010 task 4: the same run into the configured `RunLedger` — `RunLedger.Noop` prints nothing
   * (no tracking URI configured, the default); `MlflowRunLedger` prints the run's URL, or a warning
   * when the server could not be reached.
   */
  private def logBenchmarkRun(
      config: AppConfig,
      report: OceanBenchmark.Report,
      reportPath: java.nio.file.Path
  ): Unit < Async =
    config.mlflowTrackingUri match
      case None => Sync.defer(())
      case Some(uri) =>
        val context = BenchmarkLedger.Context(
          model = config.localLlmModel,
          embedModel = config.localEmbedModel,
          minScore = config.askMinScore,
          corpusSha = BenchmarkLedger.corpusSha(Paths.get("knowledge")),
          gitSha = BenchmarkLedger.gitSha()
        )
        for
          handle <- BenchmarkLedger.log(
            config.runLedger,
            config.mlflowExperiment,
            report,
            context,
            reportPath
          )
          _ <- handle match
            case Some(run) =>
              Console.printLine(
                s"mlflow: experiment \"${config.mlflowExperiment}/benchmark\", run ${run.runId.take(8)} — " +
                  s"params ${BenchmarkLedger.params(report, context).map((k, v) => s"$k=$v").mkString(" ")}; " +
                  s"artifact ${reportPath.getFileName}" + run.url
                    .map(u => s"\n        → $u")
                    .getOrElse("")
              )
            case None =>
              Console.printLine(
                s"mlflow: could not log this run to $uri (is the server up? `just mlflow-up`) — the report above is saved regardless"
              )
        yield ()

  private def reindexKnowledge(config: AppConfig): Unit < Async =
    for
      _ <- Console.printLine(
        s"Re-embedding ${config.knowledgeDir} with ${config.localEmbedModel} -> ${config.knowledgeIndexPath}"
      )
      outcome <- Abort.run(Abort.catching[Throwable](config.knowledgeStore.rebuild))
      _ <- outcome match
        case Result.Success(n) => Console.printLine(s"Indexed $n chunks.")
        case failure           => Console.printLine(s"(reindex failed: $failure)")
    yield ()

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

  /**
   * `AppConfig.tracing` with its one failure mode handled: an MLflow server that is not up when
   * `MAROLA_TRACES=mlflow` (the experiment lookup fails) prints a warning and traces nothing,
   * rather than failing a recommendation over observability.
   */
  private def resolveTracing(config: AppConfig): Tracing < Async =
    for
      outcome <- Abort.run(Abort.catching[Throwable](config.tracing))
      tracing <- outcome match
        case Result.Success(t) => Sync.defer(t)
        case failure =>
          Console
            .printLine(
              s"(traces disabled: MAROLA_TRACES=${config.tracesBackend} but the backend is unreachable: $failure)"
            )
            .andThen(Tracing.Noop)
    yield tracing

  private def runRecommendation(args: Array[String], config: AppConfig): Unit < Async =
    val summarize = args.contains("--summarize")
    val brief = args.contains("--brief")
    for
      _ <- Console.printLine(
        "marola :: the ocean intelligence layer (POC) — first case: best hour tomorrow to swim nearby"
      )
      _ <- Console.printLine(s"config -> ${config.redacted}")
      _ <- warnHalfPair(args, config)
      origin <- resolveOrigin(args, config)
      _ <- Console.printLine(
        f"origin -> lat=${origin.coordinates.lat}%.4f, lon=${origin.coordinates.lon}%.4f " +
          f"(radius ${origin.radiusKm}%.0fkm, source: ${origin.source})"
      )
      water = config.waterQualityClient(origin.coordinates)
      _ <- Console.printLine(
        s"water quality -> ${water.map(_.name).getOrElse("no provider for this region (MIP-0001 §11)")}"
      )
      // MIP-0010 task 6: one trace per run — `marola.recommend` is the root span, the
      // `bestPerBeachTomorrow` fetch/score step and the two LLM calls (`llm.<model>`) nest under
      // it.
      tracing <- resolveTracing(config)
      _ <- tracing.withSpan("marola.recommend", Map("origin.source" -> origin.source.toString)) {
        for
          results <- tracing.withSpan("bestPerBeachTomorrow") {
            Recommender.bestPerBeachTomorrow(
              origin.coordinates,
              origin.radiusKm,
              distanceRefiner = config.distanceRefiner,
              waterQuality = water,
              accessibility = Some(config.accessibilityClient)
            )
          }
          // MIP-0030: one extra Overpass query, reusing the beaches `bestPerBeachTomorrow`
          // already fetched (no second beach query) — trails don't depend on the forecast, so
          // this doesn't need to be inside the span above.
          trails <- tracing.withSpan("trails") {
            TrailFinder.nearby(origin.coordinates, origin.radiusKm, results.map(_.beach).distinct)
          }
          _ <-
            if results.isEmpty then
              Console.printLine("No beaches found nearby, or no forecast data for tomorrow yet.")
            else printLines(results.zipWithIndex.take(15), brief, trails)
          _ <- printDetail(results.headOption, brief)
          _ <- if summarize then summarizeTop(config, tracing, results.headOption) else noop
          _ <- printLore(
            results.headOption,
            origin.coordinates,
            enabled = !brief && config.seaLoreEnabled && !args.contains("--no-lore")
          )
        yield ()
      }
    yield ()

  private def printDetail(top: Option[BestHour], brief: Boolean): Unit < Async =
    top match
      case Some(best) if !brief => Console.printLine("\n" + Report.detail(best))
      case _                    => ()

  /** MIP-0001 §5.4 — the sourced paragraph, verbatim, last. */
  private def printLore(top: Option[BestHour], origin: Coordinates, enabled: Boolean): Unit <
    Async =
    top
      .filter(_ => enabled)
      .flatMap(best => Report.lore(Report.todayFor(best), best.beach.name, origin)) match
      case Some(text) => Console.printLine("\n" + text)
      case None       => ()

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
   */
  private def summarizeTop(
      config: AppConfig,
      tracing: Tracing,
      top: Option[BestHour]
  ): Unit < Async =
    (top, config.tracedLlmClient(tracing)) match
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
  private def printLines(
      items: List[(BestHour, Int)],
      brief: Boolean,
      trails: List[Trail]
  ): Unit < Async =
    items match
      case Nil => ()
      case (best, i) :: rest =>
        for
          _ <- Console.printLine(
            if brief then Report.briefLine(i + 1, best) else Report.line(i + 1, best)
          )
          // MIP-0030 §3: one line per beach when a trail is within 500m, "no data" otherwise.
          _ <- Console.printLine(Report.trailsLine(Report.nearestTrail(best.beach.name, trails)))
          _ <- printLines(rest, brief, trails)
        yield ()

  run(bootstrap(args.toArray))
