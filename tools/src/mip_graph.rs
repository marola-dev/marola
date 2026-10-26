//! Generate a Mermaid dependency graph from every MIP's metadata table and write it into
//! docs/MIPs/README.md between two HTML-comment markers. Reads only a machine-readable
//! **Blocked by** row (comma-separated MIP numbers, or the literal `none`) — the human-readable
//! **Depends on** field stays prose-only and is never parsed, because it legitimately carries four
//! different relations (blocking, blocked-by, co-delivery, negation) in one cell that a regex cannot
//! tell apart.
//!
//!     marola-tools mip-graph                    # regenerate the graph block in docs/MIPs/README.md
//!     marola-tools mip-graph --check            # exit 1 if the checked-in block is stale or missing
//!     marola-tools mip-graph --parallel 30 31   # can these two MIPs be worked in parallel?
//!
//! A MIP with no **Blocked by** row is drawn as an unconnected node. Nothing here invents an edge
//! from prose.

use crate::util::repo_root;
use regex::Regex;
use std::collections::{BTreeMap, BTreeSet};
use std::path::{Path, PathBuf};
use std::sync::LazyLock;

const START_MARK: &str = "<!-- mip-graph:start -->";
const END_MARK: &str = "<!-- mip-graph:end -->";

static TITLE_RE: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"^#\s*MIP-(\d{4}):\s*(.+?)\s*$").unwrap());
static ROW_RE: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r"^\|\s*\*\*(?P<key>[^*]+)\*\*\s*\|\s*(?P<val>.*?)\s*\|\s*$").unwrap()
});
static MIP_NUM_RE: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"\b(\d{4})\b").unwrap());
static SOURCE_PATH_RE: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"`((?:core|local|cli|site|scripts)/[A-Za-z0-9_./-]+)`").unwrap());

const STATUS_CLASS: [(&str, &str); 5] = [
    ("draft", "draft"),
    ("accepted", "accepted"),
    ("implemented", "implemented"),
    ("rejected", "rejected"),
    ("superseded", "rejected"),
];

const NO_EDGES_PLACEHOLDER: &str = "_No MIP currently declares a **Blocked by** relationship, so there is nothing to graph yet — add that field to a MIP's metadata table and run `just mip-graph` again._";

#[derive(Debug, Clone)]
pub struct Mip {
    pub num: u32,
    // Parsed for the tests' sake: the graph names a MIP by number only, never by title.
    #[cfg_attr(not(test), allow(dead_code))]
    pub title: String,
    pub status: String,
    pub blocked_by: Vec<u32>,
}

impl Mip {
    #[cfg(test)]
    pub fn new(num: u32, title: &str, status: &str, blocked_by: Vec<u32>) -> Self {
        Mip {
            num,
            title: title.into(),
            status: status.into(),
            blocked_by,
        }
    }

    fn node_id(&self) -> String {
        format!("M{:04}", self.num)
    }

    fn status_class(&self) -> &'static str {
        let s = self.status.to_lowercase();
        STATUS_CLASS
            .iter()
            .find(|(key, _)| s.starts_with(key))
            .map_or("draft", |(_, cls)| cls)
    }
}

pub fn parse_mip(text: &str) -> Option<Mip> {
    let lines: Vec<&str> = crate::util::splitlines(text);
    let (num, title) = lines.iter().take(3).find_map(|line| {
        TITLE_RE
            .captures(line)
            .map(|m| (m[1].parse::<u32>().unwrap(), m[2].to_string()))
    })?;
    let mut status = "Draft".to_string();
    let mut blocked_by = Vec::new();
    for line in &lines {
        let Some(m) = ROW_RE.captures(line) else {
            continue;
        };
        let key = m["key"].trim().to_lowercase();
        let val = m["val"].trim();
        if key == "status" {
            // first word/clause only — "Implemented, ultrareview-verified..." -> "Implemented"
            let first = val.split([',', '(']).next().unwrap_or("").trim();
            status = if first.is_empty() {
                "Draft".into()
            } else {
                first.into()
            };
        } else if key == "blocked by" && val.to_lowercase() != "none" {
            blocked_by = MIP_NUM_RE
                .captures_iter(val)
                .map(|c| c[1].parse().unwrap())
                .collect();
        }
    }
    Some(Mip {
        num,
        title,
        status,
        blocked_by,
    })
}

