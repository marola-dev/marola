package marola.tools.docs

import marola.tools.shared.Cli
import marola.tools.shared.Proc
import marola.tools.shared.Invocation

import mainargs.{main, Flag, ParserForMethods}
import SiteModel.*

// Build (or serve) marola's documentation site in its container stack (MIP-0064): docs are staged
// into mkdocs/docs, baked into an image and built or served next to Kroki by compose.
//
//     marola-tools mkdocs [--serve]   # DOCS_SRC (relative to the root), CONTAINER_RUNTIME
object Mkdocs:
  /** A runtime counts only when its daemon answers: an installed docker whose context points at a
    * dead endpoint is normal next to podman. CONTAINER_RUNTIME=docker pins one: GitHub's hosted
    * image has a podman whose `info` answers but no podman socket (MIP-0065).
    */
  def pickRuntime(config: MkdocsConfig): Option[String] =
    val proc = Proc.exact(config.env)
    config.runtimes.find { runtime =>
      proc.which(runtime, config.cwd).isDefined && proc.quiet(config.cwd, runtime, "info") == 0
    }

  // mkdocs reads one tree, so docs are copied in rather than symlinked, and then baked into the
  // image, so `--serve`'s live reload watches neither this copy nor the source (MIP-0064 §8).
  def stageDocs(src: os.Path, dest: os.Path): Unit =
    os.remove.all(dest)
    os.makeDir.all(dest)
    for child <- os.list(src) do os.copy(child, dest / child.last, followLinks = false)

  // strict does not cover this: a tree with no index.md builds green with no landing page.
  def checkIndex(dir: os.Path, shown: String): Either[Stop, Unit] =
    Either.cond(
      os.isFile(dir / Names.Index),
      (),
      Stop(
        1,
        s"mkdocs: $shown has no index.md — build it with scripts/marola-tools prepare-docs, which writes the umbrella README there"
      )
    )

  def runMode(mode: Mode, root: os.Path, config: MkdocsConfig): Int =
    build(mode, root, config) match
      case Right(())  => 0
      case Left(stop) =>
        if stop.message.nonEmpty then System.err.println(stop.message)
        stop.code

  private def build(mode: Mode, root: os.Path, config: MkdocsConfig): Either[Stop, Unit] =
    val project = projectName(root.toString)
    val image = imageTag(root.toString)
    // MKDOCS_IMAGE is read by mkdocs/docker-compose.yml's `image:` line for every compose call.
    val proc =
      Proc.exact(
        config.env ++ Map(EnvVar.MkdocsImage -> image, EnvVar.PodmanComposeWarningLogs -> "false")
      )
    val mkdocsDir = root / Names.Mkdocs
    val staged = mkdocsDir / Names.Docs
    // DOCS_SRC, relative to the root: docs.yml points it at prepare-docs' aggregated tree, so the
    // tracked docs/ tree is never written to (MIP-0070 §5.5, ruling T10-8).
    val docsSrcShown =
      config.docsSrc.map(d => s"$root/$d").getOrElse(s"$root/${Names.Docs}")
    val docsSrc = os.Path(docsSrcShown)

    def runStep(command: Seq[String], stdout: os.ProcessOutput = os.Inherit): Either[Stop, Unit] =
      val code = proc.call(mkdocsDir, command, stdout).exitCode
      Either.cond(code == 0, (), Stop(code))

    for
      runtime <- pickRuntime(config).toRight(
        Stop(
          1,
          "mkdocs: no reachable docker or podman daemon — a container runtime is this build's prerequisite"
        )
      )
      compose = Seq(runtime, "compose", "-p", project) ++ mode.composeFiles
      // `logs` names the service only, without the override files.
      logs = Seq(runtime, "compose", "-p", project, "logs", "-f", MkdocsService)
      _ <- checkIndex(docsSrc, docsSrcShown)
      _ <- Either.cond(os.isDir(mkdocsDir), (), Stop(1, s"mkdocs: no $mkdocsDir"))
      _ = proc.quiet(mkdocsDir, compose :+ "down"*)
      _ = os.remove.all(mkdocsDir / Names.GeneratedDocs)
      _ = stageDocs(docsSrc, staged)
      // #511: every top-level mermaid fence is pinned to a font Kroki has, before the kroki plugin reads it.
      pinned = MermaidFont.cli(Seq(staged.toString))
      _ <- Either.cond(pinned == 0, (), Stop(pinned))
      _ <- runStep(Seq(runtime, "build", "--tag", image, "--rm", "."))
      // -d then `logs -f`, not a foreground `up`: podman-compose does not enforce depends_on
      // health conditions in the foreground, so mkdocs would start before Kroki answers.
      _ <- runStep(compose ++ Seq("up", "-d", "--remove-orphans"))
      _ <- withComposeDown(compose, proc, mkdocsDir) {
        if mode == Mode.Serve then
          println(
            s"docs on http://localhost:8001${servePath(os.read(mkdocsDir / Names.MkdocsYml))} — Ctrl+C to stop"
          )
        for
          _ <- runStep(logs)
          cid <- mkdocsContainer(runtime, project, proc, mkdocsDir)
          _ <- containerExit(runtime, cid, mode, proc, mkdocsDir)
          _ <-
            if mode == Mode.Build then copyOut(runtime, cid, docsSrcShown, proc, mkdocsDir)
            else Right(())
        yield ()
      }
    yield ()

  // `logs -f` returns when the container stops, whatever it stopped with, so its exit code is
  // read back. Found by compose's labels, not by name: the runtimes spell the name differently.
  private def mkdocsContainer(
      runtime: String,
      project: String,
      proc: Proc,
      cwd: os.Path
  ): Either[Stop, String] =
    val listed = proc.call(
      cwd,
      Seq(
        runtime,
        "ps",
        "-aq",
        "--filter",
        s"label=com.docker.compose.project=$project",
        "--filter",
        s"label=com.docker.compose.service=$MkdocsService"
      ),
      os.Pipe,
      os.Pipe
    )
    val cid = listed.out.linesIterator.nextOption().getOrElse("")
    if listed.exitCode != 0 then Left(Stop(listed.exitCode))
    else if cid.isEmpty then
      Left(Stop(1, s"mkdocs: no mkdocs container found for compose project $project"))
    else Right(cid)

  private def containerExit(
      runtime: String,
      cid: String,
      mode: Mode,
      proc: Proc,
      cwd: os.Path
  ): Either[Stop, Unit] =
    val inspected = proc.captureQuiet(cwd, runtime, "inspect", "-f", "{{.State.ExitCode}}", cid)
    Stop.fromContainer(Option.when(inspected.exitCode == 0)(inspected.out), mode)

  private def copyOut(
      runtime: String,
      cid: String,
      docsSrcShown: String,
      proc: Proc,
      mkdocsDir: os.Path
  ): Either[Stop, Unit] =
    os.remove.all(mkdocsDir / Names.Docs)
    os.remove.all(mkdocsDir / Names.GeneratedDocs)
    val copied = proc.status(mkdocsDir, runtime, "cp", s"$cid:$ContainerSite", ".")
    if copied != 0 then Left(Stop(copied))
    else if !os.isFile(mkdocsDir / Names.GeneratedDocs / Names.IndexHtml) then
      Left(Stop(1, s"mkdocs: built no generated-docs/index.html from $docsSrcShown/index.md"))
    else
      println("built: mkdocs/generated-docs/index.html")
      Right(())

  /** Runs `body`, then `compose down`, also on Ctrl+C or a kill, so no Kroki container is left. */
  private def withComposeDown[A](compose: Seq[String], proc: Proc, cwd: os.Path)(body: => A): A =
    def down(): Unit = proc.quiet(cwd, compose :+ "down"*): Unit
    val onSignal = Thread(() => down())
    Runtime.getRuntime.addShutdownHook(onSignal)
    try body
    finally
      // A shutdown already under way runs the hook itself.
      val stillOurs =
        scala.util.Try(Runtime.getRuntime.removeShutdownHook(onSignal)).getOrElse(false)
      if stillOurs then down()

  @main(doc = "Build or serve the documentation site in its container (MIP-0064)")
  def run(serve: Flag): Int =
    val invocation = Invocation.current()
    runMode(
      if serve.value then Mode.Serve else Mode.Build,
      invocation.repoRoot,
      MkdocsConfig.from(invocation.env, invocation.cwd)
    )

  def cli(args: Seq[String]): Int = Cli.run(ParserForMethods(this), "mkdocs", args)
