package marola.oods

import java.nio.file.Path
import java.time.Instant

import kyo.*

import marola.http.Http
import marola.oods.Ingest.{Attempts, BackoffMs, PauseMs}

/** The effectful half of `Ingest`: one partition at a time, with MIP-0056 §5.2's politeness. */
private object IngestRun:

  /** What one partition did to the run so far; the manifest is only touched by a real write. */
  final private case class Progress(
      manifest: Manifest,
      fetched: Int = 0,
      written: Int = 0,
      unchanged: Int = 0,
      failed: List[PartitionFailure] = Nil,
      aborted: Option[IngestError] = None
  )

  def all(
      adapter: SourceAdapter,
      selected: List[Partition],
      skipped: Int,
      dataDir: Path,
      manifestFile: Path,
      manifest: Manifest,
      now: () => Instant,
      sleep: Long => Unit
  ): Outcome < Sync =
    for
      // The registry first: `rows` cannot join a sample to a point without it (`ImaScAdapter.rows`).
      registry <- adapter.points
      start <- Sync.defer(writePoints(adapter.source, dataDir, registry, manifest, now()))
      end <- each(adapter, selected, dataDir, start, now, sleep)
      _ <- Sync.defer(Manifest.write(manifestFile, end.manifest))
    yield Outcome(
      selected,
      end.fetched,
      end.written,
      end.unchanged,
      skipped,
      end.failed.reverse,
      end.aborted
    )

  /** Sequential, one partition at a time: `concurrency` is validated but not yet spent (§5.2). */
  private def each(
      adapter: SourceAdapter,
      rest: List[Partition],
      dataDir: Path,
      progress: Progress,
      now: () => Instant,
      sleep: Long => Unit
  ): Progress < Sync =
    rest match
      case head :: tail if progress.aborted.isEmpty =>
        for
          _ <- Sync.defer(sleep(PauseMs))
          attempted <- attempt(adapter, head, number = 1, sleep)
          next <- Sync.defer(record(adapter, head, attempted, dataDir, progress, now()))
          done <- each(adapter, tail, dataDir, next, now, sleep)
        yield done
      case _ => progress

  private def attempt(
      adapter: SourceAdapter,
      p: Partition,
      number: Int,
      sleep: Long => Unit
  ): Either[Throwable, RawFile] < Sync =
    Abort.run(Abort.catching[Throwable](adapter.fetch(p))).map {
      case Result.Success(raw) => Right(raw): Either[Throwable, RawFile]
      case Result.Failure(t)   => retry(adapter, p, number, t, sleep)
      case Result.Panic(t)     => retry(adapter, p, number, t, sleep)
    }

  private def retry(
      adapter: SourceAdapter,
      p: Partition,
      number: Int,
      failure: Throwable,
      sleep: Long => Unit
  ): Either[Throwable, RawFile] < Sync =
    if number < Attempts && retryable(failure) then
      Sync.defer(sleep(BackoffMs << (number - 1))).map(_ => attempt(adapter, p, number + 1, sleep))
    else Left(failure): Either[Throwable, RawFile]

  /** 429/403 is the portal asking us to stop — retrying it is what gets a scraper banned. */
  private def abortOf(t: Throwable): Option[IngestError] = t match
    case Http.HttpError(status, url, _) if status == 429 || status == 403 =>
      Some(IngestError.AbortedByStatus(status, url))
    case _ => None

  private def retryable(t: Throwable): Boolean = t match
    case Http.HttpError(status, _, _)          => status >= 500
    case _: java.net.http.HttpTimeoutException => true
    case _: java.net.ConnectException          => true
    case _                                     => false

  private def record(
      adapter: SourceAdapter,
      p: Partition,
      attempted: Either[Throwable, RawFile],
      dataDir: Path,
      progress: Progress,
      at: Instant
  ): Progress =
    attempted match
      case Left(t) =>
        abortOf(t) match
          case Some(stop) => progress.copy(aborted = Some(stop))
          case None => progress.copy(failed = PartitionFailure(p, reason(t)) :: progress.failed)
      case Right(raw) =>
        val fetched = progress.copy(fetched = progress.fetched + 1)
        adapter.rows(raw) match
          case Left(e)     => fetched.copy(failed = PartitionFailure(p, e.detail) :: fetched.failed)
          case Right(rows) => store(p, raw, rows.size, dataDir, fetched, at)

  private def store(
      p: Partition,
      raw: RawFile,
      rows: Int,
      dataDir: Path,
      progress: Progress,
      at: Instant
  ): Progress =
    // `rawPath` is §5.1's layout, not IMA's own — it moves onto `SourceAdapter` with adapter two.
    val path = ImaScAdapter.rawPath(p)
    if !RawStore.write(dataDir.resolve(path), raw.bytes) then
      progress.copy(unchanged = progress.unchanged + 1)
    else
      val entry = RawEntry(raw.url, RawStore.sha256(raw.bytes), raw.bytes.length.toLong, at, rows)
      progress.copy(
        written = progress.written + 1,
        manifest = progress.manifest.copy(raw = progress.manifest.raw.updated(path, entry))
      )

  private def writePoints(
      source: Source,
      dataDir: Path,
      points: List[PointRow],
      manifest: Manifest,
      at: Instant
  ): Progress =
    val path = s"raw/${source.id}/points.json"
    val bytes = RawStore.renderPoints(points)
    if !RawStore.write(dataDir.resolve(path), bytes) then Progress(manifest)
    else
      val entry =
        RawEntry(
          source.urls("points"),
          RawStore.sha256(bytes),
          bytes.length.toLong,
          at,
          points.size
        )
      Progress(manifest.copy(raw = manifest.raw.updated(path, entry)))

  private def reason(t: Throwable): String = t match
    case Http.HttpError(status, url, _)        => s"HTTP $status from $url"
    case _: java.net.http.HttpTimeoutException => "timed out"
    case _: java.net.ConnectException          => "connection refused"
    case other => s"${other.getClass.getSimpleName}: ${Option(other.getMessage).getOrElse("")}"
