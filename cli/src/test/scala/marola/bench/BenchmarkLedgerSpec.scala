package marola.bench

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}

import scala.collection.mutable.ListBuffer

import kyo.*

import marola.ledger.RunLedger
import marola.ledger.RunLedger.RunHandle

/**
 * MIP-0010 task 4 (`BenchmarkLedgerSpec` in the task list): the params/metrics an
 * `OceanBenchmark.Report` turns into, asserted against a recording `RunLedger` double — no MLflow,
 * no network. The Markdown report stays the canonical gate input; this is additive.
 */
class BenchmarkLedgerSpec extends munit.FunSuite:

  private given AllowUnsafe = AllowUnsafe.embrace.danger

  private def run[A](effect: A < Sync): A = Sync.Unsafe.evalOrThrow(effect)

  /** Records every call, in order, as a tagged tuple. */
  class Recording extends RunLedger:
    val calls: ListBuffer[(String, Any)] = ListBuffer.empty
    val handle: RunHandle =
      RunHandle("7", "3f9c", Some("http://127.0.0.1:5000/#/experiments/7/runs/3f9c"))
    def start(experiment: String, name: String, params: Map[String, String]): RunHandle < Sync =
      Sync.defer { calls += (("start", (experiment, name, params))); handle }
    def metrics(run: RunHandle, values: Map[String, Double], step: Int): Unit < Sync =
      Sync.defer { calls += (("metrics", (run, values, step))); () }
    def artifact(run: RunHandle, path: Path): Unit < Sync =
      Sync.defer { calls += (("artifact", (run, path))); () }
    def end(run: RunHandle, ok: Boolean): Unit < Sync =
      Sync.defer { calls += (("end", (run, ok))); () }

  private val q1 = OceanBenchmark.Question("rip", "safety", "rip current?", List("parallel"), true)
  private val q2 = OceanBenchmark.Question("moby", "history", "Moby Dick?", List("whale"), false)

  private val report = OceanBenchmark.Report(
    model = "local",
    results = List(
      OceanBenchmark.Result("baseline", q1, "swim parallel", 1.0, false, false, 900),
      OceanBenchmark.Result("baseline", q2, "a whale", 1.0, false, false, 1100),
      OceanBenchmark.Result("rag-strict", q1, "parallel [1]", 1.0, true, false, 2000),
      OceanBenchmark.Result("rag-strict", q2, "I don't have notes", 0.0, false, true, 500),
      OceanBenchmark.Result("rag-general", q1, "parallel [1]", 1.0, true, false, 2100),
      OceanBenchmark.Result("rag-general", q2, "a whale (general)", 1.0, false, false, 1300)
    ),
    summaries = List(
      OceanBenchmark.ArmSummary("baseline", 1.0, 1.0, 1.0, 0.0, 0.0, 1000),
      OceanBenchmark.ArmSummary("rag-strict", 1.0, 0.0, 0.5, 50.0, 50.0, 1250),
      OceanBenchmark.ArmSummary("rag-general", 1.0, 1.0, 1.0, 50.0, 0.0, 1700)
    ),
    markdown = "# report"
  )

  private val context = BenchmarkLedger.Context(
    model = "llama3.2",
    embedModel = "nomic-embed-text",
    minScore = 0.25,
    corpusSha = "a1b2c3d4",
    gitSha = "abe1ba4"
  )

  test("params: model, embed_model, min_score, corpus_sha, git_sha, questions — exact values") {
    assertEquals(
      BenchmarkLedger.params(report, context),
      Map(
        "model" -> "llama3.2",
        "embed_model" -> "nomic-embed-text",
        "min_score" -> "0.25",
        "corpus_sha" -> "a1b2c3d4",
        "git_sha" -> "abe1ba4",
        "questions" -> "2"
      )
    )
  }

  test("metrics: six per arm, keyed <arm>.<metric>, straight from the ArmSummary") {
    val m = BenchmarkLedger.metrics(report)
    assertEquals(m.size, 18)
    assertEquals(m("baseline.coverage_in_corpus"), 1.0)
    assertEquals(m("rag-strict.coverage_general"), 0.0)
    assertEquals(m("rag-strict.coverage_all"), 0.5)
    assertEquals(m("rag-strict.cited_pct"), 50.0)
    assertEquals(m("rag-strict.abstained_pct"), 50.0)
    assertEquals(m("rag-general.mean_ms"), 1700.0)
  }

  test("log: start in <prefix>/benchmark, then metrics, the report artifact, end(ok = true)") {
    val ledger = Recording()
    val path = Path.of("data/benchmark-20260905-1550.md")
    val handle = run(BenchmarkLedger.log(ledger, "marola", report, context, path))
    assertEquals(handle, Some(ledger.handle))
    assertEquals(ledger.calls.map(_._1).toList, List("start", "metrics", "artifact", "end"))
    assertEquals(
      ledger.calls.head._2,
      ("marola/benchmark", "benchmark-20260905-1550", BenchmarkLedger.params(report, context))
    )
    assertEquals(ledger.calls(1)._2, (ledger.handle, BenchmarkLedger.metrics(report), 0))
    assertEquals(ledger.calls(2)._2, (ledger.handle, path))
    assertEquals(ledger.calls(3)._2, (ledger.handle, true))
  }

  test("log: the Noop ledger yields no url and the benchmark is unaffected") {
    val handle =
      run(BenchmarkLedger.log(RunLedger.Noop, "marola", report, context, Path.of("x.md")))
    assertEquals(handle.map(_.url), Some(None))
  }

  test("log: a ledger that fails on start is reported as None, never thrown") {
    val broken = new RunLedger:
      def start(experiment: String, name: String, params: Map[String, String]): RunHandle < Sync =
        Sync.defer(throw new java.io.IOException("connection refused"))
      def metrics(run: RunHandle, values: Map[String, Double], step: Int): Unit < Sync =
        Sync.defer(())
      def artifact(run: RunHandle, path: Path): Unit < Sync = Sync.defer(())
      def end(run: RunHandle, ok: Boolean): Unit < Sync = Sync.defer(())
    assertEquals(run(BenchmarkLedger.log(broken, "marola", report, context, Path.of("x.md"))), None)
  }

  test("log: a failure after start ends the run with ok = false and reports None") {
    val ledger = new Recording:
      override def artifact(run: RunHandle, path: Path): Unit < Sync =
        Sync.defer(throw new java.io.IOException("artifact proxy 500"))
    assertEquals(run(BenchmarkLedger.log(ledger, "marola", report, context, Path.of("x.md"))), None)
    assertEquals(ledger.calls.last, ("end", (ledger.handle, false)))
  }

  test(
    "corpusSha: deterministic over sorted knowledge/*.md, changes with content, ignores non-md"
  ) {
    val dir = Files.createTempDirectory("marola-corpus")
    Files.writeString(dir.resolve("b.md"), "bravo")
    Files.writeString(dir.resolve("a.md"), "alpha")
    Files.writeString(dir.resolve("README.txt"), "not part of the corpus")
    val first = BenchmarkLedger.corpusSha(dir)
    assertEquals(first.length, 12)
    assertEquals(BenchmarkLedger.corpusSha(dir), first)
    Files.writeString(dir.resolve("README.txt"), "still not")
    assertEquals(BenchmarkLedger.corpusSha(dir), first)
    Files.write(dir.resolve("a.md"), "alpha!".getBytes(UTF_8))
    assertNotEquals(BenchmarkLedger.corpusSha(dir), first)
  }

  test("corpusSha: a missing directory hashes to 'none' rather than throwing") {
    assertEquals(BenchmarkLedger.corpusSha(Path.of("/nonexistent/knowledge")), "none")
  }

  test("runName: the report file's basename without .md") {
    assertEquals(
      BenchmarkLedger.runName(Path.of("data/benchmark-20260905-1550.md")),
      "benchmark-20260905-1550"
    )
  }
