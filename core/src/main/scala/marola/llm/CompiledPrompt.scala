package marola.llm

import scala.io.Source

import marola.json.JsonValue

/**
 * Loads a DSPy-compiled prompt artifact (`dspy/compile_recommendation_prompt.py` produces two:
 * `recommendation_prompt.json` for the summarizer, `review_prompt.json` for `Reviewer` — see
 * `ARCHITECTURE.md` §5a) and turns it into a plain chat message list any `LlmClient` can replay.
 *
 * The JSON schema below is not guessed — it's the real output of `dspy.Predict(...).save(path)`
 * against a real local `dspy==3.3.1` install (confirmed against both compiled artifacts, with
 * genuine LLM-bootstrapped demos — see `dspy/README.md`'s Status section):
 * {{{
 * {
 *   "demos": [ { "<input_field>": "<value>", ..., "<outputField>": "<output text>" }, ... ],
 *   "signature": {
 *     "instructions": "<system prompt text>",
 *     "fields": [ { "prefix": "Field Name:", "description": "..." }, ... ]
 *   }
 * }
 * }}}
 *
 * `outputField` names which key in each demo (and in the model's eventual reply) is the output —
 * `"summary"` for the summarizer, `"review_json"` for the reviewer. Every other key in a demo is
 * treated as an input field. This is a real constraint carried over from the Python side: each
 * compiled artifact corresponds to exactly one `dspy.Signature`, and that signature's output field
 * name is a fact about the artifact, not something this loader can infer — the caller (`Main`)
 * already knows which artifact it's loading and passes the matching name.
 *
 * The chat message list this builds is a good-faith replication of what DSPy's own `ChatAdapter`
 * would send (instructions as the system message, each demo as a user/assistant example pair, the
 * real input as a final user turn) — not a byte-identical replay of DSPy's internal adapter
 * formatting, which isn't accessible from Scala. Good enough to carry over the optimized
 * instructions and demos; a training/serving skew this small is an accepted, documented tradeoff of
 * compiling in Python and serving in Scala at all (see `ARCHITECTURE.md` §5's rationale for why
 * that split exists in the first place).
 */
final case class CompiledPrompt(
    instructions: String,
    demos: List[Map[String, String]],
    outputField: String
):

  /**
   * `inputs` in the same key names as the Python `Signature`'s `InputField`s (see
   * `dspy/compile_recommendation_prompt.py`'s `INPUT_FIELDS`/`REVIEW_INPUT_FIELDS`) — snake_case,
   * e.g. `beach_name`, `sea_temp_c`. Building this dict is the caller's job (`Recommender`/`Main`),
   * not this loader's, so `CompiledPrompt` stays ignorant of `BestHour`'s shape.
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
