//! Assemble the files ci.yml's graph job publishes to `routing-bundle` (MIP-0084 §5.3).
//!
//!     marola-tools routing-bundle --graph-dir DIR --out OUT --bench BENCH_OUTPUT \
//!         [--questions routing-bench.yaml] [--previous build.json] [--graphify VERSION] \
//!         [--repos-md docs/2-Building-marola/REPOS.md]
//!
//! DIR is `graph build`'s output with `wiring.json` beside it. OUT gets graph.json, wiring.json,
//! build.json (DIR's, plus the graphify version and the bench score, in total and per question type),
//! manifest.json, routing-digest.md, and whichever of DIR's doc_code.json, decisions.json and
//! meaning.json exist. Exit 1, writing nothing, when any type scores below --previous's
//! (MIP-0085 §5.1), or the total does when --previous has no types: the job then keeps the last bundle.

use crate::util::dumps;
use regex::Regex;
use serde_json::{Map, Value, json};
use std::collections::BTreeMap;
use std::path::{Path, PathBuf};
use std::sync::LazyLock;

const SCHEMA: u64 = 1;
const MAX_BYTES: u64 = 20 * (1 << 20); // route refuses a bundle file over this
const WARN_BYTES: u64 = 10 * (1 << 20); // §5.3: past this the large files move out of refs/heads/
const DIGEST_CHARS: usize = 100_000; // §5.3's 25k-token cap, at about 4 characters a token
const START: &str = "<!-- wiring:start -->";
const END: &str = "<!-- wiring:end -->";
// MIP-0085's indexes: route says a missing one is missing, so each is published only when built.
const INDEXES: [&str; 3] = ["doc_code.json", "decisions.json", "meaning.json"];

static TOTAL_LINE: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"(?m)^right first: \d+/\d+$").unwrap());
static VERDICT: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"(?m)^(right|MISS)\s+(\S+)\s").unwrap());

pub struct Args {
    pub graph_dir: PathBuf,
    pub out: PathBuf,
    pub bench: PathBuf,
    pub questions: PathBuf,
    pub previous: Option<PathBuf>,
    pub graphify: String,
    pub repos_md: PathBuf,
}

/// A fatal problem: printed, exit 1, like the SystemExit it replaces.
pub struct Fatal(pub String);

fn py_str(v: Option<&Value>) -> String {
    match v {
        None | Some(Value::Null) => "None".into(),
        Some(Value::String(s)) => s.clone(),
        Some(Value::Bool(true)) => "True".into(),
        Some(Value::Bool(false)) => "False".into(),
        Some(other) => other.to_string(),
    }
}

fn truthy_str(v: Option<&Value>) -> Option<&str> {
    v.and_then(Value::as_str).filter(|s| !s.is_empty())
}

/// `route bench`'s output: the total and, by each question's type, right first of total.
pub fn scores(bench: &str, questions: &Path) -> Result<(u64, u64, Map<String, Value>), Fatal> {
    if !TOTAL_LINE.is_match(bench) {
        return Err(Fatal(
            "routing_bundle: no 'right first: N/T' line in the bench output".into(),
        ));
    }
    let qs: Value = if questions.is_file() {
        let text = std::fs::read_to_string(questions).map_err(|e| Fatal(e.to_string()))?;
        serde_json::from_str(&text)
            .map_err(|e| Fatal(format!("routing_bundle: {}: {e}", questions.display())))?
    } else {
        json!([])
    };
    let qs = match &qs {
        Value::Object(o) => o
            .get("questions")
            .and_then(Value::as_array)
            .cloned()
            .unwrap_or_default(),
        Value::Array(a) => a.clone(),
        _ => vec![],
    };
    // A question without a type is one of MIP-0084's eight: wiring, or a symbol query.
    let kind: BTreeMap<String, String> = qs
        .iter()
        .map(|q| {
            let t = truthy_str(q.get("type"))
                .map(str::to_string)
                .unwrap_or_else(|| {
                    if q.get("via").and_then(Value::as_str) == Some("wiring") {
                        "wiring"
                    } else {
                        "symbol"
                    }
                    .into()
                });
            (py_str(q.get("id")), t)
        })
        .collect();
    let mut by = Map::new();
    for c in VERDICT.captures_iter(bench) {
        let qid = &c[2];
        if qid.starts_with("first:") {
            continue;
        }
        let t = kind.get(qid).cloned().unwrap_or_else(|| "symbol".into());
        let entry = by
            .entry(t)
            .or_insert_with(|| json!({"right": 0, "total": 0}));
        entry["total"] = json!(entry["total"].as_u64().unwrap_or(0) + 1);
        if &c[1] == "right" {
            entry["right"] = json!(entry["right"].as_u64().unwrap_or(0) + 1);
        }
    }
    let sum = |k: &str| by.values().map(|t| t[k].as_u64().unwrap_or(0)).sum::<u64>();
    Ok((sum("right"), sum("total"), by))
}

