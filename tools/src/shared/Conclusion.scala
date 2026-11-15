package marola.tools.shared

/** A check run's, job's or step's conclusion under the name the Actions API gives it, or a commit
  * status's state; `Unknown("")` when there is none yet.
  */
enum Conclusion:
  case Success, Failure, Cancelled, TimedOut, Skipped, Neutral
  case Unknown(raw: String)

  def wire: String = this match
    case Success      => "success"
    case Failure      => "failure"
    case Cancelled    => "cancelled"
    case TimedOut     => "timed_out"
    case Skipped      => "skipped"
    case Neutral      => "neutral"
    case Unknown(raw) => raw

  /** A step that reached one of these actually executed; the rest are not evidence either way. */
  def ran: Boolean = this match
    case Success | Failure | Cancelled | TimedOut => true
    case Skipped | Neutral | Unknown(_)           => false

  /** What a required check may end in and still let a merge through. */
  def passed: Boolean = this match
    case Success | Skipped | Neutral                 => true
    case Failure | Cancelled | TimedOut | Unknown(_) => false

object Conclusion:
  private val known = Seq(Success, Failure, Cancelled, TimedOut, Skipped, Neutral)

  def parse(raw: String): Conclusion = known.find(_.wire == raw).getOrElse(Unknown(raw))
