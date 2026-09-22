package marola

import kyo.*

import marola.beaches.{AccessibilityClient, NoopAccessibilityClient, OverpassAccessibilityClient}
import marola.knowledge.{FileKnowledgeStore, OceanQa, OllamaEmbedder}
import marola.ledger.{MlflowRunLedger, RunLedger}
import marola.llm.{LlmClient, LocalLlmClient, TracedLlmClient}
import marola.model.Coordinates
import marola.observability.{MlflowTracing, Tracing}
import marola.sightings.{LocalFileSightingStore, SightingStore}
import marola.vision.{LocalVisionClient, VisionClient}
import marola.water.{
  CachedWaterQualityClient,
  FallbackWaterQualityClient,
  ImaScPdfWaterQualityClient,
  ImaScWaterQualityClient,
  IneaRjWaterQualityClient,
  InemaBaWaterQualityClient,
  WaterQualityClient
}

/** Bathing-water data is regional, so its "provider" is an agency (MIP-0001 §5.2). */
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

/** MIP-0021 §5: `Overpass` (default) is the only real source, so this is an on/off switch. */
enum FacilitiesProvider derives CanEqual:
  case Overpass, Off

object FacilitiesProvider:
  def fromEnv(value: Option[String]): FacilitiesProvider =
    value.map(_.trim.toLowerCase) match
      case Some("off") => Off
      case _           => Overpass

/**
 * Which `Tracing` backend `Main` wraps the pipeline in — `MAROLA_TRACES=off|mlflow` (MIP-0010 §5).
 */
enum TraceBackend derives CanEqual:
  case Off, Mlflow

object TraceBackend:
  def fromEnv(value: Option[String]): TraceBackend =
    value.map(_.trim.toLowerCase) match
      case Some("mlflow") => Mlflow
      case _              => Off

/**
 * Minimal env-driven config: plain Scala, no Kyo `Env` effect yet (nothing to inject it into at POC
 * stage).
 */
final case class AppConfig(
    telegramBotToken: Option[String],
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
    localLlmBaseUrl: String,
    localLlmModel: String,
    localSightingStorePath: String,
    localVisionModel: String,
    mlflowTrackingUri: Option[String],
    mlflowExperiment: String,
    tracesBackend: TraceBackend,
    traceContent: Boolean,
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
      s"llm=$localLlmBaseUrl $localLlmModel",
      s"embed=$localEmbedModel",
      s"knowledge=$knowledgeDir -> $knowledgeIndexPath",
      s"lore=${if seaLoreEnabled then "on" else "off"}",
      s"ask=$askFallback>=$askMinScore",
      s"origin=${origin.map(o => f"${o.lat}%.4f,${o.lon}%.4f").getOrElse("auto")}",
      f"radius=${beachSearchRadiusKm}%.0fkm",
      s"water=$waterQualityProvider",
      s"facilities=$facilitiesProvider",
      s"sightings=$localSightingStorePath",
      s"vision=$localVisionModel",
      s"mlflow=${secret(mlflowTrackingUri)}(experiment=$mlflowExperiment)",
      s"traces=$tracesBackend${if traceContent then "(content)" else ""}"
    ).mkString(" ")

  /**
   * MIP-0001 §5.2, extended MIP-0031 §5. Every provider is wrapped so an agency outage serves the
   * last good fetch instead of blanking every beach — see `CachedWaterQualityClient`.
   */
  def waterQualityClient(origin: Coordinates): Option[WaterQualityClient] =
    selectWaterClient(origin).map { c =>
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

  def llmClient: LlmClient = LocalLlmClient(localLlmBaseUrl, localLlmModel)

  /**
   * `llmClient` behind `TracedLlmClient` (MIP-0010 task 6): one `llm.<model>` span per call on the
   * given `Tracing` — transparent with `Tracing.Noop`.
   */
  def tracedLlmClient(tracing: Tracing): LlmClient =
    TracedLlmClient(llmClient, localLlmModel, tracing, traceContent)

  def sightingStore: SightingStore = LocalFileSightingStore(localSightingStorePath)

  def visionClient: VisionClient = LocalVisionClient(localLlmBaseUrl, localVisionModel)

  /**
   * `RunLedger.Noop` (MIP-0010) unless `MAROLA_MLFLOW_TRACKING_URI` is set — no network call, no
   * mlflow server needed.
   */
  def runLedger: RunLedger =
    mlflowTrackingUri match
      case Some(uri) => MlflowRunLedger(uri)
      case None      => RunLedger.Noop

  /**
   * The `Tracing` instance `Main` wraps the pipeline in — resolved once per run, in `Main`, and
   * passed down (it is an effect: `MlflowTracing` looks the traces experiment up over REST before
   * the first span).
   */
  def tracing: Tracing < Sync =
    tracesBackend match
      case TraceBackend.Mlflow =>
        mlflowTrackingUri match
          case Some(uri) => MlflowTracing(uri, mlflowExperiment)
          case None      => Sync.defer(Tracing.Noop)
      case TraceBackend.Off => Sync.defer(Tracing.Noop)

object AppConfig:
  def fromEnv: AppConfig =
    AppConfig(
      telegramBotToken = sys.env.get("MAROLA_TELEGRAM_BOT_TOKEN"),
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
      localLlmBaseUrl =
        sys.env.getOrElse("MAROLA_LOCAL_LLM_BASE_URL", LocalLlmClient.DefaultBaseUrl),
      localLlmModel = sys.env.getOrElse("MAROLA_LOCAL_LLM_MODEL", LocalLlmClient.DefaultModel),
      localSightingStorePath =
        sys.env.getOrElse("MAROLA_LOCAL_SIGHTING_STORE_PATH", LocalFileSightingStore.DefaultPath),
      localVisionModel =
        sys.env.getOrElse("MAROLA_LOCAL_VISION_MODEL", LocalVisionClient.DefaultModel),
      // MIP-0010 §5: unset ⇒ RunLedger.Noop (`just mlflow-up` prints the tracking URI to export).
      mlflowTrackingUri = sys.env.get("MAROLA_MLFLOW_TRACKING_URI"),
      // The experiment prefix (`marola/<kind>`), not a full experiment name — see `runLedger`.
      mlflowExperiment = sys.env.getOrElse("MAROLA_MLFLOW_EXPERIMENT", "marola"),
      tracesBackend = TraceBackend.fromEnv(sys.env.get("MAROLA_TRACES")),
      // Off by default: the prompt carries the swimmer's coordinates (MIP-0010 §5).
      traceContent = TracedLlmClient.contentFromEnv(sys.env.get("MAROLA_TRACE_CONTENT"))
    )
