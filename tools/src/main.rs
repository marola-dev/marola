//! marola-tools — the umbrella's own repo tooling, one binary with a subcommand per job.
//!
//! Run it as `scripts/marola-tools <command>`, which builds this crate on first use. Each
//! command's module says what it does; `cargo test` (in tools/) is every command's test suite.

mod citation;
mod claude_project;
mod digest;
mod doc_links;
mod graph_svg;
mod mermaid_font;
mod mip_graph;
mod reading_time;
mod repo_stats;
mod routing_bundle;
mod site_links;
mod strip_scripts;
mod util;

mod anyhow_lite {
    pub type Result<T = ()> = std::result::Result<T, Box<dyn std::error::Error>>;
}

use clap::{Parser, Subcommand};
use std::path::PathBuf;
use std::process::ExitCode;

#[derive(Parser)]
#[command(name = "marola-tools", about = "The marola umbrella's repo tooling")]
struct Cli {
    #[command(subcommand)]
    command: Command,
}

#[derive(Subcommand)]
enum Command {
    /// Validate .zenodo.json and regenerate CITATION.cff and the READMEs' citation block (MIP-0079)
    Citation {
        /// Validate only; exit 1 if any generated text is stale
        #[arg(long)]
        check: bool,
        #[command(subcommand)]
        add: Option<CitationAdd>,
    },
    /// Write the MIP dependency graph into docs/MIPs/README.md
    MipGraph {
        /// Exit 1 if the checked-in block is stale or missing
        #[arg(long)]
        check: bool,
        /// Can these two MIPs be worked in parallel?
        #[arg(long, num_args = 2, value_names = ["MIP_A", "MIP_B"])]
        parallel: Option<Vec<u32>>,
    },
    /// Rewrite one repo's README and docs/** links for its mount on the docs site (MIP-0074)
    DocLinks {
        #[arg(long)]
        repo: Option<PathBuf>,
        #[arg(long)]
        name: Option<String>,
        #[arg(long)]
        sha: Option<String>,
        #[arg(long)]
        out: Option<PathBuf>,
        #[arg(long)]
        umbrella: bool,
        #[arg(long)]
        exclude: Vec<String>,
        #[arg(long, value_name = "NAME=SHA")]
        submodule: Vec<String>,
        /// Print FILE<TAB>link for every relative link outside code
        #[arg(long, num_args = 1.., value_name = "FILE")]
        links: Vec<PathBuf>,
    },
    /// Every docs.marola.dev link in a built site resolves, and no old sitemap URL was dropped
    SiteLinksCheck {
        site_dir: PathBuf,
        #[arg(long, value_name = "FILE")]
        old_sitemap: Option<PathBuf>,
    },
    /// Drop third-party <script src> from generated API docs (MIP-0044 §5.6)
    StripExternalScripts {
        #[arg(required = true)]
        dirs: Vec<PathBuf>,
        /// Report survivors, change nothing
        #[arg(long)]
        check: bool,
    },
    /// Pin the font Kroki measures Mermaid labels in, in every .md under each DIR (#511)
    MermaidFont {
        #[arg(required = true)]
        dirs: Vec<PathBuf>,
    },
    /// Assemble the files ci.yml's graph job publishes to `routing-bundle` (MIP-0084 §5.3)
    RoutingBundle {
        #[arg(long)]
        graph_dir: PathBuf,
        #[arg(long)]
        out: PathBuf,
        /// route bench's output, a file
        #[arg(long)]
        bench: PathBuf,
        #[arg(long, default_value = "routing-bench.yaml")]
        questions: PathBuf,
        #[arg(long)]
        previous: Option<PathBuf>,
        #[arg(long, default_value = "")]
        graphify: String,
        #[arg(long, default_value = "docs/2-Building-marola/REPOS.md")]
        repos_md: PathBuf,
    },
    /// CLAUDE_PROJECT.config.md has its sections and routines, and leaks no id
    ClaudeProjectCheck,
    /// The README badges as shields.io endpoint JSON
    RepoStats {
        #[command(subcommand)]
        cmd: RepoStatsCmd,
    },
    /// Each README's reading time: print it, --write the badge line, or --check it is current
    ReadingTime {
        #[arg(long)]
        write: bool,
        #[arg(long, conflicts_with = "write")]
        check: bool,
    },
    /// Draw the routing-bundle graph.json as the README's graphify figure (an SVG)
    GraphSvg {
        #[arg(long)]
        graph: PathBuf,
        #[arg(long, default_value = "docs/img/graphify.svg")]
        out: PathBuf,
        /// Checkout the source files are looked up in
        #[arg(long, default_value = ".")]
        root: PathBuf,
    },
    /// Cache arXiv papers relevant to marola's forecasting domain (MIP-0019)
    ArxivDigest {
        #[arg(long, default_value_t = 5)]
        max_results: u32,
        #[arg(long)]
        json: bool,
    },
    /// Cache GitHub repo candidates for AWESOME-AGENTIC-ENGINEERING.md (MIP-0043)
    AwesomeAgenticDigest {
        #[arg(long, default_value_t = 5)]
        max_results: u32,
        #[arg(long)]
        json: bool,
    },
}

