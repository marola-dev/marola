package marola.tools.ci

import marola.tools.shared.Cli
import marola.tools.shared.{GitHubNames, Invocation, Util}

import mainargs.{arg, main, Flag, ParserForMethods}

// This GitHub account's metered usage (Actions minutes, Packages storage and transfer, Copilot)
// from GET /users/{username}/settings/billing/usage, grouped per product and SKU.
//
//     marola-tools gh-billing [--year YYYY] [--month M] [--from-file FILE | --json-stdin] [--dry-run] [--no-scope-check]
object GhBilling:
  // The endpoint takes classic PATs and gh's own token only, with the `user` scope
  // (https://docs.github.com/en/billing/tutorials/automate-usage-reporting). A missing scope
  // comes back as HTTP 404, not 403, so the scope is checked locally before any live call.
  val ScopeFix =
    "gh-billing: your gh token lacks the \"user\" scope this endpoint needs — run: gh auth refresh -h github.com -s user"

  // ---- `gh auth status` ----------------------------------------------------------------------

  private def isBlank(c: Char) = " \t\n\u000b\f\r".contains(c)
  private def stripChars(s: String, drop: Char => Boolean) =
    s.dropWhile(drop).reverse.dropWhile(drop).reverse

  /** The lines of `gh auth status` under the `host` header, or the whole text when it has none. gh
    * prints one unindented line per host, then one indented block per account, the active one
    * first; the first unindented or blank line ends the block, so only the active account is read.
    */
  def hostBlock(text: String, host: String): String =
    val (lines, _) = text.split("\n", -1).foldLeft((Vector.empty[String], false)) {
      case ((block, _), line) if line == host => (block, true)
      case ((block, inBlock), line)           =>
        val stillIn = inBlock && line.headOption.exists(isBlank)
        (if stillIn then block :+ line else block, stillIn)
    }
    if lines.nonEmpty then lines.mkString("\n") else text

  /** The scope list of the first "Token scopes:" line. */
  def scopes(text: String): TokenScopes =
    text.split("\n", -1).find(_.contains(TokenScopes.Prefix)) match
      case None       => TokenScopes.Absent
      case Some(line) =>
        val list = stripChars(
          line
            .substring(line.indexOf(TokenScopes.Prefix) + TokenScopes.Prefix.length)
            .replace("\r", ""),
          isBlank
        )
        val items = list.split(",", -1).map(stripChars(_, c => isBlank(c) || c == '\'')).toSeq
        if list == TokenScopes.NoneWord then TokenScopes.NoScopes
        else if items.mkString(", ").isEmpty then TokenScopes.Absent
        else TokenScopes.Listed(items)

  def hasUserScope(authStatus: String): Boolean =
    githubScopes(authStatus).has(BillingNames.Scope)

  /** What the parser read for github.com, so the failure shows it rather than only a verdict. */
  def foundScopes(authStatus: String): String = githubScopes(authStatus).shown

  private def githubScopes(authStatus: String) = scopes(hostBlock(authStatus, GitHubNames.Host))

  /** No scope line at all means a token type (fine-grained PAT, installation token) that no
    * `gh auth refresh` can fix; a scope list without `user` is a refresh away.
    */
  def missingUserScopeMessage(authStatus: String): String =
    githubScopes(authStatus) match
      case TokenScopes.Absent =>
        "gh-billing: gh auth status printed no \"Token scopes:\" line for github.com at all — that means a token type this endpoint never accepts regardless of scope (a fine-grained PAT or an installation/server-to-server token; see https://docs.github.com/en/billing/tutorials/automate-usage-reporting). Re-authenticate with a classic PAT or gh's own OAuth token: gh auth login, then gh auth refresh -h github.com -s user"
      case found @ (TokenScopes.NoScopes | TokenScopes.Listed(_)) =>
        s"$ScopeFix\n(gh auth status reports: ${found.shown})"

  /** gh always prefers GH_TOKEN/GITHUB_TOKEN over the keyring, and `gh auth refresh` only rewrites
    * the keyring, so a set variable is a dead end whatever its scopes.
    */
  def envTokenMessage(env: Map[String, String]): Option[String] =
    BillingNames.TokenEnvVars.find(v => env.get(v).exists(_.nonEmpty)).map { v =>
      s"gh-billing: $v is set (a fine-grained PAT cannot call the billing endpoint) — run this with the env var unset: `env -u $v just gh-billing`, using your `gh auth login` token, which needs the `user` scope (`gh auth refresh -h github.com -s user`)"
    }

  /** The diagnosis of a failed `gh api` call's stderr, when it has a known shape. */
  def classifyError(err: String): Option[String] =
    val text = err.toLowerCase
    if text.contains("needs the \"user\" scope") then Some(ScopeFix)
    else if Seq("403", "resource not accessible", "must have", "forbidden").exists(text.contains)
    then
      Some(
        "likely a permission problem: this endpoint needs a classic PAT (or gh's own token) with the 'user' scope — fine-grained PATs are not supported here at all (https://docs.github.com/en/billing/tutorials/automate-usage-reporting). Try: gh auth refresh -s user"
      )
    else if Seq("404", "not found").exists(text.contains) then
      Some(
        "404 here can mean the account isn't on the enhanced billing platform, or — more likely a year on — that this was one of the per-product endpoints GitHub closed down 2025-09-26 (https://github.blog/changelog/2025-09-26-product-specific-billing-apis-are-closing-down/). There is no live fallback for those anymore: check https://github.com/settings/billing by hand."
      )
    else None

  // ---- the usage report ----------------------------------------------------------------------

  private def decodeItem(item: ujson.Value): UsageItem =
    def number(key: String) = Util.field(item, key).flatMap(_.numOpt).getOrElse(0.0)
    def value(key: String) = Util.field(item, key).map(JqValue(_)).getOrElse(JqValue.Null)
    UsageItem(
      value("product"),
      value("sku"),
      value("unitType"),
      number("quantity"),
      number("grossAmount"),
      number("discountAmount"),
      number("netAmount")
    )

  /** The API's response as the usage it reports. */
  def shape(raw: String): Either[String, Usage] =
    Util.readJson(raw).left.map(e => s"gh-billing: not JSON: $e").flatMap { json =>
      json.objOpt.map(_.get("usageItems")) match
        case None => Left("gh-billing: the usage report is not a JSON object")
        case Some(None) | Some(Some(ujson.Null)) => Right(Usage.Empty)
        case Some(Some(ujson.Arr(items)))        => Right(Usage.of(items.toSeq.map(decodeItem)))
        case Some(Some(_))                       => Left("gh-billing: usageItems is not an array")
    }

  // ---- the command ---------------------------------------------------------------------------

  case class GhResult(exitCode: Int, out: String, err: String):
    /** stdout as `$(...)` reads it: trailing newlines dropped. */
    def text: String = out.reverse.dropWhile(_ == '\n').reverse

  /** Runs `gh` with these arguments; tests pass a fake. */
  type Gh = Seq[String] => GhResult

  val realGh: Gh = args =>
    try
      val result = os.proc(GitHubNames.Cli +: args).call(check = false, stderr = os.Pipe)
      GhResult(result.exitCode, result.out.text(), result.err.text())
    catch case _: java.io.IOException => GhResult(127, "", "gh: command not found\n")

  /** What one run printed and its exit code. */
  case class Outcome(out: String, err: String, code: Int)

  private val AuthStatus = Seq("auth", "status")
  private val Login = Seq("api", "user", "--jq", ".login")
  private val PlanName = Seq("api", "user", "--jq", ".plan.name // empty")

  def plannedCall(period: BillingPeriod, gh: Gh): Outcome =
    val user = gh(Login)
    val login = if user.exitCode == 0 then user.text else BillingNames.LoginPlaceholder
    Outcome(
      s"gh-billing --dry-run: would call:\n  gh api \"${period.usagePath(login)}\"\n",
      "",
      0
    )

  def fromText(header: String, raw: String, plan: Option[Plan] = None): Outcome =
    shape(raw) match
      case Left(error)  => Outcome(header, error + "\n", 5)
      case Right(usage) => Outcome(header + usage.render(plan), "", 0)

  def live(config: BillingConfig, gh: Gh): Outcome =
    val BillingConfig(period, _, noScopeCheck, env, _) = config
    def fail(message: String) = Outcome("", message + "\n", 1)
    envTokenMessage(env) match
      case Some(message) => fail(message)
      case None          =>
        val status = gh(AuthStatus)
        val authStatus = status.copy(out = status.out + status.err).text
        if status.exitCode != 0 then
          fail(
            "gh is not logged in — run: gh auth login (needs the 'user' scope; fine-grained PATs are not supported by this endpoint)"
          )
        else if !noScopeCheck && !hasUserScope(authStatus) then
          fail(missingUserScopeMessage(authStatus))
        else
          val user = gh(Login)
          if user.exitCode != 0 then Outcome("", user.err, user.exitCode)
          else
            val login = user.text
            val planCall = gh(PlanName)
            val plan =
              Option.when(planCall.exitCode == 0)(planCall.text).filter(_.nonEmpty).map(Plan.parse)
            val header = s"GitHub billing usage — $login, ${period.shown}\n\n"
            val usage = gh(Seq("api", period.usagePath(login)))
            if usage.exitCode != 0 then
              val diagnosis = classifyError(usage.err).map(_ + "\n").getOrElse("")
              Outcome(
                header,
                s"consolidated usage report failed (raw error below):\n${usage.err}\n$diagnosis",
                1
              )
            else fromText(header, usage.out, plan)

  @main(
    doc = """This GitHub account's metered usage: Actions, Packages, Copilot.

Live mode also prints "Actions minutes: X of Y included this month" for personal Free/Pro plans
(GitHub's own published allowances, not exposed by the billing-usage API itself, so hardcoded;
github.com/settings/billing is the live authority if this ever looks wrong).

Live mode needs `gh auth status` with a classic PAT (or gh's own token) carrying the `user` scope:
fine-grained PATs are not supported by GitHub's billing-usage endpoint. The scope is checked
locally from `gh auth status` before any live call (skip with --no-scope-check); a 404 mentioning
the scope means re-auth with `gh auth refresh -h github.com -s user`. If GH_TOKEN or GITHUB_TOKEN
is set, gh uses that token unconditionally and `gh auth refresh` cannot touch it: this is checked
first and always fails fast (--no-scope-check does not bypass it). Not available inside ai-jail;
run from the host. --from-file/--json-stdin/--dry-run need neither a gh login nor network
(--dry-run does try `gh api user` to show your real login, and falls back to a placeholder)."""
  )
  def run(
      @arg(doc = "year to query (default: current year)") year: Option[String] = None,
      @arg(doc = "month to query, 1-12 (default: current month)") month: Option[String] = None,
      @arg(doc =
        "shape a previously saved JSON response instead of calling `gh api`"
      ) fromFile: Option[String] = None,
      @arg(doc = "read the JSON response from stdin instead of calling `gh api`") jsonStdin: Flag,
      @arg(doc =
        "print the `gh api` call this would make and exit, no network/gh required"
      ) dryRun: Flag,
      @arg(doc =
        "skip the local `gh auth status` scope check and call the API directly"
      ) noScopeCheck: Flag
  ): Int =
    val invocation = Invocation.current()
    val today = java.time.LocalDate.now()
    val source =
      if dryRun.value then BillingSource.DryRun
      else
        fromFile match
          case Some(file)              => BillingSource.File(file)
          case None if jsonStdin.value => BillingSource.Stdin
          case None                    => BillingSource.Live
    val config = BillingConfig(
      period = BillingPeriod(
        year.getOrElse(today.getYear.toString),
        month.getOrElse(today.getMonthValue.toString)
      ),
      source = source,
      noScopeCheck = noScopeCheck.value,
      env = invocation.env,
      cwd = invocation.cwd
    )
    val outcome = execute(config, realGh, () => String(System.in.readAllBytes(), "UTF-8"))
    print(outcome.out)
    System.err.print(outcome.err)
    outcome.code

  def execute(config: BillingConfig, gh: Gh, stdin: () => String): Outcome =
    config.source match
      case BillingSource.DryRun => plannedCall(config.period, gh)
      case BillingSource.File(file) if !os.isFile(os.Path(file, config.cwd)) =>
        Outcome("", s"gh-billing: file not found: $file\n", 1)
      case BillingSource.File(file) =>
        fromText(s"GitHub billing usage — from $file\n\n", os.read(os.Path(file, config.cwd)))
      case BillingSource.Stdin => fromText("GitHub billing usage — from stdin\n\n", stdin())
      case BillingSource.Live  => live(config, gh)

  def cli(args: Seq[String]): Int = Cli.run(ParserForMethods(this), "gh-billing", args)
