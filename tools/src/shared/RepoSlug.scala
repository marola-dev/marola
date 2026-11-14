package marola.tools.shared

import java.util.regex.Pattern

/** `OWNER/NAME`. */
opaque type RepoSlug = String

object RepoSlug:
  /** As given on the command line; gh judges it. */
  def apply(value: String): RepoSlug = value

  /** A repo of the marola-dev org, by name. */
  def inOrg(name: String): RepoSlug = s"${GitHubNames.Org}/$name"

  private val Web = Pattern.quote(GitHubNames.Web)
  private val GitHubUrl = s"$Web/([^/]+/[^/]+)".r
  private val OrgUrl = s"$Web/(${Pattern.quote(GitHubNames.Org)}/[A-Za-z0-9._-]+)".r

  /** The repo of a GitHub remote URL, `.git` or not. */
  def fromGitHubUrl(url: String): Either[String, RepoSlug] = url.stripSuffix(".git") match
    case GitHubUrl(repo) => Right(repo)
    case _               => Left(s"not a GitHub repo URL: $url")

  /** The repo of a marola-dev remote URL: the only owner whose pointers merge unattended. */
  def marolaDev(url: String): Either[String, RepoSlug] = url.stripSuffix(".git") match
    case OrgUrl(repo) => Right(repo)
    case _            => Left(s"not a marola-dev repo URL: $url")

  extension (repo: RepoSlug)
    def value: String = repo

    /** The repo's page on GitHub. */
    def web: String = s"${GitHubNames.Web}/$repo"