fn right_of(v: Option<&Value>) -> u64 {
    v.and_then(|t| t.get("right"))
        .and_then(Value::as_u64)
        .unwrap_or(0)
}

pub fn drops(current: &Value, previous: &Value) -> Vec<String> {
    let Some(prev_by) = previous.get("by_type").and_then(Value::as_object) else {
        let prev_right = previous.get("right").and_then(Value::as_u64).unwrap_or(0);
        let cur_right = current.get("right").and_then(Value::as_u64).unwrap_or(0);
        return if cur_right >= prev_right {
            vec![]
        } else {
            vec!["total".into()]
        };
    };
    let mut types: Vec<(&String, &Value)> = prev_by.iter().collect();
    types.sort_by(|a, b| a.0.cmp(b.0));
    types
        .into_iter()
        .filter_map(|(t, p)| {
            let cur = right_of(current.get("by_type").and_then(|b| b.get(t)));
            let prev = right_of(Some(p));
            (cur < prev).then(|| format!("{t} {cur} < {prev}"))
        })
        .collect()
}

fn repo_of(source_file: &str) -> String {
    let top = source_file.split('/').next().unwrap_or("");
    if top.starts_with("marola-") && source_file.contains('/') {
        top.into()
    } else {
        "marola".into()
    }
}

fn basename(p: &str) -> &str {
    p.trim_end_matches('/').rsplit('/').next().unwrap_or(p)
}

pub fn digest(graph: &Value, wiring_block: &str) -> String {
    let mut files: BTreeMap<String, BTreeMap<String, Vec<(u64, String)>>> = BTreeMap::new();
    for n in graph
        .get("nodes")
        .and_then(Value::as_array)
        .into_iter()
        .flatten()
    {
        let src = truthy_str(n.get("source_file")).unwrap_or("");
        let label = truthy_str(n.get("label")).unwrap_or("");
        let is_code = match n.get("file_type") {
            None => true,
            Some(v) => v.as_str() == Some("code"),
        };
        if src.is_empty() || !is_code || label == basename(src) {
            continue;
        }
        let loc = match n.get("source_location") {
            None | Some(Value::Null) => String::new(),
            Some(Value::String(s)) => s.clone(),
            Some(Value::Bool(false)) => String::new(),
            Some(Value::Number(num)) if num.as_f64() == Some(0.0) => String::new(),
            Some(other) => py_str(Some(other)),
        };
        let digits: String = loc.chars().filter(char::is_ascii_digit).collect();
        let line = digits.parse::<u64>().unwrap_or(0);
        files
            .entry(repo_of(src))
            .or_default()
            .entry(src.into())
            .or_default()
            .push((line, label.into()));
    }
    let mut out = vec![
        "# marola routing digest".to_string(),
        String::new(),
        "## Wiring".into(),
        String::new(),
        wiring_block.trim().to_string(),
        String::new(),
    ];
    for (repo, srcs) in &files {
        out.push(format!("## {repo}"));
        out.push(String::new());
        for (src, entries) in srcs {
            let mut sorted = entries.clone();
            sorted.sort();
            let mut names: Vec<&str> = Vec::new();
            for (_, label) in &sorted {
                if !names.contains(&label.as_str()) {
                    names.push(label);
                }
            }
            out.push(format!("- `{src}`: {}", names.join(", ")));
        }
        out.push(String::new());
    }
    let mut text = out.join("\n");
    if text.chars().count() > DIGEST_CHARS {
        let cap_byte = text
            .char_indices()
            .nth(DIGEST_CHARS)
            .map_or(text.len(), |(i, _)| i);
        let cut = text[..cap_byte].rfind('\n').unwrap_or(0);
        text = format!(
            "{}\n\n_Cut at the 25k-token cap: `route query` has the rest._",
            &text[..cut]
        );
    }
    text + "\n"
}

