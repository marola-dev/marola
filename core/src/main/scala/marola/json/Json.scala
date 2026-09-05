package marola.json

/**
 * Minimal hand-rolled JSON reader and writer. marola has no JSON library dependency by choice, not
 * because one doesn't exist — `kyo-schema` (already resolved transitively via `kyo-http`) is a
 * real, substantial codec library confirmed present at the exact Kyo version this repo pins (see
 * `Http.scala`'s doc comment and `docs/FUTURE-WORK.md`) — every shape this module needs to
 * read/write (Open-Meteo, Overpass, Ollama, Azure Maps/Vision, Telegram's Bot API) is plain nested
 * object/array/string/number, so a small recursive-descent parser plus a matching renderer covers
 * it without pulling in a derivation-macro-based library for a handful of ad hoc shapes. Migrating
 * to `kyo.Schema` typed derivation is tracked future work, not ruled out.
 */
enum JsonValue derives CanEqual:
  case JObject(fields: Map[String, JsonValue])
  case JArray(items: Vector[JsonValue])
  case JString(value: String)
  case JNumber(value: Double)
  case JBool(value: Boolean)
  case JNull

  /** Field access on an object; `JNull` for anything else (missing field, or not an object). */
  def apply(key: String): JsonValue = this match
    case JsonValue.JObject(fields) => fields.getOrElse(key, JsonValue.JNull)
    case _                         => JsonValue.JNull

  def arr: Vector[JsonValue] = this match
    case JsonValue.JArray(items) => items
    case _                       => Vector.empty

  def str: Option[String] = this match
    case JsonValue.JString(v) => Some(v)
    case _                    => None

  def num: Option[Double] = this match
    case JsonValue.JNumber(v) => Some(v)
    case _                    => None

  def bool: Option[Boolean] = this match
    case JsonValue.JBool(v) => Some(v)
    case _                  => None

  /**
   * Serializes back to compact JSON text — the write side of this module, added for the `llm`/
   * Cosmos DB/Vision clients that need to build request bodies, not just parse responses.
   */
  def render: String = this match
    case JsonValue.JObject(fields) =>
      fields
        .map { case (k, v) => s"${JsonValue.renderString(k)}:${v.render}" }
        .mkString("{", ",", "}")
    case JsonValue.JArray(items) => items.map(_.render).mkString("[", ",", "]")
    case JsonValue.JString(s)    => JsonValue.renderString(s)
    case JsonValue.JNumber(
          n
        ) => // NaN/Infinity aren't JSON; render as null rather than emit an invalid document
      if n.isNaN || n.isInfinite then "null"
      else if n == n.toLong then n.toLong.toString
      else n.toString
    case JsonValue.JBool(b) => b.toString
    case JsonValue.JNull    => "null"

