//! The README's CI-health and Rust LOC badges and marola-app's Scala LOC badge, as shields.io endpoint JSON.
//!
//!     marola-tools repo-stats write --out-dir stats \
//!         --repo marola-dev/marola --run-id 123 --exclude-job repo-stats
//!     marola-tools repo-stats write --out-dir stats     # LOC only (no --run-id: no CI badge)
//!
//! Each file is a shields.io "endpoint" document (`{"schemaVersion": 1, "label": ..., "message":
//! ..., "color": ...}`), published to marola-site's `site-data` branch and copied into the map by
//! its site.yml, where the READMEs read them live:
//!
//!     ci.json               "ci steps"        19/20 green
//!     scala-loc.json        "scala"           6,865 LOC
//!     rust-loc.json         "rust"            4,185 LOC   (this repo's tools/)
//!
//! CI health is *step*-level, not job-level: "build-test passed" hides which of its steps actually
//! ran. Only steps that ran count — a step whose `conclusion` is `skipped` (most of them are gated
//! on `needs.changes.outputs.*`) or still `null` (this job's own later steps) is left out of both
//! numerator and denominator, so a docs-only push reports 8/8 rather than a misleading 8/20.
//! `--exclude-job` drops the reporting job itself, whose steps are still running while it asks.
//!
//! LOC is `cloc` (from `nix develop .#lint`, locally and in ci.yml), code lines only — blanks and
//! comments excluded by cloc, and `target/` and `node_modules/` excluded by path.

use crate::util::dumps;
use serde_json::{Value, json};
use std::path::{Path, PathBuf};
use std::process::Command;

// The app is the marola-app submodule (MIP-0070 task 15); ci.yml checks it out for the count.
const SCALA_PATHS: [&str; 3] = ["marola-app/core", "marola-app/local", "marola-app/cli"];
const EXCLUDE_DIRS: [&str; 2] = ["target", "node_modules"];
const SCALA_COLOR: &str = "DC322F"; // = marola-app's README's hand-written Scala badge
// The umbrella's own tooling (marola-tools); AGENTS.md: no Python here, Rust only.
const RUST_PATHS: [&str; 1] = ["tools/src"];
const RUST_COLOR: &str = "DEA584"; // = shields.io's Rust language color

// A step that reached one of these actually executed; anything else (`skipped`, `neutral`, or a
// null conclusion for a step still queued/running) is not evidence either way and is not counted.
const RAN: [&str; 4] = ["success", "failure", "cancelled", "timed_out"];

/// One shields.io endpoint document (https://shields.io/badges/endpoint-badge).
pub fn badge(label: &str, message: &str, color: &str) -> Value {
    json!({"schemaVersion": 1, "label": label, "message": message, "color": color})
}

/// (green, ran) over every step of every job of one run — skipped/unfinished steps ignored.
pub fn count_steps(jobs: &[Value], exclude_job: Option<&str>) -> (u64, u64) {
    let (mut green, mut ran) = (0, 0);
    for job in jobs {
        if exclude_job.is_some_and(|x| job.get("name").and_then(Value::as_str) == Some(x)) {
            continue;
        }
        for step in job
            .get("steps")
            .and_then(Value::as_array)
            .into_iter()
            .flatten()
        {
            let Some(conclusion) = step.get("conclusion").and_then(Value::as_str) else {
                continue;
            };
            if !RAN.contains(&conclusion) {
                continue;
            }
            ran += 1;
            if conclusion == "success" {
                green += 1;
            }
        }
    }
    (green, ran)
}

/// Green only when every step that ran passed — a single red step is not a rounding error.
pub fn ci_badge(green: u64, ran: u64) -> Value {
    if ran == 0 {
        return badge("ci steps", "no data", "lightgrey");
    }
    let color = if green == ran {
        "brightgreen"
    } else if green as f64 >= 0.9 * ran as f64 {
        "yellow"
    } else {
        "red"
    };
    badge("ci steps", &format!("{green}/{ran} green"), color)
}

