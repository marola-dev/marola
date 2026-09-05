package marola

import kyo.*

import marola.beaches.RouteFinder
import marola.knowledge.{FileKnowledgeStore, OceanQa, OllamaEmbedder}
import marola.llm.{AzureFoundryLlmClient, LlmClient, LocalLlmClient}
import marola.model.Coordinates
import marola.sightings.{CosmosDbSightingStore, LocalFileSightingStore, SightingStore}
import marola.vision.{AzureVisionClient, LocalVisionClient, VisionClient}
import marola.water.{ImaScWaterQualityClient, WaterQualityClient}

/**
 * Which backend a pluggable integration uses — `Local` is always the zero-Azure default. A real
 * `enum` instead of a raw `String` (an earlier version of this file compared `llmProvider ==
 * "azure"` directly): typos like `"azur"` silently fell back to local before: now they don't
 * compile. Parsed once in `AppConfig.fromEnv`, case-insensitively, defaulting to `Local` for
 * anything unrecognized (including no env var set at all) rather than failing to start.
 */
enum Provider derives CanEqual:
  case Local, Azure

object Provider:
  def fromEnv(value: Option[String]): Provider =
    value match
      case Some(v) if v.equalsIgnoreCase("azure") => Azure
      case _                                      => Local

/**
 * Bathing-water data is regional, so its "provider" is an agency, not local-vs-Azure (MIP-0001
 * §5.2: there is no Azure water-quality service, and none is invented). `Auto` (default) picks
 * IMA/SC when the origin is inside Santa Catarina and `None` elsewhere, printing which.
 */
enum WaterProvider derives CanEqual:
  case Auto, ImaSc, None

object WaterProvider:
  def fromEnv(value: Option[String]): WaterProvider =
    value.map(_.trim.toLowerCase) match
      case Some("ima-sc") | Some("ima_sc") | Some("imasc") => ImaSc
      case Some("none") | Some("off")                      => None
      case _                                               => Auto

/**
 * Minimal env-driven config: plain Scala, no Kyo `Env` effect yet (nothing to inject it into at POC
 * stage). `telegramBotToken` and `foundryProjectEndpoint` are both `None` until you actually set
 * them up — see docs/ARCHITECTURE.md and `.env.example`. `Main` runs the CLI/local-coordinates path
 * regardless of whether either is set; only the Telegram bot loop needs the former.
 *
 * `llmProvider` picks which `LlmClient` backend `Main`'s query-synthesis step uses — see
 * `ARCHITECTURE.md` §5/§6 for the local-vs-Azure tradeoff this exists to make a real, permanent
 * choice rather than a bootstrap-only default. `Provider.Local` (the default) needs nothing beyond
 * a running Ollama-compatible server; `Provider.Azure` needs a provisioned Foundry/Azure OpenAI
 * deployment and `az login` or a managed identity.
 */