object JsonValue:

  /**
   * Builders for the write side — `JsonValue.obj("role" -> JsonValue.str("user"), ...)` reads
   * better at call sites than nested `JObject(Map(...))` literals.
   */
  def obj(fields: (String, JsonValue)*): JsonValue = JObject(fields.toMap)
  def arr(items: JsonValue*): JsonValue = JArray(items.toVector)
  def str(value: String): JsonValue = JString(value)
  def num(value: Double): JsonValue = JNumber(value)
  def bool(value: Boolean): JsonValue = JBool(value)

  private def renderString(s: String): String =
    val sb = new StringBuilder(s.length + 2)
    sb.append('"')
    s.foreach {
      case '"'          => sb.append("\\\"")
      case '\\'         => sb.append("\\\\")
      case '\n'         => sb.append("\\n")
      case '\r'         => sb.append("\\r")
      case '\t'         => sb.append("\\t")
      case c if c < ' ' => sb.append(f"\\u${c.toInt}%04x")
      case c            => sb.append(c)
    }
    sb.append('"')
    sb.toString

  final case class JsonParseException(message: String) extends Exception(message)

  /**
   * Parses `input` into a `JsonValue`, throwing `JsonParseException` on malformed input. Thrown
   * rather than returned as `Either` because every call site here treats a malformed response from
   * a third-party API as an unrecoverable-for-this-request fault, not a case to branch on.
   */
  def parse(input: String): JsonValue =
    val parser = new Parser(input)
    val result = parser.parseValue()
    parser.skipWhitespace()
    if !parser.atEnd then throw JsonParseException(s"trailing content at offset ${parser.pos}")
    result

  final private class Parser(s: String):
    private var i = 0

    def pos: Int = i
    def atEnd: Boolean = i >= s.length

    def skipWhitespace(): Unit =
      while i < s.length && s.charAt(i).isWhitespace do i += 1

    private def fail(msg: String): Nothing =
      throw JsonParseException(s"$msg at offset $i")

    private def expect(c: Char): Unit =
      if atEnd || s.charAt(i) != c then fail(s"expected '$c'")
      i += 1

    private def expectLiteral(lit: String): Unit =
      if i + lit.length > s.length || s.substring(i, i + lit.length) != lit then
        fail(s"expected '$lit'")
      i += lit.length

    def parseValue(): JsonValue =
      skipWhitespace()
      if atEnd then fail("unexpected end of input")
      s.charAt(i) match
        case '{'                        => parseObject()
        case '['                        => parseArray()
        case '"'                        => JsonValue.JString(parseString())
        case 't'                        => expectLiteral("true"); JsonValue.JBool(true)
        case 'f'                        => expectLiteral("false"); JsonValue.JBool(false)
        case 'n'                        => expectLiteral("null"); JsonValue.JNull
        case c if c == '-' || c.isDigit => JsonValue.JNumber(parseNumber())
        case c                          => fail(s"unexpected character '$c'")

    private def parseObject(): JsonValue =
      expect('{')
      skipWhitespace()
      var fields = Map.empty[String, JsonValue]
      if !atEnd && s.charAt(i) == '}' then i += 1
      else
        var continue = true
        while continue do
          skipWhitespace()
          val key = parseString()
          skipWhitespace()
          expect(':')
          val value = parseValue()
          fields = fields.updated(key, value)
          skipWhitespace()
          if !atEnd && s.charAt(i) == ',' then i += 1
          else
            expect('}')
            continue = false
      JsonValue.JObject(fields)

    private def parseArray(): JsonValue =
      expect('[')
      skipWhitespace()
      var items = Vector.empty[JsonValue]
      if !atEnd && s.charAt(i) == ']' then i += 1
      else
        var continue = true
        while continue do
          items = items :+ parseValue()
          skipWhitespace()
          if !atEnd && s.charAt(i) == ',' then i += 1
          else
            expect(']')
            continue = false
      JsonValue.JArray(items)

    private def parseString(): String =
      skipWhitespace()
      expect('"')
      val sb = new StringBuilder
      var continue = true
      while continue do
        if atEnd then fail("unterminated string")
        val c = s.charAt(i)
        i += 1
        if c == '"' then continue = false
        else if c == '\\' then
          if atEnd then fail("unterminated escape")
          val esc = s.charAt(i)
          i += 1
          esc match
            case '"'  => sb.append('"')
            case '\\' => sb.append('\\')
            case '/'  => sb.append('/')
            case 'b'  => sb.append('\b')
            case 'f'  => sb.append('\f')
            case 'n'  => sb.append('\n')
            case 'r'  => sb.append('\r')
            case 't'  => sb.append('\t')
            case 'u' =>
              if i + 4 > s.length then fail("truncated unicode escape")
              val hex = s.substring(i, i + 4)
              i += 4
              sb.append(Integer.parseInt(hex, 16).toChar)
            case other => fail(s"invalid escape '\\$other'")
        else sb.append(c)
      sb.toString

    private def parseNumber(): Double =
      val start = i
      if !atEnd && s.charAt(i) == '-' then i += 1
      while !atEnd && s.charAt(i).isDigit do i += 1
      if !atEnd && s.charAt(i) == '.' then
        i += 1
        while !atEnd && s.charAt(i).isDigit do i += 1
      if !atEnd && (s.charAt(i) == 'e' || s.charAt(i) == 'E') then
        i += 1
        if !atEnd && (s.charAt(i) == '+' || s.charAt(i) == '-') then i += 1
        while !atEnd && s.charAt(i).isDigit do i += 1
      val token = s.substring(start, i)
      token.toDoubleOption.getOrElse(fail(s"invalid number literal '$token'"))
