package marola.tools.docs

import SiteModel.{Repo, RepoSource}

// mkdocs/repos.yml's fixed shape (MIP-0070 §5.5), read without a YAML library.
object ReposManifest:
  def parse(file: os.Path): Either[String, Vector[Repo]] =
    if !os.isFile(file) then Left(s"repos_manifest: manifest not found: $file")
    else parseText(os.read(file), file.toString)

  /** The repos read so far, and the one whose entry is still open. */
  case class Reading(done: Vector[Repo], open: Option[Repo]):
    def repos: Vector[Repo] = done ++ open

  private def value(line: String): String =
    val v =
      line.stripSuffix("\r").replaceFirst("""^[^:]*:[ \t]*""", "").replaceFirst("""[ \t]+$""", "")
    if v.length >= 2 && v.startsWith("\"") && v.endsWith("\"") then v.drop(1).dropRight(1) else v

  def parseText(text: String, fileName: String): Either[String, Vector[Repo]] =
    val lines = text.split("\n", -1).toVector.dropRight(if text.endsWith("\n") then 1 else 0)
    lines.zipWithIndex
      .foldLeft[Either[String, Reading]](Right(Reading(Vector.empty, None))) {
        case (reading, (line, index)) =>
          reading.flatMap(read(_, line, s"repos_manifest: $fileName:${index + 1}:"))
      }
      .map(_.repos)

  private def read(reading: Reading, line: String, at: String): Either[String, Reading] =
    if line.matches("""[ \t]*""") || line.matches("""[ \t]*#.*""") then Right(reading)
    else if line.startsWith("- name:") then
      val name = value(line)
      Right(Reading(reading.repos, Option.when(name.nonEmpty)(Repo(name, RepoSource.Submodule))))
    else if line.startsWith("  mount:") then
      Left(s"$at mount: is gone, every repo mounts at 5-Repos/<name>/ (MIP-0074 §5.3)")
    else if line.startsWith("  source:") then
      val written = value(line)
      reading.open match
        case None => Left(s"$at source before any `- name:`")
        case Some(_) if written != RepoSource.FlakeLockWord =>
          Left(s"$at source must be ${RepoSource.FlakeLockWord}, got: $written")
        case Some(repo) =>
          Right(reading.copy(open = Some(repo.copy(source = RepoSource.FlakeLock))))
    else Left(s"$at unrecognised line: $line")