fn mip_files(dir: &Path) -> Vec<PathBuf> {
    let mut files: Vec<PathBuf> = std::fs::read_dir(dir)
        .into_iter()
        .flatten()
        .filter_map(Result::ok)
        .map(|e| e.path())
        .filter(|p| {
            let name = p.file_name().unwrap_or_default().to_string_lossy();
            name.starts_with("MIP-") && name.ends_with(".md") && !name.ends_with(".tasks.md")
        })
        .collect();
    files.sort();
    files
}

pub fn load_mips(dir: &Path) -> BTreeMap<u32, Mip> {
    let mut mips = BTreeMap::new();
    for path in mip_files(dir) {
        let Ok(text) = std::fs::read_to_string(&path) else {
            continue;
        };
        if let Some(mip) = parse_mip(&text) {
            mips.insert(mip.num, mip);
        }
    }
    mips
}

pub fn render_mermaid(mips: &BTreeMap<u32, Mip>) -> String {
    let edges: BTreeSet<(u32, u32)> = mips
        .values()
        .flat_map(|m| {
            m.blocked_by
                .iter()
                .filter(|b| mips.contains_key(b))
                .map(move |b| (*b, m.num))
        })
        .collect();
    if edges.is_empty() {
        return NO_EDGES_PLACEHOLDER.into();
    }
    // Only the MIPs that actually participate in an edge get drawn — an orphan MIP adds a
    // disconnected box that tells the reader nothing the index table above doesn't already say.
    let connected: BTreeSet<u32> = edges.iter().flat_map(|(a, b)| [*a, *b]).collect();
    let mut lines = vec![
        "```mermaid".to_string(),
        "flowchart TD".into(),
        // Dark fills: the site is slate only and injects white label text (MIP-0068 §5.2).
        "  classDef draft fill:#1e2129,stroke:#8fa3b0,stroke-dasharray:3 3;".into(),
        "  classDef accepted fill:#0b4261,stroke:#1ac5da;".into(),
        "  classDef implemented fill:#0f4a2e,stroke:#3ecf6e;".into(),
        "  classDef rejected fill:#2a2d35,stroke:#666,color:#999;".into(),
    ];
    for num in &connected {
        let mip = &mips[num];
        // Bare "MIP-NNNN" only — the full title is already one line up in the index table.
        lines.push(format!(
            "  {}[\"MIP-{num:04}\"]:::{}",
            mip.node_id(),
            mip.status_class()
        ));
    }
    for (a, b) in &edges {
        lines.push(format!("  {} --> {}", mips[a].node_id(), mips[b].node_id()));
    }
    lines.push("```".into());
    let orphans: Vec<u32> = mips
        .keys()
        .filter(|n| !connected.contains(n))
        .copied()
        .collect();
    if !orphans.is_empty() {
        let list = orphans
            .iter()
            .map(|n| format!("MIP-{n:04}"))
            .collect::<Vec<_>>()
            .join(", ");
        lines.push(String::new());
        lines.push(format!(
            "_{} MIP(s) with no declared Blocked-by relationship, not graphed: {list}._",
            orphans.len()
        ));
    }
    lines.join("\n")
}

pub fn graph_block(mips: &BTreeMap<u32, Mip>) -> String {
    format!("{START_MARK}\n{}\n{END_MARK}", render_mermaid(mips))
}

