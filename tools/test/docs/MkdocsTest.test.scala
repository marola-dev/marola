package marola.tools.docs

import marola.tools.shared.Util

class MkdocsTest extends munit.FunSuite:
  import Mkdocs.*
  import SiteModel.*

  private def stub(dir: os.Path, name: String, exit: Int): Unit =
    os.write.over(dir / name, s"#!/bin/sh\nexit $exit\n", createFolders = true, perms = "rwxr-xr-x")

  test("pickRuntime takes the first runtime whose daemon answers"):
    val bin = os.temp.dir()
    def path(extra: (String, String)*) = Map("PATH" -> bin.toString) ++ extra
    stub(bin, "podman", 0)
    assertEquals(pickRuntime(MkdocsConfig.from(path())), Some("podman"))
    stub(bin, "docker", 0)
    assertEquals(
      pickRuntime(MkdocsConfig.from(path("CONTAINER_RUNTIME" -> "docker"))),
      Some("docker"),
      "CONTAINER_RUNTIME=docker skips a live podman"
    )
    stub(bin, "podman", 1)
    assertEquals(
      pickRuntime(MkdocsConfig.from(path())),
      Some("docker"),
      "a podman whose daemon does not answer is skipped"
    )
    stub(bin, "docker", 1)
    assertEquals(
      pickRuntime(MkdocsConfig.from(path())),
      None,
      "two installed-but-dead runtimes fail"
    )
    assertEquals(
      pickRuntime(MkdocsConfig.from(Map("PATH" -> (bin / "empty").toString))),
      None,
      "neither installed fails too"
    )

  test("cksum is POSIX cksum's"):
    // printf '%s' /a/b/c | cksum; printf '' | cksum
    assertEquals(cksum("/a/b/c"), 146066531L)
    assertEquals(cksum(""), 4294967295L)
    assertEquals(cksum("/tmp/claude-0/wt/5"), 841637224L)

  test("the project name and image tag are per checkout, stable and well formed"):
    assertEquals(projectName("/a/b/c"), projectName("/a/b/c"))
    assertNotEquals(projectName("/a/b/c"), projectName("/a/b/d"))
    assert(projectName("/a/b/c").matches("marola-mkdocs-[0-9]+"))
    assertNotEquals(imageTag("/a/b/c"), imageTag("/a/b/d"))
    assertEquals(imageTag("/a/b/c"), "marola/mkdocs:146066531")
    assert(
      os.read(Util.repoRoot / "mkdocs" / "docker-compose.yml")
        .contains("image: ${MKDOCS_IMAGE:-marola/mkdocs}")
    )

  test("each mode layers its compose override"):
    assertEquals(
      Mode.Build.composeFiles,
      Seq("-f", "docker-compose.yml", "-f", "docker-compose.build.yml")
    )
    assertEquals(
      Mode.Serve.composeFiles,
      Seq("-f", "docker-compose.yml", "-f", "docker-compose.serve.yml")
    )

  test("stageDocs copies the tree in, and a deleted doc does not survive"):
    val t = os.temp.dir()
    os.write(t / "src" / "README.md", "hello\n", createFolders = true)
    os.write(t / "src" / "mips" / "MIP-0001.md", "mip\n", createFolders = true)
    os.write(t / "dest" / "GONE.md", "stale\n", createFolders = true)
    stageDocs(t / "src", t / "dest")
    assertEquals(os.read(t / "dest" / "README.md"), "hello\n")
    assertEquals(os.read(t / "dest" / "mips" / "MIP-0001.md"), "mip\n")
    assert(!os.exists(t / "dest" / "GONE.md"))

  test("mkdocs.yml and the image keep the keys the build depends on"):
    val cfg = os.read.lines(Util.repoRoot / "mkdocs" / "mkdocs.yml")
    def lines(pattern: String) = cfg.count(_.matches(pattern))
    assertEquals(lines("""^ *server_url:.*"""), 1, "the 1.7.0 snake_case server_url")
    assertEquals(cfg.count(_.contains("http_method: POST")), 1, "SVGs are written into the output")
    assertEquals(cfg.count(_.contains("fence_prefix: \"\"")), 1, "plain mermaid fences render")
    assertEquals(
      lines("""^ *fail_fast: true.*"""),
      1,
      "a broken diagram fails the build (MIP-0068 §5.2)"
    )
    assertEquals(lines("""^ *enable_bpmn: false.*"""), 1)
    assertEquals(lines("""^ *enable_diagramsnet: false.*"""), 1)
    assertEquals(lines("""^nav:.*"""), 0, "no hand-written nav to drift")
    assertEquals(lines("""^ *- privacy.*"""), 1, "Material's webfont is served locally")
    assertEquals(lines("""^strict: true.*"""), 1)
    assertEquals(lines("""^  anchors: warn.*"""), 1)
    assertEquals(
      lines("""^  custom_dir: overrides.*"""),
      1,
      "the forwarding 404.html (MIP-0074 §5.3)"
    )
    assertEquals(lines("""^  - redirects:.*"""), 1)
    assertEquals(
      lines("""^ *hooks:.*"""),
      0,
      "the font pin runs on the staged copy, not as a hook (#511)"
    )
    assert(os.read(Util.repoRoot / "mkdocs" / "Dockerfile").contains("mkdocs-redirects==1.2.2"))
    assertEquals(
      servePath(cfg.mkString("\n")),
      "/",
      "docs.marola.dev serves at its root (MIP-0070 §5.5)"
    )

  test("the serve banner follows site_url's path"):
    assertEquals(servePath("site_url: https://example.com/docs/\n"), "/docs/")
    assertEquals(servePath("site_url: https://example.com\n"), "/")
    assertEquals(servePath("site_url: https://example.com/\n"), "/")
    assertEquals(servePath("theme: x\n"), "/")

  test("a build dir with no index.md fails before mkdocs runs, pointing at prepare-docs"):
    val t = os.temp.dir()
    val missing = checkIndex(t, t.toString)
    assert(
      missing.left.exists(stop => stop.code == 1 && stop.message.contains("prepare-docs")),
      missing
    )
    os.write(t / "index.md", "# landing\n")
    assertEquals(checkIndex(t, t.toString), Right(()))

  test("the staged mermaid fences are pinned to a font Kroki has (#511)"):
    val t = os.temp.dir()
    os.write(
      t / "sample.md",
      """# Sample
        |
        |```mermaid
        |flowchart TD
        |  a --> b
        |```
        |
        |````markdown
        |```mermaid
        |flowchart TD
        |  a --> b
        |```
        |````
        |
        |```mermaid
        |%%{init: {"themeVariables": {"git0": "#1ac5da"}}}%%
        |gitGraph
        |  commit
        |```
        |""".stripMargin
    )
    assertEquals(MermaidFont.cli(Seq(t.toString)), 0)
    val out = os.read.lines(t / "sample.md")
    assertEquals(
      out.count(_.contains("fontFamily")),
      2,
      "once per real fence, not the nested example"
    )
    assertEquals(out.count(_ == "```"), 3, "every fence keeps its closing delimiter")
    val afterOpening = out.zip(out.drop(1)).collect { case ("```mermaid", next) => next }
    assertEquals(afterOpening.count(_.contains("Open Sans")), 2, "the init line comes first")
    assertEquals(out.count(_.contains("````markdown")), 1)
    assertEquals(out.count(_.contains("git0")), 1, "an existing init still merges (#510)")

  /** A checkout with mkdocs/ and docs/index.md, and a fake runtime that logs every call. */
  private def withFakeRuntime(exit: String)(
      body: (os.Path, Map[String, String], os.Path) => Unit
  ): Unit =
    val t = os.temp.dir(prefix = "mkdocs")
    try
      val root = t / "root"
      os.write(
        root / "mkdocs" / "mkdocs.yml",
        "site_url: https://docs.example.com/\n",
        createFolders = true
      )
      os.write(root / "docs" / "index.md", "# landing\n", createFolders = true)
      os.write(
        t / "bin" / "docker",
        """#!/bin/sh
          |echo "docker $* | cwd=$(basename "$PWD") image=$MKDOCS_IMAGE" >>"$FAKE_LOG"
          |case "$1" in
          |  ps) echo cid123; echo cid456 ;;
          |  inspect) echo "$FAKE_EXIT" ;;
          |  cp) mkdir -p generated-docs && echo '<html>' >generated-docs/index.html ;;
          |esac
          |""".stripMargin,
        createFolders = true,
        perms = "rwxr-xr-x"
      )
      val env = Map(
        "PATH" -> s"${t / "bin"}:${sys.env("PATH")}",
        "CONTAINER_RUNTIME" -> "docker",
        "FAKE_LOG" -> (t / "log").toString,
        "FAKE_EXIT" -> exit
      )
      body(root, env, t / "log")
    finally os.remove.all(t)

  test(
    "a build runs the stack, reads the container's exit code back, copies the site out, then downs it"
  ):
    withFakeRuntime("0") { (root, env, log) =>
      assertEquals(runMode(Mode.Build, root, MkdocsConfig.from(env)), 0)
      val compose =
        s"docker compose -p ${projectName(root.toString)} -f docker-compose.yml -f docker-compose.build.yml"
      val suffix = s" | cwd=mkdocs image=${imageTag(root.toString)}"
      assertEquals(
        os.read.lines(log).toSeq,
        Seq(
          s"docker info",
          s"$compose down",
          s"docker build --tag ${imageTag(root.toString)} --rm .",
          s"$compose up -d --remove-orphans",
          s"docker compose -p ${projectName(root.toString)} logs -f mkdocs",
          s"docker ps -aq --filter label=com.docker.compose.project=${projectName(root.toString)} --filter label=com.docker.compose.service=mkdocs",
          "docker inspect -f {{.State.ExitCode}} cid123",
          "docker cp cid123:/mkdocs/generated-docs/ .",
          s"$compose down"
        ).map(line =>
          if line == "docker info" then s"docker info | cwd=${os.pwd.last} image="
          else line + suffix
        )
      )
      assert(os.isFile(root / "mkdocs" / "generated-docs" / "index.html"))
      assert(!os.exists(root / "mkdocs" / "docs"), "the staged copy is removed after the build")
    }

  test("a failed build exits with the container's code, and still downs the stack"):
    withFakeRuntime("3") { (root, env, log) =>
      assertEquals(runMode(Mode.Build, root, MkdocsConfig.from(env)), 3)
      assert(
        os.read.lines(log).last.contains("compose") && os.read.lines(log).last.contains(" down ")
      )
      assert(!os.exists(root / "mkdocs" / "generated-docs"))
    }

  test("no reachable runtime is exit 1"):
    withFakeRuntime("0") { (root, env, _) =>
      assertEquals(
        runMode(
          Mode.Build,
          root,
          MkdocsConfig.from(env + ("CONTAINER_RUNTIME" -> "no-such-runtime"))
        ),
        1
      )
    }
