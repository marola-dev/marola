//! Research digests: cache arXiv papers relevant to marola's forecasting domain, and GitHub repo
//! candidates for docs/4-Research-and-plans/AWESOME-AGENTIC-ENGINEERING.md.
//!
//!     marola-tools arxiv-digest [--max-results 5] [--json]
//!     marola-tools awesome-agentic-digest [--max-results 5] [--json]
//!
//! Both cache the way `MadsLorentzen/ai-job-search`'s job tracker does (surveyed in
//! docs/4-Research-and-plans/SELF-DOCUMENTING.md): one flat JSON file per item, keyed by its id, so a
//! re-run never re-fetches or duplicates an already-seen item, plus one flat JSONL index a human
//! or another tool can grep/tail without touching the per-item files. Both caches live under .tmp/
//! (gitignored — fetched data, not source), and the index is rewritten each run from the per-item
//! files, so it never drifts from them.
//!
//! arXiv: queries export.arxiv.org/api/query (Atom XML) for oceanography, sea/ocean condition
//! forecasting, jellyfish/marine-life prediction, and LLM-for-forecasting papers — a feeder for
//! MIP research (MIP-0019). Cache: .tmp/arxiv_cache/papers/<arxiv-id-sans-version>.json.
//!
//! Awesome agentic: queries the GitHub Search API across agentic-engineering topics (MIP-0043 §4.2)
//! for candidates. **It never writes to AWESOME-AGENTIC-ENGINEERING.md**: a human reviews the cache
//! and hand-writes any good match into the curated doc. GitHub's search API needs no token for
//! this small, fixed query set, but its rate limit is tight, so there is no retry-hammering.
//! Cache: .tmp/awesome_agentic_cache/repos/<owner>__<repo>.json.

use crate::util::{dumps, repo_root, urlencode};
use serde_json::{Map, Value, json};
use std::collections::BTreeSet;
use std::path::{Path, PathBuf};
use std::time::Duration;

const ATOM_NS: &str = "http://www.w3.org/2005/Atom";
const ARXIV_API: &str = "https://export.arxiv.org/api/query";
const GITHUB_API: &str = "https://api.github.com/search/repositories";

// (label, arXiv search_query, weight). Verified against the live API on 2026-09-06 — every one of
// these returns real, on-topic results (some cross-domain noise is expected and scored down, not
// filtered out, per MIP-0019's honesty about query precision).
const ARXIV_QUERIES: [(&str, &str, u64); 7] = [
    ("ocean-physics", "cat:physics.ao-ph AND abs:ocean", 1),
    (
        "sst-forecast",
        r#"abs:"sea surface temperature" AND abs:forecast"#,
        2,
    ),
    (
        "llm-ocean",
        r#"abs:"large language model" AND abs:ocean"#,
        3,
    ),
    (
        "llm-weather-forecast",
        r#"abs:"large language model" AND abs:"weather forecasting""#,
        3,
    ),
    (
        "jellyfish-prediction",
        "abs:jellyfish AND abs:prediction",
        2,
    ),
    ("marine-heatwave", r#"abs:"marine heatwave""#, 2),
    (
        "ao-ph-ml",
        r#"cat:physics.ao-ph AND abs:"machine learning""#,
        1,
    ),
];

// (label, GitHub search query, weight). Verified live on 2026-09-07 (MIP-0043 §4.2's
// total_count table). Two sort orders per label (stars, then recently-updated) feed one cache.
const GITHUB_QUERIES: [(&str, &str, u64); 5] = [
    ("agents", "topic:agents", 1),
    ("llm-agents", "topic:llm-agents", 2),
    ("ai-agents", "topic:ai-agents", 1),
    ("mcp", "topic:mcp", 2),
    ("multi-agent-systems", "topic:multi-agent-systems", 2),
];
const SORTS: [&str; 2] = ["stars", "updated"];

/// One kind of cache: where it lives, what an item's id is, and which fields its index keeps.
struct Store {
    dir: PathBuf,
    items: &'static str,
    index_fields: &'static [&'static str],
}

