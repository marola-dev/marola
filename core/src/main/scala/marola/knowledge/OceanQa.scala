package marola.knowledge

import kyo.*
import marola.llm.{ChatMessage, LlmClient}

/**
 * "Ask the ocean": retrieve the top passages for a question, then have the local model answer
 * *only* from them, citing `[n]`. The prompt is hand-written rather than DSPy-compiled for now — a
 * compiled `AnswerFromPassages` signature is the obvious follow-up once there's an eval set
 * (`FUTURE-WORK.md` §4.1) — but the grounding rule is the same one the sea-lore feature applies:
 * nothing reaches the user that isn't in a sourced document. With no passages retrieved the model
 * isn't called at all.
 */
object OceanQa:

  final case class Answer(text: String, passages: List[Passage])

  val NoPassagesReply =
    "I don't have anything in my ocean notes about that yet — try asking about rip currents, " +
      "jellyfish stings, bathing-water quality, whales, tides or wave conditions."

  def answer(
      question: String,
      store: KnowledgeStore,
      llm: LlmClient,
      k: Int = 4
  ): Answer < Sync =
    for
      passages <- store.search(question, k)
      reply <- complete(llm, question, passages)
    yield Answer(reply, passages)

  private def complete(llm: LlmClient, question: String, passages: List[Passage]): String < Sync =
    if passages.isEmpty then NoPassagesReply
    else llm.complete(buildMessages(question, passages))

  def buildMessages(question: String, passages: List[Passage]): List[ChatMessage] =
    val numbered = passages.zipWithIndex
      .map { case (p, i) => s"[${i + 1}] (${p.docTitle} — ${p.source})\n${p.text}" }
      .mkString("\n\n")
    List(
      ChatMessage(
        "system",
        "You are marola, a swim-conditions assistant. Answer the question using ONLY the numbered " +
          "passages provided. Cite the passage number in square brackets after each fact, like [2]. " +
          "If the passages don't cover the question, say so plainly in one sentence and do not " +
          "guess. Keep it to at most four sentences. Never give medical or safety advice beyond " +
          "what the passages state."
      ),
      ChatMessage("user", s"Passages:\n\n$numbered\n\nQuestion: $question")
    )
