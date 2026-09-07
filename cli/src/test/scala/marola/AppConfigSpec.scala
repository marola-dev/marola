package marola

/**
 * `TraceBackend.fromEnv` (MIP-0010 tracing-lane task 5): pure, no network, no `sys.env` read —
 * every `MAROLA_TRACES` value plus the backward-compat default derived from whether
 * `APPLICATIONINSIGHTS_CONNECTION_STRING` is set. The first `AppConfig`-adjacent test file in this
 * module (`cli/src/test`), so it also gives future config-parsing tests a home.
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
