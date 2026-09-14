package marola.oods

import java.nio.file.Path
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
    failed: List[PartitionFailure],
    aborted: Option[IngestError]
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

  def plan(
      plan: Plan,
      today: LocalDate,
      manifest: Manifest,
      candidates: List[Partition],
      rawPath: Partition => String
  ): List[Partition] =
    val mutable = mutableYears(today)
    candidates.flatMap { candidate =>
      // The year window can only add immutability: an adapter that declared a partition immutable
      // knows the document itself cannot change, which the calendar does not.
      val p = candidate.copy(immutable = candidate.immutable || !mutable.contains(candidate.year))
      val unseen = !manifest.raw.contains(rawPath(p))
      val inMode = plan.mode match
        // A fetch-once partition belongs in an incremental run too — it is this week's bulletin,
        // not a closed year of a beach's export.
        case Mode.Incremental => !p.immutable || candidate.immutable
        case Mode.Backfill    => p.year >= plan.fromYear && p.year <= plan.toYear
      Option.when(inMode && (!p.immutable || unseen))(p)
    }

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
      selected = Ingest.plan(plan, today, manifest, candidates, adapter.rawPath)
      skipped = candidates.size - selected.size
      result <- decide(plan, selected, skipped, candidates)(
        IngestRun.all(adapter, selected, skipped, dataDir, manifestFile, manifest, now, sleep)
      )
    yield result

  private def decide(
      plan: Plan,
      selected: List[Partition],
      skipped: Int,
      candidates: List[Partition]
  )(ingest: => Outcome < Sync): Either[IngestError, Outcome] < Sync =
    unknownCity(plan, candidates) match
      case Some(error)         => Left(error)
      case None if plan.dryRun => Right(Outcome(selected, 0, 0, 0, skipped, Nil, None))
      case None                => ingest.map(Right(_))
