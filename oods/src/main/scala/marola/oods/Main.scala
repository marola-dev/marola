package marola.oods

import java.nio.file.{Path, Paths}
import java.time.LocalDate

import kyo.*

/**
 * `just oods-ingest --source ima-sc [--state SC] [--city "Florianópolis,Itajaí"] [--mode
 * incremental|backfill] [--from 2003] [--to 2026] [--concurrency 1] [--dry-run] [--data-dir PATH]`.
 * `--city` and `--state` repeat or take a comma-separated list; `--from`/`--to` only bound a
 * backfill (MIP-0056 §5.2).
 */
object Main extends KyoApp:

  private val KnownSources = List("ima-sc")
  private val FirstYear = 2003
  private val DefaultDataDir = Paths.get("data", "oods")

  final private case class Options(source: String, plan: Plan, dataDir: Path)

  final private case class Draft(
      source: Option[String] = None,
      states: Set[String] = Set.empty,
      cities: Set[String] = Set.empty,
      mode: Mode = Mode.Incremental,
      fromYear: Option[Int] = None,
      toYear: Option[Int] = None,
      concurrency: Int = 1,
      dryRun: Boolean = false,
      dataDir: Path = DefaultDataDir
  )

  private def flags(rest: List[String], draft: Draft): Either[IngestError, Draft] = rest match
    case Nil                    => Right(draft)
    case "--dry-run" :: tail    => flags(tail, draft.copy(dryRun = true))
    case "--source" :: v :: t   => flags(t, draft.copy(source = Some(v)))
    case "--state" :: v :: t    => flags(t, draft.copy(states = draft.states ++ split(v)))
    case "--city" :: v :: t     => flags(t, draft.copy(cities = draft.cities ++ split(v)))
    case "--data-dir" :: v :: t => flags(t, draft.copy(dataDir = Paths.get(v)))
    case "--mode" :: v :: t =>
      Mode.fromLabel(v) match
        case Some(mode) => flags(t, draft.copy(mode = mode))
        case None => Left(IngestError.BadFlag(s"--mode expects incremental or backfill, got '$v'"))
    case "--from" :: v :: t =>
      v.toIntOption match
        case Some(year) => flags(t, draft.copy(fromYear = Some(year)))
        case None       => Left(IngestError.BadFlag(s"--from expects a year, got '$v'"))
    case "--to" :: v :: t =>
      v.toIntOption match
        case Some(year) => flags(t, draft.copy(toYear = Some(year)))
        case None       => Left(IngestError.BadFlag(s"--to expects a year, got '$v'"))
    case "--concurrency" :: v :: t =>
      v.toIntOption.filter(n => n >= 1 && n <= Ingest.MaxConcurrency) match
        case Some(n) => flags(t, draft.copy(concurrency = n))
        case None =>
          Left(IngestError.BadFlag(s"--concurrency expects 1..${Ingest.MaxConcurrency}, got '$v'"))
    case flag :: Nil if flag.startsWith("--") =>
      Left(IngestError.BadFlag(s"$flag needs a value"))
    case flag :: _ => Left(IngestError.BadFlag(s"unknown argument '$flag'"))

  private def split(value: String): Set[String] =
    value.split(",").map(_.trim).filter(_.nonEmpty).toSet

  private def options(args: List[String], today: LocalDate): Either[IngestError, Options] =
    flags(args, Draft()).flatMap { draft =>
      draft.source match
        case None =>
          Left(IngestError.BadFlag(s"--source is required — known: ${KnownSources.mkString(", ")}"))
        case Some(id) if !KnownSources.contains(id) =>
          Left(IngestError.UnknownSource(id, KnownSources))
        case Some(id) =>
          Right(
            Options(
              id,
              Plan(
                sources = Set(id),
                states = draft.states,
                cities = draft.cities,
                mode = draft.mode,
                fromYear = draft.fromYear.getOrElse(FirstYear),
                toYear = draft.toYear.getOrElse(today.getYear),
                dryRun = draft.dryRun,
                concurrency = draft.concurrency
              ),
              draft.dataDir
            )
          )
    }

  private def report(outcome: Outcome): String =
    val counts =
      s"planned ${outcome.planned.size}, skipped ${outcome.skipped}, " +
        s"skipped_upstream ${outcome.skippedUpstream.size}, fetched ${outcome.fetched}, " +
        s"written ${outcome.written}, unchanged ${outcome.unchanged}, failed ${outcome.failed.size}"
    val failures =
      outcome.failed.map(f => s"  failed ${f.partition.key} ${f.partition.year}: ${f.reason}")
    val aborted = outcome.aborted.map(e => s"  aborted: ${e.message}").toList
    (counts :: upstreamBroken(outcome) ++ failures ++ aborted).mkString("\n")

  /** Not a failure: the source's own `known_broken` list, named so a run says what it left out. */
  private def upstreamBroken(outcome: Outcome): List[String] =
    Option
      .when(outcome.skippedUpstream.nonEmpty)(
        s"  skipped_upstream: ${outcome.skippedUpstream.map(Ingest.partitionId).mkString(", ")}"
      )
      .toList

  private def plannedLines(outcome: Outcome): String =
    val lines = outcome.planned.map(p => s"${p.sourceId} ${p.channel.label} ${p.key} ${p.year}")
    ((lines :+ s"${lines.size} partitions planned (dry run: nothing fetched, nothing written)") ++
      upstreamBroken(outcome)).mkString("\n")

  private def ingest(options: Options, today: LocalDate): Unit < Async =
    val adapter = ImaScAdapter()
    for
      result <- Ingest.run(adapter, options.plan, options.dataDir, today)
      _ <- result match
        case Left(error)                     => Console.printLine(s"oods: ${error.message}")
        case Right(o) if options.plan.dryRun => Console.printLine(plannedLines(o))
        case Right(o)                        => Console.printLine(report(o))
      _ <- stop(Ingest.exitCode(result))
    yield ()

  private def stop(code: Int): Unit < Async =
    if code == 0 then ()
    else
      Sync.defer {
        import AllowUnsafe.embrace.danger
        exit(code)
      }

  private def bootstrap(args: List[String]): Unit < Async =
    val today = LocalDate.now()
    options(args, today) match
      case Left(error) =>
        for
          _ <- Console.printLine(s"oods: ${error.message}")
          _ <- stop(Ingest.exitCode(Left(error)))
        yield ()
      case Right(chosen) => ingest(chosen, today)

  run(bootstrap(args.toList))
