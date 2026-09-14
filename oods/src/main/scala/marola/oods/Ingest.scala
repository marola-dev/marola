package marola.oods

import java.nio.file.{Files, Path}
import java.time.{Instant, LocalDate}

import kyo.*

/** Expected failures of a run — bad input, or a portal telling us to stop (MIP-0056 §5.2). */
enum IngestError derives CanEqual:
  case BadFlag(detail: String)
  case UnknownSource(requested: String, known: List[String])
  case UnknownCity(cities: Set[String])
  case AbortedByStatus(status: Int, url: String)

object IngestError:
  extension (e: IngestError)
    def message: String = e match
      case BadFlag(detail) => detail
      case UnknownSource(requested, known) =>
        s"unknown source '$requested' — known: ${known.mkString(", ")}"
      case UnknownCity(cities) =>
        s"no adapter covers ${cities.toList.sorted.mkString(", ")}"
      case AbortedByStatus(status, url) =>
        s"HTTP $status from $url — aborting the run instead of retrying"

final case class PartitionFailure(partition: Partition, reason: String) derives CanEqual

/** `written`/`unchanged` count raw partition files; `points.json` is accounted for separately. */
final case class Outcome(
    planned: List[Partition],
    fetched: Int,
    written: Int,
    unchanged: Int,
    skipped: Int,
    skippedUpstream: List[Partition],
    failed: List[PartitionFailure],
    aborted: Option[IngestError]
) derives CanEqual

/**
 * What a run will fetch and what it will not: `skipped` is the planner's own rules (mode,
 * manifest), `upstreamBroken` the source's `known_broken` list — never a failure, so it leaves the
 * exit code alone (MIP-0056 §5.2).
 */
final case class Selection(
    fetch: List[Partition],
    upstreamBroken: List[Partition],
    skipped: Int
) derives CanEqual

object Ingest:

  val UserAgent = "marola-oods (+https://github.com/h0ffmann/marola)"
  val PauseMs: Long = 250
  val BackoffMs: Long = 500
  val Attempts = 3
  val MaxConcurrency = 4

  /**
   * The refetch window: this year, plus last year while `today − 45 days` still falls in it — IMA
   * posts late-December samples in January (MIP-0056 §5.2).
   */
  def mutableYears(today: LocalDate): Set[Int] = Set(today.getYear, today.minusDays(45).getYear)

  /** `known_broken`'s id for a partition: `sources.json` names `<key>/<year>`. */
  def partitionId(p: Partition): String = s"${p.key}/${p.year}"

  /**
   * `exists` is the raw file on disk. A manifest entry whose file is gone is not a skip: the raw
   * layer lives in a Hugging Face dataset (MIP-0056 §4.4), so a runner that could not pull it must
   * refetch rather than build a store whose manifest names files nobody has.
   */
  def plan(
      plan: Plan,
      today: LocalDate,
      manifest: Manifest,
      candidates: List[Partition],
      broken: Set[String],
      exists: String => Boolean = _ => true
  ): Selection =
    val mutable = mutableYears(today)
    val wanted = candidates
      .map(p => p.copy(immutable = !mutable.contains(p.year)))
      .filter { p =>
        val inMode = plan.mode match
          case Mode.Incremental => !p.immutable
          case Mode.Backfill    => p.year >= plan.fromYear && p.year <= plan.toYear
        val path = ImaScAdapter.rawPath(p)
        inMode && (!p.immutable || !(manifest.raw.contains(path) && exists(path)))
      }
    val (upstreamBroken, fetch) = wanted.partition(p => broken.contains(partitionId(p)))
    Selection(fetch, upstreamBroken, candidates.size - wanted.size)

  /** An empty enumeration under a `--city` filter means the name matched nothing, not "done". */
  def unknownCity(plan: Plan, candidates: List[Partition]): Option[IngestError] =
    Option.when(plan.cities.nonEmpty && candidates.isEmpty)(IngestError.UnknownCity(plan.cities))

  def exitCode(result: Either[IngestError, Outcome]): Int = result match
    case Left(_)                                              => 2
    case Right(o) if o.aborted.isDefined || o.failed.nonEmpty => 1
    case Right(_)                                             => 0

  def run(
      adapter: SourceAdapter,
      plan: Plan,
      dataDir: Path,
      today: LocalDate = LocalDate.now(),
      now: () => Instant = () => Instant.now(),
      sleep: Long => Unit = ms => Thread.sleep(ms)
  ): Either[IngestError, Outcome] < Sync =
    val manifestFile = dataDir.resolve(s"manifest/${adapter.source.id}.json")
    for
      candidates <- adapter.partitions(plan)
      manifest <- Sync.defer(Manifest.read(manifestFile))
      selected = Ingest.plan(
        plan,
        today,
        manifest,
        candidates,
        adapter.source.knownBroken.map(_.partition).toSet,
        path => Files.isRegularFile(dataDir.resolve(path))
      )
      result <- decide(plan, selected, candidates)(
        IngestRun.all(adapter, selected, dataDir, manifestFile, manifest, now, sleep)
      )
    yield result

  private def decide(
      plan: Plan,
      selected: Selection,
      candidates: List[Partition]
  )(ingest: => Outcome < Sync): Either[IngestError, Outcome] < Sync =
    unknownCity(plan, candidates) match
      case Some(error) => Left(error)
      case None if plan.dryRun =>
        Right(
          Outcome(
            selected.fetch,
            0,
            0,
            0,
            selected.skipped,
            selected.upstreamBroken,
            Nil,
            None
          )
        )
      case None => ingest.map(Right(_))
