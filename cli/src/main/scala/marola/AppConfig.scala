package marola

import kyo.*

import marola.beaches.{
  AccessibilityClient,
  NoopAccessibilityClient,
  OverpassAccessibilityClient,
  RouteFinder
}
import marola.knowledge.{FileKnowledgeStore, OceanQa, OllamaEmbedder}
import marola.ledger.{MlflowRunLedger, RunLedger}
import marola.llm.{AzureFoundryLlmClient, LlmClient, LocalLlmClient, TracedLlmClient}
import marola.model.Coordinates
import marola.observability.{AzureMonitorTracing, MlflowTracing, Tracing}
import marola.sightings.{CosmosDbSightingStore, LocalFileSightingStore, SightingStore}
import marola.vision.{AzureVisionClient, LocalVisionClient, VisionClient}
import marola.water.{
  CachedWaterQualityClient,
  FallbackWaterQualityClient,
  ImaScPdfWaterQualityClient,
  ImaScWaterQualityClient,
  IneaRjWaterQualityClient,
  InemaBaWaterQualityClient,
  WaterQualityClient
}

/** Which backend a pluggable integration uses — `Local` is always the zero-Azure default. */
enum Provider derives CanEqual:
  case Local, Azure

object Provider:
  def fromEnv(value: Option[String]): Provider =
    value match
      case Some(v) if v.equalsIgnoreCase("azure") => Azure
      case _                                      => Local

/**
 * Bathing-water data is regional, so its "provider" is an agency, not local-vs-Azure (MIP-0001
 * §5.2: there is no Azure water-quality service, and none is invented).
 */
enum WaterProvider derives CanEqual:
  case Auto, ImaSc, InemaBa, IneaRj, None

object WaterProvider:
  def fromEnv(value: Option[String]): WaterProvider =
    value.map(_.trim.toLowerCase) match
      case Some("ima-sc") | Some("ima_sc") | Some("imasc")       => ImaSc
      case Some("inema-ba") | Some("inema_ba") | Some("inemaba") => InemaBa
      case Some("inea-rj") | Some("inea_rj") | Some("inearj")    => IneaRj
      case Some("none") | Some("off")                            => None
      case _                                                     => Auto

/**
 * MIP-0021 §5: `Overpass` (default) is the only real source — there is no Azure alternative for OSM
 * amenities — so this is a simple on/off switch, not a `Provider`-shaped local-vs-Azure choice.
 */
enum FacilitiesProvider derives CanEqual:
  case Overpass, Off

object FacilitiesProvider:
  def fromEnv(value: Option[String]): FacilitiesProvider =
    value.map(_.trim.toLowerCase) match
      case Some("off") => Off
      case _           => Overpass

/**
 * Which `Tracing` backend `Main` wraps the pipeline in — `MAROLA_TRACES=off|mlflow|azure` (MIP-0010
 * §5).
 */
enum TraceBackend derives CanEqual:
  case Off, Mlflow, Azure

object TraceBackend:
  def fromEnv(value: Option[String], appInsightsConnectionString: Option[String]): TraceBackend =
    value.map(_.trim.toLowerCase) match
      case Some("off")    => Off
      case Some("mlflow") => Mlflow
      case Some("azure")  => Azure
      case _              => if appInsightsConnectionString.isDefined then Azure else Off

/**
 * Minimal env-driven config: plain Scala, no Kyo `Env` effect yet (nothing to inject it into at POC
 * stage).
 */
