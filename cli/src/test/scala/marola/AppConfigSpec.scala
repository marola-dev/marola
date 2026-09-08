package marola

import kyo.*

import marola.beaches.{NoopAccessibilityClient, OverpassAccessibilityClient}
import marola.knowledge.OceanQa
import marola.ledger.{MlflowRunLedger, RunLedger}
import marola.llm.{AzureFoundryLlmClient, LocalLlmClient}
import marola.model.Coordinates
import marola.observability.Tracing
import marola.sightings.{CosmosDbSightingStore, LocalFileSightingStore}
import marola.vision.{AzureVisionClient, LocalVisionClient}

/**
 * `TraceBackend.fromEnv` (MIP-0010 tracing-lane task 5): pure, no network, no `sys.env` read —
 * every `MAROLA_TRACES` value plus the backward-compat default derived from whether
 * `APPLICATIONINSIGHTS_CONNECTION_STRING` is set.
 */
class AppConfigSpec extends munit.FunSuite:

  private val appInsightsSet: Option[String] = Some("InstrumentationKey=fake")
  private val appInsightsUnset: Option[String] = None

  test("MAROLA_TRACES=off is always Off, regardless of the App Insights var") {
    assertEquals(TraceBackend.fromEnv(Some("off"), appInsightsSet), TraceBackend.Off)
    assertEquals(TraceBackend.fromEnv(Some("off"), appInsightsUnset), TraceBackend.Off)
  }

  test("MAROLA_TRACES=mlflow is always Mlflow, regardless of the App Insights var") {
    assertEquals(TraceBackend.fromEnv(Some("mlflow"), appInsightsSet), TraceBackend.Mlflow)
    assertEquals(TraceBackend.fromEnv(Some("mlflow"), appInsightsUnset), TraceBackend.Mlflow)
  }

  test("MAROLA_TRACES=azure is always Azure, regardless of the App Insights var") {
    assertEquals(TraceBackend.fromEnv(Some("azure"), appInsightsSet), TraceBackend.Azure)
    assertEquals(TraceBackend.fromEnv(Some("azure"), appInsightsUnset), TraceBackend.Azure)
  }

  test("MAROLA_TRACES is parsed case-insensitively") {
    assertEquals(TraceBackend.fromEnv(Some("OFF"), appInsightsSet), TraceBackend.Off)
    assertEquals(TraceBackend.fromEnv(Some("MlFlOw"), appInsightsUnset), TraceBackend.Mlflow)
    assertEquals(TraceBackend.fromEnv(Some("AZURE"), appInsightsUnset), TraceBackend.Azure)
  }

  test("unset MAROLA_TRACES with the App Insights var set: backward-compat default is Azure") {
    assertEquals(TraceBackend.fromEnv(None, appInsightsSet), TraceBackend.Azure)
  }

  test("unset MAROLA_TRACES with nothing configured: default is Off") {
    assertEquals(TraceBackend.fromEnv(None, appInsightsUnset), TraceBackend.Off)
  }

  test("an unrecognized MAROLA_TRACES value falls back the same way unset does") {
    assertEquals(TraceBackend.fromEnv(Some("bogus"), appInsightsSet), TraceBackend.Azure)
    assertEquals(TraceBackend.fromEnv(Some("bogus"), appInsightsUnset), TraceBackend.Off)
  }

  // MIP-0021 §5: MAROLA_FACILITIES=off|overpass, default overpass — unlike WaterProvider there is
  // no Azure/region-auto case, just an on/off switch.
  test("MAROLA_FACILITIES=off is Off; unset or anything else defaults to Overpass") {
    assertEquals(FacilitiesProvider.fromEnv(Some("off")), FacilitiesProvider.Off)
    assertEquals(FacilitiesProvider.fromEnv(Some("OFF")), FacilitiesProvider.Off)
    assertEquals(FacilitiesProvider.fromEnv(Some("overpass")), FacilitiesProvider.Overpass)
    assertEquals(FacilitiesProvider.fromEnv(Some("bogus")), FacilitiesProvider.Overpass)
    assertEquals(FacilitiesProvider.fromEnv(None), FacilitiesProvider.Overpass)
  }

  // --- the enum parsers: every branch, including the "don't fail to start on a typo" default
  // -----.

  test(
    "Provider.fromEnv: only 'azure' (any case) is Azure; anything else, including unset, is Local"
  ) {
    assertEquals(Provider.fromEnv(Some("azure")), Provider.Azure)
    assertEquals(Provider.fromEnv(Some("AZURE")), Provider.Azure)
    assertEquals(Provider.fromEnv(Some("local")), Provider.Local)
    assertEquals(
      Provider.fromEnv(Some("azur")),
      Provider.Local,
      "a typo falls back, never fails to start"
    )
    assertEquals(Provider.fromEnv(None), Provider.Local)
  }

  test("WaterProvider.fromEnv: every documented alias, plus off/none, plus the Auto default") {
    List("ima-sc", "ima_sc", "imasc").foreach(v =>
      assertEquals(WaterProvider.fromEnv(Some(v)), WaterProvider.ImaSc, v)
    )
    List("inema-ba", "inema_ba", "inemaba").foreach(v =>
      assertEquals(WaterProvider.fromEnv(Some(v)), WaterProvider.InemaBa, v)
    )
    List("inea-rj", "inea_rj", "inearj").foreach(v =>
      assertEquals(WaterProvider.fromEnv(Some(v)), WaterProvider.IneaRj, v)
    )
    List("none", "off").foreach(v =>
      assertEquals(WaterProvider.fromEnv(Some(v)), WaterProvider.None, v)
    )
    assertEquals(
      WaterProvider.fromEnv(Some("  INEA-RJ  ")),
      WaterProvider.IneaRj,
      "trimmed and case-insensitive"
    )
    assertEquals(WaterProvider.fromEnv(None), WaterProvider.Auto)
    assertEquals(WaterProvider.fromEnv(Some("nonsense")), WaterProvider.Auto)
  }

  test("FacilitiesProvider.fromEnv: only 'off' disables it") {
    assertEquals(FacilitiesProvider.fromEnv(Some("off")), FacilitiesProvider.Off)
    assertEquals(FacilitiesProvider.fromEnv(Some(" OFF ")), FacilitiesProvider.Off)
    assertEquals(FacilitiesProvider.fromEnv(None), FacilitiesProvider.Overpass)
    assertEquals(FacilitiesProvider.fromEnv(Some("overpass")), FacilitiesProvider.Overpass)
  }

  // --- origin: both or neither, never half-applied
  // ----------------------------------------------.

  test("origin needs both lat and lon — one without the other is ignored, not half-applied") {
    assertEquals(
      base.copy(originLat = Some(-27.6), originLon = Some(-48.4)).origin,
      Some(Coordinates(-27.6, -48.4))
    )
    assertEquals(base.copy(originLat = Some(-27.6), originLon = scala.None).origin, scala.None)
    assertEquals(base.copy(originLat = scala.None, originLon = Some(-48.4)).origin, scala.None)
    assertEquals(base.copy(originLat = scala.None, originLon = scala.None).origin, scala.None)
  }

  // --- redacted: the whole point is that secrets never reach stdout (FABLE_REVIEW C1)
  // ------------.

  test("redacted never prints a secret's value, only whether it is set") {
    val withSecrets = base
      .copy(
        telegramBotToken = Some("telegram-secret-value"),
        azureMapsSubscriptionKey = Some("maps-secret-value"),
        cosmosDbKey = Some("cosmos-secret-value"),
        azureVisionKey = Some("vision-secret-value"),
        appInsightsConnectionString = Some("InstrumentationKey=appinsights-secret-value"),
        mlflowTrackingUri = Some("http://mlflow.example"),
        sightingStoreProvider = Provider.Azure,
        visionProvider = Provider.Azure
      )
      .redacted
    List(
      "telegram-secret-value",
      "maps-secret-value",
      "cosmos-secret-value",
      "vision-secret-value",
      "appinsights-secret-value"
    )
      .foreach(s => assert(!withSecrets.contains(s), s"redacted leaked $s: $withSecrets"))
    assert(withSecrets.contains("telegram=<set>"), withSecrets)
    assert(withSecrets.contains("maps=<set>"), withSecrets)
  }

  test("redacted says 'unset' for absent secrets and shows the local branches verbatim") {
    val r = base.redacted
    assert(r.contains("telegram=unset"), r)
    assert(r.contains("maps=unset"), r)
    assert(r.contains(base.localLlmModel), "the local model name is not a secret and should show")
    assert(r.contains("lore=on"), r)
    assert(r.contains("origin=auto"), "no origin configured reads as auto")
  }

  test("redacted shows the Azure branch of llm/sightings/vision when those providers are Azure") {
    val r = base
      .copy(
        llmProvider = Provider.Azure,
        foundryProjectEndpoint = Some("https://f.example/deployments/gpt4o"),
        sightingStoreProvider = Provider.Azure,
        visionProvider = Provider.Azure,
        seaLoreEnabled = false,
        originLat = Some(-27.6),
        originLon = Some(-48.4)
      )
      .redacted
    assert(r.contains("https://f.example/deployments/gpt4o"), r)
    assert(r.contains("marola/sightings"), r)
    assert(r.contains("lore=off"), r)
    assert(r.contains("origin=-27.6000,-48.4000"), r)
  }

  test("redacted names the Foundry endpoint's absence rather than crashing on it") {
    val r = base.copy(llmProvider = Provider.Azure, foundryProjectEndpoint = scala.None).redacted
    assert(r.contains("no endpoint"), r)
  }

  // --- waterQualityClient: every explicit provider, and Auto's geography
  // ------------------------.

  private val floripa = Coordinates(-27.6, -48.5) // Santa Catarina -> IMA/SC
  private val salvador = Coordinates(-12.97, -38.5) // Bahia -> INEMA/BA
  private val rio = Coordinates(-22.97, -43.18) // Rio de Janeiro -> INEA/RJ
  private val lisbon = Coordinates(38.72, -9.14) // nowhere marola has an agency for

  test("waterQualityClient honours an explicit provider regardless of where the origin is") {
    assertEquals(
      agency(base.copy(waterQualityProvider = WaterProvider.ImaSc).waterQualityClient(lisbon)),
      "ima-sc"
    )
    assertEquals(
      agency(base.copy(waterQualityProvider = WaterProvider.InemaBa).waterQualityClient(lisbon)),
      "inema-ba"
    )
    assertEquals(
      agency(base.copy(waterQualityProvider = WaterProvider.IneaRj).waterQualityClient(lisbon)),
      "inea-rj"
    )
    assertEquals(
      base.copy(waterQualityProvider = WaterProvider.None).waterQualityClient(floripa),
      scala.None
    )
  }

  test("waterQualityClient=Auto picks the agency whose region covers the origin, else None") {
    assertEquals(agency(base.waterQualityClient(floripa)), "ima-sc", "SC -> IMA/SC")
    assertEquals(agency(base.waterQualityClient(salvador)), "inema-ba", "BA -> INEMA/BA")
    assertEquals(agency(base.waterQualityClient(rio)), "inea-rj", "RJ -> INEA/RJ")
    assertEquals(
      base.waterQualityClient(lisbon),
      scala.None,
      "outside every covered region there is no data, not a guess"
    )
  }

  // --- the client selectors: Local always resolves, Azure only with its settings present
  // ---------.

  test("accessibilityClient is never None — Off resolves to the Noop client, not absence") {
    assert(isA[OverpassAccessibilityClient](base.accessibilityClient))
    assert(
      isA[NoopAccessibilityClient](
        base.copy(facilitiesProvider = FacilitiesProvider.Off).accessibilityClient
      )
    )
  }

  test("llmClient: Local always resolves; Azure needs an endpoint or it is None") {
    assert(base.llmClient.exists(isA[LocalLlmClient]))
    assert(
      base
        .copy(llmProvider = Provider.Azure, foundryProjectEndpoint = Some("https://f.example/x"))
        .llmClient
        .exists(isA[AzureFoundryLlmClient])
    )
    assertEquals(
      base.copy(llmProvider = Provider.Azure, foundryProjectEndpoint = scala.None).llmClient,
      scala.None
    )
  }

  test("llmModelName is the local model, or the Foundry deployment (the endpoint's last segment)") {
    assertEquals(base.llmModelName, base.localLlmModel)
    assertEquals(
      base
        .copy(
          llmProvider = Provider.Azure,
          foundryProjectEndpoint = Some("https://f.example/deployments/gpt4o")
        )
        .llmModelName,
      "gpt4o"
    )
    assertEquals(
      base
        .copy(
          llmProvider = Provider.Azure,
          foundryProjectEndpoint = Some("https://f.example/deployments/gpt4o/")
        )
        .llmModelName,
      "gpt4o",
      "a trailing slash is stripped"
    )
    assertEquals(
      base.copy(llmProvider = Provider.Azure, foundryProjectEndpoint = scala.None).llmModelName,
      "foundry"
    )
  }

  test("tracedLlmClient wraps whatever llmClient returns, and stays None when that is None") {
    assert(base.tracedLlmClient(Tracing.Noop).isDefined)
    assertEquals(
      base
        .copy(llmProvider = Provider.Azure, foundryProjectEndpoint = scala.None)
        .tracedLlmClient(Tracing.Noop),
      scala.None
    )
  }

  test("sightingStore: Local always resolves; Azure needs BOTH endpoint and key") {
    assert(base.sightingStore.exists(isA[LocalFileSightingStore]))
    val azure = base.copy(sightingStoreProvider = Provider.Azure)
    assert(
      azure
        .copy(cosmosDbEndpoint = Some("https://c.example"), cosmosDbKey = Some("k"))
        .sightingStore
        .exists(isA[CosmosDbSightingStore])
    )
    assertEquals(
      azure
        .copy(cosmosDbEndpoint = Some("https://c.example"), cosmosDbKey = scala.None)
        .sightingStore,
      scala.None
    )
    assertEquals(
      azure.copy(cosmosDbEndpoint = scala.None, cosmosDbKey = Some("k")).sightingStore,
      scala.None
    )
  }

  test("visionClient: Local always resolves; Azure needs BOTH endpoint and key") {
    assert(base.visionClient.exists(isA[LocalVisionClient]))
    val azure = base.copy(visionProvider = Provider.Azure)
    assert(
      azure
        .copy(azureVisionEndpoint = Some("https://v.example"), azureVisionKey = Some("k"))
        .visionClient
        .exists(isA[AzureVisionClient])
    )
    assertEquals(
      azure
        .copy(azureVisionEndpoint = Some("https://v.example"), azureVisionKey = scala.None)
        .visionClient,
      scala.None
    )
    assertEquals(
      azure.copy(azureVisionEndpoint = scala.None, azureVisionKey = Some("k")).visionClient,
      scala.None
    )
  }

  test("runLedger is Noop until a tracking URI is set — no server needed by default") {
    assertEquals(base.runLedger, RunLedger.Noop)
    assert(
      isA[MlflowRunLedger](base.copy(mlflowTrackingUri = Some("http://mlflow.example")).runLedger)
    )
  }

  test("distanceRefiner exists only with an Azure Maps key — otherwise haversine stands") {
    assertEquals(base.distanceRefiner, scala.None)
    assert(base.copy(azureMapsSubscriptionKey = Some("k")).distanceRefiner.isDefined)
  }

  test("knowledgeStore is constructed from config without touching the filesystem or Ollama") {
    // corpusDir/indexPath are private, so assert what is observable: building it is pure — no
    // directory has to exist and no embedder call is made until the store is actually used.
    val store = base
      .copy(knowledgeDir = "no/such/dir", knowledgeIndexPath = "no/such/index.json")
      .knowledgeStore
    assert(store ne null)
    assert(base.copy(localEmbedModel = "all-minilm").knowledgeStore ne null)
  }

  // --- tracing: an effect, so run it.

  test("tracing is Noop when off, and when a backend is chosen without the settings it needs") {
    assertEquals(run(base.copy(tracesBackend = TraceBackend.Off).tracing), Tracing.Noop)
    assertEquals(
      run(
        base
          .copy(tracesBackend = TraceBackend.Azure, appInsightsConnectionString = scala.None)
          .tracing
      ),
      Tracing.Noop,
      "Azure without a connection string has nothing to connect to — degrade, never throw"
    )
    assertEquals(
      run(base.copy(tracesBackend = TraceBackend.Mlflow, mlflowTrackingUri = scala.None).tracing),
      Tracing.Noop,
      "Mlflow without a tracking URI likewise"
    )
  }

  /** scalafix's DisableSyntax bans isInstanceOf, and a ClassTag test reads the same. */
  private def isA[T](value: Any)(using ct: scala.reflect.ClassTag[T]): Boolean =
    ct.runtimeClass.isInstance(value)

  /** Which agency a water-quality client speaks for — a name asserts better than a type. */
  // By the provider's own name, not its class: every client is now wrapped in
  // CachedWaterQualityClient, and the name is both what survives that wrapping and what the board
  // reports as `sources.water` — the behaviour, rather than which class implements it.
  private def agency(client: Option[marola.water.WaterQualityClient]): String = client match
    case Some(c) =>
      c.name match
        case "IMA/SC"   => "ima-sc"
        case "INEMA/BA" => "inema-ba"
        case "INEA/RJ"  => "inea-rj"
        case other      => other
    case None => "none"

  private given AllowUnsafe = AllowUnsafe.embrace.danger
  private def run[A](effect: A < Sync): A = Sync.Unsafe.evalOrThrow(effect)

  /**
   * Every field at its `fromEnv` default, so each test can `.copy` exactly the one thing it is
   * about.
   */
  private val base = AppConfig(
    telegramBotToken = scala.None,
    foundryProjectEndpoint = scala.None,
    foundryApiVersion = "2026-01-01-preview",
    beachSearchRadiusKm = 15.0,
    originLat = scala.None,
    originLon = scala.None,
    waterQualityProvider = WaterProvider.Auto,
    facilitiesProvider = FacilitiesProvider.Overpass,
    localEmbedModel = "nomic-embed-text",
    knowledgeDir = "knowledge",
    knowledgeIndexPath = "target/knowledge-index.json",
    seaLoreEnabled = true,
    askFallback = OceanQa.Fallback.General,
    askMinScore = 0.2,
    llmProvider = Provider.Local,
    localLlmBaseUrl = "http://localhost:11434/v1",
    localLlmModel = "llama3.2",
    azureMapsSubscriptionKey = scala.None,
    sightingStoreProvider = Provider.Local,
    localSightingStorePath = "target/sightings.json",
    cosmosDbEndpoint = scala.None,
    cosmosDbKey = scala.None,
    cosmosDbDatabase = "marola",
    cosmosDbContainer = "sightings",
    visionProvider = Provider.Local,
    localVisionModel = "llava",
    azureVisionEndpoint = scala.None,
    azureVisionKey = scala.None,
    appInsightsConnectionString = scala.None,
    mlflowTrackingUri = scala.None,
    mlflowExperiment = "marola",
    tracesBackend = TraceBackend.Off,
    traceContent = false
  )
