package marola.site

import java.nio.file.{Files, Path, Paths}
import java.time.{LocalDate, OffsetDateTime, ZoneOffset}

import kyo.*

import marola.http.Http
import marola.json.JsonValue
import marola.lore.SeaLore
import marola.model.{BestHour, Coordinates}
import marola.water.ImaScWaterQualityClient
import marola.{Fixtures, Recommender, Report}

/**
 * MIP-0005 §7: the board is built from the same recorded transport as `PipelineGoldenSpec`, so
 * every number on the map is the number the CLI would print. The schema (`site/board.schema.json`)
 * is checked here with a small structural validator (type/required/properties/items/enum/$ref) — no
 * JSON-schema library, same dependency stance as `marola.json`.
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
    // same ranking rule as the CLI: best first
    assertEquals(all.map(_.score), all.map(_.score).sortBy(-_))
  }

  test("board: header fields, one entry per beach, ranked by best score") {
    val b = board(tomorrow)
    assertEquals(b("schema").num, Some(1.0))
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
      // daylight only — the fixture has is_day, and no dark hour appears on the board
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
    assertEquals(campeche("best")("notes").arr.flatMap(_.str).toList, best.notes)
    assertEquals(campeche("jellyfish").str, Some(best.jellyfishRisk.toString))
    assertEquals(campeche("whales")("now").str, Some(best.whaleSightingLikelihood.toString))
    // Rio Tavares has no IMA point: the water object still exists and says so
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

  test("board: round-trips through JsonValue and conforms to site/board.schema.json") {
    val b = board(tomorrow)
    assertEquals(JsonValue.parse(b.render), b)
    val schema = JsonValue.parse(Files.readString(BoardSpec.schemaPath))
    assertEquals(SchemaCheck.validate(schema, b), Nil)
    // the validator must actually bite: drop a required field and it reports it
    val broken = b match
      case JsonValue.JObject(fields) => JsonValue.JObject(fields - "beaches")
      case other                     => other
    assert(SchemaCheck.validate(schema, broken).exists(_.contains("beaches")))
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
    // the fixture must exercise at least one real band, or this test proves nothing
    assert(hours.exists(_("wind_level").str.isDefined), "no hour had a wind_level")
  }

  test(
    "board: schema accepts a board with wind_level and one without it (optional, schema stays 1)"
  ) {
    val schema = JsonValue.parse(Files.readString(BoardSpec.schemaPath))
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
    // and a wrong band is rejected — the enum bites
    val bad =
      b.render.replaceFirst(
        "\"wind_level\":\\s*\"(calm|breezy|strong)\"",
        "\"wind_level\":\"gale\""
      )
    assert(SchemaCheck.validate(schema, JsonValue.parse(bad)).exists(_.contains("not in enum")))
  }

end BoardSpec

object BoardSpec:
  /** `site/board.schema.json` at the repo root, wherever sbt was launched from. */
  def schemaPath: Path =
    Iterator
      .iterate(Paths.get("").toAbsolutePath)(_.getParent)
      .takeWhile(_ != null)
      .map(_.resolve("site/board.schema.json"))
      .find(Files.exists(_))
      .getOrElse(throw new IllegalStateException("site/board.schema.json not found above the cwd"))

/**
 * The subset of JSON Schema the board contract uses: `type` (single or list), `required`,
 * `properties`, `additionalProperties: false`, `items`, `enum`, `$ref` to `#/$defs/...`. Returns
 * every violation with its JSON path.
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
