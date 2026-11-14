class DocLinksTest extends munit.FunSuite:
  import DocLinks.*

  private def refused(source: String, link: String, reason: String) = Vector(
    s"$source: $link: $reason"
  )
  private def ok(s: String) = (s, Vector.empty[String])
  private def bad(s: String, errors: Vector[String]) = (s, errors)

  private def touch(root: os.Path, files: String*): Unit =
    for f <- files do os.write.over(root / os.RelPath(f), "# t\n", createFolders = true)

  test("rewrites a repo"):
    val sha = "a" * 40
    val gh = s"https://github.com/marola-dev/marola-ml/blob/$sha"
    val tree = s"https://github.com/marola-dev/marola-ml/tree/$sha"
    val tmp = os.temp.dir()
    val repo = tmp / "marola-ml"
    touch(
      repo,
      "docs/x.md",
      "docs/sub/index.md",
      "docs/guide/page.md",
      "docs/img/a.png",
      "docs/benchmarks/2026-09-05.md",
      "docs/benchmarks/plot.png",
      "AGENTS.md",
      "scripts/x.sh",
      "knowledge/tides.md"
    )
    os.makeDir(repo / "docs" / "empty")
    os.write(repo / "README.md", "# Repo\n\n## 🌊 Run it!\n\n```\n## Not a heading\n```\n")
    val ctx = Context("marola-ml", sha, excludeDocs = Seq("benchmarks/", "superpowers/"))
    def page(text: String, source: String) = rewrite(text, source, repo, ctx)
    def readme(text: String) = page(text, "README.md")

    assertEquals(
      readme("[a](docs/x.md) [b](./docs/x.md) [c](https://x.example/docs/x.md) [d](mailto:a@b)"),
      ok("[a](x.md) [b](x.md) [c](https://x.example/docs/x.md) [d](mailto:a@b)")
    )
    assertEquals(readme("[a](docs/x.md#sec)"), ok("[a](x.md#sec)"))
    assertEquals(readme("[full docs](docs/) [d](docs)"), ok("[full docs](index.md) [d](index.md)"))
    assertEquals(
      readme("[i](docs/index.md)"),
      bad(
        "[i](docs/index.md)",
        refused(
          "README.md",
          "docs/index.md",
          "docs/index.md is banned (MIP-0074 D1); link docs/ for the landing"
        )
      )
    )
    assertEquals(
      readme("[s](docs/sub/) [adr](docs/adr/)"),
      ok("[s](sub/index.md) [adr](adr/index.md)")
    )
    assertEquals(
      readme("[e](docs/empty/)"),
      bad("[e](docs/empty/)", refused("README.md", "docs/empty/", "a directory with no index.md"))
    )
    assertEquals(
      readme("""![l](docs/img/a.png) <img alt="l" src="docs/img/a.png">"""),
      ok("""![l](img/a.png) <img alt="l" src="img/a.png">""")
    )
    val outside = "an image outside docs/; move it under docs/"
    assertEquals(
      readme("![l](assets/logo.png)"),
      bad("![l](assets/logo.png)", refused("README.md", "assets/logo.png", outside))
    )
    assertEquals(
      readme("""<img src="logo.svg">"""),
      bad("""<img src="logo.svg">""", refused("README.md", "logo.svg", outside))
    )
    val offSite = "an image under exclude_docs; move it to a published docs/ path"
    assertEquals(
      readme("![p](docs/benchmarks/plot.png)"),
      bad(
        "![p](docs/benchmarks/plot.png)",
        refused("README.md", "docs/benchmarks/plot.png", offSite)
      )
    )
    assertEquals(
      readme("""<img src="docs/benchmarks/plot.png">"""),
      bad(
        """<img src="docs/benchmarks/plot.png">""",
        refused("README.md", "docs/benchmarks/plot.png", offSite)
      )
    )
    assertEquals(
      page("[r](../README.md) [a](../README.md#repo)", "docs/x.md"),
      ok("[r](index.md) [a](index.md#repo)")
    )
    assertEquals(page("[r](../../README.md)", "docs/guide/page.md"), ok("[r](../index.md)"))
    assertEquals(page("[r](../README.md#-run-it)", "docs/x.md"), ok("[r](index.md#run-it)"))
    assertEquals(readme("[r](#-run-it) [s](#run-it)"), ok("[r](#run-it) [s](#run-it)"))
    assertEquals(
      readme("[n](#not-a-heading)"),
      bad(
        "[n](#not-a-heading)",
        refused("README.md", "#not-a-heading", "no README heading has the anchor #not-a-heading")
      )
    )
    val api = "/api/scala/core/marola.html"
    assertEquals(
      page(s"[api]($api)", "docs/x.md"),
      bad(s"[api]($api)", refused("docs/x.md", api, "root-absolute; link the page relatively"))
    )
    assertEquals(
      readme("[o](../other/x.md)"),
      bad("[o](../other/x.md)", refused("README.md", "../other/x.md", "leaves the repo"))
    )
    assertEquals(
      page("[o](../../x.md)", "docs/x.md"),
      bad("[o](../../x.md)", refused("docs/x.md", "../../x.md", "leaves the repo"))
    )
    assertEquals(
      readme("[x]: docs/x.md \"Title\"\n<a href=\"docs/x.md#a\">x</a> <a href='docs/'>d</a>\n"),
      ok("[x]: x.md \"Title\"\n<a href=\"x.md#a\">x</a> <a href='index.md'>d</a>\n")
    )
    val proseAttr = "Set href=\"/abs\" in the tag.\n"
    assertEquals(readme(proseAttr), ok(proseAttr))
    val fencedText = "```md\n[a](docs/x.md)\n```\n~~~\n![i](/abs.png)\n~~~\n"
    assertEquals(
      readme(s"`[a](docs/x.md)` [`b`](docs/x.md) ``[c](/abs)``\n$fencedText"),
      ok(s"`[a](docs/x.md)` [`b`](x.md) ``[c](/abs)``\n$fencedText")
    )
    val stray = "Type ` to start.\n\n[a](docs/x.md) and [b](/abs)\n\nlater ` here\n"
    val (text, errors) = readme(stray)
    assertEquals(text, stray.replace("(docs/x.md)", "(x.md)"))
    assertEquals(errors.length, 1)
    // docs-lint closes a fence only on a bare fence line; ```bash inside it is still code.
    val nested = "```\n```bash\n[a](/abs)\n```\n"
    assertEquals(readme(nested), ok(nested))

    assertEquals(
      readme("[b](docs/benchmarks/2026-09-05.md#r) [d](docs/benchmarks/)"),
      ok(s"[b]($gh/docs/benchmarks/2026-09-05.md#r) [d]($tree/docs/benchmarks)")
    )
    assertEquals(
      page("[b](benchmarks/2026-09-05.md)", "docs/x.md"),
      ok(s"[b]($gh/docs/benchmarks/2026-09-05.md)")
    )
    assertEquals(
      readme("[a](AGENTS.md) [s](./scripts/x.sh#L3) [p](AGENTS.md?plain=1#L5)"),
      ok(s"[a]($gh/AGENTS.md) [s]($gh/scripts/x.sh#L3) [p]($gh/AGENTS.md?plain=1#L5)")
    )
    assertEquals(
      page("""[a](../AGENTS.md) <a href="../scripts/x.sh">s</a>""", "docs/x.md"),
      ok(s"""[a]($gh/AGENTS.md) <a href="$gh/scripts/x.sh">s</a>""")
    )
    assertEquals(
      readme("[k](knowledge/) [k2](knowledge) [s](scripts/)"),
      ok(s"[k]($tree/knowledge) [k2]($tree/knowledge) [s]($tree/scripts)")
    )
    val own = "https://github.com/marola-dev/marola-ml"
    assertEquals(
      readme(
        s"[a]($own/blob/main/AGENTS.md#x) [t]($own/tree/feature-x/knowledge)\n[r]: $own/blob/main/scripts/x.sh?plain=1\n"
      ),
      ok(s"[a]($gh/AGENTS.md#x) [t]($tree/knowledge)\n[r]: $gh/scripts/x.sh?plain=1\n")
    )
    assertEquals(
      readme(s"[t]($own/tree/mip-0074/x/knowledge) [a]($own/blob/mip-0074/k-y/AGENTS.md#L1)"),
      ok(s"[t]($tree/knowledge) [a]($gh/AGENTS.md#L1)")
    )
    for pinned <- Seq("7ba0458", "v0.2.4") do
      val l = s"[a]($own/blob/$pinned/.claude/hooks/stop-gate.sh#L23)"
      assertEquals(readme(l), ok(l))
    assertEquals(
      readme(s"[t]($own/tree/main/) [k]($own/tree/main/knowledge/)"),
      ok(s"[t]($tree/) [k]($tree/knowledge/)")
    )
    val others =
      s"[a](https://github.com/marola-dev/marola-app/blob/main/README.md) [b](https://github.com/marola-dev/marola-ml-old/blob/main/AGENTS.md) [c]($own/blob/${"c" * 40}/AGENTS.md)"
    assertEquals(readme(others), ok(others))
    val absolute =
      s"[d](https://docs.marola.dev/5-Repos/marola-ml/) [m](mailto:a@b) [h](http://x.example/AGENTS.md) [i]($own/issues/3) [r]($own)"
    assertEquals(readme(absolute), ok(absolute))
    val missing = s"missing at $sha"
    assertEquals(
      readme("[m](scripts/nope.sh)"),
      bad("[m](scripts/nope.sh)", refused("README.md", "scripts/nope.sh", missing))
    )
    assertEquals(
      readme("[m](docs/benchmarks/nope.md)"),
      bad("[m](docs/benchmarks/nope.md)", refused("README.md", "docs/benchmarks/nope.md", missing))
    )
    val l = s"$own/blob/main/nope.md"
    assertEquals(readme(s"[m]($l)"), bad(s"[m]($l)", refused("README.md", l, missing)))

    // mount: the landing, docs beside it, exclude_docs skipped, refused links reported.
    val out = tmp / "out"
    assertEquals(mount(repo, out, ctx.copy(excludeDocs = Seq("benchmarks/"))), Vector.empty)
    assertEquals(os.read.lines(out / "index.md").headOption, Some("# Repo"))
    assert(os.isFile(out / "img" / "a.png") && os.isFile(out / "sub" / "index.md"))
    assert(!os.exists(out / "benchmarks"))
    os.write(repo / "docs" / "bad.md", "[x](/abs)\n")
    assertEquals(
      mount(repo, out, ctx.copy(excludeDocs = Nil)),
      Vector("docs/bad.md: /abs: root-absolute; link the page relatively")
    )

  test("rewrites the umbrella"):
    val (sha, appSha) = ("a" * 40, "b" * 40)
    val umbrella = os.temp.dir() / "marola"
    touch(
      umbrella,
      "docs/PHASES.md",
      "docs/3-Working/x.md",
      "docs/MIPs/MIP-0001.md",
      "docs/MIPs/MIP-0002.md",
      "marola-app/README.md",
      "marola-app/docs/x.md",
      "marola-app/core/A.scala",
      "marola-app/core/l.svg",
      "marola-app/assets/l.png"
    )
    val ctx = Context("marola", sha, umbrella = true, submoduleShas = Map("marola-app" -> appSha))
    def page(text: String, source: String) = rewrite(text, source, umbrella, ctx)
    assertEquals(
      page(
        "[a](marola-app/README.md) [b](marola-app/docs/x.md#s) [c](marola-app/docs/)",
        "README.md"
      ),
      ok(
        "[a](5-Repos/marola-app/index.md) [b](5-Repos/marola-app/x.md#s) [c](5-Repos/marola-app/index.md)"
      )
    )
    assertEquals(
      page("[b](../../marola-app/docs/x.md)", "docs/3-Working/x.md"),
      ok("[b](../5-Repos/marola-app/x.md)")
    )
    val app = "https://github.com/marola-dev/marola-app"
    assertEquals(
      page("[c](marola-app/core/A.scala#L2) [d](marola-app/core/)", "README.md"),
      ok(s"[c]($app/blob/$appSha/core/A.scala#L2) [d]($app/tree/$appSha/core)")
    )
    assertEquals(
      page("[m](marola-app/core/Nope.scala)", "README.md"),
      bad(
        "[m](marola-app/core/Nope.scala)",
        refused("README.md", "marola-app/core/Nope.scala", s"missing at $appSha")
      )
    )
    val outside = "an image outside docs/; move it under docs/"
    assertEquals(
      page("![l](marola-app/assets/l.png)", "README.md"),
      bad("![l](marola-app/assets/l.png)", refused("README.md", "marola-app/assets/l.png", outside))
    )
    assertEquals(
      page("""<img src="marola-app/core/l.svg">""", "README.md"),
      bad(
        """<img src="marola-app/core/l.svg">""",
        refused("README.md", "marola-app/core/l.svg", outside)
      )
    )
    assertEquals(page("[m](docs/MIPs/MIP-0001.md#a)", "README.md"), ok("[m](6-MIPs/MIP-0001.md#a)"))
    assertEquals(page("[m](MIPs/MIP-0001.md)", "docs/PHASES.md"), ok("[m](6-MIPs/MIP-0001.md)"))
    assertEquals(
      page("[p](../PHASES.md) [o](MIP-0002.md)", "docs/MIPs/MIP-0001.md"),
      ok("[p](../PHASES.md) [o](MIP-0002.md)")
    )

  test("lists links outside code"):
    assertEquals(
      links(
        "[a](x.md#s) [![i](img/a.png)](<y z.md>)\n[r]: ref.md\n`[c](span.md)` and `wrapped\n[d](span2.md)`\n\n````\n```bash\n[e](f.md)\n```\n````\n"
      ),
      Vector("ref.md", "x.md#s", "y z.md", "img/a.png")
    )

  test("adr index skips excluded"):
    val tmp = os.temp.dir()
    val adrs = tmp / "adrs"
    for n <- Seq("0001", "0002") do
      os.write(
        adrs / "docs" / "adr" / s"$n-x.md",
        s"# ADR-$n: D$n\n\n| **Status** | Accepted |\n",
        createFolders = true
      )
    os.write(adrs / "README.md", "# adrs\n")
    val out = tmp / "out" / "adrs"
    val ctx = Context("adrs", "a" * 40, excludeDocs = Seq("0002-*.md"))
    assertEquals(mount(adrs, out, ctx), Vector.empty)
    assertEquals(
      os.read.lines(out / "adr" / "index.md").lastOption,
      Some("| [0001](0001-x.md) | D0001 | Accepted |")
    )

  test("a trailing slash on a file names nothing"):
    val repo = os.temp.dir()
    touch(repo, "README.md", "AGENTS.md")
    val ctx = Context("r", "a" * 40)
    val l = "https://github.com/marola-dev/r/blob/main/AGENTS.md/"
    assertEquals(rewrite(s"[a]($l)", "README.md", repo, ctx)._2.length, 1)

  test("a lone [ before a long paragraph does not overflow the stack"):
    val text = "[ " + "word " * 40000 + "\n[a](x.md)\n"
    assertEquals(links(text), Vector("x.md"))