final case class AppConfig(
    telegramBotToken: Option[String],
    foundryProjectEndpoint: Option[String],
    foundryApiVersion: String,
    beachSearchRadiusKm: Double,
    originLat: Option[Double],
    originLon: Option[Double],
    waterQualityProvider: WaterProvider,
    facilitiesProvider: FacilitiesProvider,
    localEmbedModel: String,
    knowledgeDir: String,
    knowledgeIndexPath: String,
    seaLoreEnabled: Boolean,
    askFallback: OceanQa.Fallback,
    askMinScore: Double,
    llmProvider: Provider,
    localLlmBaseUrl: String,
    localLlmModel: String,
    azureMapsSubscriptionKey: Option[String],
    sightingStoreProvider: Provider,
    localSightingStorePath: String,
    cosmosDbEndpoint: Option[String],
    cosmosDbKey: Option[String],
    cosmosDbDatabase: String,
    cosmosDbContainer: String,
    visionProvider: Provider,
    localVisionModel: String,
    azureVisionEndpoint: Option[String],
    azureVisionKey: Option[String],
    appInsightsConnectionString: Option[String],
    mlflowTrackingUri: Option[String],
    mlflowExperiment: String,
    tracesBackend: TraceBackend,
    traceContent: Boolean,
    // Defaulted so every existing construction still compiles, and injected rather than read
    // inline from the environment (`.claude/rules/scala.md`) so a test can point it at a tmpdir.
    waterCacheDir: java.nio.file.Path = java.nio.file.Path.of("data", "water-cache")
):
  /**
   * A fixed "current location" for the CLI, from `MAROLA_ORIGIN_LAT`/`MAROLA_ORIGIN_LON` — both or
   * neither: one without the other is ignored (and `Main` says so) rather than half-applied.
   */
  def origin: Option[Coordinates] =
    for
      lat <- originLat
      lon <- originLon
    yield Coordinates(lat, lon)

  /**
   * What `Main` prints instead of the raw case class (FABLE_REVIEW C1: the raw `toString` echoed
   * every key/token to stdout).
   */
  def redacted: String =
    def secret(v: Option[String]) = if v.isDefined then "<set>" else "unset"
    List(
      s"telegram=${secret(telegramBotToken)}",
      s"llm=$llmProvider(${
          if llmProvider == Provider.Local then s"$localLlmBaseUrl $localLlmModel"
          else foundryProjectEndpoint.getOrElse("no endpoint") + " " + foundryApiVersion
        })",
      s"embed=$localEmbedModel",
      s"knowledge=$knowledgeDir -> $knowledgeIndexPath",
      s"lore=${if seaLoreEnabled then "on" else "off"}",
      s"ask=$askFallback>=$askMinScore",
      s"origin=${origin.map(o => f"${o.lat}%.4f,${o.lon}%.4f").getOrElse("auto")}",
      f"radius=${beachSearchRadiusKm}%.0fkm",
      s"water=$waterQualityProvider",
      s"facilities=$facilitiesProvider",
      s"maps=${secret(azureMapsSubscriptionKey)}",
      s"sightings=$sightingStoreProvider(${
          if sightingStoreProvider == Provider.Local then localSightingStorePath
          else s"$cosmosDbDatabase/$cosmosDbContainer key=${secret(cosmosDbKey)}"
        })",
      s"vision=$visionProvider(${
          if visionProvider == Provider.Local then localVisionModel
          else s"endpoint=${secret(azureVisionEndpoint)} key=${secret(azureVisionKey)}"
        })",
      s"appinsights=${secret(appInsightsConnectionString)}",
      s"mlflow=${secret(mlflowTrackingUri)}(experiment=$mlflowExperiment)",
      s"traces=$tracesBackend${if traceContent then "(content)" else ""}"
    ).mkString(" ")

  /**
   * MIP-0001 §5.2, extended MIP-0031 §5. Every provider is wrapped so an agency outage serves the
   * last good fetch instead of blanking every beach — see `CachedWaterQualityClient`.
   */
  def waterQualityClient(origin: Coordinates): Option[WaterQualityClient] =
    selectWaterClient(origin).map { c =>
      // Live feed -> the agency's other publication channel -> last good fetch. IMA's JSON went
      // down while its weekly bulletin PDF stayed up, so an outage in one channel should not be a
      // blackout for the user.
      val withBackup = backupFor(c).fold(c)(FallbackWaterQualityClient(c, _))
      CachedWaterQualityClient(
        withBackup,
        CachedWaterQualityClient.fileFor(waterCacheDir, c.name)
      )
    }

  /** Only IMA/SC has a second channel today; INEA and INEMA publish one bulletin each. */
  private def backupFor(primary: WaterQualityClient): Option[WaterQualityClient] =
    primary match
      case _: ImaScWaterQualityClient => Some(ImaScPdfWaterQualityClient())
      case _                          => scala.None

  private def selectWaterClient(origin: Coordinates): Option[WaterQualityClient] =
    waterQualityProvider match
      case WaterProvider.ImaSc   => Some(ImaScWaterQualityClient())
      case WaterProvider.InemaBa => Some(InemaBaWaterQualityClient())
      case WaterProvider.IneaRj  => Some(IneaRjWaterQualityClient())
      case WaterProvider.None    => scala.None
      case WaterProvider.Auto =>
        if ImaScWaterQualityClient.coversOrigin(origin) then Some(ImaScWaterQualityClient())
        else if InemaBaWaterQualityClient.coversOrigin(origin) then
          Some(InemaBaWaterQualityClient())
        else if IneaRjWaterQualityClient.coversOrigin(origin) then Some(IneaRjWaterQualityClient())
        else scala.None

  /**
   * MIP-0021 §5: never `None` — `Off` still needs a client, `NoopAccessibilityClient`, so
   * `Recommender`'s `Some(...)` call site doesn't have to special-case "off" separately from "the
   * real client returned no data." `Overpass` (default) reuses the same public endpoint
   * `BeachFinder` already talks to.
   */
  def accessibilityClient: AccessibilityClient =
    facilitiesProvider match
      case FacilitiesProvider.Overpass => OverpassAccessibilityClient()
      case FacilitiesProvider.Off      => NoopAccessibilityClient()

  /**
   * Local-only RAG (`FUTURE-WORK.md` §9.1, first cut): the corpus under `knowledgeDir`, embedded by
   * the same Ollama server as the LLM, indexed on disk.
   */
  def knowledgeStore: FileKnowledgeStore =
    FileKnowledgeStore(
      knowledgeDir,
      knowledgeIndexPath,
      OllamaEmbedder(OllamaEmbedder.nativeBaseUrl(localLlmBaseUrl), localEmbedModel)
    )

  /**
   * `None` for `llmProvider = Azure` without `foundryProjectEndpoint` set — there's no reasonable
   * Azure default to fall back to, unlike the local provider's `localhost` default.
   */
  def llmClient: Option[LlmClient] =
    llmProvider match
      case Provider.Azure => foundryProjectEndpoint.map(AzureFoundryLlmClient(_, foundryApiVersion))
      case Provider.Local => Some(LocalLlmClient(localLlmBaseUrl, localLlmModel))

  /**
   * `llmClient` behind `TracedLlmClient` (MIP-0010 task 6): one `llm.<model>` span per call on the
   * given `Tracing` — transparent with `Tracing.Noop`.
   */
  def tracedLlmClient(tracing: Tracing): Option[LlmClient] =
    llmClient.map(TracedLlmClient(_, llmModelName, tracing, traceContent))

  def llmModelName: String =
    llmProvider match
      case Provider.Local => localLlmModel
      case Provider.Azure =>
        foundryProjectEndpoint.map(_.stripSuffix("/").split('/').last).getOrElse("foundry")

  /**
   * Same shape as `llmClient`: `None` for `sightingStoreProvider = Azure` without both Cosmos
   * settings present.
   */
  def sightingStore: Option[SightingStore] =
    sightingStoreProvider match
      case Provider.Azure =>
        for
          endpoint <- cosmosDbEndpoint
          key <- cosmosDbKey
        yield CosmosDbSightingStore(endpoint, key, cosmosDbDatabase, cosmosDbContainer)
      case Provider.Local => Some(LocalFileSightingStore(localSightingStorePath))

  /** Same shape again: `None` for `visionProvider = Azure` without both Vision settings present. */
  def visionClient: Option[VisionClient] =
    visionProvider match
      case Provider.Azure =>
        for
          endpoint <- azureVisionEndpoint
          key <- azureVisionKey
        yield AzureVisionClient(endpoint, key)
      case Provider.Local => Some(LocalVisionClient(localLlmBaseUrl, localVisionModel))

  /**
   * `RunLedger.Noop` (MIP-0010) unless `MAROLA_MLFLOW_TRACKING_URI` is set — no network call, no
   * mlflow server needed, matches every other pluggable integration's local-by-nothing default
   * except this one has no Azure sibling yet (MIP §4.5 is still Draft).
   */
  def runLedger: RunLedger =
    mlflowTrackingUri match
      case Some(uri) => MlflowRunLedger(uri)
      case None      => RunLedger.Noop

  /**
   * `Recommender` (in `marola-core`) can't reference `RouteFinder` (in `marola-azure`) directly —
   * `marola-core` has zero Azure SDK dependency by design (`FUTURE-WORK.md` §7.3).
   */
  def distanceRefiner: Option[(Coordinates, Coordinates) => Double < Sync] =
    azureMapsSubscriptionKey.map(key =>
      (origin, dest) => RouteFinder.travelDistanceKm(key, origin, dest)
    )

  /**
   * The `Tracing` instance `Main` wraps the pipeline in — resolved once per run, in `Main`, and
   * passed down (it is an effect: `MlflowTracing` looks the traces experiment up over REST before
   * the first span).
   */
  def tracing: Tracing < Sync =
    tracesBackend match
      case TraceBackend.Azure =>
        Sync.defer(appInsightsConnectionString.fold(Tracing.Noop)(AzureMonitorTracing(_)))
      case TraceBackend.Mlflow =>
        mlflowTrackingUri match
          case Some(uri) => MlflowTracing(uri, mlflowExperiment)
          case None      => Sync.defer(Tracing.Noop)
      case TraceBackend.Off => Sync.defer(Tracing.Noop)

