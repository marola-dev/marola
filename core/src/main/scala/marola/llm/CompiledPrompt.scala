package marola.llm

import scala.io.Source

import marola.json.JsonValue

/**
 * Loads a DSPy-compiled prompt artifact (`dspy/compile_recommendation_prompt.py` produces two:
 * `recommendation_prompt.json` for the summarizer, `review_prompt.json` for `Reviewer` — see
 * `ARCHITECTURE.md` §5a) and turns it into a plain chat message list any `LlmClient` can replay.
 */
final case class CompiledPrompt(
    instructions: String,
    demos: List[Map[String, String]],
    outputField: String
):

  /**
   * `inputs` in the same key names as the Python `Signature`'s `InputField`s (see
   * `dspy/compile_recommendation_prompt.py`'s `INPUT_FIELDS`/`REVIEW_INPUT_FIELDS`) — snake_case,
   * e.g. `beach_name`, `sea_temp_c`.
   */
  def buildMessages(inputs: Map[String, String]): List[ChatMessage] =
    val demoMessages = demos.flatMap { demo =>
      val (inputFields, outputFields) = demo.partition(_._1 != outputField)
      List(
        ChatMessage("user", CompiledPrompt.renderFields(inputFields)),
        ChatMessage("assistant", outputFields.getOrElse(outputField, ""))
      )
    }
    ChatMessage("system", instructions) +: demoMessages :+ ChatMessage(
      "user",
      CompiledPrompt.renderFields(inputs)
    )

object CompiledPrompt:

  private def renderFields(fields: Map[String, String]): String =
    fields.map { case (k, v) => s"${toFieldLabel(k)}: $v" }.mkString("\n")

  private def toFieldLabel(snakeCase: String): String =
    snakeCase.split('_').map(_.capitalize).mkString(" ")

  def loadFromFile(path: String, outputField: String): CompiledPrompt =
    val content = Source.fromFile(path).mkString
    loadFromString(content, outputField)

  def loadFromString(jsonText: String, outputField: String): CompiledPrompt =
    val json = JsonValue.parse(jsonText)
    val instructions = json("signature")("instructions").str.getOrElse("")
    val demos = json("demos").arr.map { demoJson =>
      demoJson match
        case JsonValue.JObject(fields) =>
          fields.collect { case (k, JsonValue.JString(v)) if k != "augmented" => k -> v }
        case _ => Map.empty[String, String]
    }.toList
    CompiledPrompt(instructions, demos, outputField)
