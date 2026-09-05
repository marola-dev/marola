package marola.llm

import kyo.*

import marola.json.JsonValue

/**
 * The reviewer/critic pass (`FUTURE-WORK.md` §4.2, now built rather than just proposed): replays a
 * second DSPy-compiled artifact (`review_prompt.json`, compiled from
 * `dspy/compile_recommendation_prompt.py`'s `ReviewSwimSummary` signature) against the summarizer's
 * own draft output, before either reaches a user. A model grading its own answer in the same call
 * can't catch its own mistakes as reliably as a fresh pass focused only on checking, not generating
 * — the same reasoning DSPy's own `Evaluate`/LLM-as-judge pattern uses.
 *
 * The review signature's sole output field is a single JSON string (`review_json` — see
 * `CompiledPrompt`'s doc comment on why: one JSON-string output field reuses the exact same
 * demo/replay mechanics as the summarizer's single-text-field output, no `CompiledPrompt` change
 * needed beyond parameterizing which field is the output). Confirmed against a real local Ollama
 * model while building this: it reliably returns well-formed JSON matching this schema, and in one
 * bootstrap run correctly caught a deliberately-planted flaw (a draft summary missing a required
 * jellyfish mention) and produced a corrected `final_summary` — see `dspy/review_prompt.json`, a
 * real compiled artifact, not a hand-written fixture.
 */
object Reviewer:

  final case class ReviewResult(finalSummary: String, score: Int, verdict: String)
  final case class MalformedReviewException(message: String) extends Exception(message)

  def review(
      client: LlmClient,
      prompt: CompiledPrompt,
      factInputs: Map[String, String],
      draftSummary: String
  ): ReviewResult < Sync =
    val inputs = factInputs + ("summary" -> draftSummary)
    client.complete(prompt.buildMessages(inputs)).map { rawReply =>
      val json = JsonValue.parse(extractJsonObject(rawReply))
      val score = json("score").num
        .getOrElse(throw MalformedReviewException(s"no numeric score in reviewer reply: $rawReply"))
        .toInt
      val verdict = json("verdict").str.getOrElse("approve")
      val finalSummary = json("final_summary").str.getOrElse(draftSummary)
      ReviewResult(finalSummary, score, verdict)
    }

  /**
   * Small/quantized local models sometimes wrap the requested JSON in a sentence or a code fence
   * despite being told not to (confirmed as a real, if infrequent, failure mode while testing
   * locally) — extract the first `{...}` block rather than requiring byte-perfect compliance from
   * every model this might run against.
   */
  private def extractJsonObject(text: String): String =
    val start = text.indexOf('{')
    val end = text.lastIndexOf('}')
    if start >= 0 && end > start then text.substring(start, end + 1) else text