#[derive(Subcommand)]
enum CitationAdd {
    /// Add a contributor (or, with --author, a creator)
    Add {
        /// "Family, Given"
        #[arg(long)]
        name: String,
        /// Bare iD, 0000-0000-0000-0000
        #[arg(long)]
        orcid: Option<String>,
        #[arg(long)]
        affiliation: Option<String>,
        #[arg(long = "type", default_value = "ProjectMember", value_parser = clap::builder::PossibleValuesParser::new(citation::CONTRIBUTOR_TYPES))]
        ctype: String,
        /// List as a creator (cited author)
        #[arg(long)]
        author: bool,
    },
}

#[derive(Subcommand)]
enum RepoStatsCmd {
    /// Write the badge JSONs into --out-dir
    Write {
        #[arg(long)]
        out_dir: PathBuf,
        /// Repo root the LOC paths are relative to
        #[arg(long, default_value = ".")]
        root: PathBuf,
        /// owner/name, for the CI-health badge
        #[arg(long, default_value = "marola-dev/marola")]
        repo: String,
        /// Workflow run to report on; omitted = LOC badge only
        #[arg(long)]
        run_id: Option<String>,
        /// Job name to leave out (the reporting job itself)
        #[arg(long)]
        exclude_job: Option<String>,
    },
}

fn main() -> ExitCode {
    let code = match Cli::parse().command {
        Command::Citation { check, add } => citation::main(
            check,
            add.as_ref().map(
                |CitationAdd::Add {
                     name,
                     orcid,
                     affiliation,
                     ctype,
                     author,
                 }| citation::AddArgs {
                    name,
                    orcid: orcid.as_deref(),
                    affiliation: affiliation.as_deref(),
                    ctype,
                    author: *author,
                },
            ),
        ),
        Command::MipGraph { check, parallel } => {
            mip_graph::main(check, parallel.map(|p| (p[0], p[1])))
        }
        Command::DocLinks {
            repo,
            name,
            sha,
            out,
            umbrella,
            exclude,
            submodule,
            links,
        } => doc_links::main(doc_links::Args {
            repo,
            name,
            sha,
            out,
            umbrella,
            exclude,
            submodule,
            links,
        }),
        Command::SiteLinksCheck {
            site_dir,
            old_sitemap,
        } => site_links::main(&site_dir, old_sitemap.as_deref()),
        Command::StripExternalScripts { dirs, check } => {
            if check {
                strip_scripts::check(&dirs)
            } else {
                strip_scripts::process(&dirs)
            }
        }
        Command::MermaidFont { dirs } => report(mermaid_font::run(&dirs)),
        Command::RoutingBundle {
            graph_dir,
            out,
            bench,
            questions,
            previous,
            graphify,
            repos_md,
        } => routing_bundle::main(&routing_bundle::Args {
            graph_dir,
            out,
            bench,
            questions,
            previous,
            graphify,
            repos_md,
        }),
        Command::ClaudeProjectCheck => claude_project::run(),
        Command::RepoStats {
            cmd:
                RepoStatsCmd::Write {
                    out_dir,
                    root,
                    repo,
                    run_id,
                    exclude_job,
                },
        } => repo_stats::main(
            &out_dir,
            &root,
            &repo,
            run_id.as_deref(),
            exclude_job.as_deref(),
        ),
        Command::ReadingTime { write, check } => report(reading_time::run(write, check)),
        Command::GraphSvg { graph, out, root } => report(graph_svg::run(&graph, &out, &root)),
        Command::ArxivDigest { max_results, json } => {
            report(digest::arxiv(max_results, json).map_err(Into::into))
        }
        Command::AwesomeAgenticDigest { max_results, json } => {
            report(digest::awesome(max_results, json).map_err(Into::into))
        }
    };
    ExitCode::from(u8::try_from(code).unwrap_or(1))
}

fn report(result: anyhow_lite::Result) -> i32 {
    match result {
        Ok(()) => 0,
        Err(e) => {
            eprintln!("{e}");
            1
        }
    }
}
