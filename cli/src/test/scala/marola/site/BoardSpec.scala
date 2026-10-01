package marola.site

import java.time.{LocalDate, OffsetDateTime, ZoneOffset}

import kyo.*

import marola.beaches.{AccessibilityClient, Facilities, Facility}
import marola.http.Http
import marola.json.JsonValue
import marola.lore.SeaLore
import marola.model.{Beach, BestHour, Coordinates}
import marola.scoring.{Note, NoteCode}
import marola.trails.Trail
import marola.water.ImaScWaterQualityClient
import marola.{Fixtures, Recommender, Report}

/**
 * MIP-0005 §7: the board is built from the same recorded transport as `PipelineGoldenSpec`, so
 * every number on the map is the number the CLI would print.
 */
class BoardSpec extends munit.FunSuite:

  private given unsafe: AllowUnsafe = AllowUnsafe.embrace.danger

  private val origin = Coordinates(-27.6733, -48.4700)
  private val today = LocalDate.of(2026, 9, 5)
  private val tomorrow = today.plusDays(1)
  private val generatedAt = OffsetDateTime.of(2026, 9, 5, 18, 0, 0, 0, ZoneOffset.ofHours(-3))
  private val sources = Board.Sources(
    beaches = "OpenStreetMap/Overpass",
    forecast = "Open-Meteo",
    water = Some("IMA/SC")
  )

  /** Every scored (beach, hour) for today and tomorrow, one forecast fetch per beach. */
  private lazy val scored: List[BestHour] =
    Http.withTransport(Fixtures.campeche()) {
      Sync.Unsafe.evalOrThrow(
        Recommender.scoreDays(
          origin,
          radiusKm = 15.0,
          waterQuality = Some(ImaScWaterQualityClient()),
          today = _ => today,
          days = 2
        )
      )
    }

  private def board(day: LocalDate): JsonValue =
    val lore = SeaLore.pick(SeaLore.loadDefault(), day, "floripa", SeaLore.regionTagsFor(origin))
    Board.build("floripa", day, today, generatedAt, scored, lore, sources)

  private def beaches(b: JsonValue): Vector[JsonValue] = b("beaches").arr

  private def beach(b: JsonValue, name: String): JsonValue =
    beaches(b).find(_("name").str.contains(name)).getOrElse(fail(s"no $name in board"))

  test("scoreDays fetches each beach once and scores both days") {
    val transport = Fixtures.campeche()
    val all = Http.withTransport(transport) {
      Sync.Unsafe.evalOrThrow(
        Recommender.scoreDays(
          origin,
          waterQuality = Some(ImaScWaterQualityClient()),
          today = _ => today
        )
      )
    }
    val beachCount = all.map(_.beach.name).distinct.size
    val byHost =
      transport.requests.groupBy(u => java.net.URI.create(u).getHost).view.mapValues(_.size).toMap
    assertEquals(byHost("api.open-meteo.com"), beachCount)
    assertEquals(byHost("marine-api.open-meteo.com"), beachCount)
    assertEquals(all.map(_.hour.time.toLocalDate).toSet, Set(today, tomorrow))
    // same ranking rule as the CLI: best first.
    assertEquals(all.map(_.score), all.map(_.score).sortBy(-_))
  }

  test("board: header fields, one entry per beach, ranked by best score") {
    val b = board(tomorrow)
    assertEquals(b("schema").num, Some(2.0))
    assertEquals(b("area").str, Some("floripa"))
    assertEquals(b("day").str, Some("2026-09-06"))
    assertEquals(b("today").str, Some("2026-09-05"))
    assertEquals(b("generated_at").str, Some("2026-09-05T18:00:00-03:00"))
    assertEquals(b("sources")("water").str, Some("IMA/SC"))
    assertEquals(
      beaches(b).flatMap(_("name").str).toSet,
      scored.map(_.beach.name).toSet
    )
    val bestScores = beaches(b).map(_("best")("score").num.getOrElse(-1.0))
    assertEquals(bestScores, bestScores.sortBy(-_))
  }

  test("board: every hour is a daylight hour of the board's day, best is the top of hours") {
    val b = board(tomorrow)
    beaches(b).foreach { beach =>
      val hours = beach("hours").arr
      assert(hours.nonEmpty, beach("name").str.toString)
      val hs = hours.flatMap(_("h").str)
      assertEquals(hs, hs.sorted, "hours must be chronological")
      hs.foreach(h => assert(h.matches("\\d\\d:00"), h))
      val name = beach("name").str.get
      val source =
        scored.filter(r => r.beach.name == name && r.hour.time.toLocalDate.isEqual(tomorrow))
      // daylight only — the fixture has is_day, and no dark hour appears on the board.
      assertEquals(hs.size, source.count(_.hour.isDaylight.contains(true)))
      val max = hours.flatMap(_("score").num).max
      assertEquals(beach("best")("score").num, Some(max))
      assert(hs.contains(beach("best")("hour").str.get))
    }
  }

  test("board: Campeche's water, tides and sea are Report's numbers, verbatim") {
    val b = board(tomorrow)
    val campeche = beach(b, "Praia do Campeche")
    val best = scored
      .filter(r => r.beach.name == "Praia do Campeche" && r.hour.time.toLocalDate.isEqual(tomorrow))
      .minBy(r => (-r.score, marola.scoring.Swimability.hourPreference(r.hour)))
    assertEquals(campeche("water")("summary").str, Some(Report.waterSummary(best)))
    assertEquals(campeche("water")("unfit").bool, Some(false))
    assertEquals(
      campeche("water")("points").arr.flatMap(_("point").str).toSet,
      Set("Ponto 35", "Ponto 73", "Ponto 75", "Ponto 89", "Ponto 90")
    )
    val p73 = campeche("water")("points").arr.find(_("point").str.contains("Ponto 73")).get
    assertEquals(p73("condition").str, Some("improper"))
    assertEquals(campeche("tides").arr.size, best.dayTides.size)
    assertEquals(
      campeche("tides").arr.headOption.flatMap(_("high").bool),
      best.dayTides.headOption.map(_.isHigh)
    )
    assertEquals(campeche("sea")("temp_c").num, best.hour.seaTempC)
    assertEquals(campeche("sea")("wave_m").num, best.hour.waveHeightM)
    assertEquals(campeche("best")("notes").arr.flatMap(_.str).toList, best.notes.map(_.english))
    assertEquals(campeche("best")("note_codes").arr.toList, best.notes.map(_.json))
    assertEquals(campeche("jellyfish").str, Some(best.jellyfishRisk.toString))
    assertEquals(campeche("whales")("now").str, Some(best.whaleSightingLikelihood.toString))
    // Rio Tavares has no IMA point: the water object still exists and says so.
    val rt = beach(b, "Praia do Rio Tavares")
    assertEquals(rt("water")("summary").str, Some("no data"))
    assertEquals(rt("water")("points").arr, Vector.empty)
  }

  test("board: today's board has today's hours and the lore paragraph is sourced") {
    val b = board(today)
    assertEquals(b("day").str, Some("2026-09-05"))
    beaches(b).foreach { beach =>
      val name = beach("name").str.get
      val source =
        scored.filter(r => r.beach.name == name && r.hour.time.toLocalDate.isEqual(today))
      assertEquals(beach("hours").arr.size, source.count(_.hour.isDaylight.contains(true)))
    }
    assert(b("lore")("text").str.exists(_.nonEmpty))
    assert(b("lore")("source").str.exists(_.startsWith("http")))
  }

  test("board: round-trips through JsonValue and conforms to board.schema.json") {
    val b = board(tomorrow)
    assertEquals(JsonValue.parse(b.render), b)
    val schema = BoardSpec.schema
    assertEquals(SchemaCheck.validate(schema, b), Nil)
    // the validator must actually bite: drop a required field and it reports it.
    val broken = b match
      case JsonValue.JObject(fields) => JsonValue.JObject(fields - "beaches")
      case other                     => other
    assert(SchemaCheck.validate(schema, broken).exists(_.contains("beaches")))
  }

  /**
   * The fixture shipped to ml (`build-resources-tarball.sh`, MIP-0070 §5.4): nothing else in the
   * app test suite reads it, so a schema drift here would only surface downstream.
   */
  test("board: the ml-facing fixture (site/board.json) conforms to board.schema.json") {
    val fixture = JsonValue.parse(BoardSpec.resource("site/board.json"))
    assertEquals(SchemaCheck.validate(BoardSpec.schema, fixture), Nil)
  }

  test("board: every hour carries wind_level, consistent with its wind_kmh (MIP-0009 task 1)") {
    val b = board(tomorrow)
    val hours = b("beaches").arr.flatMap(_("hours").arr)
    assert(hours.nonEmpty)
    hours.foreach { h =>
      val expected =
        marola.scoring.Swimability.windLevel(h("wind_kmh").num).map(_.toString.toLowerCase)
      assertEquals(h("wind_level").str, expected, h.render)
    }
    // the fixture must exercise at least one real band, or this test proves nothing.
    assert(hours.exists(_("wind_level").str.isDefined), "no hour had a wind_level")
  }

  test(
    "board: schema accepts a board with wind_level and one without it (optional)"
  ) {
    val schema = BoardSpec.schema
    val b = board(tomorrow)
    assertEquals(SchemaCheck.validate(schema, b), Nil)
    def strip(v: JsonValue): JsonValue = v match
      case JsonValue.JObject(fields) =>
        JsonValue.JObject((fields - "wind_level").map { case (k, x) => k -> strip(x) })
      case JsonValue.JArray(items) => JsonValue.JArray(items.map(strip))
      case other                   => other
    val old = strip(b)
    assert(old.render != b.render, "strip must have removed something")
    assertEquals(SchemaCheck.validate(schema, old), Nil)
    // and a wrong band is rejected — the enum bites.
    val bad =
      b.render.replaceFirst(
        "\"wind_level\":\\s*\"(calm|breezy|strong)\"",
        "\"wind_level\":\"gale\""
      )
    assert(SchemaCheck.validate(schema, JsonValue.parse(bad)).exists(_.contains("not in enum")))
  }

  /** Every best/hour stop of `b`, after checking its `note_codes` word exactly to its `notes`. */
  private def assertCodesAreNotes(b: JsonValue): Vector[JsonValue] =
    val stops = beaches(b).flatMap(beach => beach("best") +: beach("hours").arr)
    stops.foreach { s =>
      val notes = s("note_codes").arr.toList.map { c =>
        val code = c("code").str.flatMap(NoteCode.fromLabel).getOrElse(fail(c.render))
        c("args") match
          case JsonValue.JObject(args) => Note(code, args)
          case other                   => fail(other.render)
      }
      assertEquals(notes.map(_.english), s("notes").arr.flatMap(_.str).toList, s.render)
    }
    stops

  test("board: every hour and best carry note_codes whose English is exactly notes (MIP-0054)") {
    val b = board(tomorrow)
    val stops = assertCodesAreNotes(b)
    // the fixture must exercise real codes, the water mix among them, or this proves nothing.
    val seen = stops.flatMap(_("note_codes").arr.flatMap(_("code").str)).toSet
    assert(seen.contains("water_mixed") && seen.size >= 3, seen.toString)
  }

  test("board: a schema-1 board (no note_codes) still validates, and a bad code is rejected") {
    val schema = BoardSpec.schema
    val v1 = JsonValue.parse(BoardSpec.resource("site/board.json"))
    assertEquals(v1("schema").num, Some(1.0))
    assertEquals(SchemaCheck.validate(schema, v1), Nil)
    val v2 = board(tomorrow)
    assertEquals(v2("schema").num, Some(2.0))
    assertEquals(SchemaCheck.validate(schema, v2), Nil)
    assert(assertCodesAreNotes(v2).exists(_("note_codes").arr.nonEmpty))
    assertEquals(
      schema("$defs")("note_codes")("items")("properties")("code")("enum").arr.flatMap(_.str).toSet,
      NoteCode.values.map(_.label).toSet
    )
    val bad = board(tomorrow).render.replaceFirst("\"code\":\"[a-z_]+\"", "\"code\":\"gale\"")
    assert(SchemaCheck.validate(schema, JsonValue.parse(bad)).exists(_.contains("not in enum")))
  }

  /** MIP-0021 §5/§7: `Facilities` per beach, and the board's `facilities` object built from it. */
  final class Fixed(byBeach: Map[String, Facilities]) extends AccessibilityClient:
    def near(beaches: List[Beach], radiusM: Int = 300): Map[String, Facilities] < Sync =
      beaches.map(b => b.name -> byBeach.getOrElse(b.name, Facilities.NoData)).toMap

  private lazy val scoredWithFacilities: List[BestHour] =
    Http.withTransport(Fixtures.campeche()) {
      Sync.Unsafe.evalOrThrow(
        Recommender.scoreDays(
          origin,
          radiusKm = 15.0,
          waterQuality = Some(ImaScWaterQualityClient()),
          today = _ => today,
          days = 2,
          accessibility = Some(
            Fixed(
              Map(
                "Praia do Campeche" -> Facilities(
                  Map(Facility.Parking -> 3, Facility.Lifeguard -> 1)
                )
              )
            )
          )
        )
      )
    }

  test(
    "board: facilities omits absent facilities and OSM-absent beaches read '{}', not a zeroed count"
  ) {
    val b = Board.build(
      "floripa",
      tomorrow,
      today,
      generatedAt,
      scoredWithFacilities,
      SeaLore.pick(SeaLore.loadDefault(), tomorrow, "floripa", SeaLore.regionTagsFor(origin)),
      sources
    )
    val campecheFacilities = beach(b, "Praia do Campeche")("facilities")
    assertEquals(campecheFacilities("parking").num, Some(3.0))
    assertEquals(campecheFacilities("lifeguard").num, Some(1.0))
    assertEquals(campecheFacilities("toilets"), JsonValue.JNull) // absent key = no data, never 0
    assertEquals(campecheFacilities("shower"), JsonValue.JNull)
    val rioTavares = beach(b, "Praia do Rio Tavares")("facilities")
    assertEquals(rioTavares, JsonValue.obj())
    assertEquals(
      SchemaCheck.validate(BoardSpec.schema, b),
      Nil
    )
  }

  test("board: schema accepts a board with and without the optional facilities field") {
    val schema = BoardSpec.schema
    val b = Board.build(
      "floripa",
      tomorrow,
      today,
      generatedAt,
      scoredWithFacilities,
      SeaLore.pick(SeaLore.loadDefault(), tomorrow, "floripa", SeaLore.regionTagsFor(origin)),
      sources
    )
    assertEquals(SchemaCheck.validate(schema, b), Nil)
    def strip(v: JsonValue): JsonValue = v match
      case JsonValue.JObject(fields) =>
        JsonValue.JObject((fields - "facilities").map { case (k, x) => k -> strip(x) })
      case JsonValue.JArray(items) => JsonValue.JArray(items.map(strip))
      case other                   => other
    val old = strip(b)
    assert(old.render != b.render, "strip must have removed something")
    assertEquals(SchemaCheck.validate(schema, old), Nil)
  }

  test("board: trails is empty by default, and schema still accepts an empty array (MIP-0030)") {
    val b = board(tomorrow)
    assertEquals(b("trails").arr, Vector.empty)
    val schema = BoardSpec.schema
    assertEquals(SchemaCheck.validate(schema, b), Nil)
  }

  test(
    "board: a trail serializes verbatim OSM facts, geometry, and near-anchor distances (MIP-0030)"
  ) {
    val trail = Trail(
      name = "Trilha da Lagoinha do Leste",
      lengthKm = 2.1149,
      difficulty = None,
      surface = Some("paving_stones"),
      geometry = List(Coordinates(-27.7910, -48.4890), Coordinates(-27.7925, -48.4871)),
      nearBeach = Some(("Praia do Campeche", 0.42)),
      nearLake = None
    )
    val b = Board.build(
      "floripa",
      tomorrow,
      today,
      generatedAt,
      scored,
      None,
      sources,
      trails = List(trail)
    )
    val schema = BoardSpec.schema
    assertEquals(SchemaCheck.validate(schema, b), Nil)
    val t = b("trails").arr.head
    assertEquals(t("name").str, Some("Trilha da Lagoinha do Leste"))
    assertEquals(t("length_km").num, Some(2.1149))
    assertEquals(t("difficulty"), JsonValue.JNull)
    assertEquals(t("surface").str, Some("paving_stones"))
    assertEquals(t("geometry").arr.size, 2)
    assertEquals(
      t("geometry").arr.head.arr.flatMap(_.num).toList,
      List(-27.7910, -48.4890)
    )
    assertEquals(t("near_beach")("name").str, Some("Praia do Campeche"))
    assertEquals(t("near_beach")("distance_km").num, Some(0.42))
    assertEquals(t("near_lake"), JsonValue.JNull)
  }

