package marola.agents

import scala.jdk.CollectionConverters.*

import kyo.*

import marola.json.JsonValue

class KyoToolSpec extends munit.FunSuite:

  final private class Echo(body: ToolArgs => JsonValue < Sync) extends KyoTool("echo", "Echoes."):
    def params: List[ToolParam] = List(
      ToolParam("lat", ToolParam.Kind.Number, "Latitude."),
      ToolParam("note", ToolParam.Kind.Text, "A note.", required = false)
    )
    def call(args: ToolArgs): JsonValue < Sync = body(args)

  private def run(tool: KyoTool, args: Map[String, Object]): Map[String, Object] =
    tool.runAsync(args.asJava, null).blockingGet().asScala.toMap

  test("the declaration carries name, description, typed properties and the required list") {
    val declaration = Echo(_ => JsonValue.obj()).declaration().get()
    assertEquals(declaration.name().get(), "echo")
    assertEquals(declaration.description().get(), "Echoes.")
    val schema = declaration.parameters().get()
    assertEquals(schema.properties().get().keySet().asScala.toSet, Set("lat", "note"))
    assertEquals(schema.required().get().asScala.toList, List("lat"))
    assertEquals(schema.properties().get().get("lat").`type`().get().toString, "NUMBER")
  }

  test("an object result becomes a java map, nested values included") {
    val tool = Echo(args =>
      JsonValue.obj(
        "lat" -> JsonValue.num(args.number("lat").getOrElse(0.0)),
        "tags" -> JsonValue.arr(JsonValue.str("a"), JsonValue.JNull, JsonValue.bool(true)),
        "absent" -> JsonValue.JNull
      )
    )
    val out = run(tool, Map("lat" -> java.lang.Double.valueOf(-27.5)))
    assertEquals(out("lat"), java.lang.Double.valueOf(-27.5))
    assertEquals(out("tags").toString, "[a, true]")
    assert(!out.contains("absent"), "null fields are dropped, not passed to ADK as nulls")
  }

  test("a number that arrives as text is still a number; rubbish is None") {
    val seen = Echo(args =>
      JsonValue.obj(
        "lat" -> JsonValue.num(args.number("lat").getOrElse(-1.0)),
        "bad" -> JsonValue.bool(args.number("note").isEmpty)
      )
    )
    val out = run(seen, Map("lat" -> "12.5", "note" -> "north"))
    assertEquals(out("lat"), java.lang.Double.valueOf(12.5))
    assertEquals(out("bad"), java.lang.Boolean.TRUE)
  }

  test("a failing effect comes back as an error entry, not an exception") {
    val tool = Echo(_ => Sync.defer(throw new RuntimeException("overpass timed out")))
    assertEquals(run(tool, Map.empty), Map[String, Object]("error" -> "overpass timed out"))
  }

  test("a non-object result is wrapped under `result`") {
    val out = run(Echo(_ => JsonValue.arr(JsonValue.num(1.0))), Map.empty)
    assertEquals(out.keySet, Set("result"))
  }