/// Every MIP transitively blocking (or blocked by, if `blocked_direction` is false) `start`.
pub fn reachable(mips: &BTreeMap<u32, Mip>, start: u32, blocked_direction: bool) -> BTreeSet<u32> {
    let mut seen = BTreeSet::new();
    let mut stack = vec![start];
    while let Some(cur) = stack.pop() {
        if !mips.contains_key(&cur) {
            continue;
        }
        let neighbors: Vec<u32> = if blocked_direction {
            mips[&cur].blocked_by.clone()
        } else {
            mips.values()
                .filter(|m| m.blocked_by.contains(&cur))
                .map(|m| m.num)
                .collect()
        };
        for n in neighbors {
            if seen.insert(n) {
                stack.push(n);
            }
        }
    }
    seen
}

fn source_paths(path: &Path) -> BTreeSet<String> {
    let text = std::fs::read_to_string(path).unwrap_or_default();
    SOURCE_PATH_RE
        .captures_iter(&text)
        .map(|c| c[1].to_string())
        .collect()
}

fn first_file(dir: &Path, num: u32) -> Option<PathBuf> {
    let prefix = format!("MIP-{num:04}-");
    let mut found: Vec<PathBuf> = std::fs::read_dir(dir)
        .into_iter()
        .flatten()
        .filter_map(Result::ok)
        .map(|e| e.path())
        .filter(|p| {
            let n = p.file_name().unwrap_or_default().to_string_lossy();
            n.starts_with(&prefix) && n.ends_with(".md")
        })
        .collect();
    found.sort();
    found.into_iter().next()
}

pub fn cmd_parallel(dir: &Path, a: u32, b: u32) -> i32 {
    let mips = load_mips(dir);
    if let Some(missing) = [a, b].into_iter().find(|n| !mips.contains_key(n)) {
        println!("mip_graph --parallel: MIP-{missing:04} not found");
        return 1;
    }
    let dep_conflict =
        reachable(&mips, a, true).contains(&b) || reachable(&mips, b, true).contains(&a);
    let paths_a = first_file(dir, a)
        .map(|p| source_paths(&p))
        .unwrap_or_default();
    let paths_b = first_file(dir, b)
        .map(|p| source_paths(&p))
        .unwrap_or_default();
    let overlap: Vec<&String> = paths_a.intersection(&paths_b).collect();
    println!("MIP-{a:04} vs MIP-{b:04}:");
    println!(
        "  dependency conflict (Blocked by graph): {}",
        if dep_conflict { "yes" } else { "no" }
    );
    if overlap.is_empty() {
        println!("  §5 source-path overlap: no (or no backticked source paths found in one/both)");
    } else {
        let list: Vec<&str> = overlap.iter().map(|s| s.as_str()).collect();
        println!("  §5 source-path overlap: yes — {}", list.join(", "));
    }
    if !dep_conflict && overlap.is_empty() {
        println!("  -> parallel-safe on both checks");
        return 0;
    }
    println!("  -> NOT confirmed parallel-safe");
    1
}

pub fn cmd_generate(dir: &Path, readme: &Path, check: bool) -> i32 {
    let Ok(text) = std::fs::read_to_string(readme) else {
        eprintln!("mip_graph: {} not found", readme.display());
        return 1;
    };
    let mips = load_mips(dir);
    let new_block = graph_block(&mips);
    let new_text = match text.split_once(START_MARK).and_then(|(pre, rest)| {
        rest.split_once(END_MARK)
            .map(|(_, post)| format!("{pre}{new_block}{post}"))
    }) {
        Some(t) => t,
        None => {
            let sep = if text.ends_with('\n') { "" } else { "\n" };
            format!("{text}{sep}\n{new_block}\n")
        }
    };
    if check {
        if new_text != text {
            println!(
                "mip_graph --check: docs/MIPs/README.md's graph block is stale or missing — run `just mip-graph` and commit the result"
            );
            return 1;
        }
        println!("mip_graph --check: graph block is current");
        return 0;
    }
    if new_text != text {
        if let Err(e) = std::fs::write(readme, &new_text) {
            eprintln!("mip_graph: {}: {e}", readme.display());
            return 1;
        }
        println!(
            "mip_graph: wrote graph block to {} ({} MIPs)",
            readme.display(),
            mips.len()
        );
    } else {
        println!("mip_graph: graph block already current, nothing written");
    }
    0
}