fn wiring_block(repos_md: &Path) -> String {
    let text = if repos_md.is_file() {
        std::fs::read_to_string(repos_md).unwrap_or_default()
    } else {
        String::new()
    };
    let rest = text.split_once(START).map_or("", |(_, r)| r);
    match rest.split_once(END) {
        Some((block, _)) => block.to_string(),
        None => "_No wiring block found._".into(),
    }
}

fn read_json(p: &Path) -> Result<Value, Fatal> {
    let text = std::fs::read_to_string(p)
        .map_err(|e| Fatal(format!("routing_bundle: {}: {e}", p.display())))?;
    serde_json::from_str(&text).map_err(|e| Fatal(format!("routing_bundle: {}: {e}", p.display())))
}

pub fn assemble(args: &Args) -> Result<i32, Fatal> {
    let (src, out) = (&args.graph_dir, &args.out);
    let bench_text = std::fs::read_to_string(&args.bench)
        .map_err(|e| Fatal(format!("routing_bundle: {}: {e}", args.bench.display())))?;
    let (right, total, by_type) = scores(&bench_text, &args.questions)?;
    let bench = json!({"right": right, "total": total, "by_type": by_type});
    if let Some(prev) = args.previous.as_ref().filter(|p| p.is_file()) {
        let prev = read_json(prev)?
            .get("bench")
            .cloned()
            .filter(|b| !b.is_null())
            .unwrap_or(json!({}));
        let lower = drops(&bench, &prev);
        if !lower.is_empty() {
            println!(
                "routing_bundle: bench below the published one ({}); keeping it",
                lower.join(", ")
            );
            return Ok(1);
        }
    }
    let indexes: Vec<&str> = INDEXES
        .into_iter()
        .filter(|n| src.join(n).is_file())
        .collect();
    let mut names = vec!["graph.json", "wiring.json", "build.json"];
    names.extend(&indexes);
    for name in &names {
        let p = src.join(name);
        let Ok(meta) = std::fs::metadata(&p)
            .map_err(|_| ())
            .and_then(|m| if m.is_file() { Ok(m) } else { Err(()) })
        else {
            return Err(Fatal(format!("routing_bundle: {} is missing", p.display())));
        };
        if meta.len() > MAX_BYTES {
            return Err(Fatal(format!(
                "routing_bundle: {name} is over {} MB; route would refuse it",
                MAX_BYTES >> 20
            )));
        }
        if meta.len() > WARN_BYTES {
            println!(
                "::warning::{name} is over {} MB: time to move it out of refs/heads/ (§5.3)",
                WARN_BYTES >> 20
            );
        }
    }
    let io = |e: std::io::Error| Fatal(format!("routing_bundle: {e}"));
    std::fs::create_dir_all(out).map_err(io)?;
    let mut copied = vec!["graph.json", "wiring.json"];
    copied.extend(&indexes);
    for name in copied {
        std::fs::copy(src.join(name), out.join(name)).map_err(io)?;
    }
    let mut build = read_json(&src.join("build.json"))?;
    if let Some(obj) = build.as_object_mut() {
        obj.insert("graphify".into(), json!(args.graphify));
        obj.insert("bench".into(), bench);
    }
    std::fs::write(out.join("build.json"), dumps(&build, Some(1), true) + "\n").map_err(io)?;
    let graph = read_json(&src.join("graph.json"))?;
    std::fs::write(
        out.join("routing-digest.md"),
        digest(&graph, &wiring_block(&args.repos_md)),
    )
    .map_err(io)?;
    let mut entries: Vec<PathBuf> = std::fs::read_dir(out)
        .map_err(io)?
        .filter_map(Result::ok)
        .map(|e| e.path())
        .collect();
    entries.sort();
    let mut sizes = Map::new();
    for p in entries {
        let name = p
            .file_name()
            .unwrap_or_default()
            .to_string_lossy()
            .into_owned();
        if p.is_file() && name != "manifest.json" {
            sizes.insert(name, json!(std::fs::metadata(&p).map_err(io)?.len()));
        }
    }
    let count = sizes.len() + 1;
    let manifest = json!({"schema": SCHEMA, "files": sizes});
    std::fs::write(
        out.join("manifest.json"),
        dumps(&manifest, Some(1), true) + "\n",
    )
    .map_err(io)?;
    println!(
        "routing_bundle: bench {right}/{total}, {count} files in {}",
        out.display()
    );
    Ok(0)
}

