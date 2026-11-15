package marola.tools.publishing

import marola.tools.shared.Proc

class ReleaseTest extends munit.FunSuite:
  import Release.*

  test("tags are vMAJOR.MINOR.PATCH"):
    for tag <- Seq("v0.2.0", "v10.0.12") do assert(SemVer.validTag(tag), tag)
    for tag <- Seq("v0.2", "0.2.0", "v0.2.0-rc1") do assert(!SemVer.validTag(tag), tag)

  test("a version is read with or without its v"):
    assertEquals(SemVer.parse("0.2.0").map(_.tag), Right("v0.2.0"))
    assertEquals(SemVer.parse("v0.2.0").map(_.tag), Right("v0.2.0"))
    assertEquals(SemVer.parse("0.2").map(_.tag), Left("'0.2' is not MAJOR.MINOR.PATCH"))
    assert(SemVer.parse("vv0.2.0").isLeft)

  test("the guard's verdict"):
    assert(RawDiffEntry.parse(":160000 100644 a b T\tsub").touchesGitlink)
    assert(!RawDiffEntry.parse(":100644 100644 a b M\tREADME.md").touchesGitlink)
    assertEquals(
      GuardVerdict.compared("v0.1.0", "HEAD", 0),
      GuardVerdict.PointersOnly("v0.1.0", "HEAD")
    )
    assertEquals(GuardVerdict.compared("v0.1.0", "HEAD", 2), GuardVerdict.Content("v0.1.0", 2))
    assertEquals(GuardVerdict.PointersOnly("v0.1.0", "HEAD").exitCode, 1)
    assertEquals(GuardVerdict.Content("v0.1.0", 2).exitCode, 0)
    assertEquals(GuardVerdict.NoBase("HEAD").exitCode, 0)
    assertEquals(
      GuardVerdict.Content("v0.1.0", 2).message,
      "2 file(s) changed outside the submodule pointers since v0.1.0"
    )

  test("pointer moves are not content"):
    val pointers =
      ":160000 160000 c62001b 3912182 M\tmarola-corpus\n:000000 160000 0000000 e0f5720 A\tmarola-ml\n" +
        ":160000 000000 e0f5720 0000000 D\tmarola-old"
    assertEquals(contentLines(pointers), Vector.empty)
    val doc = ":100644 100644 94edc98 fdccf0a M\tdocs/PHASES.md"
    assertEquals(contentLines(pointers + "\n" + doc), Vector(doc))

  // A repo whose `origin` is a local bare repo, a gitlink `sub`, and a stand-in
  // `scripts/marola-tools` whose exit code is the file `citation-exit`.
  private final class Fixture(val t: os.Path):
    val gitEnv: Map[String, String] =
      Seq("GIT_DIR", "GIT_WORK_TREE", "GIT_INDEX_FILE", "GIT_PREFIX", "GIT_COMMON_DIR")
        .map(_ -> null)
        .toMap ++
        Map("GIT_CONFIG_GLOBAL" -> (t / "gitconfig").toString, "GIT_CONFIG_NOSYSTEM" -> "1")
    os.write(t / "gitconfig", "[user]\n\tname = t\n\temail = t@t\n")
    val proc = Proc(gitEnv)
    val work = t / "work"

    def git(args: String*): String =
      os.proc("git" +: args)
        .call(cwd = work, env = gitEnv, stderr = os.Pipe)
        .out
        .text()
        .stripLineEnd

    def gitlink(fill: Char): Unit =
      val _ = git("update-index", "--add", "--cacheinfo", s"160000,${fill.toString * 40},sub")

    def commit(message: String): Unit =
      val _ = git("commit", "-q", "-m", message)

    os.proc("git", "init", "-q", "--bare", "-b", "main", (t / "origin.git").toString)
      .call(env = gitEnv)
    os.proc("git", "init", "-q", "-b", "main", work.toString).call(env = gitEnv)
    git("remote", "add", "origin", (t / "origin.git").toString)
    os.write(
      work / "scripts" / "marola-tools",
      "#!/bin/sh\nexit \"$(cat citation-exit)\"\n",
      createFolders = true,
      perms = "rwxr-xr-x"
    )
    os.write(work / "citation-exit", "0\n")
    os.write(work / "README.md", "one\n")
    os.makeDir(work / "sub") // an empty dir is an unpopulated submodule
    git("add", "README.md", "scripts", "citation-exit")
    gitlink('1')
    commit("one")
    git("tag", "v0.1.0")

    def push(): Unit =
      val _ = git("push", "-q", "origin", "main", "--tags")

  private val fixture = FunFixture[Fixture](_ => Fixture(os.temp.dir()), f => os.remove.all(f.t))

  fixture.test("--guard: a pointer move alone is refused, a file change is not"): f =>
    assertEquals(guard("HEAD", f.work, f.proc), 0, "no earlier tag: nothing to compare")
    f.gitlink('2')
    f.commit("pointer")
    assertEquals(guard("HEAD", f.work, f.proc), 1)
    f.git("tag", "v0.2.0")
    assertEquals(guard("v0.2.0", f.work, f.proc), 1, "the ref's own tag is not its base")
    os.write.over(f.work / "README.md", "two\n")
    f.git("add", "README.md")
    f.commit("two")
    assertEquals(guard("HEAD", f.work, f.proc), 0, "README.md changed since v0.2.0")

  fixture.test("release --dry-run prints the commands, a real run tags and pushes"): f =>
    os.write.over(f.work / "README.md", "two\n")
    f.git("add", "README.md")
    f.commit("two")
    f.push()
    assertEquals(release("0.2.0", dryRun = true, f.work, f.proc), 0)
    assertEquals(f.git("tag", "-l", "v0.2.0"), "")
    assertEquals(release("v0.2.0", dryRun = false, f.work, f.proc), 0)
    assertEquals(f.git("ls-remote", "--tags", "origin", "v0.2.0").nonEmpty, true)
    assertEquals(release("0.2.0", dryRun = true, f.work, f.proc), 1, "the tag exists")

  fixture.test("release refuses what is not a clean main level with origin"): f =>
    f.gitlink('2')
    f.commit("pointer")
    f.push()
    assertEquals(release("", dryRun = true, f.work, f.proc), 1, "no version")
    assertEquals(release("0.2", dryRun = true, f.work, f.proc), 1, "not MAJOR.MINOR.PATCH")
    assertEquals(
      release("0.2.0", dryRun = true, f.work, f.proc),
      1,
      "nothing but pointers since v0.1.0"
    )
    os.write.over(f.work / "README.md", "dirty\n")
    assertEquals(release("0.2.0", dryRun = true, f.work, f.proc), 1, "dirty")
    f.git("add", "README.md")
    f.commit("two")
    assertEquals(release("0.2.0", dryRun = true, f.work, f.proc), 1, "ahead of origin/main")
    f.push()
    os.write.over(f.work / "citation-exit", "3\n")
    f.git("add", "citation-exit")
    f.commit("citation breaks")
    f.push()
    assertEquals(release("0.2.0", dryRun = true, f.work, f.proc), 3, "citation --check's exit code")
    f.git("checkout", "-q", "-b", "other")
    assertEquals(release("0.2.0", dryRun = true, f.work, f.proc), 1, "not on main")