fn thousands(n: u64) -> String {
    let digits = n.to_string();
    let mut out = String::new();
    for (i, c) in digits.chars().enumerate() {
        if i > 0 && (digits.len() - i).is_multiple_of(3) {
            out.push(',');
        }
        out.push(c);
    }
    out
}

pub fn loc_badge(label: &str, code: u64, color: &str) -> Value {
    badge(label, &format!("{} LOC", thousands(code)), color)
}

/// Code lines for one language out of `cloc --json`; 0 when it found none of that language.
pub fn parse_cloc(payload: &str, language: &str) -> Result<u64, String> {
    let v: Value = serde_json::from_str(payload).map_err(|e| e.to_string())?;
    Ok(v.get(language)
        .and_then(|l| l.get("code"))
        .and_then(Value::as_u64)
        .unwrap_or(0))
}

/// Every job of one workflow run, steps included. Needs `actions: read` on the token.
fn fetch_jobs(repo: &str, run_id: &str) -> Result<Vec<Value>, String> {
    let mut jobs = Vec::new();
    for page in 1.. {
        let url = format!("repos/{repo}/actions/runs/{run_id}/jobs?per_page=100&page={page}");
        let out = Command::new("gh")
            .args(["api", "-H", "Accept: application/vnd.github+json", &url])
            .output()
            .map_err(|e| format!("repo_stats: gh: {e}"))?;
        if !out.status.success() {
            return Err(format!(
                "repo_stats: gh api {url}: {}",
                String::from_utf8_lossy(&out.stderr)
            ));
        }
        let v: Value = serde_json::from_slice(&out.stdout).map_err(|e| e.to_string())?;
        let batch = v
            .get("jobs")
            .and_then(Value::as_array)
            .cloned()
            .unwrap_or_default();
        let n = batch.len();
        jobs.extend(batch);
        if n < 100 {
            break;
        }
    }
    Ok(jobs)
}

/// Code lines of `language` under `paths`; a path that does not exist is simply skipped.
fn cloc_code(paths: &[&str], language: &str, root: &Path) -> Result<u64, String> {
    let present: Vec<&str> = paths
        .iter()
        .copied()
        .filter(|p| root.join(p).exists())
        .collect();
    if present.is_empty() {
        return Ok(0);
    }
    let out = Command::new("cloc")
        .args(["--json", "--quiet", &format!("--exclude-dir={}", EXCLUDE_DIRS.join(",")), &format!("--include-lang={language}")])
        .args(&present)
        .current_dir(root)
        .output()
        .map_err(|_| "repo_stats: `cloc` is not on PATH (nix develop has it, and ci.yml takes it from nix develop .#lint)".to_string())?;
    if !out.status.success() {
        return Err(format!(
            "repo_stats: cloc: {}",
            String::from_utf8_lossy(&out.stderr)
        ));
    }
    let text = String::from_utf8_lossy(&out.stdout);
    // cloc prints nothing at all when no file of that language survived the filters.
    if text.trim().is_empty() {
        Ok(0)
    } else {
        parse_cloc(&text, language)
    }
}

pub fn write(out_dir: &Path, badges: &[(&str, Value)]) -> std::io::Result<Vec<PathBuf>> {
    std::fs::create_dir_all(out_dir)?;
    let mut written = Vec::new();
    for (name, doc) in badges {
        let path = out_dir.join(name);
        std::fs::write(&path, dumps(doc, None, true) + "\n")?;
        written.push(path);
    }
    Ok(written)
}