impl Store {
    fn arxiv(root: &Path) -> Self {
        Store {
            dir: root.join(".tmp/arxiv_cache"),
            items: "papers",
            index_fields: &[
                "arxiv_id",
                "title",
                "published",
                "relevance_score",
                "fetched_at",
            ],
        }
    }

    fn awesome(root: &Path) -> Self {
        Store {
            dir: root.join(".tmp/awesome_agentic_cache"),
            items: "repos",
            index_fields: &[
                "full_name",
                "html_url",
                "stars",
                "description",
                "relevance_score",
                "fetched_at",
            ],
        }
    }

    fn items_dir(&self) -> PathBuf {
        self.dir.join(self.items)
    }

    fn init(&self) -> std::io::Result<()> {
        std::fs::create_dir_all(self.items_dir())
    }

    fn cached_ids(&self) -> BTreeSet<String> {
        self.item_files()
            .iter()
            .filter_map(|p| p.file_stem().map(|s| s.to_string_lossy().into_owned()))
            .collect()
    }

    fn item_files(&self) -> Vec<PathBuf> {
        let mut files: Vec<PathBuf> = std::fs::read_dir(self.items_dir())
            .into_iter()
            .flatten()
            .filter_map(Result::ok)
            .map(|e| e.path())
            .filter(|p| p.extension().is_some_and(|e| e == "json"))
            .collect();
        files.sort();
        files
    }

    /// Rewrite index.jsonl from the per-item files, highest relevance first (then most stars).
    fn write_index(&self) -> Result<(), String> {
        let mut rows = Vec::new();
        for f in self.item_files() {
            let text = std::fs::read_to_string(&f).map_err(|e| e.to_string())?;
            let data: Value =
                serde_json::from_str(&text).map_err(|e| format!("{}: {e}", f.display()))?;
            let mut row = Map::new();
            for k in self.index_fields {
                row.insert(
                    (*k).into(),
                    data.get(*k)
                        .cloned()
                        .ok_or(format!("{}: no {k}", f.display()))?,
                );
            }
            rows.push(Value::Object(row));
        }
        let key = |r: &Value| {
            (
                r["relevance_score"].as_u64().unwrap_or(0),
                r.get("stars").and_then(Value::as_u64).unwrap_or(0),
            )
        };
        rows.sort_by_key(|r| std::cmp::Reverse(key(r)));
        let lines: String = rows.iter().map(|r| dumps(r, None, false) + "\n").collect();
        std::fs::write(self.dir.join("index.jsonl"), lines).map_err(|e| e.to_string())
    }

    /// Cache every item not seen before, then rewrite the index; returns how many were new.
    fn store(&self, found: Vec<Hit<'_>>) -> Result<usize, String> {
        let already = self.cached_ids();
        let mut new = 0;
        for (id, item, matched) in found {
            if already.contains(&id) {
                continue;
            }
            let mut record = item;
            record.insert(
                "matched_queries".into(),
                json!(matched.iter().map(|(l, _)| *l).collect::<Vec<_>>()),
            );
            record.insert("relevance_score".into(), json!(relevance_score(&matched)));
            record.insert("fetched_at".into(), json!(now_iso()));
            let path = self.items_dir().join(format!("{id}.json"));
            std::fs::write(path, dumps(&Value::Object(record), Some(2), false))
                .map_err(|e| e.to_string())?;
            new += 1;
        }
        self.write_index()?;
        Ok(new)
    }
}

/// `datetime.now(UTC).isoformat()`.
fn now_iso() -> String {
    chrono::Utc::now()
        .format("%Y-%m-%dT%H:%M:%S%.6f+00:00")
        .to_string()
}

/// Sum of the weights of every query that matched an item — one hit by two narrow queries
/// outscores one hit by a single broad query.
pub fn relevance_score(matched: &[(&str, u64)]) -> u64 {
    matched.iter().map(|(_, w)| w).sum()
}

