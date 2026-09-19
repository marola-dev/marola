package marola.agents

import java.util.Optional

import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

import kyo.*

import marola.json.JsonValue

import com.google.adk.tools.{BaseTool, ToolContext}
import com.google.genai.types.{FunctionDeclaration, Schema, Type}
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.schedulers.Schedulers

/** One declared argument of a [[KyoTool]]. Numbers and strings are all the swim tools need. */
final case class ToolParam(
    name: String,
    kind: ToolParam.Kind,
    description: String,
    required: Boolean = true
)

object ToolParam:
  enum Kind derives CanEqual:
    case Number, Text

/** What the model passed, read defensively: a bad or missing argument is `None`, never a crash. */
final class ToolArgs(raw: java.util.Map[String, Object]):
  def number(key: String): Option[Double] =
    Option(raw.get(key)).flatMap {
      case n: java.lang.Number => Some(n.doubleValue())
      case other               => other.toString.trim.toDoubleOption
    }

  def text(key: String): Option[String] =
    Option(raw.get(key)).map(_.toString.trim).filter(_.nonEmpty)

/**
 * An ADK tool whose body is a Kyo effect (MIP-0061 §5.2).
 *
 * adk-java's usual route is `FunctionTool.create(Class, "method")`, which finds a static Java
 * method by reflection and reads `@Schema` annotations off its parameters — awkward from a Scala
 * object and one more thing to register for a native image. `BaseTool` is an ordinary abstract
 * class, so this subclasses it instead: the declaration is built by hand from [[ToolParam]]s and
 * `runAsync` evaluates the effect on RxJava's IO scheduler. Confirmed against
 * `com/google/adk/tools/BaseTool.java` at v1.10.1.
 *
 * A tool never throws at the model: a failure comes back as `{"error": ...}`, which the agent's
 * instruction tells it to report rather than paper over.
 */
abstract class KyoTool(toolName: String, toolDescription: String)
    extends BaseTool(toolName, toolDescription):

  def params: List[ToolParam]

  def call(args: ToolArgs): JsonValue < Sync

  final override def declaration(): Optional[FunctionDeclaration] =
    val properties = params.map { p =>
      val kind = p.kind match
        case ToolParam.Kind.Number => Type.Known.NUMBER
        case ToolParam.Kind.Text   => Type.Known.STRING
      p.name -> Schema.builder().`type`(kind).description(p.description).build()
    }.toMap
    val schema = Schema
      .builder()
      .`type`(Type.Known.OBJECT)
      .properties(properties.asJava)
      .required(params.filter(_.required).map(_.name).asJava)
      .build()
    Optional.of(
      FunctionDeclaration
        .builder()
        .name(toolName)
        .description(toolDescription)
        .parameters(schema)
        .build()
    )

  final override def runAsync(
      args: java.util.Map[String, Object],
      toolContext: ToolContext
  ): Single[java.util.Map[String, Object]] =
    Single
      .fromCallable[java.util.Map[String, Object]](() => KyoTool.evaluate(call(ToolArgs(args))))
      .subscribeOn(Schedulers.io())

object KyoTool:

  // ADK calls a tool from a plain Java thread, so this is the one place the effect has to be run
  // to a value — the same integration boundary as `SwimConditionsMcpServer.runSync`.
  private given unsafe: AllowUnsafe = AllowUnsafe.embrace.danger

  /** Runs the effect to a plain Java map; any failure becomes an `error` entry. */
  def evaluate(effect: JsonValue < Sync): java.util.Map[String, Object] =
    try asResultMap(Sync.Unsafe.evalOrThrow(effect))
    catch case NonFatal(e) => errorMap(Option(e.getMessage).getOrElse(e.getClass.getSimpleName))

  def errorMap(message: String): java.util.Map[String, Object] =
    java.util.Map.of("error", message)

  /** ADK wants a map as a tool result; anything else is wrapped under `result`. */
  def asResultMap(json: JsonValue): java.util.Map[String, Object] = json match
    case JsonValue.JObject(fields) => javaMap(fields)
    case other =>
      val wrapped = new java.util.LinkedHashMap[String, Object]()
      val _ = wrapped.put("result", toJava(other))
      wrapped

  private def javaMap(fields: Map[String, JsonValue]): java.util.Map[String, Object] =
    val out = new java.util.LinkedHashMap[String, Object]()
    // `null` values are dropped: an absent key reads the same to a model and keeps the map
    // safe for ADK's immutable-copy paths, which reject nulls.
    fields.toList.sortBy(_._1).foreach { (k, v) =>
      if v != JsonValue.JNull then
        val _ = out.put(k, toJava(v))
    }
    out

  private def toJava(json: JsonValue): Object = json match
    case JsonValue.JObject(fields) => javaMap(fields)
    case JsonValue.JArray(items)   => items.filter(_ != JsonValue.JNull).map(toJava).asJava
    case JsonValue.JString(value)  => value
    case JsonValue.JNumber(value)  => java.lang.Double.valueOf(value)
    case JsonValue.JBool(value)    => java.lang.Boolean.valueOf(value)
    case JsonValue.JNull           => ""
