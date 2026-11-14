class PrepareDocsTest extends munit.FunSuite:
  import PrepareDocs.*

  // No network: every api-docs and flake-lock fetch goes to an empty local directory unless a
  // test says otherwise.
  private val noRemote = os.temp.dir(prefix = "no-remote")
  private val env = FetchApiDocs.gitEnv(sys.env) ++ Map(
    "GIT_CONFIG_GLOBAL" -> "/dev/null",
    "GIT_CONFIG_NOSYSTEM" -> "1",
    "API_DOCS_REMOTE" -> s"$noRemote/<repo>.git",
    "DOCS_SOURCE_REMOTE" -> s"$noRemote/<repo>.git"
  )

  private def git(dir: os.Path, args: String*): String =
    os.proc(Seq("git", "-C", dir.toString, "-c", "user.email=t@t", "-c", "user.name=t") ++ args)
      .call(env = env, propagateEnv = false, stderr = os.Pipe)
      .out
      .text()
      .trim

  private def commitAll(dir: os.Path, message: String = "fixture"): Unit =
    if !os.exists(dir / ".git") then git(dir, "init", "-q"): Unit
    git(dir, "-c", "advice.addEmbeddedRepo=false", "add", "-A")
    git(dir, "commit", "-q", "-m", message): Unit

  /** A fake umbrella checkout; its submodules are the caller's. */
  private def umbrella(root: os.Path, reposYml: String): Unit =
    os.write(
      root / "README.md",
      "# umbrella\n\nSee [a MIP](docs/MIPs/MIP-0001.md) and [the app](fake-app/README.md).\n",
      createFolders = true
    )
    os.write(
      root / "docs" / "PHASES.md",
      "# phases, see [MIP-0001](MIPs/MIP-0001.md)\n",
      createFolders = true
    )
    os.write(
      root / "docs" / "MIPs" / "MIP-0001.md",
      "# MIP-0001, back to [phases](../PHASES.md)\n",
      createFolders = true
    )
    os.write(
      root / "mkdocs" / "mkdocs.yml",
      "exclude_docs: |\n  benchmarks/\n\ntheme:\n  name: material\n",
      createFolders = true
    )
    os.write(root / "mkdocs" / "repos.yml", reposYml)

  /** A fake submodule: a README landing, a docs page, a code file and an off-site ledger. */
  private def app(dir: os.Path): Unit =
    os.write(
      dir / "README.md",
      "# fake-app\n\nSee [design](docs/1-design.md), [./docs/ prefixed](./docs/sub/notes.md), [code](core/A.scala)\nand [a run](docs/benchmarks/run.md).\n",
      createFolders = true
    )
    os.write(
      dir / "docs" / "1-design.md",
      "# design, back to [the landing](../README.md)\n",
      createFolders = true
    )
    os.write(dir / "docs" / "sub" / "notes.md", "# notes\n", createFolders = true)
    os.write(dir / "docs" / "benchmarks" / "run.md", "# a kept run\n", createFolders = true)
    os.write(dir / "core" / "A.scala", "object A\n", createFolders = true)
    commitAll(dir)

  private def addPage(dir: os.Path, page: String, text: String): Unit =
    os.write.over(dir / os.RelPath(page), text, createFolders = true)
    git(dir, "add", "-A")
    git(dir, "commit", "-q", "-m", "more"): Unit

  /** prepare's result and everything it printed, stdout and stderr together. */
  private def prepared(
      root: os.Path,
      build: os.Path,
      extra: Map[String, String] = Map.empty
  ): (Boolean, String) =
    val buffer = java.io.ByteArrayOutputStream()
    val stream = java.io.PrintStream(buffer, true, "UTF-8")
    val (savedOut, savedErr) = (System.out, System.err)
    System.setOut(stream)
    System.setErr(stream)
    try
      val result =
        Console.withOut(stream)(Console.withErr(stream)(prepare(root, build, env ++ extra)))
      (result.isRight, buffer.toString("UTF-8") + result.left.getOrElse(""))
    finally
      System.setOut(savedOut)
      System.setErr(savedErr)

  private def fixture(reposYml: String = "- name: fake-app\n")(
      body: (os.Path, os.Path) => Unit
  ): Unit =
    val t = os.temp.dir(prefix = "prepare-docs")
    try
      umbrella(t / "root", reposYml)
      body(t / "root", t / "build")
    finally os.remove.all(t)

  private def count(text: String, needle: String) = text.linesIterator.count(_.contains(needle))

  test("the umbrella's README is the site's index.md, docs/MIPs/ is 6-MIPs/"):
    fixture() { (root, build) =>
      app(root / "fake-app")
      os.write(root / "llms.txt", "# umbrella\n\n> a summary\n")
      commitAll(root)
      assert(prepared(root, build)._1)
      assertEquals(os.read(build / "llms.txt"), os.read(root / "llms.txt"))
      assertEquals(
        os.read.lines(build / "index.md")(2),
        "See [a MIP](6-MIPs/MIP-0001.md) and [the app](5-Repos/fake-app/index.md)."
      )
      assert(os.isFile(build / "6-MIPs" / "MIP-0001.md"))
      assert(!os.exists(build / "MIPs"))
      assertEquals(os.read(build / "PHASES.md"), "# phases, see [MIP-0001](6-MIPs/MIP-0001.md)\n")
      assertEquals(
        os.read(build / "6-MIPs" / "MIP-0001.md"),
        "# MIP-0001, back to [phases](../PHASES.md)\n"
      )
      assert(!os.exists(root / "docs" / "6-MIPs"), "the tracked docs/ tree is never written to")

      // Every repo mounts at 5-Repos/<name>/, links rewritten (Appendix A).
      val blob =
        s"https://github.com/marola-dev/fake-app/blob/${git(root / "fake-app", "rev-parse", "HEAD")}"
      assertEquals(
        os.read.lines(build / "5-Repos" / "fake-app" / "index.md").slice(2, 4).mkString("\n"),
        s"See [design](1-design.md), [./docs/ prefixed](sub/notes.md), [code]($blob/core/A.scala)\nand [a run]($blob/docs/benchmarks/run.md)."
      )
      assertEquals(
        os.read(build / "5-Repos" / "fake-app" / "1-design.md"),
        "# design, back to [the landing](index.md)\n"
      )
      assert(os.isFile(build / "5-Repos" / "fake-app" / "sub" / "notes.md"))
      assert(
        !os.exists(build / "5-Repos" / "fake-app" / "benchmarks"),
        "an exclude_docs path is not copied"
      )
    }

  test("build.json and each landing name the commit each repo was built from"):
    fixture() { (root, build) =>
      app(root / "fake-app")
      commitAll(root)
      assert(prepared(root, build)._1)
      assert(!os.exists(build / "llms.txt"))
      val appSha = git(root / "fake-app", "rev-parse", "HEAD")
      val rootSha = git(root, "rev-parse", "HEAD")
      assertEquals(
        os.read(build / "build.json"),
        s"{\n  \"marola\": \"$rootSha\",\n  \"fake-app\": \"$appSha\"\n}\n"
      )
      assertEquals(
        os.read.lines(build / "5-Repos" / "fake-app" / "index.md").last,
        s"_Built from [marola-dev/fake-app@${appSha.take(7)}](https://github.com/marola-dev/fake-app/tree/$appSha)._"
      )
      assertEquals(
        os.read.lines(build / "index.md").last,
        s"_Built from [marola-dev/marola@${rootSha.take(7)}](https://github.com/marola-dev/marola/tree/$rootSha)._"
      )
    }

  test("adr/index.md lists each ADR's number, title and status"):
    fixture() { (root, build) =>
      val fakeApp = root / "fake-app"
      app(fakeApp)
      os.write(
        fakeApp / "docs" / "adr" / "0002-x.md",
        "# ADR-0002: Drop the cache | for now\n\n| | |\n|---|---|\n| **Status** | Superseded by [ADR-0003](0003-y.md) |\n",
        createFolders = true
      )
      os.write(
        fakeApp / "docs" / "adr" / "0003-y.md",
        "# ADR-0003: Keep one cache\n\n| | |\n|---|---|\n| **Status** | Accepted |\n| **Date** | 2026-10-04 |\n"
      )
      addPage(
        fakeApp,
        "README.md",
        os.read(fakeApp / "README.md") + "Decisions: [ADRs](docs/adr/).\n"
      )
      commitAll(root)
      val (ok, out) = prepared(root, build)
      assert(ok, out)
      assertEquals(
        os.read(build / "5-Repos" / "fake-app" / "adr" / "index.md"),
        """# Architecture decisions
          |
          || ADR | Decision | Status |
          ||---|---|---|
          || [0002](0002-x.md) | Drop the cache \| for now | Superseded by [ADR-0003](0003-y.md) |
          || [0003](0003-y.md) | Keep one cache | Accepted |
          |""".stripMargin
      )
      assert(!os.exists(build / "adr"), "the umbrella, with no docs/adr/, gets no index")
      os.write(fakeApp / "docs" / "adr" / "0004-z.md", "# ADR-0004 keep\n")
      val (offTemplate, message) = prepared(root, build)
      assert(!offTemplate)
      assertEquals(
        count(message, "docs/adr/0004-z.md: no \"# ADR-0004: <decision>\" heading"),
        1,
        message
      )
    }

  test("a hand-written docs/adr/index.md or README.md is refused"):
    fixture() { (root, build) =>
      val adr = root / "fake-app" / "docs" / "adr"
      app(root / "fake-app")
      os.write(
        adr / "0001-one.md",
        "# ADR-0001: One\n\n| **Status** | Accepted |\n",
        createFolders = true
      )
      os.write(adr / "index.md", "# my ADRs\n")
      commitAll(root)
      val (ok, out) = prepared(root, build)
      assert(!ok)
      assertEquals(count(out, "fake-app: docs/adr/index.md: hand-written"), 1, out)
      os.move(adr / "index.md", adr / "README.md")
      assert(!prepared(root, build)._1)
    }

  test("README.md plus docs/index.md is refused (D1), in a submodule and in the umbrella"):
    fixture() { (root, build) =>
      app(root / "fake-app")
      os.write(root / "fake-app" / "docs" / "index.md", "# old index\n")
      commitAll(root)
      val (ok, out) = prepared(root, build)
      assert(!ok)
      assertEquals(count(out, "fake-app has both README.md and docs/index.md"), 1)
      os.write(root / "docs" / "index.md", "# umbrella's old index\n")
      os.remove(root / "fake-app" / "docs" / "index.md")
      val (umbrellaOk, umbrellaOut) = prepared(root, build)
      assert(!umbrellaOk)
      assertEquals(count(umbrellaOut, "marola has both README.md and docs/index.md"), 1)
    }

  test("a repo with no README.md has no landing"):
    fixture() { (root, build) =>
      app(root / "fake-app")
      git(root / "fake-app", "rm", "-q", "README.md")
      commitAll(root)
      val (ok, out) = prepared(root, build)
      assert(!ok)
      assertEquals(count(out, "fake-app has no README.md"), 1)
    }

  test("mount: is an error, in the parser and in prepare"):
    assertEquals(
      ReposManifest.parseText("- name: fake-app\n  mount: ./\n", "m.yml"),
      Left(
        "repos_manifest: m.yml:2: mount: is gone, every repo mounts at 5-Repos/<name>/ (MIP-0074 §5.3)"
      )
    )
    fixture("- name: fake-app\n  mount: ./\n") { (root, build) =>
      app(root / "fake-app")
      commitAll(root)
      assert(!prepared(root, build)._1)
    }

  test("a link Appendix A refuses fails the run, naming the file and the link"):
    fixture() { (root, build) =>
      app(root / "fake-app")
      addPage(root / "fake-app", "docs/4-reference.md", "# api, see [root-absolute](/api/x.html)\n")
      commitAll(root)
      val (ok, out) = prepared(root, build)
      assert(!ok)
      assertEquals(count(out, "docs/4-reference.md: /api/x.html: root-absolute"), 1, out)
    }

  test("a broken relative link is reported (inline, reference-style and HTML), not inside code"):
    fixture() { (root, build) =>
      app(root / "fake-app")
      addPage(
        root / "fake-app",
        "docs/page.md",
        """See [gone](missing.md).
          |
          |[ref]: also-missing.md
          |
          |<img src="gone.png" alt="x"> and <a href="https://example.com/x">absolute</a>
          |
          |Code is not a link: `f[A](effect: A)`, nor in a span that wraps: `way["n"](around:R, lat, lon); out
          |geom;`, and
          |
          |```text
          |q='node(around:15000,-27.6,-48.4)[x](in-a-fence.md)'
          |```
          |
          |````markdown
          |```bash
          |[y](in-a-nested-fence.md)
          |```
          |````
          |""".stripMargin
      )
      commitAll(root)
      val (ok, out) = prepared(root, build)
      assert(!ok)
      assertEquals(count(out, "5-Repos/fake-app/page.md: broken relative link: missing.md"), 1, out)
      assertEquals(count(out, "broken relative link: also-missing.md"), 1)
      assertEquals(count(out, "broken relative link: gone.png"), 1)
      assertEquals(count(out, "broken relative link"), 3)
    }

  test("a data:, tel: or javascript: link is not a relative link"):
    fixture() { (root, build) =>
      app(root / "fake-app")
      addPage(
        root / "fake-app",
        "docs/page.md",
        "<img src=\"data:image/png;base64,AA==\" alt=\"x\"> [call](tel:+554800000000) <a href=\"javascript:void(0)\">x</a>\n"
      )
      commitAll(root)
      val (ok, out) = prepared(root, build)
      assert(ok, out)
      assertEquals(count(out, "broken relative link"), 0)
    }

  test("a removed mount does not survive a re-run (ruling T10-8)"):
    fixture("- name: fake-app\n- name: fake-two\n") { (root, build) =>
      app(root / "fake-app")
      os.write(root / "fake-two" / "README.md", "# two\n", createFolders = true)
      commitAll(root / "fake-two")
      commitAll(root)
      assert(prepared(root, build)._1)
      assert(os.isFile(build / "5-Repos" / "fake-two" / "index.md"))
      os.write.over(root / "mkdocs" / "repos.yml", "- name: fake-app\n")
      assert(prepared(root, build)._1)
      assert(os.isFile(build / "5-Repos" / "fake-app" / "index.md"))
      assert(!os.exists(build / "5-Repos" / "fake-two"))
    }

  test("a manifest entry with no checked-out submodule is a hard error"):
    fixture("- name: fake-ghost\n") { (root, build) =>
      os.makeDir.all(root / "fake-ghost")
      commitAll(root)
      val (ok, out) = prepared(root, build)
      assert(!ok)
      assertEquals(count(out, "fake-ghost is not checked out"), 1, out)
    }

  test("the api-docs branch is unpacked under <mount>/api-docs/; a repo without one is a notice"):
    fixture() { (root, build) =>
      app(root / "fake-app")
      addPage(
        root / "fake-app",
        "docs/4-reference_api.md",
        "# api, see [the tree](api-docs/scala/index.html)\n"
      )
      commitAll(root)
      val (ok, out) = prepared(root, build)
      assert(!ok, "without the branch, a link into api-docs/ is broken")
      assertEquals(count(out, "fake-app has no api-docs branch"), 1, out)
      val remote = root / os.up / "remote"
      FetchApiDocsTest.seedApiDocs(remote / "fake-app.git", "api-docs: 1234567", env)
      val (withBranch, out2) =
        prepared(root, build, Map("API_DOCS_REMOTE" -> s"$remote/<repo>.git"))
      assert(withBranch, out2)
      assert(os.isFile(build / "5-Repos" / "fake-app" / "api-docs" / "scala" / "index.html"))
    }

  test("the manifest: source: flake-lock is the one source, a submodule has none"):
    assertEquals(
      ReposManifest
        .parseText("- name: fake-devkit\n  source: flake-lock\n- name: fake-app\n", "m.yml"),
      Right(
        Vector(
          ReposManifest.Repo("fake-devkit", ReposManifest.Source.FlakeLock),
          ReposManifest.Repo("fake-app", ReposManifest.Source.Submodule)
        )
      )
    )
    assertEquals(
      ReposManifest.parseText("- name: fake-devkit\n  source: submodule\n", "m.yml"),
      Left("repos_manifest: m.yml:2: source must be flake-lock, got: submodule")
    )
    assertEquals(
      ReposManifest.parseText("# only a comment\n\n", "m.yml"),
      Right(Vector.empty)
    )
    assertEquals(
      ReposManifest.parseText("  source: flake-lock\n", "m.yml"),
      Left("repos_manifest: m.yml:1: source before any `- name:`")
    )
    assertEquals(
      ReposManifest.parseText("- name: \"quoted\"\r\nwhat\n", "m.yml"),
      Left("repos_manifest: m.yml:2: unrecognised line: what")
    )
    fixture("- name: fake-devkit\n  source: submodule\n") { (root, build) =>
      commitAll(root)
      assert(!prepared(root, build)._1)
    }

  test("a flake-lock source mounts flake.lock's rev, not the tip"):
    fixture("- name: fake-devkit\n  source: flake-lock\n") { (root, build) =>
      val t = root / os.up
      os.makeDir.all(t / "remote")
      git(t / "remote", "init", "-q", "--bare", "fake-devkit.git")
      val seed = t / "seed"
      os.write(seed / "README.md", "# locked, see [ref](docs/ref.md)\n", createFolders = true)
      os.write(seed / "docs" / "ref.md", "# ref\n", createFolders = true)
      commitAll(seed, "locked")
      val locked = git(seed, "rev-parse", "HEAD")
      os.write.over(seed / "README.md", "# tip\n")
      git(seed, "commit", "-q", "-am", "tip")
      git(seed, "push", "-q", (t / "remote" / "fake-devkit.git").toString, "HEAD:refs/heads/main")
      os.write.over(root / "README.md", "# umbrella\n\nSee [a MIP](docs/MIPs/MIP-0001.md).\n")
      os.write(
        root / "flake.lock",
        s"""{"nodes":{"fake-devkit":{"locked":{"rev":"$locked"}}},"root":"root","version":7}\n"""
      )
      commitAll(root)
      val remote = Map("DOCS_SOURCE_REMOTE" -> s"${t / "remote"}/<repo>.git")
      val (ok, out) = prepared(root, build, remote)
      assert(ok, out)
      assertEquals(
        os.read.lines(build / "5-Repos" / "fake-devkit" / "index.md").head,
        "# locked, see [ref](ref.md)"
      )
      assert(os.isFile(build / "5-Repos" / "fake-devkit" / "ref.md"))
      assertEquals(git(root / ".tmp" / "docs-sources" / "fake-devkit", "rev-parse", "HEAD"), locked)
      assertEquals(ujson.read(os.read(build / "build.json"))("fake-devkit").str, locked)

      // A git whose checkout fails, first on PATH.
      val bin = t / "bin"
      val realGit = Proc.exact(env).which("git", os.pwd).get
      os.write(
        bin / "git",
        s"#!/bin/sh\nfor a; do [ \"$$a\" = checkout ] && exit 1; done\nexec $realGit \"$$@\"\n",
        createFolders = true,
        perms = "rwxr-xr-x"
      )
      val (failed, message) = prepared(root, build, remote + ("PATH" -> s"$bin:${env("PATH")}"))
      assert(!failed)
      assertEquals(count(message, "could not check out fake-devkit"), 1, message)
    }

  test("exclude_docs reads the block's patterns and stops at the next key"):
    assertEquals(
      excludeDocs(
        "site_name: x\nexclude_docs: |\n  benchmarks/\n  # a comment\n\n  *.tmp  # trailing\ntheme:\n  name: material\n"
      ),
      Vector("benchmarks/", "*.tmp")
    )

  test("brokenLinks skips anchors, root-absolute links and query-only targets"):
    val build = os.temp.dir(prefix = "links")
    try
      os.write(build / "a.md", "")
      val listing = Seq("a.md", "#top", "/abs", "?q", "a.md#x", "gone.md?y", "a.md/")
        .map(l => s"$build/p.md\t$l")
        .mkString("\n")
      assertEquals(
        brokenLinks(listing, build),
        Vector(
          "prepare-docs: p.md: broken relative link: gone.md?y",
          "prepare-docs: p.md: broken relative link: a.md/"
        )
      )
    finally os.remove.all(build)
