package marola.oods

import java.time.{Instant, LocalDate, LocalTime}

import kyo.*

import marola.water.BathingCondition

/**
 * The common model every OODS adapter produces (MIP-0056 §5.2). One institute is one
 * `SourceAdapter`; a state or a city is a filter on the `Plan`, never a new shape.
 *
 * Every enum here is persisted — in the manifest, in Parquet, or in a workflow input — so each
 * carries an explicit `label`/`fromLabel` pair and never `toString`/`ordinal`
 * (`.claude/rules/scala.md`).
 */
enum Channel derives CanEqual:
  case Csv, Pdf, Json

object Channel:
  extension (c: Channel)
    def label: String = c match
      case Csv  => "csv"
      case Pdf  => "pdf"
      case Json => "json"

  def fromLabel(s: String): Option[Channel] = s match
    case "csv"  => Some(Csv)
    case "pdf"  => Some(Pdf)
    case "json" => Some(Json)
    case _      => None

/**
 * Which bacterium the count is of. A column, not an assumption: IMA's CSV header says `E. coli`
 * while marola's `WaterSample` field is named `enterococciPer100ml` — the store must not inherit
 * that guess (MIP-0056 §8).
 */
enum Indicator derives CanEqual:
  case EColi, Enterococci, Unknown

object Indicator:
  extension (i: Indicator)
    def label: String = i match
      case EColi       => "e_coli"
      case Enterococci => "enterococci"
      case Unknown     => "unknown"

  def fromLabel(s: String): Option[Indicator] = s match
    case "e_coli"      => Some(EColi)
    case "enterococci" => Some(Enterococci)
    case "unknown"     => Some(Unknown)
    case _             => None

/**
 * `<20` is kept as (20, `Below`): below the detection limit is information, not a missing value.
 */
enum Qualifier derives CanEqual:
  case Exact, Below, Above

object Qualifier:
  extension (q: Qualifier)
    def label: String = q match
      case Exact => "exact"
      case Below => "below"
      case Above => "above"

  def fromLabel(s: String): Option[Qualifier] = s match
    case "exact" => Some(Exact)
    case "below" => Some(Below)
    case "above" => Some(Above)
    case _       => None

/** Where a point's coordinates came from; `Missing` is the label `none` (MIP-0056 §5.3). */
enum GeoSource derives CanEqual:
  case Feed, Curated, Missing

object GeoSource:
  extension (g: GeoSource)
    def label: String = g match
      case Feed    => "feed"
      case Curated => "curated"
      case Missing => "none"

  def fromLabel(s: String): Option[GeoSource] = s match
    case "feed"    => Some(Feed)
    case "curated" => Some(Curated)
    case "none"    => Some(Missing)
    case _         => None

enum Mode derives CanEqual:
  case Incremental, Backfill

object Mode:
  extension (m: Mode)
    def label: String = m match
      case Incremental => "incremental"
      case Backfill    => "backfill"

  def fromLabel(s: String): Option[Mode] = s match
    case "incremental" => Some(Incremental)
    case "backfill"    => Some(Backfill)
    case _             => None

final case class SampleRow(
    sourceId: String,
    pointKey: String,
    sampledOn: LocalDate,
    sampledAt: Option[LocalTime],
    condition: BathingCondition,
    indicator: Indicator,
    indicatorValue: Option[Int],
    qualifier: Qualifier,
    rain: Option[String],
    wind: Option[String],
    tide: Option[String],
    waterTempC: Option[Double],
    airTempC: Option[Double],
    channel: Channel,
    bulletinDate: Option[LocalDate]
) derives CanEqual

final case class PointRow(
    sourceId: String,
    pointKey: String,
    country: String,
    state: String,
    municipality: String,
    ibgeCode: Option[String],
    beachName: String,
    pointName: String,
    locationDesc: Option[String],
    lat: Option[Double],
    lon: Option[Double],
    geoSource: GeoSource,
    firstSeen: Option[LocalDate],
    lastSeen: Option[LocalDate]
) derives CanEqual

/** The unit of work and of idempotency: one fetch, one manifest entry, one Parquet rewrite. */
final case class Partition(
    sourceId: String,
    channel: Channel,
    key: String,
    year: Int,
    immutable: Boolean
) derives CanEqual

/**
 * No `CanEqual`: comparing two responses structurally means comparing their sha256, not the bytes.
 */
final case class RawFile(partition: Partition, url: String, bytes: Array[Byte], fetchedAt: Instant)

/**
 * A partition the portal itself cannot export, reproduced by hand on `observed` — not a transient
 * failure, so the planner drops it instead of failing every run on it (MIP-0056 §5.2).
 */
final case class BrokenPartition(partition: String, observed: LocalDate, reason: String)
    derives CanEqual

/** One entry of `data/oods/sources.json`. */
final case class Source(
    id: String,
    institute: String,
    state: String,
    country: String,
    urls: Map[String, String],
    cadence: String,
    licence: String,
    knownBroken: List[BrokenPartition]
) derives CanEqual

final case class Plan(
    sources: Set[String],
    states: Set[String],
    cities: Set[String],
    mode: Mode,
    fromYear: Int,
    toYear: Int,
    dryRun: Boolean,
    concurrency: Int
) derives CanEqual

final case class ParseError(rawPath: String, detail: String) derives CanEqual

trait SourceAdapter:
  def source: Source
  def partitions(plan: Plan): List[Partition] < Sync
  def fetch(p: Partition): RawFile < Sync
  def rows(raw: RawFile): Either[ParseError, List[SampleRow]]
  def points: List[PointRow] < Sync