/// http://arxiv.org/abs/2609.03658v1 -> 2609.03658 (version-stripped, safe as a filename).
pub fn arxiv_id_from_url(id_url: &str) -> String {
    let tail = id_url
        .trim_end_matches('/')
        .rsplit('/')
        .next()
        .unwrap_or("");
    let digits = tail.len() - tail.trim_end_matches(|c: char| c.is_ascii_digit()).len();
    if digits > 0 && tail[..tail.len() - digits].ends_with('v') {
        tail[..tail.len() - digits - 1].to_string()
    } else {
        tail.to_string()
    }
}

fn squash(s: &str) -> String {
    s.split_whitespace().collect::<Vec<_>>().join(" ")
}

/// Atom feed text -> paper records. Malformed XML is an error, never "no results".
pub fn parse_atom(xml: &str) -> Result<Vec<Map<String, Value>>, String> {
    let doc = roxmltree::Document::parse(xml).map_err(|e| format!("malformed Atom XML: {e}"))?;
    fn child<'a, 'i>(n: roxmltree::Node<'a, 'i>, tag: &str) -> Option<roxmltree::Node<'a, 'i>> {
        n.children().find(|c| c.has_tag_name((ATOM_NS, tag)))
    }
    let text =
        |n: Option<roxmltree::Node>| n.and_then(|n| n.text()).unwrap_or("").trim().to_string();
    let mut entries = Vec::new();
    for entry in doc
        .root_element()
        .children()
        .filter(|c| c.has_tag_name((ATOM_NS, "entry")))
    {
        let id_url = text(child(entry, "id"));
        if id_url.is_empty() {
            continue;
        }
        let authors: Vec<String> = entry
            .children()
            .filter(|c| c.has_tag_name((ATOM_NS, "author")))
            .filter_map(|a| child(a, "name"))
            .map(|n| text(Some(n)))
            .collect();
        let categories: Vec<&str> = entry
            .children()
            .filter(|c| c.has_tag_name((ATOM_NS, "category")))
            .filter_map(|c| c.attribute("term"))
            .filter(|t| !t.is_empty())
            .collect();
        let mut paper = Map::new();
        paper.insert("arxiv_id".into(), json!(arxiv_id_from_url(&id_url)));
        paper.insert("abs_url".into(), json!(id_url));
        paper.insert("title".into(), json!(squash(&text(child(entry, "title")))));
        paper.insert(
            "summary".into(),
            json!(squash(&text(child(entry, "summary")))),
        );
        paper.insert("authors".into(), json!(authors));
        paper.insert("categories".into(), json!(categories));
        paper.insert("published".into(), json!(text(child(entry, "published"))));
        entries.push(paper);
    }
    Ok(entries)
}

/// 'owner/repo' -> 'owner__repo', safe as a filename.
pub fn repo_key(full_name: &str) -> String {
    full_name.replace('/', "__")
}

/// GitHub Search API JSON text -> repo records. Malformed JSON, or no `items`, is an error.
pub fn parse_search_response(text: &str) -> Result<Vec<Map<String, Value>>, String> {
    let data: Value = serde_json::from_str(text).map_err(|e| format!("malformed JSON: {e}"))?;
    let items = data
        .get("items")
        .and_then(Value::as_array)
        .ok_or("GitHub Search API response missing 'items'")?;
    let mut repos = Vec::new();
    for item in items {
        let full_name = item.get("full_name").and_then(Value::as_str).unwrap_or("");
        if full_name.is_empty() {
            continue;
        }
        let mut repo = Map::new();
        repo.insert("full_name".into(), json!(full_name));
        repo.insert(
            "html_url".into(),
            item.get("html_url")
                .cloned()
                .unwrap_or_else(|| json!(format!("https://github.com/{full_name}"))),
        );
        repo.insert(
            "description".into(),
            json!(
                item.get("description")
                    .and_then(Value::as_str)
                    .unwrap_or("")
                    .trim()
            ),
        );
        repo.insert(
            "stars".into(),
            item.get("stargazers_count").cloned().unwrap_or(json!(0)),
        );
        repo.insert(
            "pushed_at".into(),
            item.get("pushed_at").cloned().unwrap_or(json!("")),
        );
        let topics = item
            .get("topics")
            .filter(|t| t.as_array().is_some_and(|a| !a.is_empty()));
        repo.insert("topics".into(), topics.cloned().unwrap_or(json!([])));
        repos.push(repo);
    }
    Ok(repos)
}

