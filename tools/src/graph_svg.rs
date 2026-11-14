//! The graphify figure of the README: the umbrella's code graph (the `routing-bundle` branch's
//! graph.json, MIP-0084) drawn as one SVG, a dot per node and a line per link, coloured by repo.
//!
//!     marola-tools graph-svg --graph graph.json --out docs/img/graphify.svg
//!
//! The layout is Fruchterman–Reingold with a pull toward each repo's own spot on a ring, so every
//! repo stays one cluster. It is deterministic: the start positions come from a hash of each node
//! id, never from a clock, so the same graph.json always draws the same bytes.
//!
//! A node whose source file is gone from this checkout (deleted since the bundle was built) is left
//! out, unless its repo is a submodule that is not checked out, where nothing can be checked.

use serde_json::Value;
use std::collections::{BTreeMap, HashMap};
use std::fmt::Write as _;
use std::path::Path;

const W: f64 = 1600.0;
const H: f64 = 1000.0;
const ITERATIONS: usize = 300;
const PULL: f64 = 0.02;

/// Each repo's dot colour, in the order of the legend; anything else is grey.
const REPOS: [(&str, &str); 9] = [
    ("marola-app", "#DC322F"),
    ("marola-site", "#4CC9F0"),
    ("marola-ml", "#F2C14E"),
    ("marola", "#DEA584"),
    ("marola-research", "#B388FF"),
    ("marola-skills", "#7BD389"),
    ("marola-agents", "#F78FB3"),
    ("marola-oods", "#2EC4B6"),
    ("marola-corpus", "#FF8C42"),
];

pub struct Node {
    pub id: String,
    pub repo: &'static str,
}

/// The repo a source file belongs to: its submodule, or the umbrella.
pub fn repo_of(source_file: &str) -> &'static str {
    let top = source_file.split('/').next().unwrap_or("");
    REPOS
        .iter()
        .map(|(r, _)| *r)
        .find(|r| *r == top)
        .unwrap_or("marola")
}

fn fnv(s: &str) -> u64 {
    s.bytes().fold(0xcbf2_9ce4_8422_2325, |h, b| {
        (h ^ u64::from(b)).wrapping_mul(0x0100_0000_01b3)
    })
}

fn unit(h: u64) -> f64 {
    (h >> 11) as f64 / (1u64 << 53) as f64
}

/// The nodes (sorted by id) and the links between them, as index pairs.
pub fn load(graph: &Value, keep: impl Fn(&str) -> bool) -> (Vec<Node>, Vec<(usize, usize)>) {
    let mut nodes: Vec<Node> = graph["nodes"]
        .as_array()
        .into_iter()
        .flatten()
        .filter_map(|n| {
            let file = n["source_file"].as_str().unwrap_or("");
            keep(file).then(|| Node {
                id: n["id"].as_str().unwrap_or("").to_string(),
                repo: repo_of(file),
            })
        })
        .collect();
    nodes.sort_by(|a, b| a.id.cmp(&b.id));
    nodes.dedup_by(|a, b| a.id == b.id);
    let index: HashMap<&str, usize> = nodes
        .iter()
        .enumerate()
        .map(|(i, n)| (n.id.as_str(), i))
        .collect();
    let mut links: Vec<(usize, usize)> = graph["links"]
        .as_array()
        .into_iter()
        .flatten()
        .filter_map(|l| {
            let a = *index.get(l["source"].as_str()?)?;
            let b = *index.get(l["target"].as_str()?)?;
            (a != b).then(|| (a.min(b), a.max(b)))
        })
        .collect();
    links.sort_unstable();
    links.dedup();
    (nodes, links)
}

fn anchor(repo: &str) -> (f64, f64) {
    let i = REPOS
        .iter()
        .position(|(r, _)| *r == repo)
        .unwrap_or(REPOS.len());
    let a = i as f64 / REPOS.len() as f64 * std::f64::consts::TAU;
    (W / 2.0 + W * 0.3 * a.cos(), H / 2.0 + H * 0.3 * a.sin())
}

