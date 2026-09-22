package marola

import kyo.*

import marola.beaches.{NoopAccessibilityClient, OverpassAccessibilityClient}
import marola.knowledge.OceanQa
import marola.ledger.{MlflowRunLedger, RunLedger}
import marola.llm.LocalLlmClient
import marola.model.Coordinates
import marola.observability.Tracing
import marola.sightings.LocalFileSightingStore
import marola.vision.LocalVisionClient

/** `TraceBackend.fromEnv` (MIP-0010 tracing-lane task 5): pure, no network, no `sys.env` read. */
class AppConfigSpec extends munit.FunSuite:

  test("MAROLA_TRACES=mlflow (any case) is Mlflow") {
    assertEquals(TraceBackend.fromEnv(Some("mlflow")), TraceBackend.Mlflow)
    assertEquals(TraceBackend.fromEnv(Some("MlFlOw")), TraceBackend.Mlflow)
  }

  test("MAROLA_TRACES off, unset or unrecognized is Off") {
    assertEquals(TraceBackend.fromEnv(Some("off")), TraceBackend.Off)
    assertEquals(TraceBackend.fromEnv(Some("OFF")), TraceBackend.Off)
    assertEquals(TraceBackend.fromEnv(None), TraceBackend.Off)
    assertEquals(TraceBackend.fromEnv(Some("bogus")), TraceBackend.Off)
  }

  // MIP-0021 §5: MAROLA_FACILITIES=off|overpass, default overpass — unlike WaterProvider there is
  // no region-auto case, just an on/off switch.
  test("MAROLA_FACILITIES=off is Off; unset or anything else defaults to Overpass") {
    assertEquals(FacilitiesProvider.fromEnv(Some("off")), FacilitiesProvider.Off)
    assertEquals(FacilitiesProvider.fromEnv(Some("OFF")), FacilitiesProvider.Off)
    assertEquals(FacilitiesProvider.fromEnv(Some("overpass")), FacilitiesProvider.Overpass)
    assertEquals(FacilitiesProvider.fromEnv(Some("bogus")), FacilitiesProvider.Overpass)
    assertEquals(FacilitiesProvider.fromEnv(None), FacilitiesProvider.Overpass)
  }

  // --- the enum parsers: every branch, including the "don't fail to start on a typo" default
  // -----.

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
        mlflowTrackingUri = Some("http://mlflow.example")
      )
      .redacted
    List("telegram-secret-value", "http://mlflow.example")
      .foreach(s => assert(!withSecrets.contains(s), s"redacted leaked $s: $withSecrets"))
    assert(withSecrets.contains("telegram=<set>"), withSecrets)
    assert(withSecrets.contains("mlflow=<set>"), withSecrets)
  }

  test("redacted says 'unset' for absent secrets and shows the local branches verbatim") {
    val r = base.redacted
    assert(r.contains("telegram=unset"), r)
    assert(r.contains("mlflow=unset"), r)
    assert(r.contains(base.localLlmModel), "the local model name is not a secret and should show")
    assert(r.contains("lore=on"), r)
    assert(r.contains("origin=auto"), "no origin configured reads as auto")
  }

  test("redacted shows lore and a configured origin") {
    val r = base
      .copy(seaLoreEnabled = false, originLat = Some(-27.6), originLon = Some(-48.4))
      .redacted
    assert(r.contains(base.localSightingStorePath), r)
    assert(r.contains("lore=off"), r)
    assert(r.contains("origin=-27.6000,-48.4000"), r)
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

  // --- the client selectors
  // ---------.

  test("accessibilityClient is never None — Off resolves to the Noop client, not absence") {
    assert(isA[OverpassAccessibilityClient](base.accessibilityClient))
    assert(
      isA[NoopAccessibilityClient](
        base.copy(facilitiesProvider = FacilitiesProvider.Off).accessibilityClient
      )
    )
  }

  test("llmClient, sightingStore and visionClient resolve to the local implementations") {
    assert(isA[LocalLlmClient](base.llmClient))
    assert(isA[LocalFileSightingStore](base.sightingStore))
    assert(isA[LocalVisionClient](base.visionClient))
  }

  test("runLedger is Noop until a tracking URI is set — no server needed by default") {
    assertEquals(base.runLedger, RunLedger.Noop)
    assert(
      isA[MlflowRunLedger](base.copy(mlflowTrackingUri = Some("http://mlflow.example")).runLedger)
    )
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
      run(base.copy(tracesBackend = TraceBackend.Mlflow, mlflowTrackingUri = scala.None).tracing),
      Tracing.Noop,
      "Mlflow without a tracking URI has nothing to connect to — degrade, never throw"
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
    localLlmBaseUrl = "http://localhost:11434/v1",
    localLlmModel = "llama3.2",
    localSightingStorePath = "target/sightings.json",
    localVisionModel = "llava",
    mlflowTrackingUri = scala.None,
    mlflowExperiment = "marola",
    tracesBackend = TraceBackend.Off,
    traceContent = false
  )