fn agent() -> ureq::Agent {
    ureq::AgentBuilder::new()
        .timeout(Duration::from_secs(20))
        .build()
}

fn get(url: &str, user_agent: &str, accept: Option<&str>) -> Result<String, String> {
    let mut req = agent().get(url).set("User-Agent", user_agent);
    if let Some(a) = accept {
        req = req.set("Accept", a);
    }
    req.call()
        .map_err(|e| e.to_string())?
        .into_string()
        .map_err(|e| e.to_string())
}

/// The queries that found an item, each with its weight.
type Matched<'a> = Vec<(&'a str, u64)>;
/// One found item: its id, its fields and the queries that found it.
type Hit<'a> = (String, Map<String, Value>, Matched<'a>);

/// Collects (id, item, matched queries) in first-seen order.
#[derive(Default)]
struct Found<'a> {
    order: Vec<String>,
    items: std::collections::HashMap<String, (Map<String, Value>, Matched<'a>)>,
}

impl<'a> Found<'a> {
    fn add(&mut self, id: String, item: Map<String, Value>, label: &'a str, weight: u64) {
        let entry = self.items.entry(id.clone()).or_insert_with(|| {
            self.order.push(id);
            (item, Vec::new())
        });
        entry.1.push((label, weight));
    }

    fn into_list(mut self) -> Vec<Hit<'a>> {
        self.order
            .into_iter()
            .map(|id| {
                let (item, matched) = self.items.remove(&id).unwrap();
                (id, item, matched)
            })
            .collect()
    }
}

fn summary(store: &Store, run: usize, failed: Vec<String>, new_key: &str, new: usize) -> Value {
    let mut s = Map::new();
    s.insert("queries_run".into(), json!(run));
    s.insert("queries_failed".into(), json!(failed));
    s.insert(new_key.into(), json!(new));
    s.insert("total_cached".into(), json!(store.cached_ids().len()));
    s.insert(
        "index_path".into(),
        json!(store.dir.join("index.jsonl").display().to_string()),
    );
    Value::Object(s)
}

pub fn arxiv(max_results: u32, as_json: bool) -> Result<(), String> {
    let store = Store::arxiv(&repo_root());
    store.init().map_err(|e| e.to_string())?;
    let mut found = Found::default();
    let mut errors = Vec::new();
    for (label, query, weight) in ARXIV_QUERIES {
        let params = urlencode(&[
            ("search_query", query.to_string()),
            ("start", "0".into()),
            ("max_results", max_results.to_string()),
            ("sortBy", "submittedDate".into()),
            ("sortOrder", "descending".into()),
        ]);
        let xml = match get(
            &format!("{ARXIV_API}?{params}"),
            "marola-arxiv-digest/1",
            None,
        ) {
            Ok(x) => x,
            Err(e) => {
                errors.push(format!("{label}: {e}"));
                continue;
            }
        };
        for paper in parse_atom(&xml)? {
            let id = paper["arxiv_id"].as_str().unwrap_or("").to_string();
            found.add(id, paper, label, weight);
        }
    }
    let new = store.store(found.into_list())?;
    let run = ARXIV_QUERIES.len() - errors.len();
    let s = summary(&store, run, errors, "new_papers", new);
    report(&s, as_json, ARXIV_QUERIES.len(), "new papers cached");
    Ok(())
}

