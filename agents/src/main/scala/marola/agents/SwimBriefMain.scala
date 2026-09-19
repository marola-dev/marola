package marola.agents

import kyo.*

import marola.AppConfig

/**
 * `just swim-brief --lat -27.59 --lon -48.55 [--radius-km 15] [--ask "..."] [--trace]`
 *
 * A separate entry point on purpose (MIP-0061 §5.1): the ADK never reaches `marola.Main`, the CLI
 * jar or the native image.
 */
object SwimBriefMain:

  // A plain `main`, not a `KyoApp`: the ADK runner is blocking Java and this is its only caller.
  private given unsafe: AllowUnsafe = AllowUnsafe.embrace.danger

  final case class Args(
      lat: Option[Double] = None,
      lon: Option[Double] = None,
      radiusKm: Double = 15.0,
      ask: Option[String] = None,
      trace: Boolean = false
  )

  val Usage =
    "usage: swim-brief --lat <deg> --lon <deg> [--radius-km <km>] [--ask \"<question>\"] [--trace]"

  def parse(argv: List[String], acc: Args = Args()): Either[String, Args] = argv match
    case Nil => Right(acc)
    case "--lat" :: v :: rest =>
      v.toDoubleOption
        .toRight(s"--lat $v is not a number")
        .flatMap(d => parse(rest, acc.copy(lat = Some(d))))
    case "--lon" :: v :: rest =>
      v.toDoubleOption
        .toRight(s"--lon $v is not a number")
        .flatMap(d => parse(rest, acc.copy(lon = Some(d))))
    case "--radius-km" :: v :: rest =>
      v.toDoubleOption
        .toRight(s"--radius-km $v is not a number")
        .flatMap(d => parse(rest, acc.copy(radiusKm = d)))
    case "--ask" :: v :: rest => parse(rest, acc.copy(ask = Some(v)))
    case "--trace" :: rest    => parse(rest, acc.copy(trace = true))
    case other :: _           => Left(s"unknown argument: $other")

  /** The coordinate travels in the prompt — the agent is told to ask for it when it is absent. */
  def question(args: Args): String =
    val place = (args.lat, args.lon) match
      case (Some(lat), Some(lon)) =>
        s"I am at latitude $lat, longitude $lon (search radius ${args.radiusKm} km). "
      case _ => ""
    place + args.ask.getOrElse("Where and when should I swim tomorrow, and when should I leave?")

  def main(argv: Array[String]): Unit =
    val outcome =
      for
        args <- parse(argv.toList)
        settings <- AgentModels.settings(k => Option(java.lang.System.getenv(k)))
      yield (args, settings)
    outcome match
      case Left(problem) =>
        java.lang.System.err.println(s"swim-brief: $problem\n$Usage")
        sys.exit(2)
      case Right((args, settings)) =>
        val agent = SwimBriefAgent.build(
          AgentModels.build(settings),
          SwimTools.all(SwimData.live(AppConfig.fromEnv))
        )
        val result = Sync.Unsafe.evalOrThrow(SwimBriefRunner.ask(agent, question(args)))
        if args.trace then
          java.lang.System.err.println(
            s"swim-brief: ${settings.provider} ${settings.model} · tools called: ${result.toolCalls.mkString(", ")}"
          )
          result.toolResults.foreach((name, payload) =>
            java.lang.System.err.println(s"swim-brief: $name -> $payload")
          )
        val outings = BriefGuard.outings(result.toolResults)
        if BriefGuard.planned(result.toolResults) then println(BriefGuard.facts(outings))
        else println("(no plan was computed: the model did not call plan_swim_outing)")
        val toolText = result.toolResults.map(_._2.toString).mkString(" ")
        BriefGuard.verify(result.reply, outings, toolText) match
          case None if result.reply.nonEmpty => println(s"\n${result.reply}")
          case None =>
            java.lang.System.err
              .println("swim-brief: the model returned no reply (does it support tool calls?)")
          case Some(why) => println(s"\n(model commentary withheld: $why)")
        // RxJava and the HTTP clients keep non-daemon threads alive.
        sys.exit(0)
