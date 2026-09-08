package marola.knowledge

import kyo.*

import marola.llm.{ChatMessage, LlmClient}

/**
 * "Ask the ocean": retrieve the top passages for a question, then have the local model answer
 * *only* from them, citing `[n]`.
 */
object OceanQa:

  /**
   * `safety` is true iff any retained passage came from `knowledge/safety/` (MIP-0022) — computed
   * once from retrieval, independent of which passage's text the model actually cited, so a
   * strict-mode abstention on a safety question still carries the footer.
   */
  final case class Answer(text: String, passages: List[Passage], safety: Boolean)

  val NoPassagesReply =
    "I don't have anything in my ocean notes about that yet — try asking about rip currents, " +
      "jellyfish stings, bathing-water quality, whales, tides or wave conditions."

  /** `Strict`: below `minScore` (or nothing retrieved) → abstain with `NoPassagesReply`. */
  enum Fallback derives CanEqual:
    case Strict, General

  val GeneralKnowledgeLabel =
    "(From the model's general knowledge — unsourced; verify before relying on it.) "

  /** Cosine score under which the best passage is treated as "not about this". */
  val DefaultMinScore = 0.0

  /**
   * What the grounded prompt asks the model to reply when the passages don't cover the question.
   */
  val NoAnswerSentinel = "NO_ANSWER_IN_PASSAGES"

  /** True for the sentinel and for the free-text ways small models say the same thing. */
  def saysNoAnswer(reply: String): Boolean =
    val r = reply.trim.toLowerCase
    r.startsWith(NoAnswerSentinel.toLowerCase) ||
    r.contains("none of the passages") || r.contains("passages do not") ||
    r.contains("passages don't") || r.contains("not covered by the passages") ||
    r.contains("no passage")

  def answer(
      question: String,
      store: KnowledgeStore,
      llm: LlmClient,
      k: Int = 4,
      fallback: Fallback = Fallback.Strict,
      minScore: Double = DefaultMinScore
  ): Answer < Sync =
    for
      passages <- store.search(question, k)
      relevant = passages.filter(_.score >= minScore)
      reply <- complete(llm, question, relevant, fallback)
    yield Answer(reply, relevant, relevant.exists(_.safety))

  private def complete(
      llm: LlmClient,
      question: String,
      passages: List[Passage],
      fallback: Fallback
  ): String < Sync =
    if passages.isEmpty then unanswered(llm, question, fallback)
    else
      // Two-stage: try grounded; if the model itself says the passages don't cover it, fall back.
      llm.complete(buildMessages(question, passages)).flatMap { grounded =>
        if saysNoAnswer(grounded) then unanswered(llm, question, fallback)
        else grounded: String < Sync
      }

  private def unanswered(llm: LlmClient, question: String, fallback: Fallback): String < Sync =
    fallback match
      case Fallback.Strict => NoPassagesReply
      case Fallback.General =>
        llm.complete(generalMessages(question)).map(GeneralKnowledgeLabel + _)

  /** The "manual prompt" baseline — also what `just benchmark` compares marola against. */
  def generalMessages(question: String): List[ChatMessage] =
    List(
      ChatMessage(
        "system",
        "You are a knowledgeable, careful assistant answering questions about the ocean — its " +
          "science, history, animals and nature. Answer in at most four plain sentences. If you " +
          "are not sure, say so."
      ),
      ChatMessage("user", question)
    )

  def buildMessages(question: String, passages: List[Passage]): List[ChatMessage] =
    val numbered = passages.zipWithIndex
      .map { case (p, i) => s"[${i + 1}] (${p.docTitle} — ${p.source})\n${p.text}" }
      .mkString("\n\n")
    List(
      ChatMessage(
        "system",
        "You are marola, a swim-conditions assistant. Answer the question using ONLY the numbered " +
          "passages provided. Cite the passage number in square brackets after each fact, like [2]. " +
          s"If the passages don't cover the question, reply with exactly $NoAnswerSentinel and " +
          "nothing else. Keep it to at most four sentences. Never give medical or safety advice beyond " +
          "what the passages state."
      ),
      ChatMessage("user", s"Passages:\n\n$numbered\n\nQuestion: $question")
    )