final case class AppConfig(
    telegramBotToken: Option[String],
    foundryProjectEndpoint: Option[String],
    foundryApiVersion: String,
    beachSearchRadiusKm: Double,
    originLat: Option[Double],
    originLon: Option[Double],
    waterQualityProvider: WaterProvider,
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
    appInsightsConnectionString: Option[String]
):
  /**
   * A fixed "current location" for the CLI, from `MAROLA_ORIGIN_LAT`/`MAROLA_ORIGIN_LON` — both or
   * neither: one without the other is ignored (and `Main` says so) rather than half-applied. Sits
   * between `--lat/--lon` (wins) and IP geolocation (fallback) in `Main.resolveOrigin`.
   */
  def origin: Option[Coordinates] =
    for
      lat <- originLat
      lon <- originLon
    yield Coordinates(lat, lon)

  /**
   * What `Main` prints instead of the raw case class (FABLE_REVIEW C1: the raw `toString` echoed
   * every key/token to stdout). Secrets show as set/unset; everything else verbatim.
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
      s"maps=${secret(azureMapsSubscriptionKey)}",
      s"sightings=$sightingStoreProvider(${
          if sightingStoreProvider == Provider.Local then localSightingStorePath
          else s"$cosmosDbDatabase/$cosmosDbContainer key=${secret(cosmosDbKey)}"
        })",
      s"vision=$visionProvider(${
          if visionProvider == Provider.Local then localVisionModel
          else s"endpoint=${secret(azureVisionEndpoint)} key=${secret(azureVisionKey)}"
        })",
      s"appinsights=${secret(appInsightsConnectionString)}"
    ).mkString(" ")

  /** MIP-0001 §5.2. `None` = no data, which `Swimability.waterVerdict` scores as nothing. */
  def waterQualityClient(origin: Coordinates): Option[WaterQualityClient] =
    waterQualityProvider match
      case WaterProvider.ImaSc => Some(ImaScWaterQualityClient())
      case WaterProvider.None  => scala.None
      case WaterProvider.Auto =>
        if ImaScWaterQualityClient.coversOrigin(origin) then Some(ImaScWaterQualityClient())
        else scala.None

  /**
   * Local-only RAG (`FUTURE-WORK.md` §9.1, first cut): the corpus under `knowledgeDir`, embedded by
   * the same Ollama server as the LLM, indexed on disk. No Azure alternative yet — Azure AI Search
   * is the obvious sibling when Phase 2 comes.
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
   * `Recommender` (in `marola-core`) can't reference `RouteFinder` (in `marola-azure`) directly —
   * `marola-core` has zero Azure SDK dependency by design (`FUTURE-WORK.md` §7.3). This is the
   * dependency-inversion seam: `Recommender.bestPerBeachTomorrow`'s `distanceRefiner` parameter
   * takes a plain function, and only `marola-cli` (which depends on both `core` and `azure`) is in
   * a position to build one backed by `RouteFinder`. `None` when no Azure Maps key is configured —
   * `Recommender` already treats that as "keep the haversine distance."
   */
  def distanceRefiner: Option[(Coordinates, Coordinates) => Double < Sync] =
    azureMapsSubscriptionKey.map(key =>
      (origin, dest) => RouteFinder.travelDistanceKm(key, origin, dest)
    )

object AppConfig:
  def fromEnv: AppConfig =
    AppConfig(
      telegramBotToken = sys.env.get("MAROLA_TELEGRAM_BOT_TOKEN"),
      foundryProjectEndpoint = sys.env.get("FOUNDRY_PROJECT_ENDPOINT"),
      foundryApiVersion = sys.env.getOrElse("FOUNDRY_API_VERSION", "2026-01-01-preview"),
      beachSearchRadiusKm =
        sys.env.get("MAROLA_BEACH_SEARCH_RADIUS_KM").flatMap(_.toDoubleOption).getOrElse(15.0),
      originLat = sys.env.get("MAROLA_ORIGIN_LAT").flatMap(_.toDoubleOption),
      originLon = sys.env.get("MAROLA_ORIGIN_LON").flatMap(_.toDoubleOption),
      waterQualityProvider = WaterProvider.fromEnv(sys.env.get("MAROLA_WATER_QUALITY_PROVIDER")),
      localEmbedModel = sys.env.getOrElse("MAROLA_LOCAL_EMBED_MODEL", OllamaEmbedder.DefaultModel),
      knowledgeDir = sys.env.getOrElse("MAROLA_KNOWLEDGE_DIR", FileKnowledgeStore.DefaultCorpusDir),
      knowledgeIndexPath =
        sys.env.getOrElse("MAROLA_KNOWLEDGE_INDEX_PATH", FileKnowledgeStore.DefaultIndexPath),
      seaLoreEnabled =
        !sys.env.get("MAROLA_SEA_LORE").exists(v => v.equalsIgnoreCase("off") || v == "0"),
      // `general` (default): off-corpus questions get a labelled unsourced answer instead of a
      // refusal; `strict` keeps the pure-RAG behaviour. See OceanQa.Fallback and `just benchmark`.
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
      // directly rather than bridged through a MAROLA_-prefixed name, unlike Langfuse's Python env
      // vars (which don't share this repo's naming convention to begin with).
      appInsightsConnectionString = sys.env.get("APPLICATIONINSIGHTS_CONNECTION_STRING")
    )
