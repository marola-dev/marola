package marola.tools.shared

/** The git names several commands pass to git. */
object GitNames:
  val Origin = "origin"
  val FetchHead = "FETCH_HEAD"

  /** A hook exports these, and they beat `git -C`: unset, git finds the repo from its cwd. */
  val RepoPointerEnvVars =
    Seq("GIT_DIR", "GIT_WORK_TREE", "GIT_INDEX_FILE", "GIT_PREFIX", "GIT_COMMON_DIR")
