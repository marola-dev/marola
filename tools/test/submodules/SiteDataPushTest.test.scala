package marola.tools.submodules

import marola.tools.shared.Proc

class SiteDataPushTest extends munit.FunSuite:
  // A pre-push hook exports GIT_DIR and friends; the fixtures would otherwise commit into the caller's repo.
  private val gitEnv: Map[String, String] =
    Seq("GIT_DIR", "GIT_WORK_TREE", "GIT_INDEX_FILE", "GIT_PREFIX", "GIT_COMMON_DIR")
      .map(_ -> null)
      .toMap

  private def git(cwd: os.Path, args: String*): String =
    os.proc("git" +: args).call(cwd = cwd, env = gitEnv, stderr = os.Pipe).out.text().stripLineEnd

  private val noSleep: Int => Unit = _ => ()

  test("three writers race: the second is replayed onto the new tip, a failed rebase is an error"):
    val t = os.temp.dir()
    try
      val origin = t / "origin.git"
      git(t, "init", "-q", "--bare", origin.toString)
      git(origin, "symbolic-ref", "HEAD", "refs/heads/site-data")
      git(t, "init", "-q", "-b", "site-data", "seed")
      git(
        t / "seed",
        "-c",
        "user.name=t",
        "-c",
        "user.email=t@t",
        "commit",
        "-q",
        "--allow-empty",
        "-m",
        "root"
      )
      git(t / "seed", "push", "-q", origin.toString, "site-data")
      for writer <- Seq("a", "b", "c") do
        git(t, "clone", "-q", "--depth=1", "-b", "site-data", s"file://$origin", writer)
        os.write(t / writer / writer / "latest.json", s"$writer\n", createFolders = true)
        git(t / writer, "add", writer)
        git(t / writer, "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "-m", writer)
      git(t / "b", "config", "user.name", "t")
      git(t / "b", "config", "user.email", "t@t")

      assertEquals(SiteDataPush.push((t / "a").toString, Proc(gitEnv), noSleep), 0, "first push")
      val retried = Proc(gitEnv + ("SITE_DATA_PUSH_TRIES" -> "3"))
      assertEquals(
        SiteDataPush.push((t / "b").toString, retried, noSleep),
        0,
        "second push was not retried onto the new tip"
      )
      // c has no committer identity (a bare CI runner), so its rebase fails: that must be an error.
      val noIdentity = Proc(
        gitEnv ++ Map(
          "GIT_COMMITTER_NAME" -> null,
          "GIT_COMMITTER_EMAIL" -> null,
          "GIT_CONFIG_GLOBAL" -> null,
          "HOME" -> t.toString,
          "XDG_CONFIG_HOME" -> t.toString,
          "GIT_CONFIG_NOSYSTEM" -> "1",
          "GIT_CONFIG_COUNT" -> "1",
          "GIT_CONFIG_KEY_0" -> "user.useConfigOnly",
          "GIT_CONFIG_VALUE_0" -> "true",
          "SITE_DATA_PUSH_TRIES" -> "2"
        )
      )
      assertEquals(
        SiteDataPush.push((t / "c").toString, noIdentity, noSleep),
        1,
        "a failed rebase was reported as a successful push"
      )

      assertEquals(
        git(origin, "ls-tree", "-r", "--name-only", "site-data").linesIterator.toSeq,
        Seq("a/latest.json", "b/latest.json")
      )
      assertEquals(
        git(origin, "rev-list", "--count", "site-data"),
        "3",
        "history is not root + a + b"
      )
    finally os.remove.all(t)

  test("no attempts left is a rejection"):
    val t = os.temp.dir()
    try
      git(t, "init", "-q", "-b", "site-data", "w")
      assertEquals(
        SiteDataPush
          .push((t / "w").toString, Proc(gitEnv + ("SITE_DATA_PUSH_TRIES" -> "0")), noSleep),
        1
      )
    finally os.remove.all(t)

  test("a checkout is required"):
    assertEquals(SiteDataPush.run(""), 2)
    assertEquals(SiteDataPush.run("-x"), 2)
