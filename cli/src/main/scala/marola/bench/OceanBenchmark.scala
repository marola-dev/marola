package marola.bench

import java.nio.file.{Files, Paths}
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

import kyo.*

import marola.json.JsonValue
import marola.knowledge.{KnowledgeStore, OceanQa}
import marola.llm.LlmClient

/**
 * Is marola's answer better than just asking the model? Three arms, same local model, same
 * questions (`benchmark_questions.json`: ocean science, history, animals, nature, safety — some
 * inside the `knowledge/` corpus, most outside it on purpose):
 *
 *   - `baseline` — the plain prompt (`OceanQa.generalMessages`), no retrieval.
 *   - `rag-strict` — marola's grounded answer; abstains when nothing relevant is retrieved.
 *   - `rag-general` — grounded when the corpus covers it, labelled general knowledge otherwise.
 *
 * Scoring is deterministic and cheap — no LLM-as-judge (that's `FUTURE-WORK.md` §4.1):
 *   - **coverage**: share of the question's expected keywords present in the answer (each keyword
 *     may list alternatives with `|`). A proxy for "said the right thing".
 *   - **cited**: the answer carries a `[n]` citation — only RAG can do this; it's the user-facing
 *     difference between "trust me" and "here's where it's from".
 *   - **abstained**: the answer is the no-notes reply — honest on off-corpus questions, useless for
 *     the user who asked.
 *   - **latency**.
 * The report ends with a verdict computed from the numbers, including what would beat the baseline
 * where it loses. Run with `just benchmark`; the report is saved under `data/`.
 */