pub fn layout(nodes: &[Node], links: &[(usize, usize)]) -> Vec<(f64, f64)> {
    let n = nodes.len();
    let k = (W * H / n.max(1) as f64).sqrt() * 0.6;
    let mut pos: Vec<(f64, f64)> = nodes
        .iter()
        .map(|nd| {
            let (ax, ay) = anchor(nd.repo);
            let h = fnv(&nd.id);
            (
                ax + (unit(h) - 0.5) * 200.0,
                ay + (unit(h.rotate_left(29)) - 0.5) * 200.0,
            )
        })
        .collect();
    let mut disp = vec![(0.0, 0.0); n];
    for step in 0..ITERATIONS {
        let t = W / 10.0 * (1.0 - step as f64 / ITERATIONS as f64) + 0.5;
        disp.iter_mut().for_each(|d| *d = (0.0, 0.0));
        for i in 0..n {
            for j in i + 1..n {
                let (dx, dy) = (pos[i].0 - pos[j].0, pos[i].1 - pos[j].1);
                let d2 = (dx * dx + dy * dy).max(0.01);
                let f = k * k / d2;
                disp[i].0 += dx * f;
                disp[i].1 += dy * f;
                disp[j].0 -= dx * f;
                disp[j].1 -= dy * f;
            }
        }
        for &(a, b) in links {
            let (dx, dy) = (pos[a].0 - pos[b].0, pos[a].1 - pos[b].1);
            let d = (dx * dx + dy * dy).sqrt().max(0.1);
            let f = d / k;
            disp[a].0 -= dx * f;
            disp[a].1 -= dy * f;
            disp[b].0 += dx * f;
            disp[b].1 += dy * f;
        }
        for (i, nd) in nodes.iter().enumerate() {
            let (ax, ay) = anchor(nd.repo);
            disp[i].0 += (ax - pos[i].0) * PULL * k;
            disp[i].1 += (ay - pos[i].1) * PULL * k;
            let (dx, dy) = disp[i];
            let d = (dx * dx + dy * dy).sqrt().max(0.01);
            pos[i].0 += dx / d * d.min(t);
            pos[i].1 += dy / d * d.min(t);
        }
    }
    pos
}