object AppConfig:
  def fromEnv: AppConfig =
    val appInsightsConnectionString = sys.env.get("APPLICATIONINSIGHTS_CONNECTION_STRING")
    AppConfig(
      telegramBotToken = sys.env.get("MAROLA_TELEGRAM_BOT_TOKEN"),
      foundryProjectEndpoint = sys.env.get("FOUNDRY_PROJECT_ENDPOINT"),
      foundryApiVersion = sys.env.getOrElse("FOUNDRY_API_VERSION", "2026-01-01-preview"),
      beachSearchRadiusKm =
        sys.env.get("MAROLA_BEACH_SEARCH_RADIUS_KM").flatMap(_.toDoubleOption).getOrElse(15.0),
      originLat = sys.env.get("MAROLA_ORIGIN_LAT").flatMap(_.toDoubleOption),
      originLon = sys.env.get("MAROLA_ORIGIN_LON").flatMap(_.toDoubleOption),
      waterQualityProvider = WaterProvider.fromEnv(sys.env.get("MAROLA_WATER_QUALITY_PROVIDER")),
      facilitiesProvider = FacilitiesProvider.fromEnv(sys.env.get("MAROLA_FACILITIES")),
      localEmbedModel = sys.env.getOrElse("MAROLA_LOCAL_EMBED_MODEL", OllamaEmbedder.DefaultModel),
      knowledgeDir = sys.env.getOrElse("MAROLA_KNOWLEDGE_DIR", FileKnowledgeStore.DefaultCorpusDir),
      knowledgeIndexPath =
        sys.env.getOrElse("MAROLA_KNOWLEDGE_INDEX_PATH", FileKnowledgeStore.DefaultIndexPath),
      seaLoreEnabled =
        !sys.env.get("MAROLA_SEA_LORE").exists(v => v.equalsIgnoreCase("off") || v == "0"),
      // `general` (default): off-corpus questions get a labelled unsourced answer instead of a
      // refusal; `strict` keeps the pure-RAG behaviour.
      askFallback =
        if sys.env.get("MAROLA_ASK_FALLBACK").exists(_.equalsIgnoreCase("strict")) then
          OceanQa.Fallback.Strict
        else OceanQa.Fallback.General,
      askMinScore = sys.env
        .get("MAROLA_ASK_MIN_SCORE")
        .flatMap(_.toDoubleOption)
        .getOrElse(OceanQa.DefaultMinScore),
      llmProvider = Provider.fromEnv(sys.env.get("MAROLA_LLM_PROVIDER")),
      localLlmBaseUrl =
        sys.env.getOrElse("MAROLA_LOCAL_LLM_BASE_URL", LocalLlmClient.DefaultBaseUrl),
      localLlmModel = sys.env.getOrElse("MAROLA_LOCAL_LLM_MODEL", LocalLlmClient.DefaultModel),
      azureMapsSubscriptionKey = sys.env.get("AZURE_MAPS_SUBSCRIPTION_KEY"),
      sightingStoreProvider = Provider.fromEnv(sys.env.get("MAROLA_SIGHTING_STORE_PROVIDER")),
      localSightingStorePath =
        sys.env.getOrElse("MAROLA_LOCAL_SIGHTING_STORE_PATH", LocalFileSightingStore.DefaultPath),
      cosmosDbEndpoint = sys.env.get("COSMOS_DB_ENDPOINT"),
      cosmosDbKey = sys.env.get("COSMOS_DB_KEY"),
      cosmosDbDatabase = sys.env.getOrElse("COSMOS_DB_DATABASE", "marola"),
      cosmosDbContainer = sys.env.getOrElse("COSMOS_DB_CONTAINER", "sightings"),
      visionProvider = Provider.fromEnv(sys.env.get("MAROLA_VISION_PROVIDER")),
      localVisionModel =
        sys.env.getOrElse("MAROLA_LOCAL_VISION_MODEL", LocalVisionClient.DefaultModel),
      azureVisionEndpoint = sys.env.get("AZURE_VISION_ENDPOINT"),
      azureVisionKey = sys.env.get("AZURE_VISION_KEY"),
      // Azure's own standard env var name (every Azure Monitor SDK/agent auto-detects it) — used
      // directly rather than bridged through a MAROLA_-prefixed name, unlike Langfuse's Python
      // env vars (which don't share this repo's naming convention to begin with).
      appInsightsConnectionString = appInsightsConnectionString,
      // MIP-0010 §5: unset ⇒ RunLedger.Noop (`just mlflow-up` prints the tracking URI to export).
      mlflowTrackingUri = sys.env.get("MAROLA_MLFLOW_TRACKING_URI"),
      // The experiment prefix (`marola/<kind>`), not a full experiment name — see `runLedger`.
      mlflowExperiment = sys.env.getOrElse("MAROLA_MLFLOW_EXPERIMENT", "marola"),
      tracesBackend =
        TraceBackend.fromEnv(sys.env.get("MAROLA_TRACES"), appInsightsConnectionString),
      // Off by default: the prompt carries the swimmer's coordinates (MIP-0010 §5).
      traceContent = TracedLlmClient.contentFromEnv(sys.env.get("MAROLA_TRACE_CONTENT"))
    )
