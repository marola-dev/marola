import mainargs.ParserForMethods

// The one way a command reads its arguments: a container object with a single `@main` method
// that returns the exit code. `--help` prints mainargs' usage and exits 0; a bad argument prints
// the error and exits 2.
object Cli:
  def run[T](parser: ParserForMethods[T], name: String, args: Seq[String]): Int =
    if args.contains("--help") || args.contains("-h") then
      System.err.println(s"marola-tools $name\n" + parser.helpText())
      0
    else
      parser.runEither(args) match
        case Right(code: Int) => code
        case Right(_)         => 0
        case Left(error)      =>
          System.err.println(s"marola-tools $name: $error")
          2

  /** An expected failure carried as a message becomes exit 1, with the message on stderr. */
  def report(result: Either[String, Unit]): Int = result match
    case Right(())   => 0
    case Left(error) =>
      System.err.println(error)
      1
