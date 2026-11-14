package marola.tools

import marola.tools.ci.Digest
import marola.tools.ci.GhBilling
import marola.tools.ci.RepoStats
import marola.tools.docs.AgentsReposCheck
import marola.tools.docs.ClaudeProject
import marola.tools.docs.DocLinks
import marola.tools.docs.FetchApiDocs
import marola.tools.docs.Figure
import marola.tools.docs.MermaidFont
import marola.tools.docs.Mkdocs
import marola.tools.docs.PrepareDocs
import marola.tools.docs.ReadingTime
import marola.tools.docs.SiteLinks
import marola.tools.docs.StripScripts
import marola.tools.publishing.Citation
import marola.tools.publishing.MipGraph
import marola.tools.publishing.Release
import marola.tools.routing.GraphSvg
import marola.tools.routing.RoutingBundle
import marola.tools.submodules.PointerSync
import marola.tools.submodules.PointerSyncMerge
import marola.tools.submodules.SiteDataPush

// marola-tools: the umbrella's repo tooling, one command per job. Run it as
// `scripts/marola-tools <command> [args]`; `scripts/marola-tools --help` lists the commands.

object Main:
  // name -> (one-line help, entry point). Each entry point parses its own arguments with
  // mainargs and returns the process exit code.
  val commands: Seq[(String, String, Seq[String] => Int)] = Seq(
    (
      "citation",
      "Validate .zenodo.json and regenerate CITATION.cff and the READMEs' citation block (MIP-0079)",
      Citation.cli
    ),
    ("mip-graph", "Write the MIP dependency graph into docs/MIPs/README.md", MipGraph.cli),
    (
      "doc-links",
      "Rewrite one repo's README and docs/** links for its mount on the docs site (MIP-0074)",
      DocLinks.cli
    ),
    (
      "site-links-check",
      "Every docs.marola.dev link in a built site resolves, and no old sitemap URL was dropped",
      SiteLinks.cli
    ),
    (
      "strip-external-scripts",
      "Drop third-party <script src> from generated API docs (MIP-0044 §5.6)",
      StripScripts.cli
    ),
    (
      "mermaid-font",
      "Pin the font Kroki measures Mermaid labels in, in every .md under each DIR (#511)",
      MermaidFont.cli
    ),
    (
      "routing-bundle",
      "Assemble the files ci.yml's graph job publishes to `routing-bundle` (MIP-0084 §5.3)",
      RoutingBundle.cli
    ),
    (
      "claude-project-check",
      "CLAUDE_PROJECT.config.md has its sections and routines, and leaks no id",
      ClaudeProject.cli
    ),
    ("repo-stats", "The README badges as shields.io endpoint JSON", RepoStats.cli),
    (
      "reading-time",
      "Each README's reading time: print it, --write the badge line, or --check it is current",
      ReadingTime.cli
    ),
    (
      "graph-svg",
      "Draw the routing-bundle graph.json as the README's graphify figure (an SVG)",
      GraphSvg.cli
    ),
    (
      "arxiv-digest",
      "Cache arXiv papers relevant to marola's forecasting domain (MIP-0019)",
      Digest.arxivCli
    ),
    (
      "awesome-agentic-digest",
      "Cache GitHub repo candidates for AWESOME-AGENTIC-ENGINEERING.md (MIP-0043)",
      Digest.awesomeCli
    ),
    (
      "agents-repos-check",
      "AGENTS.md links the routing table instead of restating it (MIP-0074 §7)",
      AgentsReposCheck.cli
    ),
    (
      "prepare-docs",
      "Build docs.marola.dev's source tree in .tmp/docs-aggregated (MIP-0074 §5.3)",
      PrepareDocs.cli
    ),
    (
      "fetch-api-docs",
      "Unpack a repo's api-docs branch into a directory (MIP-0074 §5.2)",
      FetchApiDocs.cli
    ),
    ("mkdocs", "Build or serve the documentation site in its container (MIP-0064)", Mkdocs.cli),
    ("figure", "Draw one Archify source as a PNG in picasso's look", Figure.cli),
    (
      "pointer-sync",
      "Move every submodule pointer to its default branch's tip, for pointer-sync.yml (MIP-0070 §5.6)",
      PointerSync.cli
    ),
    (
      "pointer-sync-merge",
      "Squash-merge pointer-sync.yml's rolling PR once it is green and in scope (#739)",
      PointerSyncMerge.cli
    ),
    (
      "release",
      "Tag main as vX.Y.Z for a Zenodo version, or --guard a tag (MIP-0079)",
      Release.cli
    ),
    (
      "site-data-push",
      "Push the commit a workflow made on marola-site's site-data branch, retrying on a race",
      SiteDataPush.cli
    ),
    ("gh-billing", "This GitHub account's metered usage: Actions, Packages, Copilot", GhBilling.cli)
  )

  def main(args: Array[String]): Unit =
    val code = args.toList match
      case Nil | ("-h" | "--help" | "help") :: _ =>
        usage()
        if args.isEmpty then 2 else 0
      case name :: rest =>
        commands.find(_._1 == name) match
          case Some((_, _, run)) => run(rest)
          case None              =>
            System.err.println(s"marola-tools: unknown command '$name'")
            usage()
            2
    sys.exit(code)

  private def usage(): Unit =
    val width = commands.map(_._1.length).max
    System.err.println(
      "The marola umbrella's repo tooling\n\nUsage: marola-tools <command> [args]\n"
    )
    for (name, help, _) <- commands do System.err.println(s"  ${name.padTo(width, ' ')}  $help")