pub fn main(
    out_dir: &Path,
    root: &Path,
    repo: &str,
    run_id: Option<&str>,
    exclude_job: Option<&str>,
) -> i32 {
    let result = (|| -> Result<Vec<PathBuf>, String> {
        let mut badges = vec![
            (
                "scala-loc.json",
                loc_badge(
                    "scala",
                    cloc_code(&SCALA_PATHS, "Scala", root)?,
                    SCALA_COLOR,
                ),
            ),
            (
                "rust-loc.json",
                loc_badge("rust", cloc_code(&RUST_PATHS, "Rust", root)?, RUST_COLOR),
            ),
        ];
        if let Some(run_id) = run_id {
            let (green, ran) = count_steps(&fetch_jobs(repo, run_id)?, exclude_job);
            badges.push(("ci.json", ci_badge(green, ran)));
        }
        write(out_dir, &badges).map_err(|e| e.to_string())
    })();
    match result {
        Ok(paths) => {
            for p in paths {
                println!(
                    "{}: {}",
                    p.display(),
                    std::fs::read_to_string(&p).unwrap_or_default().trim()
                );
            }
            0
        }
        Err(e) => {
            eprintln!("{e}");
            1
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn counts_steps() {
        let jobs = vec![
            json!({"name": "build-test", "steps": [
                {"name": "checkout", "conclusion": "success"},
                {"name": "compile+test", "conclusion": "success"},
                {"name": "coverage", "conclusion": "skipped"}
            ]}),
            json!({"name": "quality-other", "steps": [
                {"name": "fmt", "conclusion": "success"},
                {"name": "actionlint", "conclusion": "failure"},
                {"name": "hadolint", "conclusion": "skipped"},
                {"name": "docker compose config", "conclusion": null}
            ]}),
            json!({"name": "repo-stats", "steps": [
                {"name": "checkout", "conclusion": "success"},
                {"name": "badges", "conclusion": null}
            ]}),
        ];
        assert_eq!(count_steps(&jobs, Some("repo-stats")), (3, 4));
        assert_eq!(count_steps(&jobs, None), (4, 5));
        assert_eq!(
            count_steps(
                &[
                    json!({"name": "changes"}),
                    json!({"name": "x", "steps": null})
                ],
                None
            ),
            (0, 0)
        );
        let mut steps = vec![json!({"conclusion": "success"}); 2];
        steps.extend(vec![json!({"conclusion": "skipped"}); 7]);
        let docs_only = vec![json!({"name": "quality-other", "steps": steps})];
        assert_eq!(count_steps(&docs_only, None), (2, 2));
        assert_eq!(ci_badge(2, 2)["message"], "2/2 green");
    }

    #[test]
    fn shapes_badges() {
        assert_eq!(
            ci_badge(20, 20),
            json!({"schemaVersion": 1, "label": "ci steps", "message": "20/20 green", "color": "brightgreen"})
        );
        assert_eq!(ci_badge(19, 20)["color"], "yellow");
        assert_eq!(ci_badge(17, 20)["color"], "red");
        assert_eq!(
            ci_badge(0, 0),
            json!({"schemaVersion": 1, "label": "ci steps", "message": "no data", "color": "lightgrey"})
        );
        assert_eq!(
            loc_badge("scala", 6865, SCALA_COLOR)["message"],
            "6,865 LOC"
        );
        assert_eq!(
            loc_badge("scala", 1_234_567, SCALA_COLOR)["message"],
            "1,234,567 LOC"
        );
        assert_eq!(loc_badge("scala", 0, SCALA_COLOR)["message"], "0 LOC");
        let cloc = r#"{"header": {"cloc_version": "2.10"}, "Scala": {"nFiles": 84, "blank": 1050, "comment": 1673, "code": 6865}}"#;
        assert_eq!(parse_cloc(cloc, "Scala"), Ok(6865));
        assert_eq!(parse_cloc(cloc, "Rust"), Ok(0));
    }

    #[test]
    fn writes_whole_documents() {
        let t = tempfile::tempdir().unwrap();
        let out = t.path().join("stats");
        let paths = write(
            &out,
            &[
                ("ci.json", ci_badge(20, 20)),
                ("scala-loc.json", loc_badge("s", 1, "x")),
            ],
        )
        .unwrap();
        assert_eq!(paths.len(), 2);
        let ci = std::fs::read_to_string(out.join("ci.json")).unwrap();
        assert_eq!(
            ci,
            "{\"schemaVersion\": 1, \"label\": \"ci steps\", \"message\": \"20/20 green\", \"color\": \"brightgreen\"}\n"
        );
        write(&out, &[("ci.json", ci_badge(1, 2))]).unwrap();
        assert!(
            std::fs::read_to_string(out.join("ci.json"))
                .unwrap()
                .contains("1/2 green")
        );
    }
}