pub fn main(args: &Args) -> i32 {
    match assemble(args) {
        Ok(code) => code,
        Err(Fatal(msg)) => {
            eprintln!("{msg}");
            1
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn graph() -> Value {
        json!({"nodes": [
            {"label": "Swimability", "source_file": "marola-app/core/Swimability.scala", "source_location": "L12", "file_type": "code"},
            {"label": "score", "source_file": "marola-app/core/Swimability.scala", "source_location": "L30", "file_type": "code"},
            {"label": "Swimability.scala", "source_file": "marola-app/core/Swimability.scala", "file_type": "code"},
            {"label": "release", "source_file": "scripts/release.sh", "source_location": "L4", "file_type": "code"},
            {"label": "Overview", "source_file": "docs/README.md", "file_type": "document"}
        ], "links": []})
    }

    #[test]
    fn assembles() {
        let d = tempfile::tempdir().unwrap();
        let d = d.path();
        let (src, out) = (d.join("graph"), d.join("out"));
        std::fs::create_dir(&src).unwrap();
        std::fs::write(src.join("graph.json"), graph().to_string()).unwrap();
        std::fs::write(src.join("wiring.json"), "{}").unwrap();
        let build = json!({"head": "a".repeat(40), "submodules": format!(" {} marola-app (heads/main)\n", "b".repeat(40))});
        std::fs::write(src.join("build.json"), build.to_string()).unwrap();
        std::fs::write(
            d.join("REPOS.md"),
            format!("intro\n{START}\n| artifact | by |\n{END}\nrest\n"),
        )
        .unwrap();
        let prev = d.join("prev.json");
        std::fs::write(&prev, r#"{"bench": {"right": 3, "total": 4}}"#).unwrap();
        let questions = d.join("bench.yaml");
        std::fs::write(
            &questions,
            json!({"questions": [
                {"id": 1, "via": "query", "ask": "A", "expect": ["a"]},
                {"id": 2, "via": "query", "ask": "B", "expect": ["b"]},
                {"id": 3, "via": "wiring", "ask": "C", "expect": ["c"]},
                {"id": 4, "type": "decision", "via": "why", "ask": "D", "expect": ["d"]}
            ]})
            .to_string(),
        )
        .unwrap();

        let bench_out = |results: &[&str]| -> PathBuf {
            let mut lines = vec!["answers from main@abc (bundle)".to_string()];
            for (i, r) in results.iter().enumerate() {
                lines.push(format!("{r:<5}  {}  q 'x'", i + 1));
            }
            let right = results.iter().filter(|r| **r == "right").count();
            lines.push(format!("right first: {right}/{}", results.len()));
            let f = d.join("bench.txt");
            std::fs::write(&f, lines.join("\n") + "\n").unwrap();
            f
        };
        let run = |bench: PathBuf, previous: Option<PathBuf>| {
            assemble(&Args {
                graph_dir: src.clone(),
                out: out.clone(),
                bench,
                questions: questions.clone(),
                previous,
                graphify: "0.9.84".into(),
                repos_md: d.join("REPOS.md"),
            })
            .unwrap_or_else(|Fatal(m)| panic!("{m}"))
        };

        let (r, t, by) = scores(
            &std::fs::read_to_string(bench_out(&["right", "MISS", "right", "right"])).unwrap(),
            &questions,
        )
        .unwrap_or_else(|Fatal(m)| panic!("{m}"));
        assert_eq!((r, t), (3, 4));
        assert_eq!(
            Value::Object(by),
            json!({"symbol": {"right": 1, "total": 2}, "wiring": {"right": 1, "total": 1}, "decision": {"right": 1, "total": 1}})
        );
        assert_eq!(
            run(
                bench_out(&["right", "MISS", "MISS", "right"]),
                Some(prev.clone())
            ),
            1
        );
        assert!(!out.exists(), "lower_score_keeps_previous");
        assert_eq!(
            run(
                bench_out(&["right", "MISS", "right", "right"]),
                Some(prev.clone())
            ),
            0
        );
        let b: Value =
            serde_json::from_str(&std::fs::read_to_string(out.join("build.json")).unwrap())
                .unwrap();
        assert_eq!(b["head"], json!("a".repeat(40)));
        assert_eq!(b["bench"]["right"], json!(3));
        assert_eq!(
            b["bench"]["by_type"]["symbol"],
            json!({"right": 1, "total": 2})
        );
        assert_eq!(b["graphify"], json!("0.9.84"));
        std::fs::copy(out.join("build.json"), &prev).unwrap();
        assert_eq!(
            run(
                bench_out(&["MISS", "right", "right", "right"]),
                Some(prev.clone())
            ),
            0
        );
        // The total holds at 3, symbol rises to 2 and wiring falls to 0: one type cannot pay for another.
        assert_eq!(
            run(
                bench_out(&["right", "right", "MISS", "right"]),
                Some(prev.clone())
            ),
            1
        );
        // session-check.sh reads build.json with grep, not a JSON parser.
        let raw = std::fs::read_to_string(out.join("build.json")).unwrap();
        assert!(raw.contains(&format!("\"head\": \"{}\"", "a".repeat(40))));
        assert!(raw.contains(&format!("{} marola-app ", "b".repeat(40))));
        let m: Value =
            serde_json::from_str(&std::fs::read_to_string(out.join("manifest.json")).unwrap())
                .unwrap();
        assert_eq!(m["schema"], json!(SCHEMA));
        let keys: Vec<&String> = m["files"].as_object().unwrap().keys().collect();
        assert_eq!(
            keys,
            vec![
                "build.json",
                "graph.json",
                "routing-digest.md",
                "wiring.json"
            ]
        );
        let dg = std::fs::read_to_string(out.join("routing-digest.md")).unwrap();
        assert!(dg.contains("| artifact | by |"));
        assert!(
            dg.contains("## marola-app")
                && dg.contains("`marola-app/core/Swimability.scala`: Swimability, score"),
            "{dg}"
        );
        assert!(dg.contains("## marola\n") && dg.contains("scripts/release.sh"));
        assert!(!dg.contains("Overview") && !dg.contains("Swimability.scala`: Swimability.scala"));
        assert_eq!(
            run(bench_out(&["right"; 4]), Some(d.join("absent.json"))),
            0
        );
        std::fs::write(src.join("decisions.json"), r#"{"rows": []}"#).unwrap();
        assert_eq!(run(bench_out(&["right"; 4]), None), 0);
        let m: Value =
            serde_json::from_str(&std::fs::read_to_string(out.join("manifest.json")).unwrap())
                .unwrap();
        assert!(
            m["files"].get("decisions.json").is_some() && m["files"].get("meaning.json").is_none()
        );
        assert!(m["files"].get("manifest.json").is_none());
        assert_eq!(
            std::fs::read_to_string(out.join("decisions.json")).unwrap(),
            r#"{"rows": []}"#
        );
        assert!(scores("no score\n", &questions).is_err());
    }

    #[test]
    fn digest_is_capped() {
        let nodes: Vec<Value> = (0..9000).map(|i| json!({"label": format!("s{i}"), "source_file": format!("f{i}.rs"), "file_type": "code"})).collect();
        assert!(digest(&json!({"nodes": nodes}), "").chars().count() <= DIGEST_CHARS + 100);
    }
}