pub fn awesome(max_results: u32, as_json: bool) -> Result<(), String> {
    let store = Store::awesome(&repo_root());
    store.init().map_err(|e| e.to_string())?;
    let mut found = Found::default();
    let mut errors = Vec::new();
    for (label, query, weight) in GITHUB_QUERIES {
        for sort in SORTS {
            let params = urlencode(&[
                ("q", query.to_string()),
                ("sort", sort.into()),
                ("order", "desc".into()),
                ("per_page", max_results.to_string()),
            ]);
            let url = format!("{GITHUB_API}?{params}");
            let text = match get(
                &url,
                "marola-awesome-agentic-digest/1",
                Some("application/vnd.github+json"),
            ) {
                Ok(t) => t,
                Err(e) => {
                    errors.push(format!("{label} ({sort}): {e}"));
                    continue;
                }
            };
            for repo in parse_search_response(&text)? {
                let id = repo_key(repo["full_name"].as_str().unwrap_or(""));
                found.add(id, repo, label, weight);
            }
        }
    }
    let new = store.store(found.into_list())?;
    let total = GITHUB_QUERIES.len() * SORTS.len();
    let s = summary(&store, total - errors.len(), errors, "new_candidates", new);
    report(&s, as_json, total, "new candidates cached");
    Ok(())
}

fn report(s: &Value, as_json: bool, total: usize, new_label: &str) {
    if as_json {
        println!("{}", dumps(s, Some(2), true));
        return;
    }
    println!("queries run: {}/{total}", s["queries_run"]);
    let failed = s["queries_failed"].as_array().map_or(0, Vec::len);
    if failed > 0 {
        println!("queries failed: {}", s["queries_failed"]);
    }
    let new = s
        .get("new_papers")
        .or_else(|| s.get("new_candidates"))
        .cloned()
        .unwrap_or(json!(0));
    println!("{new_label}: {new}");
    println!("total cached: {}", s["total_cached"]);
    println!("index: {}", s["index_path"].as_str().unwrap_or(""));
}

#[cfg(test)]
mod tests {
    use super::*;

    const ATOM: &str = r#"<?xml version='1.0' encoding='UTF-8'?>
<feed xmlns:opensearch="http://a9.com/-/spec/opensearch/1.1/" xmlns:arxiv="http://arxiv.org/schemas/atom" xmlns="http://www.w3.org/2005/Atom">
  <entry>
    <id>http://arxiv.org/abs/2609.03658v1</id>
    <title>  A Deep Learning Model for
      Forecasting  Sea Surface Temperature</title>
    <updated>2026-09-03T10:56:02Z</updated>
    <summary>We forecast sea surface temperature anomalies with a transformer.</summary>
    <category term="physics.ao-ph" scheme="http://arxiv.org/schemas/atom"/>
    <published>2026-09-03T10:56:02Z</published>
    <author><name>Ada Lovelace</name></author>
    <author><name>Grace Hopper</name></author>
  </entry>
  <entry>
    <id>http://arxiv.org/abs/2601.00001v2</id>
    <title>Notify About Jellyfish Blooms via Sonar</title>
    <summary>A sonar pipeline to detect jellyfish blooms near beaches.</summary>
    <category term="cs.CV" scheme="http://arxiv.org/schemas/atom"/>
    <published>2026-01-01T00:00:00Z</published>
    <author><name>Marie Curie</name></author>
  </entry>
</feed>"#;

    #[test]
    fn parses_atom() {
        let entries = parse_atom(ATOM).unwrap();
        assert_eq!(entries.len(), 2);
        let e0 = &entries[0];
        assert_eq!(e0["arxiv_id"], "2609.03658");
        assert_eq!(
            e0["title"],
            "A Deep Learning Model for Forecasting Sea Surface Temperature"
        );
        assert_eq!(e0["authors"], json!(["Ada Lovelace", "Grace Hopper"]));
        assert_eq!(e0["categories"], json!(["physics.ao-ph"]));
        assert_eq!(entries[1]["arxiv_id"], "2601.00001");
        assert!(
            parse_atom("<not-xml").is_err(),
            "malformed XML is an error, not zero results"
        );
        assert_eq!(relevance_score(&[("ocean-physics", 1)]), 1);
        assert_eq!(
            relevance_score(&[("ocean-physics", 1), ("sst-forecast", 2)]),
            3
        );
    }

