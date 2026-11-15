package marola.tools.ci

import marola.tools.shared.{Invocation, Jq}

class GhBillingTest extends munit.FunSuite:
  import GhBilling.*

  private val fixture =
    os.read(Invocation.current().repoRoot / "tools" / "test" / "fixtures" / "gh-billing-usage.json")

  // The table for the fixture with --from-file.
  private val fixtureTable =
    """PRODUCT   SKU                 QUANTITY  UNIT           GROSS_USD  DISCOUNT_USD  NET_USD
      |actions   Actions Linux       2000      minutes        16         12            4
      |packages  Shared Storage      3         GigabyteHours  0.75       0             0.75
      |copilot   Copilot Individual  1         Seats          0          0             0
      |
      |total: $4.75 net (gross minus any plan-included allowance already applied — a $0.00 row means the SKU is fully covered by the plan, not free by nature)
      |GHCR storage: public packages are free, so nothing prunes old -<sha> tags (MIP-0065 §4.4; docs/1-Using-marola/DOCKER.md).
      |""".stripMargin

  test("shapes the fixture: total $4.75 over three product/SKU rows"):
    val usage = shape(fixture).toOption.get
    usage match
      case Usage.Report(rows, total) =>
        assertEquals(Jq.money(total), "4.75")
        assertEquals(rows.length, 3)
      case Usage.Empty => fail("the fixture has usage")
    assertEquals(usage.render(None), fixtureTable)
    assertEquals(fromText("H\n\n", fixture), Outcome("H\n\n" + fixtureTable, "", 0))

  test("the plan's included minutes"):
    val usage = shape(fixture).toOption.get
    assert(
      usage
        .render(Some(Plan.parse("free")))
        .contains(
          "Actions minutes: 2000 of 2000 included this month (100%, plan: free) — Linux-minute"
        )
    )
    assert(
      usage
        .render(Some(Plan.parse("pro")))
        .contains(
          "Actions minutes: 2000 of 3000 included this month (67%, plan: pro)"
        )
    )
    assert(
      usage
        .render(Some(Plan.parse("team")))
        .contains(
          "Actions minutes used: 2000 (plan 'team' isn't in this script's known table"
        )
    )

  test("an empty month"):
    assertEquals(
      shape("""{"usageItems": []}""").map(_.render(Some(Plan.Pro))),
      Right("no usage recorded for this period yet\n")
    )
    assertEquals(shape("{}"), Right(Usage.Empty))
    assertEquals(fromText("H\n", "{bad").code, 5)

  test("numbers as jq prints them"):
    assertEquals(Jq.number(2000), "2000")
    assertEquals(Jq.number(0.1 + 0.2), "0.30000000000000004")
    assertEquals(Jq.number(1e-5), "1e-05")
    assertEquals(Jq.number(0.0001), "0.0001")
    assertEquals(Jq.number(1e15), "1000000000000000")
    assertEquals(Jq.number(1e16), "1e+16")
    assertEquals(Jq.number(1.5e20), "1.5e+20")
    assertEquals(Jq.number(2.5e-7), "2.5e-07")
    assertEquals(Jq.number(123456789012345678.0), "123456789012345680")
    assertEquals(Jq.money(1.005), "1")
    assertEquals(Jq.money(2.675), "2.68")
    assertEquals(Jq.money(-1.5), "-1.5")
    assertEquals(Jq.money(0.125), "0.13")

  test("column -t keeps empty cells and pads all but the last column"):
    assertEquals(
      Usage.columns(Seq(Seq("a", "", "c"), Seq("xx", "yyy", "z"))),
      Seq("a        c", "xx  yyy  z")
    )
    assertEquals(
      Usage.columns(Seq(Seq("a", "b", ""), Seq("xx", "yyy", "z"))),
      Seq("a   b    ", "xx  yyy  z")
    )

  // Verbatim gh 2.99 `gh auth status` samples, from cli/cli's status_test.go ('user' appended where
  // marked, since none of gh's own fixtures carries it).
  private val withUser = """github.com
    |  ✓ Logged in to github.com account monalisa (/home/m/.config/gh/hosts.yml)
    |  - Active account: true
    |  - Git operations protocol: https
    |  - Token: ghp_******
    |  - Token scopes: 'gist', 'read:org', 'repo', 'user'""".stripMargin
  private val withoutUser = """github.com
    |  ✓ Logged in to github.com account monalisa (GH_CONFIG_DIR/hosts.yml)
    |  - Active account: true
    |  - Git operations protocol: https
    |  - Token: gho_******
    |  - Token scopes: 'repo', 'read:org'""".stripMargin
  private val scopesNone = """ghe.io
    |  ✓ Logged in to ghe.io account monalisa-ghe (GH_CONFIG_DIR/hosts.yml)
    |  - Active account: true
    |  - Git operations protocol: ssh
    |  - Token: gho_******
    |  - Token scopes: none""".stripMargin
  private val envToken = """github.com
    |  ✓ Logged in to github.com account monalisa (GH_TOKEN)
    |  - Active account: true
    |  - Git operations protocol: https
    |  - Token: gho_******
    |  - Token scopes: none""".stripMargin
  private val fineGrained = """github.com
    |  ✓ Logged in to github.com account monalisa (GH_CONFIG_DIR/hosts.yml)
    |  - Active account: true
    |  - Git operations protocol: https
    |  - Token: github_pat_**********""".stripMargin
  private val multiAccount = """github.com
    |  ✓ Logged in to github.com account monalisa-2 (GH_CONFIG_DIR/hosts.yml)
    |  - Active account: true
    |  - Git operations protocol: https
    |  - Token: gho_******
    |  - Token scopes: 'repo', 'read:org', 'user'
    |
    |  ✓ Logged in to github.com account monalisa (GH_CONFIG_DIR/hosts.yml)
    |  - Active account: false
    |  - Git operations protocol: https
    |  - Token: gho_******
    |  - Token scopes: 'repo', 'read:org', 'project:read'""".stripMargin

  test("scope-line parser"):
    assert(hasUserScope(withUser), "quoted 'user' among several scopes")
    assert(!hasUserScope(withoutUser), "no 'user' scope")
    assert(!hasUserScope(scopesNone), "'Token scopes: none'")
    assert(!hasUserScope("  - Token scopes: 'read:user', 'repo'"), "'read:user' is not 'user'")
    assert(!hasUserScope(envToken), "GH_TOKEN layout with an empty scope set")
    assert(!hasUserScope(fineGrained), "fine-grained PAT: no Token scopes: line")
    assertEquals(foundScopes(fineGrained), "")
    assert(missingUserScopeMessage(fineGrained).contains("never accepts"))
    assert(missingUserScopeMessage(withoutUser).contains("gh auth status reports: repo, read:org"))
    assert(hasUserScope(multiAccount), "the active (first) github.com account's scopes")

  test("the active account only, when it lacks the scope"):
    val swapped = multiAccount
      .replace("'repo', 'read:org', 'user'", "'repo'")
      .replace("'project:read'", "'user'")
    assert(!hasUserScope(swapped))
    assertEquals(foundScopes(swapped), "repo")

  test("GH_TOKEN/GITHUB_TOKEN in the environment"):
    val gh = envTokenMessage(Map("GH_TOKEN" -> "github_pat_x", "GITHUB_TOKEN" -> "")).get
    assert(gh.contains("GH_TOKEN is set") && gh.contains("env -u GH_TOKEN just gh-billing"), gh)
    val github = envTokenMessage(Map("GH_TOKEN" -> "", "GITHUB_TOKEN" -> "github_pat_y")).get
    assert(
      github.contains("GITHUB_TOKEN is set") && github.contains(
        "env -u GITHUB_TOKEN just gh-billing"
      ),
      github
    )
    assertEquals(envTokenMessage(Map("GH_TOKEN" -> "", "GITHUB_TOKEN" -> "")), None)

  test("error classification"):
    val scope404 =
      "gh: Not Found (HTTP 404)\ngh: This API operation needs the \"user\" scope. To request it, run:  gh auth refresh -h github.com -s user"
    assertEquals(classifyError(scope404), Some(ScopeFix))
    assert(classifyError("gh: Not Found (HTTP 404)").exists(_.contains("closed down 2025-09-26")))
    assert(
      classifyError("HTTP 403: Resource not accessible by integration").exists(
        _.contains("permission problem")
      )
    )
    assertEquals(classifyError("boom"), None)

  private def config(noScopeCheck: Boolean, env: Map[String, String]) =
    BillingConfig(BillingPeriod("2026", "8"), BillingSource.Live, noScopeCheck, env, os.pwd)

  /** A fake `gh`: answers by its arguments, records every call. */
  private class FakeGh(answers: PartialFunction[Seq[String], GhResult])
      extends (Seq[String] => GhResult):
    val calls = collection.mutable.ArrayBuffer.empty[Seq[String]]
    def apply(args: Seq[String]): GhResult =
      calls += args
      answers.applyOrElse(args, _ => GhResult(1, "", "unexpected\n"))

  private def loggedIn(usage: GhResult) = FakeGh {
    case Seq("auth", "status")                               => GhResult(0, "", withUser + "\n")
    case Seq("api", "user", "--jq", ".login")                => GhResult(0, "monalisa\n", "")
    case Seq("api", "user", "--jq", _)                       => GhResult(0, "pro\n", "")
    case Seq("api", path) if path.contains("/billing/usage") => usage
  }

  test("live: the report, with the plan's minutes"):
    val gh = loggedIn(GhResult(0, fixture, ""))
    val outcome = live(config(noScopeCheck = false, Map.empty), gh)
    assertEquals(outcome.code, 0)
    assert(
      outcome.out.startsWith("GitHub billing usage — monalisa, 2026-08\n\nPRODUCT"),
      outcome.out
    )
    assert(
      outcome.out.contains("Actions minutes: 2000 of 3000 included this month (67%, plan: pro)")
    )
    assert(
      gh.calls.contains(Seq("api", "/users/monalisa/settings/billing/usage?year=2026&month=8"))
    )

  test("live: a set token stops before gh is asked anything"):
    val gh = loggedIn(GhResult(0, fixture, ""))
    val outcome =
      live(config(noScopeCheck = true, Map("GITHUB_TOKEN" -> "x")), gh)
    assertEquals(outcome.code, 1)
    assert(outcome.err.contains("GITHUB_TOKEN is set"))
    assertEquals(gh.calls.toSeq, Nil)

  test("live: a missing scope stops before the API call, unless --no-scope-check"):
    val noScope = FakeGh {
      case Seq("auth", "status")                => GhResult(0, withoutUser + "\n", "")
      case Seq("api", "user", "--jq", ".login") => GhResult(0, "monalisa\n", "")
      case Seq("api", path) if path.contains("/billing/usage") =>
        GhResult(
          1,
          "",
          "gh: Not Found (HTTP 404)\ngh: This API operation needs the \"user\" scope.\n"
        )
    }
    val stopped = live(config(noScopeCheck = false, Map.empty), noScope)
    assertEquals(stopped, Outcome("", s"$ScopeFix\n(gh auth status reports: repo, read:org)\n", 1))
    val called = live(config(noScopeCheck = true, Map.empty), noScope)
    assertEquals(called.code, 1)
    assertEquals(
      called.err,
      "consolidated usage report failed (raw error below):\ngh: Not Found (HTTP 404)\ngh: This API operation needs the \"user\" scope.\n\n" + ScopeFix + "\n"
    )

  test("live: not logged in"):
    val outcome = live(
      config(noScopeCheck = false, Map.empty),
      FakeGh { case Seq("auth", "status") => GhResult(1, "", "no\n") }
    )
    assert(outcome.err.startsWith("gh is not logged in"))

  test("dry run falls back to a placeholder login"):
    assertEquals(
      plannedCall(BillingPeriod("2026", "8"), FakeGh(PartialFunction.empty)).out,
      "gh-billing --dry-run: would call:\n  gh api \"/users/{username}/settings/billing/usage?year=2026&month=8\"\n"
    )

  test("the source decides what a run reads"):
    val base = config(noScopeCheck = false, Map.empty)
    val noGh = FakeGh(PartialFunction.empty)
    assertEquals(
      execute(base.copy(source = BillingSource.Stdin), noGh, () => fixture),
      Outcome("GitHub billing usage — from stdin\n\n" + fixtureTable, "", 0)
    )
    assertEquals(
      execute(base.copy(source = BillingSource.File("no-such.json")), noGh, () => ""),
      Outcome("", "gh-billing: file not found: no-such.json\n", 1)
    )
    val dir = os.temp.dir()
    os.write(dir / "u.json", fixture)
    assertEquals(
      execute(base.copy(source = BillingSource.File("u.json"), cwd = dir), noGh, () => "").out,
      "GitHub billing usage — from u.json\n\n" + fixtureTable
    )
    assert(
      execute(base.copy(source = BillingSource.DryRun), noGh, () => "").out.contains("{username}")
    )
    assertEquals(noGh.calls.toSeq, Seq(Seq("api", "user", "--jq", ".login")))
