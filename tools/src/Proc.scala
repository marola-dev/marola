// Subprocesses run as a shell runs them (#942): the program looked up on the child's own PATH,
// exit 127 when it is not there, stderr passed through, and `must` ending the command with the
// failed step's exit code, as `set -e` does.
//
// `env` is added to this process's environment, a null value unsetting a variable; with
// `inheritEnv` off it is the whole environment. stdin is empty unless `stdin` says otherwise.
final case class Proc(
    env: Map[String, String] = Map.empty,
    inheritEnv: Boolean = true,
    stdin: os.ProcessInput = os.Pipe
):
  def variable(name: String): Option[String] =
    if env.contains(name) then Option(env(name))
    else if inheritEnv then sys.env.get(name)
    else None

  /** `command -v`. Trap: ProcessBuilder resolves a bare program on the JVM's PATH, not the child's,
    * so without this a test's fake on the child's PATH would never run.
    */
  def which(program: String, cwd: os.Path): Option[os.Path] =
    def runnable(p: os.Path) = os.isFile(p) && p.toIO.canExecute
    if program.contains('/') then Some(os.Path(program, cwd)).filter(runnable)
    else
      // An empty PATH entry is the working directory, as in a shell.
      val dirs = variable("PATH").getOrElse("").split(":", -1)
      dirs.iterator
        .map(dir => os.Path(if dir.isEmpty then "." else dir, cwd) / program)
        .find(runnable)

  /** Run `cmd` in `cwd`: its exit code, and its stdout when `stdout` is `os.Pipe`. */
  def call(
      cwd: os.Path,
      cmd: Seq[String],
      stdout: os.ProcessOutput = os.Inherit,
      stderr: os.ProcessOutput = os.Inherit,
      extraEnv: Map[String, String] = Map.empty
  ): Proc.Result =
    which(cmd.head, cwd) match
      case None =>
        Proc.err(s"${cmd.head}: command not found")
        Proc.Result(127, "")
      case Some(program) =>
        System.out.flush()
        val result = os
          .proc(program.toString +: cmd.tail)
          .call(
            cwd = cwd,
            env = env ++ extraEnv,
            stdin = stdin,
            stdout = stdout,
            stderr = stderr,
            check = false,
            propagateEnv = inheritEnv
          )
        Proc.Result(result.exitCode, if stdout == os.Pipe then result.out.text() else "")

  /** Output passed through: the exit code. */
  def status(cwd: os.Path, cmd: String*): Int = call(cwd, cmd).exitCode

  /** `>/dev/null 2>&1`: the exit code only. */
  def quiet(cwd: os.Path, cmd: String*): Int = call(cwd, cmd, os.Pipe, os.Pipe).exitCode

  /** `$(cmd)`: the exit code and stdout without its trailing newlines. */
  def capture(cwd: os.Path, cmd: String*): Proc.Result =
    val result = call(cwd, cmd, os.Pipe)
    result.copy(out = Proc.chomp(result.out))

  /** `$(cmd 2>/dev/null)`. */
  def captureQuiet(cwd: os.Path, cmd: String*): Proc.Result =
    val result = call(cwd, cmd, os.Pipe, os.Pipe)
    result.copy(out = Proc.chomp(result.out))

  /** A step whose failure ends the run with its exit code. */
  def must(cwd: os.Path, cmd: String*): Unit =
    val code = status(cwd, cmd*)
    if code != 0 then throw Proc.Abort(code)

  def mustCapture(cwd: os.Path, cmd: String*): String =
    val result = capture(cwd, cmd*)
    if result.exitCode != 0 then throw Proc.Abort(result.exitCode)
    result.out

  /** `cd "$(git rev-parse --show-toplevel)"`: outside a repo git says so and the shell stays put.
    */
  def toplevel(cwd: os.Path): os.Path =
    capture(cwd, "git", "rev-parse", "--show-toplevel") match
      case Proc.Result(0, dir) if dir.nonEmpty => os.Path(dir)
      case _                                   => cwd

  /** `git config <source...> --get-regexp '^submodule\..*\.path$' || true` as (key, path) pairs. */
  def submodulePaths(cwd: os.Path, source: String*): Vector[(String, String)] =
    val cmd = Seq("git", "config") ++ source ++ Seq("--get-regexp", raw"^submodule\..*\.path$$")
    Proc.lines(capture(cwd, cmd*).out).filter(_.nonEmpty).map(Proc.firstWordAndRest)

object Proc:
  final case class Result(exitCode: Int, out: String)

  // For the commands whose whole environment is a parameter, so a test decides all of it.
  def exact(env: Map[String, String]): Proc = Proc(env, inheritEnv = false, stdin = os.Inherit)

  /** Thrown by `must`, turned back into the exit code by `exitCode`. */
  final class Abort(val code: Int)
      extends Exception(s"exit $code")
      with scala.util.control.NoStackTrace

  def exitCode(body: => Int): Int =
    try body
    catch case abort: Abort => abort.code

  /** What `$(...)` keeps of an output: all but its trailing newlines. */
  def chomp(text: String): String = text.reverse.dropWhile(_ == '\n').reverse

  /** The lines of `text` as awk and `read` see them: split on \n only, so a \r stays part of its
    * line.
    */
  def lines(text: String): Vector[String] =
    if text.isEmpty then Vector.empty else text.stripSuffix("\n").split("\n", -1).toVector

  /** `read -r first rest`: the first word, and the remainder with its surrounding blanks trimmed.
    */
  def firstWordAndRest(line: String): (String, String) =
    def blank(c: Char) = c == ' ' || c == '\t'
    val trimmed = line.dropWhile(blank)
    val first = trimmed.takeWhile(!blank(_))
    (first, trimmed.drop(first.length).dropWhile(blank).reverse.dropWhile(blank).reverse)

  def say(line: String): Unit =
    System.out.println(line)
    System.out.flush()

  def err(line: String): Unit =
    System.out.flush()
    System.err.println(line)
    System.err.flush()