    #[test]
    fn parses_search() {
        let fixture = json!({"total_count": 2, "incomplete_results": false, "items": [
            {"full_name": "example-org/agent-critic", "html_url": "https://github.com/example-org/agent-critic",
             "description": "A reviewer/critic pattern for LLM agent pipelines.", "stargazers_count": 4200,
             "pushed_at": "2026-09-01T00:00:00Z", "topics": ["llm-agents", "mcp"]},
            {"full_name": "another-org/dspy-scala", "html_url": "https://github.com/another-org/dspy-scala",
             "description": "DSPy-style prompt compilation, replayed from Scala.", "stargazers_count": 130,
             "pushed_at": "2026-08-15T00:00:00Z", "topics": ["ai-agents"]}
        ]});
        let repos = parse_search_response(&fixture.to_string()).unwrap();
        assert_eq!(repos.len(), 2);
        assert_eq!(repos[0]["full_name"], "example-org/agent-critic");
        assert_eq!(repos[0]["stars"], 4200);
        assert_eq!(
            repo_key("example-org/agent-critic"),
            "example-org__agent-critic"
        );
        assert!(parse_search_response("{not json").is_err());
        assert!(parse_search_response(r#"{"total_count": 0}"#).is_err());
    }

    #[test]
    fn caches_and_dedups() {
        let t = tempfile::tempdir().unwrap();
        let store = Store::arxiv(t.path());
        store.init().unwrap();
        assert!(store.cached_ids().is_empty());
        let papers = parse_atom(ATOM).unwrap();
        let found: Vec<_> = papers
            .into_iter()
            .map(|p| {
                (
                    p["arxiv_id"].as_str().unwrap().to_string(),
                    p,
                    vec![("sst-forecast", 2)],
                )
            })
            .collect();
        assert_eq!(store.store(found.clone()).unwrap(), 2);
        assert_eq!(
            store.cached_ids(),
            BTreeSet::from(["2609.03658".to_string(), "2601.00001".to_string()])
        );
        assert_eq!(
            std::fs::read_to_string(t.path().join(".tmp/arxiv_cache/index.jsonl"))
                .unwrap()
                .lines()
                .count(),
            2
        );
        assert_eq!(
            store.store(found).unwrap(),
            0,
            "an already-cached id is skipped on refetch"
        );

        let repos = Store::awesome(t.path());
        repos.init().unwrap();
        let fixture = json!({"items": [
            {"full_name": "a/low", "stargazers_count": 1}, {"full_name": "b/high", "stargazers_count": 9}
        ]});
        let found: Vec<_> = parse_search_response(&fixture.to_string())
            .unwrap()
            .into_iter()
            .map(|r| {
                (
                    repo_key(r["full_name"].as_str().unwrap()),
                    r,
                    vec![("mcp", 2)],
                )
            })
            .collect();
        assert_eq!(repos.store(found).unwrap(), 2);
        let index =
            std::fs::read_to_string(t.path().join(".tmp/awesome_agentic_cache/index.jsonl"))
                .unwrap();
        assert!(
            index.lines().next().unwrap().contains("b/high"),
            "equal score: more stars first"
        );
    }

    #[test]
    fn ids_and_timestamps() {
        assert_eq!(
            arxiv_id_from_url("http://arxiv.org/abs/2609.03658v12"),
            "2609.03658"
        );
        assert_eq!(
            arxiv_id_from_url("http://arxiv.org/abs/2609.03658"),
            "2609.03658"
        );
        assert!(now_iso().ends_with("+00:00"));
    }
}