pub fn render(
    nodes: &[Node],
    links: &[(usize, usize)],
    pos: &[(f64, f64)],
    caption: &str,
) -> String {
    let (top, side, legend_h) = (56.0, 24.0, 40.0);
    let (mut x0, mut y0, mut x1, mut y1) = (f64::MAX, f64::MAX, f64::MIN, f64::MIN);
    for &(x, y) in pos {
        (x0, y0, x1, y1) = (x0.min(x), y0.min(y), x1.max(x), y1.max(y));
    }
    let s = ((W - 2.0 * side) / (x1 - x0).max(1.0))
        .min((H - top - legend_h - side) / (y1 - y0).max(1.0));
    let ox = side + ((W - 2.0 * side) - (x1 - x0) * s) / 2.0;
    let px = |(x, y): (f64, f64)| ((x - x0) * s + ox, (y - y0) * s + top);
    let colour = |repo: &str| {
        REPOS
            .iter()
            .find(|(r, _)| *r == repo)
            .map_or("#888888", |(_, c)| c)
    };

    let mut out = String::new();
    let _ = writeln!(
        out,
        r##"<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {W} {H}" width="{W}" height="{H}" font-family="Inter, Helvetica, Arial, sans-serif">"##
    );
    let _ = writeln!(out, "<title>{}</title>", html_escape::encode_text(caption));
    let _ = writeln!(out, r##"<rect width="{W}" height="{H}" fill="#0F1117"/>"##);
    let _ = writeln!(
        out,
        r##"<text x="{side}" y="34" fill="#E6E6E6" font-size="20" font-weight="600">{}</text>"##,
        html_escape::encode_text(caption)
    );
    let _ = writeln!(
        out,
        r##"<g stroke="#8A94A6" stroke-opacity="0.32" stroke-width="0.7">"##
    );
    for &(a, b) in links {
        let ((xa, ya), (xb, yb)) = (px(pos[a]), px(pos[b]));
        let _ = writeln!(
            out,
            r#"<line x1="{xa:.1}" y1="{ya:.1}" x2="{xb:.1}" y2="{yb:.1}"/>"#
        );
    }
    out.push_str("</g>\n");
    let mut by_repo: BTreeMap<&str, Vec<usize>> = BTreeMap::new();
    for (i, nd) in nodes.iter().enumerate() {
        by_repo.entry(nd.repo).or_default().push(i);
    }
    for (repo, members) in &by_repo {
        let _ = writeln!(out, r#"<g fill="{}">"#, colour(repo));
        for &i in members {
            let (x, y) = px(pos[i]);
            let _ = writeln!(out, r#"<circle cx="{x:.1}" cy="{y:.1}" r="2.4"/>"#);
        }
        out.push_str("</g>\n");
    }
    let mut x = side;
    let y = H - side;
    for (repo, c) in REPOS {
        let Some(members) = by_repo.get(repo) else {
            continue;
        };
        let label = format!("{repo} {}", members.len());
        let _ = writeln!(
            out,
            r##"<circle cx="{:.1}" cy="{:.1}" r="6" fill="{c}"/><text x="{:.1}" y="{:.1}" fill="#C9CED6" font-size="15">{label}</text>"##,
            x + 6.0,
            y - 5.0,
            x + 18.0,
            y
        );
        x += 18.0 + label.len() as f64 * 8.2 + 24.0;
    }
    out.push_str("</svg>\n");
    out
}

pub fn run(graph: &Path, out: &Path, root: &Path) -> crate::anyhow_lite::Result {
    let g: Value = serde_json::from_str(&std::fs::read_to_string(graph)?)?;
    let empty_dir = |p: &Path| {
        std::fs::read_dir(p)
            .map(|mut d| d.next().is_none())
            .unwrap_or(false)
    };
    let keep = |file: &str| {
        if file.is_empty() || root.join(file).exists() {
            return true;
        }
        let top = file.split('/').next().unwrap_or("");
        repo_of(file) != "marola" && empty_dir(&root.join(top))
    };
    let (nodes, links) = load(&g, keep);
    let commit = g["built_at_commit"].as_str().unwrap_or("");
    let caption = format!(
        "graphify: the marola code graph, {} nodes and {} links (marola@{})",
        nodes.len(),
        links.len(),
        &commit[..commit.len().min(7)]
    );
    let pos = layout(&nodes, &links);
    std::fs::write(out, render(&nodes, &links, &pos, &caption))?;
    println!(
        "graph-svg: {} ({} nodes, {} links)",
        out.display(),
        nodes.len(),
        links.len()
    );
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn sample() -> Value {
        json!({"built_at_commit": "0123456789", "nodes": [
            {"id": "b", "source_file": "marola-app/core/A.scala"},
            {"id": "a", "source_file": "tools/src/main.rs"},
            {"id": "c", "source_file": "scripts/gone.py"},
            {"id": "a", "source_file": "tools/src/main.rs"}
        ], "links": [
            {"source": "a", "target": "b"}, {"source": "b", "target": "a"},
            {"source": "a", "target": "c"}, {"source": "a", "target": "a"}
        ]})
    }

    #[test]
    fn assigns_repos() {
        assert_eq!(repo_of("marola-ml/x.py"), "marola-ml");
        assert_eq!(repo_of("tools/src/main.rs"), "marola");
        assert_eq!(repo_of("marola-appx/y"), "marola");
    }

    #[test]
    fn loads_kept_nodes_and_their_links_once() {
        let (nodes, links) = load(&sample(), |f| !f.ends_with(".py"));
        assert_eq!(
            nodes.iter().map(|n| n.id.as_str()).collect::<Vec<_>>(),
            ["a", "b"]
        );
        assert_eq!(nodes[1].repo, "marola-app");
        assert_eq!(links, [(0, 1)]);
    }

    #[test]
    fn draws_the_same_bytes_every_time() {
        let (nodes, links) = load(&sample(), |_| true);
        let one = render(&nodes, &links, &layout(&nodes, &links), "t");
        let two = render(&nodes, &links, &layout(&nodes, &links), "t");
        assert_eq!(one, two);
        assert_eq!(one.matches("<circle").count(), 3 + 2);
        assert!(one.contains(">marola-app 1</text>"));
    }
}