end BoardSpec

object BoardSpec:
  /** `board.schema.json`, shipped in the image (`cli/src/main/resources/`, MIP-0070 §5.4). */
  def schema: JsonValue =
    val stream = getClass.getClassLoader.getResourceAsStream("board.schema.json")
    if stream == null then throw new IllegalStateException("board.schema.json not on the classpath")
    try JsonValue.parse(scala.io.Source.fromInputStream(stream, "UTF-8").mkString)
    finally stream.close()

  /** A test resource, read as text (`cli/src/test/resources/<name>`). */
  def resource(name: String): String =
    val stream = getClass.getClassLoader.getResourceAsStream(name)
    if stream == null then throw new IllegalArgumentException(s"missing test resource $name")
    try scala.io.Source.fromInputStream(stream, "UTF-8").mkString
    finally stream.close()

/**
 * The subset of JSON Schema the board contract uses: `type` (single or list), `required`,
 * `properties`, `additionalProperties: false`, `items`, `enum`, `$ref` to `#/$defs/...`.
 */
object SchemaCheck:

  def validate(schema: JsonValue, value: JsonValue): List[String] =
    check(schema, schema, value, "$")

  private def typeName(v: JsonValue): String = v match
    case JsonValue.JObject(_) => "object"
    case JsonValue.JArray(_)  => "array"
    case JsonValue.JString(_) => "string"
    case JsonValue.JNumber(n) => if n == n.toLong then "integer" else "number"
    case JsonValue.JBool(_)   => "boolean"
    case JsonValue.JNull      => "null"

  private def typeOk(expected: String, actual: String): Boolean =
    expected == actual || (expected == "number" && actual == "integer")

  private def check(
      root: JsonValue,
      schema: JsonValue,
      value: JsonValue,
      path: String
  ): List[String] =
    schema("$ref").str match
      case Some(ref) if ref.startsWith("#/$defs/") =>
        check(root, root("$defs")(ref.stripPrefix("#/$defs/")), value, path)
      case Some(ref) => List(s"$path: unsupported $$ref $ref")
      case None =>
        val actual = typeName(value)
        val types = schema("type") match
          case JsonValue.JString(t) => List(t)
          case JsonValue.JArray(ts) => ts.flatMap(_.str).toList
          case _                    => Nil
        val typeErrors =
          if types.isEmpty || types.exists(typeOk(_, actual)) then Nil
          else List(s"$path: expected ${types.mkString("|")}, got $actual")
        val enumErrors = schema("enum") match
          case JsonValue.JArray(allowed) if !allowed.contains(value) =>
            List(s"$path: ${value.render} not in enum")
          case _ => Nil
        val objectErrors = (value, schema("properties")) match
          case (JsonValue.JObject(fields), props) if actual == "object" =>
            val required = schema("required").arr.flatMap(_.str)
            val missing = required.filterNot(fields.contains).map(k => s"$path: missing $k").toList
            val nested = fields.toList.flatMap {
              case (k, v) =>
                props(k) match
                  case JsonValue.JNull =>
                    if schema("additionalProperties").bool.contains(false) then
                      List(s"$path.$k: not allowed")
                    else Nil
                  case sub => check(root, sub, v, s"$path.$k")
            }
            missing ++ nested
          case _ => Nil
        val arrayErrors = value match
          case JsonValue.JArray(items) if schema("items") != JsonValue.JNull =>
            items.zipWithIndex.toList.flatMap {
              case (item, i) => check(root, schema("items"), item, s"$path[$i]")
            }
          case _ => Nil
        typeErrors ++ enumErrors ++ objectErrors ++ arrayErrors