pub fn main(check: bool, parallel: Option<(u32, u32)>) -> i32 {
    let dir = repo_root().join("docs").join("MIPs");
    match parallel {
        Some((a, b)) => cmd_parallel(&dir, a, b),
        None => cmd_generate(&dir, &dir.join("README.md"), check),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses() {
        let mip = parse_mip(
            "# MIP-0099: A sample title\n\n| | |\n|---|---|\n| **Status** | Draft |\n| **Blocked by** | MIP-0010, MIP-0025 |\n"
        )
        .unwrap();
        assert_eq!((mip.num, mip.title.as_str()), (99, "A sample title"));
        assert_eq!(mip.blocked_by, vec![10, 25]);
        let none =
            parse_mip("# MIP-0098: Another\n\n| | |\n|---|---|\n| **Blocked by** | none |\n");
        assert_eq!(none.unwrap().blocked_by, Vec::<u32>::new());
        let no_row = parse_mip("# MIP-0097: No row\n\n| | |\n|---|---|\n| **Status** | Draft |\n");
        assert_eq!(no_row.unwrap().blocked_by, Vec::<u32>::new());
        let status = parse_mip("# MIP-0096: S\n| **Status** | Implemented, verified (x) |\n");
        assert_eq!(status.unwrap().status, "Implemented");
    }

    #[test]
    fn renders() {
        let mips = BTreeMap::from([
            (10, Mip::new(10, "Ledger", "Implemented", vec![])),
            (32, Mip::new(32, "Benchmark", "Draft", vec![10])),
        ]);
        let block = graph_block(&mips);
        assert!(block.starts_with(START_MARK) && block.ends_with(END_MARK));
        assert!(block.contains("M0010 --> M0032"));
        assert!(block.contains(":::implemented") && block.contains(":::draft"));
        assert!(block.contains("\"MIP-0010\""));
        assert!(!block.contains("Ledger") && !block.contains("Benchmark"));
        assert_eq!(reachable(&mips, 32, true), BTreeSet::from([10]));

        let zero = BTreeMap::from([
            (1, Mip::new(1, "A", "Draft", vec![])),
            (2, Mip::new(2, "B", "Draft", vec![])),
        ]);
        let zero_block = graph_block(&zero);
        assert!(!zero_block.contains("```mermaid") && zero_block.contains("nothing to graph yet"));

        let mut orphans = mips.clone();
        orphans.insert(1, Mip::new(1, "Orphan", "Draft", vec![]));
        let ob = graph_block(&orphans);
        assert!(!ob.contains("M0001") && ob.contains("MIP-0001"));
    }

    #[test]
    fn generate_and_check() {
        let d = tempfile::tempdir().unwrap();
        let readme = d.path().join("README.md");
        std::fs::write(
            &readme,
            "before\n<!-- mip-graph:start -->\nold\n<!-- mip-graph:end -->\nafter\n",
        )
        .unwrap();
        std::fs::write(d.path().join("MIP-0010-a.md"), "# MIP-0010: A\n").unwrap();
        std::fs::write(
            d.path().join("MIP-0032-b.md"),
            "# MIP-0032: B\n| **Blocked by** | MIP-0010 |\n",
        )
        .unwrap();
        assert_eq!(cmd_generate(d.path(), &readme, true), 1);
        assert_eq!(cmd_generate(d.path(), &readme, false), 0);
        assert_eq!(cmd_generate(d.path(), &readme, true), 0);
        let text = std::fs::read_to_string(&readme).unwrap();
        assert!(
            text.starts_with("before\n")
                && text.ends_with("after\n")
                && text.contains("M0010 --> M0032")
        );
    }

    #[test]
    fn the_committed_graph_is_current() {
        let dir = repo_root().join("docs/MIPs");
        assert_eq!(cmd_generate(&dir, &dir.join("README.md"), true), 0);
    }
}