object OceanBenchmark:

  final case class Question(
      id: String,
      topic: String,
      question: String,
      keywords: List[String],
      inCorpus: Boolean
  )
  final case class Result(
      arm: String,
      q: Question,
      answer: String,
      coverage: Double,
      cited: Boolean,
      abstained: Boolean,
      ms: Long
  )
  final case class ArmSummary(
      arm: String,
      coverageInCorpus: Double,
      coverageGeneral: Double,
      coverageAll: Double,
      citedPct: Double,
      abstainedPct: Double,
      meanMs: Long
  )
  final case class Report(
      model: String,
      results: List[Result],
      summaries: List[ArmSummary],
      markdown: String
  )

  val Arms: List[String] = List("baseline", "rag-strict", "rag-general")

  def load(): List[Question] =
    val stream = getClass.getClassLoader.getResourceAsStream("benchmark_questions.json")
    if stream == null then Nil
    else
      val text =
        try scala.io.Source.fromInputStream(stream, "UTF-8").mkString
        finally stream.close()
      JsonValue.parse(text).arr.toList.flatMap { q =>
        for
          id <- q("id").str
          topic <- q("topic").str
          question <- q("question").str
        yield Question(
          id,
          topic,
          question,
          q("keywords").arr.flatMap(_.str).toList,
          q("in_corpus").bool.getOrElse(false)
        )
      }

  /** Pure: fraction of keyword groups (`a|b` = any of) found, case-insensitive, accents ignored. */
  def coverage(answer: String, keywords: List[String]): Double =
    if keywords.isEmpty then 0.0
    else
      val hay = fold(answer)
      keywords
        .count(k => k.split('|').exists(alt => hay.contains(fold(alt))))
        .toDouble / keywords.size

  private def fold(s: String): String =
    java.text.Normalizer
      .normalize(s, java.text.Normalizer.Form.NFD)
      .replaceAll("\\p{M}", "")
      .toLowerCase

  def isCited(answer: String): Boolean = "\\[\\d+\\]".r.findFirstIn(answer).isDefined
  def isAbstained(answer: String): Boolean =
    answer.startsWith(OceanQa.NoPassagesReply.take(30)) || OceanQa.saysNoAnswer(answer)

  def run(store: KnowledgeStore, llm: LlmClient, minScore: Double): Report < Sync =
    val questions = load()
    for results <- runAll(questions, store, llm, minScore)
    yield
      val summaries = Arms.map(arm => summarise(arm, results.filter(_.arm == arm)))
      Report("local", results, summaries, render(results, summaries))

  private def runAll(
      qs: List[Question],
      store: KnowledgeStore,
      llm: LlmClient,
      minScore: Double
  ): List[Result] < Sync =
    qs match
      case Nil => Nil
      case q :: rest =>
        for
          b <- timed("baseline", q, llm.complete(OceanQa.generalMessages(q.question)))
          s <- timed(
            "rag-strict",
            q,
            OceanQa
              .answer(
                q.question,
                store,
                llm,
                fallback = OceanQa.Fallback.Strict,
                minScore = minScore
              )
              .map(_.text)
          )
          g <- timed(
            "rag-general",
            q,
            OceanQa
              .answer(
                q.question,
                store,
                llm,
                fallback = OceanQa.Fallback.General,
                minScore = minScore
              )
              .map(_.text)
          )
          tail <- runAll(rest, store, llm, minScore)
        yield b :: s :: g :: tail

  private def timed(arm: String, q: Question, effect: String < Sync): Result < Sync =
    for
      start <- Sync.defer(java.lang.System.nanoTime())
      answer <- effect
      end <- Sync.defer(java.lang.System.nanoTime())
    yield Result(
      arm,
      q,
      answer,
      coverage(answer, q.keywords),
      isCited(answer),
      isAbstained(answer),
      (end - start) / 1000000L
    )

  private def mean(xs: List[Double]): Double = if xs.isEmpty then 0.0 else xs.sum / xs.size

  def summarise(arm: String, rs: List[Result]): ArmSummary =
    ArmSummary(
      arm,
      mean(rs.filter(_.q.inCorpus).map(_.coverage)),
      mean(rs.filterNot(_.q.inCorpus).map(_.coverage)),
      mean(rs.map(_.coverage)),
      100.0 * mean(rs.map(r => if r.cited then 1.0 else 0.0)),
      100.0 * mean(rs.map(r => if r.abstained then 1.0 else 0.0)),
      if rs.isEmpty then 0L else rs.map(_.ms).sum / rs.size
    )

  /** Deterministic verdict + "how to beat it" from the numbers — no model in the loop. */
  def verdict(summaries: List[ArmSummary], results: List[Result]): String =
    def s(arm: String) = summaries.find(_.arm == arm).getOrElse(ArmSummary(arm, 0, 0, 0, 0, 0, 0))
    val (base, strict, general) = (s("baseline"), s("rag-strict"), s("rag-general"))
    val lines = scala.collection.mutable.ListBuffer[String]()
    lines += (if strict.coverageInCorpus >= base.coverageInCorpus
              then
                f"- On questions the corpus covers, marola (strict) matches or beats the plain prompt on coverage (${strict.coverageInCorpus}%.2f vs ${base.coverageInCorpus}%.2f) *and* cites its source on ${strict.citedPct}%.0f%% of answers — the baseline cites on ${base.citedPct}%.0f%%. That citation is the user-visible win: a swimmer can check it."
              else
                f"- On questions the corpus covers, the plain prompt still out-covers marola (strict) (${base.coverageInCorpus}%.2f vs ${strict.coverageInCorpus}%.2f). The corpus text is missing the expected facts, or retrieval ranks the wrong chunk — see the per-question table, fix the document, re-run `just knowledge-index`."
    )
    lines += (if base.coverageGeneral > strict.coverageGeneral
              then
                f"- Off-corpus (history/science/animals), the plain prompt wins (${base.coverageGeneral}%.2f vs strict ${strict.coverageGeneral}%.2f): strict marola abstains on ${strict.abstainedPct}%.0f%% of all questions. That is honest but a poor experience."
              else
                f"- Off-corpus, marola (strict) is not behind the plain prompt (${strict.coverageGeneral}%.2f vs ${base.coverageGeneral}%.2f)."
    )
    lines += (if general.coverageAll >= base.coverageAll
              then
                f"- `rag-general` (the default `--ask` mode) closes the gap: overall coverage ${general.coverageAll}%.2f vs baseline ${base.coverageAll}%.2f, with citations where the corpus applies and a visible 'unsourced' label elsewhere."
              else
                f"- `rag-general` is still below the baseline overall (${general.coverageAll}%.2f vs ${base.coverageAll}%.2f): the relevance threshold is probably routing corpus questions to general knowledge, or vice-versa — tune `MAROLA_ASK_MIN_SCORE` (see the top-score column) and re-run."
    )
    val missedTopics = results
      .filter(r => r.arm == "rag-strict" && !r.q.inCorpus && r.abstained)
      .map(_.q.topic)
      .distinct
    if missedTopics.nonEmpty then
      lines += s"- To beat the baseline *with* sources, add corpus documents for: ${missedTopics.mkString(", ")} (one `knowledge/*.md` per topic, `Source:` line required) — each turns an unsourced answer into a cited one."
    lines += "- Better retrieval for free: `MAROLA_LOCAL_EMBED_MODEL=nomic-embed-text` (or `all-minilm` for speed) usually separates relevant from irrelevant chunks more sharply than `llama3.2`'s own embeddings; re-run this benchmark after switching."
    lines += "- User experience beyond coverage: keep answers to two-to-four sentences, always show the source line, and prefer the labelled general-knowledge answer over a refusal — nobody asks a second question after 'I don't have notes on that'."
    lines.mkString("\n")

  def render(results: List[Result], summaries: List[ArmSummary]): String =
    val sb = new StringBuilder
    sb ++= s"# marola ocean-answer benchmark — ${LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)}\n\n"
    sb ++= "| arm | coverage (in-corpus) | coverage (general) | coverage (all) | cited | abstained | mean ms |\n|---|---|---|---|---|---|---|\n"
    summaries.foreach { a =>
      sb ++= f"| ${a.arm} | ${a.coverageInCorpus}%.2f | ${a.coverageGeneral}%.2f | ${a.coverageAll}%.2f | ${a.citedPct}%.0f%% | ${a.abstainedPct}%.0f%% | ${a.meanMs} |\n"
    }
    sb ++= "\n## Verdict\n\n" + verdict(summaries, results) + "\n\n## Per question\n\n"
    sb ++= "| id | topic | in corpus | arm | coverage | cited | abstained | ms | answer (first 140 chars) |\n|---|---|---|---|---|---|---|---|---|\n"
    results.foreach { r =>
      val a = r.answer.replace("\n", " ").replace("|", "/").take(140)
      sb ++= f"| ${r.q.id} | ${r.q.topic} | ${if r.q.inCorpus then "yes" else "no"} | ${r.arm} | ${r.coverage}%.2f | ${if r.cited then "yes" else ""} | ${if r.abstained then "yes" else ""} | ${r.ms} | $a |\n"
    }
    sb.toString

  def save(report: Report): String =
    val dir = Paths.get("./data")
    Files.createDirectories(dir)
    val path = dir.resolve(
      s"benchmark-${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm"))}.md"
    )
    Files.writeString(path, report.markdown)
    path.toString
