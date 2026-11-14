package marola.tools.shared

/** The GitHub names every context uses: the CLI, the web host, the org, the REST media type. */
object GitHubNames:
  val Cli = "gh"
  val Host = "github.com"
  val Web = s"https://$Host"
  val Org = "marola-dev"
  val OrgWeb = s"$Web/$Org"
  val JsonMediaType = "application/vnd.github+json"

  /** GitHub Actions' app id: the only author of a check run the pointer merge trusts. */
  val ActionsAppId = 15368

  /** The variables gh reads a token from, preferred over its keyring in this order. */
  val GhTokenEnvVar = "GH_TOKEN"
  val GithubTokenEnvVar = "GITHUB_TOKEN"
