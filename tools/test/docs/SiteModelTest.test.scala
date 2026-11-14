package marola.tools.docs

import marola.tools.shared.Sha

class SiteModelTest extends munit.FunSuite:
  import SiteModel.*

  private val sha = "a" * 40
  private def commit(hex: String) = Sha.parse(hex).fold(fail(_), identity)

  test("a remote template replaces <repo>, an empty variable takes the default"):
    assertEquals(RemoteTemplate.MarolaDev.url("x"), "https://github.com/marola-dev/x")
    assertEquals(
      RemoteTemplate.from(Some("/r/<repo>.git"), RemoteTemplate.MarolaDev).url("a"),
      "/r/a.git"
    )
    assertEquals(
      RemoteTemplate.from(Some(""), RemoteTemplate.MarolaDev).url("a"),
      "https://github.com/marola-dev/a"
    )

  test("the git environment drops a hook's repo pointers and never prompts"):
    val env = Map("GIT_DIR" -> "/x", "GIT_WORK_TREE" -> "/y", "HOME" -> "/h")
    assertEquals(isolatedGitEnv(env), Map("HOME" -> "/h", "GIT_TERMINAL_PROMPT" -> "0"))

  test("the configs read their variables once, defaults for unset or empty ones"):
    val prepare = PrepareConfig.from(
      Map("GIT_DIR" -> "/x", "DOCS_SOURCE_REMOTE" -> "/s/<repo>", "API_DOCS_REMOTE" -> "/a/<repo>")
    )
    assertEquals(prepare.sourceRemote.url("r"), "/s/r")
    assertEquals(prepare.apiDocs.remote.url("r"), "/a/r")
    assert(!prepare.gitEnv.contains("GIT_DIR"))
    assertEquals(ApiDocsConfig.from(Map.empty).remote.url("r"), "https://github.com/marola-dev/r")
    assertEquals(MkdocsConfig.from(Map.empty, os.pwd).runtimes, Vector("podman", "docker"))
    assertEquals(
      MkdocsConfig.from(Map("CONTAINER_RUNTIME" -> " docker "), os.pwd).runtimes,
      Vector("docker")
    )
    assertEquals(MkdocsConfig.from(Map("DOCS_SRC" -> ""), os.pwd).docsSrc, None)
    assertEquals(MkdocsConfig.from(Map("DOCS_SRC" -> ".tmp/d"), os.pwd).docsSrc, Some(".tmp/d"))

  test("a failure that a tool already reported says nothing"):
    assertEquals(Failure.Reported.message, "")
    assertEquals(Failure.Said("x").message, "x")

  test("the landing is the README, and docs/index.md beside it is refused"):
    assertEquals(Landing.check(hasReadme = true, hasDocsIndex = false), None)
    assertEquals(Landing.check(hasReadme = false, hasDocsIndex = true), Some(Landing.NoReadme))
    assertEquals(Landing.check(hasReadme = true, hasDocsIndex = true), Some(Landing.DocsIndexToo))
    assert(Landing.DocsIndexToo.problem("r").startsWith("prepare-docs: r has both README.md"))

  test("built-from line and build.json name each commit"):
    assertEquals(
      builtFrom("r", commit(sha)),
      s"\n_Built from [marola-dev/r@aaaaaaa](https://github.com/marola-dev/r/tree/$sha)._\n"
    )
    val app = Checkout(Repo("app", RepoSource.Submodule), os.pwd, commit("b" * 40))
    assertEquals(
      buildJson(commit(sha), Seq(app)),
      s"{\n  \"marola\": \"$sha\",\n  \"app\": \"${"b" * 40}\"\n}\n"
    )

  test("a listed link: what is checked, where it is looked for, how it is reported"):
    val listed = ListedLink.parse("/b/d/p.md\t\tx.md#top\t")
    assertEquals(listed, ListedLink("/b/d/p.md", "x.md#top"))
    assertEquals(listed.target, "x.md")
    assertEquals(listed.candidate, "/b/d/x.md")
    assert(listed.checked)
    assert(!ListedLink("p.md", "?q").checked)
    assertEquals(ListedLink("p.md", "x").candidate, "./x")
    assertEquals(listed.broken("/b"), "prepare-docs: d/p.md: broken relative link: x.md#top")

  test("a mount's paths: the README is the landing, the umbrella's MIPs go to 6-MIPs"):
    val umbrella = Context("marola", sha, umbrella = true)
    assertEquals(umbrella.outPath("README.md"), "index.md")
    assertEquals(umbrella.outPath("docs/MIPs/MIP-1.md"), "6-MIPs/MIP-1.md")
    assertEquals(Context("r", sha).outPath("docs/MIPs/MIP-1.md"), "MIPs/MIP-1.md")
    assertEquals(umbrella.siteHref("docs/a/b.md", "index.md"), "../index.md")
    assertEquals(umbrella.siteHref("README.md", "c/d.md"), "c/d.md")

  test("a fence closes on a bare run at least as long as its opening"):
    assert(Fence('`', 3).closedBy("  ````  \n"))
    assert(!Fence('`', 4).closedBy("```\n"))
    assert(!Fence('`', 3).closedBy("``` md\n"))
    assert(!Fence('~', 3).closedBy("```\n"))

  test("a link splits at its first # and then its first ?"):
    val link = Link.parse("a.md?x=1#h?y")
    assertEquals(link, Link("a.md", "?x=1", hasAnchor = true, anchor = "h?y"))
    assertEquals(link.suffix, "?x=1#h?y")
    assertEquals(link.after("t"), "?x=1#t")
    assertEquals(Link.parse("a.md").suffix, "")

  test("a link error is reported against its page and link"):
    assertEquals(LinkError.LeavesRepo.line("README.md", "../x"), "README.md: ../x: leaves the repo")
    assertEquals(LinkError.MissingAt("abc").reason, "missing at abc")

  test("where a relative link points, under Appendix A"):
    val tree = RepoTree(isDir = Set("docs/guide"), isFile = Set("docs/guide/index.md"))
    val ctx = Context("r", sha, excludeDocs = Seq("benchmarks/"))
    def of(written: String, source: String = "README.md", image: Boolean = false) =
      LinkTarget.of(Link.parse(written), source, image, ctx, tree)
    assertEquals(of("/abs"), LinkTarget.Refused(LinkError.RootAbsolute))
    assertEquals(of("../x"), LinkTarget.Refused(LinkError.LeavesRepo))
    assertEquals(of("#top"), LinkTarget.Fragment(readmeAnchor = true))
    assertEquals(of("#top", "docs/a.md"), LinkTarget.Fragment(readmeAnchor = false))
    assertEquals(of("AGENTS.md"), LinkTarget.GitHub("r", sha, None, "AGENTS.md"))
    assertEquals(of("img.png"), LinkTarget.Refused(LinkError.ImageOutsideDocs))
    assertEquals(
      of("../README.md#a", "docs/a.md"),
      LinkTarget.Site("index.md", readmeAnchor = true)
    )
    assertEquals(of("docs/index.md"), LinkTarget.Refused(LinkError.DocsIndexBanned))
    assertEquals(of("docs/"), LinkTarget.Site("index.md"))
    assertEquals(of("docs/guide"), LinkTarget.Site("guide/index.md"))
    assertEquals(of("docs/empty/"), LinkTarget.Refused(LinkError.DirectoryWithoutIndex))
    assertEquals(of("docs/adr/"), LinkTarget.Site("adr/index.md"))
    assertEquals(
      of("docs/benchmarks/run.md"),
      LinkTarget.GitHub("r", sha, None, "docs/benchmarks/run.md")
    )
    assertEquals(of("docs/benchmarks/x.png"), LinkTarget.Refused(LinkError.ImageExcluded))

  test("an umbrella link into a submodule: its published docs on the site, the rest on GitHub"):
    val tree = RepoTree(isDir = Set("app/docs/sub"), isFile = _ => false)
    val ctx = Context("marola", sha, umbrella = true, submoduleShas = Map("app" -> "b" * 40))
    def of(written: String) = LinkTarget.of(Link.parse(written), "README.md", false, ctx, tree)
    assertEquals(of("app/README.md"), LinkTarget.Site("5-Repos/app/index.md"))
    assertEquals(of("app/docs/x.md"), LinkTarget.Site("5-Repos/app/x.md"))
    assertEquals(of("app/docs/sub"), LinkTarget.Site("5-Repos/app/sub/index.md"))
    assertEquals(of("app/build.sbt"), LinkTarget.GitHub("app", "b" * 40, Some("app"), "build.sbt"))
    assertEquals(of("app/logo.svg"), LinkTarget.Refused(LinkError.ImageOutsideDocs))

  test("an ADR row: its number from the name, its title and status from the page"):
    assertEquals(AdrRow.number("0007-x.md"), Right("0007"))
    assertEquals(AdrRow.number("x.md"), Left("docs/adr/x.md: not NNNN-<slug>.md"))
    assertEquals(
      AdrRow.read("0007-x.md", "0007", "# ADR-0007: Use a|b\n\n| **Status** | Accepted |\n"),
      Right(AdrRow("0007", "0007-x.md", "Use a|b", "Accepted"))
    )
    assertEquals(
      AdrRow.read("0007-x.md", "0007", "# ADR-0008: wrong\n"),
      Left(
        Vector(
          "docs/adr/0007-x.md: no \"# ADR-0007: <decision>\" heading",
          "docs/adr/0007-x.md: no | **Status** | row"
        )
      )
    )
    assertEquals(AdrRow("1", "p.md", "a|b", "s").line, "| [1](p.md) | a\\|b | s |")

  test("the ADR index page, with or without rows"):
    assertEquals(adrIndexPage(Nil), "# Architecture decisions\n\n_None yet._\n")
    assertEquals(
      adrIndexPage(Seq(AdrRow("1", "p.md", "t", "s"))),
      "# Architecture decisions\n\n| ADR | Decision | Status |\n|---|---|---|\n| [1](p.md) | t | s |\n"
    )

  test("the mkdocs container's exit: 0 passes, anything else fails with its low byte"):
    assertEquals(Stop.fromContainer(Some("0"), Mode.Build), Right(()))
    assertEquals(
      Stop.fromContainer(Some("257"), Mode.Serve),
      Left(Stop(1, "mkdocs: serve failed (exit 257) — see the log above"))
    )
    assertEquals(Stop.fromContainer(None, Mode.Build).left.map(_.code), Left(1))
    assertEquals(Stop.fromContainer(Some("x"), Mode.Build).left.map(_.code), Left(2))

  test("an api-docs fetch: its notice and its exit code"):
    assertEquals(ApiDocsFetch.NoBranch.exitCode, 0)
    assertEquals(
      ApiDocsFetch.NoBranch.notice("r"),
      Some("fetch-api-docs: r has no api-docs branch yet — skipping")
    )
    assertEquals(ApiDocsFetch.Failed(3).exitCode, 3)
    assertEquals(ApiDocsFetch.Failed(3).notice("r"), None)

  test("a move forwards a path under its prefix, and the longest prefix comes first"):
    assertEquals(Move("/repos/", "/5-Repos/").forward("/repos/a/"), Some("/5-Repos/a/"))
    assertEquals(Move("/repos/", "/5-Repos/").forward("/other/"), None)
    assertEquals(
      Move.longestFirst(Seq(Move("/a/", "/x/"), Move("/a/b/", "/y/"))).map(_.from),
      Vector("/a/b/", "/a/")
    )

  test("site-links-check's verdict: an empty old sitemap fails, a clean run summarises"):
    val clean = SiteLinksVerdict(LinkCheck(3, Vector.empty), None)
    assert(clean.ok)
    assertEquals(clean.errors, Vector.empty)
    assertEquals(clean.summary, "site_links_check: ok (3 href(s) checked)")
    val withOld = SiteLinksVerdict(
      LinkCheck(3, Vector.empty),
      Some(OldSitemap("s.xml", LinkCheck(5, Vector.empty)))
    )
    assertEquals(withOld.summary, "site_links_check: ok (3 href(s), 5 old url(s) checked)")
    val empty = SiteLinksVerdict(
      LinkCheck(0, Vector("a: u")),
      Some(OldSitemap("s.xml", LinkCheck(0, Vector.empty)))
    )
    assert(!empty.ok)
    assertEquals(empty.errors.head, "broken href: a: u")
    assert(empty.errors(1).startsWith("site_links_check: s.xml has no <loc> at all"))
    assertEquals(empty.errors.last, "site_links_check: 1 broken href(s), 0 dropped url(s)")

  test("strip tallies add up and summarise"):
    assertEquals(StripTally.Zero + StripTally(2, 3) + StripTally(1, 0), StripTally(3, 3))
    assertEquals(StripTally(3, 3).summary, "stripped 3 external <script> tags across 3 html files")
    assert(ApiDocsPage.hasExternalScript("""<script src="https://x/a.js"></script>"""))
    assert(!ApiDocsPage.hasExternalScript("""<script src="a.js"></script>"""))

  test("repos.yml: a name with an empty value opens no entry"):
    assertEquals(
      ReposManifest.parseText("- name:\n  source: flake-lock\n", "m.yml"),
      Left("repos_manifest: m.yml:2: source before any `- name:`")
    )
