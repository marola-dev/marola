//! Rewrite one repo's README and docs/** links for its mount on the docs site (MIP-0074 Appendix A).
//!
//!     marola-tools doc-links --repo DIR --name NAME --sha SHA --out DIR [--umbrella]
//!         [--exclude PATTERN]... [--submodule NAME=SHA]...
//!     marola-tools doc-links --links FILE...   # FILE<TAB>link per relative link outside code

use crate::util::{fnmatchcase, posix, splitlines, splitlines_keepends, unquote};
use fancy_regex::{Captures, Regex};
use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::sync::LazyLock;
use unicode_normalization::UnicodeNormalization;

fn re(pattern: &str) -> Regex {
    Regex::new(pattern).expect("valid regex")
}

// Fences as docs-lint (marola-devkit scripts/docs_lint.py) sees them, so the two never disagree on
// what is code: any indent, and only a bare fence line closes.
static FENCE: LazyLock<Regex> = LazyLock::new(|| re(r"^\s*(`{3,}|~{3,})"));
// A code span may wrap lines but never crosses a blank line (CommonMark), so a lone ` can't mask
// later paragraphs.
static CODE_SPAN: LazyLock<Regex> =
    LazyLock::new(|| re(r"(?s)(?<!`)(`+)(?!`)(?:(?!\n[ \t]*\n).)*?(?<!`)\1(?!`)"));
static INLINE: LazyLock<Regex> = LazyLock::new(|| {
    re(concat!(
        r"(!?)\[((?:[^\[\]]|\[[^\[\]]*\])*)\]",
        r#"\(\s*(<[^>]*>|[^)\s]+)((?:\s+(?:"[^"]*"|'[^']*'|\([^)]*\)))?\s*)\)"#
    ))
});
static REFDEF: LazyLock<Regex> =
    LazyLock::new(|| re(r"(?m)^( {0,3}\[[^\]]+\]:[ \t]*)(<[^>]*>|\S+)"));
static MASK: LazyLock<Regex> = LazyLock::new(|| re(r"\x00(\d+)\x00"));
static HTML_TAG: LazyLock<Regex> = LazyLock::new(|| re(r"<[A-Za-z][A-Za-z0-9-]*\s[^<>]*>"));
static HTML_ATTR: LazyLock<Regex> =
    LazyLock::new(|| re(r#"(?i)(?<=\s)(href|src)(\s*=\s*)(["'])([^"']*)\3"#));
static SCHEME: LazyLock<Regex> = LazyLock::new(|| re(r"^(?:[a-zA-Z][a-zA-Z0-9+.-]*:|//)"));
static HEADING: LazyLock<Regex> =
    LazyLock::new(|| re(r"^ {0,3}#{1,6}[ \t]+(.*?)(?:[ \t]+#+)?[ \t]*$"));
static HEADING_LINK: LazyLock<Regex> = LazyLock::new(|| re(r"!?\[([^\]]*)\]\([^)]*\)"));
static HEADING_MARKUP: LazyLock<Regex> = LazyLock::new(|| re(r"<[^>]+>|[`*]"));
static GH_DROP: LazyLock<Regex> = LazyLock::new(|| re(r"[^\w\- ]"));
static TOC_DROP: LazyLock<Regex> = LazyLock::new(|| re(r"[^\w\s-]"));
static TOC_DASH: LazyLock<Regex> = LazyLock::new(|| re(r"[-\s]+"));
// A GitHub permalink sha (7-40 hex, abbreviated or full) or a version tag: already pinned, so left
// exactly as written, #L anchors included, with no existence check against ctx.sha.
static PINNED_REF: LazyLock<Regex> = LazyLock::new(|| re(r"^(?:[0-9a-f]{7,40}|v\d[\w.\-]*)$"));
static ADR_FILE: LazyLock<Regex> = LazyLock::new(|| re(r"^(\d{4})-[^/]+\.md$"));
static ADR_STATUS: LazyLock<Regex> =
    LazyLock::new(|| re(r"(?m)^\|\s*\*\*Status\*\*\s*\|\s*(.*?)\s*\|\s*$"));

const IMAGE_SUFFIXES: [&str; 8] = [
    ".png", ".jpg", ".jpeg", ".gif", ".svg", ".webp", ".avif", ".ico",
];

fn is_match(r: &Regex, text: &str) -> bool {
    r.is_match(text).unwrap_or(false)
}

type Match = (usize, usize, Vec<Option<(usize, String)>>);

/// Every match of `r` in `text`, as (start, end, captures by group as owned strings).
fn matches(r: &Regex, text: &str) -> Vec<Match> {
    r.captures_iter(text)
        .filter_map(Result::ok)
        .map(|c: Captures| {
            let m = c.get(0).unwrap();
            let groups = (0..c.len())
                .map(|i| c.get(i).map(|g| (g.start(), g.as_str().to_string())))
                .collect();
            (m.start(), m.end(), groups)
        })
        .collect()
}

/// `re.sub(r, f, text)` with a closure that may borrow mutably.
fn sub(r: &Regex, text: &str, mut f: impl FnMut(&[Option<(usize, String)>]) -> String) -> String {
    let mut out = String::with_capacity(text.len());
    let mut last = 0;
    for (start, end, groups) in matches(r, text) {
        out.push_str(&text[last..start]);
        out.push_str(&f(&groups));
        last = end;
    }
    out.push_str(&text[last..]);
    out
}

fn g(groups: &[Option<(usize, String)>], i: usize) -> &str {
    groups
        .get(i)
        .and_then(|x| x.as_ref())
        .map_or("", |(_, s)| s.as_str())
}

/// The mounted repo: `repo` is checked out at `sha`.
#[derive(Debug, Clone, Default)]
pub struct Context {
    pub name: String,
    pub sha: String,
    pub umbrella: bool,
    // mkdocs.yml's patterns are relative to the site root; matched against the repo's docs/ they
    // agree only while unanchored (benchmarks/), which is all mkdocs.yml has.
    pub exclude_docs: Vec<String>,
    // Umbrella only; each submodule must be checked out at its sha under repo/<name>, where the
    // existence checks read it.
    pub submodule_shas: HashMap<String, String>,
}

/// Split into (is_fence, chunk).
fn fenced(text: &str) -> Vec<(bool, String)> {
    let mut out = Vec::new();
    let mut prose = String::new();
    let mut fence: Option<(char, usize)> = None;
    for line in splitlines_keepends(text) {
        if let Some((c, n)) = fence {
            out.push((true, line.to_string()));
            if closes(line, c, n) {
                fence = None;
            }
        } else if let Ok(Some(m)) = FENCE.captures(line) {
            out.push((false, std::mem::take(&mut prose)));
            let run = m.get(1).unwrap().as_str();
            fence = Some((run.chars().next().unwrap(), run.chars().count()));
            out.push((true, line.to_string()));
        } else {
            prose.push_str(line);
        }
    }
    out.push((false, prose));
    out
}

/// `^\s*{c}{n,}\s*$`: a bare fence line of at least the opening's length.
fn closes(line: &str, c: char, n: usize) -> bool {
    let rest = line.trim_start();
    let run = rest.chars().take_while(|x| *x == c).count();
    run >= n && rest[run * c.len_utf8()..].chars().all(char::is_whitespace)
}

fn unique(slug: String, seen: &mut HashMap<String, usize>, sep: &str) -> String {
    let n = seen.entry(slug.clone()).or_insert(0);
    let out = if *n > 0 {
        format!("{slug}{sep}{n}")
    } else {
        slug
    };
    *n += 1;
    out
}

/// GitHub's anchor (and toc's own, for links already written for the site) -> toc's anchor.
fn readme_anchors(readme: &str) -> HashMap<String, String> {
    let mut anchors = HashMap::new();
    let (mut gh_seen, mut toc_seen) = (HashMap::new(), HashMap::new());
    for (is_fence, chunk) in fenced(readme) {
        if is_fence {
            continue;
        }
        for line in splitlines(&chunk) {
            let Ok(Some(m)) = HEADING.captures(line) else {
                continue;
            };
            let title = m.get(1).map_or("", |x| x.as_str());
            let title = HEADING_LINK.replace_all(title, "$1").into_owned();
            let title = HEADING_MARKUP.replace_all(&title, "").into_owned();
            let gh = GH_DROP
                .replace_all(&title.to_lowercase(), "")
                .replace(' ', "-");
            // Python-Markdown toc's default slugify, which mkdocs uses (mkdocs.yml sets none).
            let ascii: String = title.nfkd().filter(char::is_ascii).collect();
            let dropped = TOC_DROP.replace_all(&ascii, "");
            let toc = TOC_DASH
                .replace_all(&dropped.trim().to_lowercase(), "-")
                .into_owned();
            let toc = unique(toc, &mut toc_seen, "_");
            anchors.insert(unique(gh, &mut gh_seen, "-"), toc.clone());
            anchors.entry(toc.clone()).or_insert(toc);
        }
    }
    anchors
}

/// A gitignore-style subset: `*` globs, a trailing / for directories, a / inside anchors.
fn excluded(rel: &str, patterns: &[String], is_dir: bool) -> bool {
    let parts: Vec<&str> = rel.split('/').collect();
    for pattern in patterns {
        let anchored = pattern.trim_end_matches('/').contains('/');
        let dir_only = pattern.ends_with('/');
        let pattern = pattern.trim_matches('/');
        for i in 1..=parts.len() {
            if pattern.is_empty() || (dir_only && i == parts.len() && !is_dir) {
                continue;
            }
            let candidate = if anchored {
                parts[..i].join("/")
            } else {
                parts[i - 1].to_string()
            };
            if fnmatchcase(&candidate, pattern) {
                return true;
            }
        }
    }
    false
}

/// Where docs/<rel> lands, relative to the mount.
fn docs_mount(rel: &str, ctx: &Context) -> String {
    if ctx.umbrella && (rel == "MIPs" || rel.starts_with("MIPs/")) {
        format!("6-{rel}")
    } else {
        rel.to_string()
    }
}

fn out_path(source: &str, ctx: &Context) -> String {
    if source == "README.md" {
        "index.md".into()
    } else {
        docs_mount(source.strip_prefix("docs/").unwrap_or(source), ctx)
    }
}

struct Rewriter<'a> {
    source: &'a str,
    repo: &'a Path,
    ctx: &'a Context,
    errors: Vec<String>,
    anchors: Option<HashMap<String, String>>,
    own_github: Regex,
}

impl Rewriter<'_> {
    fn readme_anchor(&mut self, link: &str, anchor: &str) -> Option<String> {
        if self.anchors.is_none() {
            let readme = self.repo.join("README.md");
            self.anchors = Some(match std::fs::read_to_string(&readme) {
                Ok(t) if readme.is_file() => readme_anchors(&t),
                _ => HashMap::new(),
            });
        }
        match self.anchors.as_ref().unwrap().get(anchor) {
            Some(a) => Some(a.clone()),
            None => {
                self.errors.push(format!(
                    "{}: {link}: no README heading has the anchor #{anchor}",
                    self.source
                ));
                None
            }
        }
    }

    fn fail(&mut self, link: &str, reason: &str) -> String {
        self.errors
            .push(format!("{}: {link}: {reason}", self.source));
        link.to_string()
    }

    fn github(
        &mut self,
        link: &str,
        name: &str,
        sha: &str,
        checkout: &Path,
        path: &str,
        suffix: &str,
    ) -> String {
        let full = checkout.join(unquote(path));
        if !full.exists() {
            return self.fail(link, &format!("missing at {sha}"));
        }
        let kind = if full.is_dir() { "tree" } else { "blob" };
        format!("https://github.com/marola-dev/{name}/{kind}/{sha}/{path}{suffix}")
    }

    fn target(&mut self, link: &str, image: bool) -> String {
        if link.is_empty() {
            return String::new();
        }
        if is_match(&SCHEME, link) {
            let Ok(Some(m)) = self.own_github.captures(link) else {
                return link.to_string();
            };
            let refpath = m.get(1).unwrap();
            let parts: Vec<&str> = refpath.as_str().split('/').collect();
            if is_match(&PINNED_REF, parts[0]) {
                return link.to_string();
            }
            // A branch may hold slashes (mip-NNNN/k-*): like GitHub, take the shortest ref whose
            // remaining path exists. A slashed ref with no path at all is refused as ambiguous.
            for i in 1..=parts.len() {
                let rest = parts[i..].join("/");
                if (!rest.is_empty() || i == 1) && self.repo.join(unquote(&rest)).exists() {
                    let r = parts[..i].join("/");
                    let start = refpath.start();
                    return format!(
                        "{}{}{}",
                        &link[..start],
                        self.ctx.sha,
                        &link[start + r.len()..]
                    );
                }
            }
            let sha = self.ctx.sha.clone();
            return self.fail(link, &format!("missing at {sha}"));
        }
        let (path, hash, anchor) = match link.split_once('#') {
            Some((p, a)) => (p, "#", a.to_string()),
            None => (link, "", String::new()),
        };
        let (path, query) = match path.split_once('?') {
            Some((p, q)) => (p, format!("?{q}")),
            None => (path, String::new()),
        };
        let mut anchor = anchor;
        if path.starts_with('/') {
            return self.fail(link, "root-absolute; link the page relatively");
        }
        if path.is_empty() {
            if !hash.is_empty() && self.source == "README.md" {
                anchor = self.readme_anchor(link, &anchor).unwrap_or(anchor);
            }
            return format!("{query}{hash}{anchor}");
        }
        let resolved = posix::normpath(&posix::join(posix::dirname(self.source), path));
        if resolved == ".." || resolved.starts_with("../") {
            return self.fail(link, "leaves the repo");
        }
        let is_dir = path.ends_with('/') || self.repo.join(unquote(&resolved)).is_dir();
        let image =
            image || IMAGE_SUFFIXES.contains(&posix::extension(&resolved).to_lowercase().as_str());
        let (head, rest) = resolved.split_once('/').unwrap_or((resolved.as_str(), ""));
        let suffix = format!("{query}{hash}{anchor}");
        let mount: String;
        if self.ctx.umbrella && self.ctx.submodule_shas.contains_key(head) {
            if !matches!(rest, "" | "README.md" | "docs") && !rest.starts_with("docs/") {
                if image {
                    return self.fail(link, "an image outside docs/; move it under docs/");
                }
                let sha = self.ctx.submodule_shas[head].clone();
                let checkout = self.repo.join(head);
                return self.github(link, head, &sha, &checkout, rest, &suffix);
            }
            let rel = if rest == "README.md" {
                ""
            } else {
                rest.strip_prefix("docs").unwrap_or(rest)
            };
            let rel = rel.strip_prefix('/').unwrap_or(rel);
            mount = format!(
                "5-Repos/{head}/{}",
                if !rel.is_empty() && is_dir {
                    format!("{rel}/index.md")
                } else if rel.is_empty() {
                    "index.md".into()
                } else {
                    rel.to_string()
                }
            );
        } else if resolved != "docs" && !resolved.starts_with("docs/") {
            if image {
                return self.fail(link, "an image outside docs/; move it under docs/");
            }
            if resolved != "README.md" {
                let (name, sha) = (self.ctx.name.clone(), self.ctx.sha.clone());
                let repo = self.repo.to_path_buf();
                return self.github(link, &name, &sha, &repo, &resolved, &suffix);
            }
            mount = "index.md".into();
            if !hash.is_empty() {
                anchor = self.readme_anchor(link, &anchor).unwrap_or(anchor);
            }
        } else {
            let rel = resolved.strip_prefix("docs").unwrap_or(&resolved);
            let rel = rel.strip_prefix('/').unwrap_or(rel);
            if !rel.is_empty() && excluded(rel, &self.ctx.exclude_docs, is_dir) {
                if image {
                    return self.fail(
                        link,
                        "an image under exclude_docs; move it to a published docs/ path",
                    );
                }
                let (name, sha) = (self.ctx.name.clone(), self.ctx.sha.clone());
                let repo = self.repo.to_path_buf();
                return self.github(link, &name, &sha, &repo, &resolved, &suffix);
            }
            if rel == "index.md" {
                return self.fail(
                    link,
                    "docs/index.md is banned (MIP-0074 D1); link docs/ for the landing",
                );
            }
            if rel.is_empty() {
                mount = "index.md".into();
            } else if is_dir {
                // adr/index.md is generated by prepare-docs, so it never exists in the repo.
                if rel != "adr" && !self.repo.join(&resolved).join("index.md").is_file() {
                    return self.fail(link, "a directory with no index.md");
                }
                mount = format!("{}/index.md", docs_mount(rel, self.ctx));
            } else {
                mount = docs_mount(rel, self.ctx);
            }
        }
        let out = out_path(self.source, self.ctx);
        let start = match posix::dirname(&out) {
            "" => ".",
            d => d,
        };
        format!("{}{query}{hash}{anchor}", posix::relpath(&mount, start))
    }

    fn bracketed(&mut self, link: &str, image: bool) -> String {
        if link.starts_with('<') && link.ends_with('>') && link.len() >= 2 {
            format!("<{}>", self.target(&link[1..link.len() - 1], image))
        } else {
            self.target(link, image)
        }
    }

    fn inline_sub(&mut self, text: &str) -> String {
        sub(&INLINE, text, |gs| {
            let (bang, label, link, title) = (g(gs, 1), g(gs, 2), g(gs, 3), g(gs, 4));
            let label = self.inline_sub(label);
            format!(
                "{bang}[{label}]({}{title})",
                self.bracketed(link, !bang.is_empty())
            )
        })
    }

    fn prose(&mut self, chunk: &str) -> String {
        // Masked, not split out: a code span can be a link's label, as in [`AGENTS.md`](…).
        let mut spans: Vec<String> = Vec::new();
        let chunk = sub(&CODE_SPAN, chunk, |gs| {
            spans.push(g(gs, 0).to_string());
            format!("\x00{}\x00", spans.len() - 1)
        });
        let chunk = sub(&REFDEF, &chunk, |gs| {
            format!("{}{}", g(gs, 1), self.bracketed(g(gs, 2), false))
        });
        let chunk = self.inline_sub(&chunk);
        let chunk = sub(&HTML_TAG, &chunk, |gs| {
            sub(&HTML_ATTR, g(gs, 0), |a| {
                let (name, eq, quote, link) = (g(a, 1), g(a, 2), g(a, 3), g(a, 4));
                format!(
                    "{name}{eq}{quote}{}{quote}",
                    self.target(link, name.eq_ignore_ascii_case("src"))
                )
            })
        });
        sub(&MASK, &chunk, |gs| {
            spans[g(gs, 1).parse::<usize>().unwrap()].clone()
        })
    }
}

/// Rewrite `text`, the repo-relative file `source` (README.md or docs/**), for the mount.
///
/// `repo` is the checkout at `ctx.sha`. Returns the new text and one error per link Appendix A
/// refuses; a refused link is left as written.
pub fn rewrite(text: &str, source: &str, repo: &Path, ctx: &Context) -> (String, Vec<String>) {
    let own_github = re(&format!(
        r"^https://github\.com/marola-dev/{}/(?:blob|tree)/([^?#]+)(.*)$",
        regex::escape(&ctx.name)
    ));
    let mut w = Rewriter {
        source,
        repo,
        ctx,
        errors: Vec::new(),
        anchors: None,
        own_github,
    };
    let mut out = String::with_capacity(text.len());
    for (is_fence, chunk) in fenced(text) {
        if is_fence {
            out.push_str(&chunk);
        } else {
            out.push_str(&w.prose(&chunk));
        }
    }
    (out, w.errors)
}

fn strip_angles(s: &str) -> &str {
    let s = s.strip_prefix('<').unwrap_or(s);
    s.strip_suffix('>').unwrap_or(s)
}

/// Every reference definition, inline link, then HTML href=/src= target outside code, as written.
pub fn links(text: &str) -> Vec<String> {
    fn inline(chunk: &str, found: &mut Vec<String>) {
        for (_, _, gs) in matches(&INLINE, chunk) {
            found.push(strip_angles(g(&gs, 3)).to_string());
            inline(g(&gs, 2), found);
        }
    }
    let mut found = Vec::new();
    for (is_fence, chunk) in fenced(text) {
        if is_fence {
            continue;
        }
        let chunk = sub(&CODE_SPAN, &chunk, |_| String::new());
        for (_, _, gs) in matches(&REFDEF, &chunk) {
            found.push(strip_angles(g(&gs, 2)).to_string());
        }
        inline(&chunk, &mut found);
        for (_, _, tag) in matches(&HTML_TAG, &chunk) {
            for (_, _, gs) in matches(&HTML_ATTR, g(&tag, 0)) {
                found.push(g(&gs, 4).to_string());
            }
        }
    }
    found
}

fn files_under(dir: &Path) -> Vec<PathBuf> {
    walkdir::WalkDir::new(dir)
        .min_depth(1)
        .into_iter()
        .filter_map(Result::ok)
        .map(|e| e.into_path())
        .filter(|p| p.is_file())
        .collect()
}

/// Write README.md as out/index.md and docs/** beside it, skipping exclude_docs; the errors.
pub fn mount(repo: &Path, out: &Path, ctx: &Context) -> std::io::Result<Vec<String>> {
    let mut errors = Vec::new();
    let mut docs: Vec<String> = files_under(&repo.join("docs"))
        .iter()
        .filter_map(|p| p.strip_prefix(repo).ok())
        .map(|p| p.to_string_lossy().replace('\\', "/"))
        .collect();
    docs.sort();
    let mut sources = vec!["README.md".to_string()];
    sources.extend(docs);
    for source in &sources {
        if source != "README.md" && excluded(&source["docs/".len()..], &ctx.exclude_docs, false) {
            continue;
        }
        let dest = out.join(out_path(source, ctx));
        std::fs::create_dir_all(dest.parent().unwrap())?;
        if source.ends_with(".md") {
            let text = std::fs::read_to_string(repo.join(source))?;
            let (text, errs) = rewrite(&text, source, repo, ctx);
            std::fs::write(&dest, text)?;
            errors.extend(errs);
        } else {
            std::fs::copy(repo.join(source), &dest)?;
        }
    }
    if repo.join("docs/adr").is_dir() {
        errors.extend(adr_index(
            &repo.join("docs/adr"),
            &out.join(docs_mount("adr", ctx)),
            &ctx.exclude_docs,
        )?);
    }
    Ok(errors)
}

/// Write out/index.md from the published ADRs' rewritten copies in out (their links are the site's).
pub fn adr_index(src: &Path, out: &Path, exclude_docs: &[String]) -> std::io::Result<Vec<String>> {
    let mut errors: Vec<String> = ["index.md", "README.md"]
        .iter()
        .filter(|n| src.join(n).exists())
        .map(|n| format!("docs/adr/{n}: hand-written; prepare-docs generates adr/index.md"))
        .collect();
    let mut pages: Vec<String> = std::fs::read_dir(src)?
        .filter_map(Result::ok)
        .map(|e| e.file_name().to_string_lossy().into_owned())
        .filter(|n| n.ends_with(".md"))
        .collect();
    pages.sort();
    let mut rows = Vec::new();
    for page in pages {
        if page == "index.md"
            || page == "README.md"
            || excluded(&format!("adr/{page}"), exclude_docs, false)
        {
            continue;
        }
        let Ok(Some(m)) = ADR_FILE.captures(&page) else {
            errors.push(format!("docs/adr/{page}: not NNNN-<slug>.md"));
            continue;
        };
        let n = m.get(1).unwrap().as_str().to_string();
        let text = std::fs::read_to_string(out.join(&page))?;
        let title_re = re(&format!(r"(?m)^# ADR-{n}:[ \t]*(\S.*?)[ \t]*$"));
        let title = title_re
            .captures(&text)
            .ok()
            .flatten()
            .map(|c| c.get(1).unwrap().as_str().to_string());
        let status = ADR_STATUS
            .captures(&text)
            .ok()
            .flatten()
            .map(|c| c.get(1).unwrap().as_str().to_string());
        if title.is_none() {
            errors.push(format!(
                "docs/adr/{page}: no \"# ADR-{n}: <decision>\" heading"
            ));
        }
        if status.is_none() {
            errors.push(format!("docs/adr/{page}: no | **Status** | row"));
        }
        if let (Some(t), Some(s)) = (title, status) {
            rows.push(format!(
                "| [{n}]({page}) | {} | {s} |",
                t.replace('|', "\\|")
            ));
        }
    }
    std::fs::create_dir_all(out)?;
    let mut lines = vec!["# Architecture decisions".to_string(), String::new()];
    if rows.is_empty() {
        lines.push("_None yet._".into());
    } else {
        lines.push("| ADR | Decision | Status |".into());
        lines.push("|---|---|---|".into());
        lines.extend(rows);
    }
    std::fs::write(out.join("index.md"), lines.join("\n") + "\n")?;
    Ok(errors)
}

pub struct Args {
    pub repo: Option<PathBuf>,
    pub name: Option<String>,
    pub sha: Option<String>,
    pub out: Option<PathBuf>,
    pub umbrella: bool,
    pub exclude: Vec<String>,
    pub submodule: Vec<String>,
    pub links: Vec<PathBuf>,
}

pub fn main(args: Args) -> i32 {
    if !args.links.is_empty() {
        for f in &args.links {
            let text = match std::fs::read_to_string(f) {
                Ok(t) => t,
                Err(e) => {
                    eprintln!("doc_links: {}: {e}", f.display());
                    return 1;
                }
            };
            for link in links(&text) {
                if !is_match(&SCHEME, &link) {
                    println!("{}\t{link}", f.display());
                }
            }
        }
        return 0;
    }
    let (Some(repo), Some(name), Some(sha), Some(out)) = (args.repo, args.name, args.sha, args.out)
    else {
        eprintln!("doc_links: --repo, --name, --sha and --out are required (or --links FILE...)");
        return 2;
    };
    let ctx = Context {
        name: name.clone(),
        sha,
        umbrella: args.umbrella,
        exclude_docs: args.exclude,
        submodule_shas: args
            .submodule
            .iter()
            .map(|s| {
                let (n, sha) = s.split_once('=').unwrap_or((s, ""));
                (n.to_string(), sha.to_string())
            })
            .collect(),
    };
    match mount(&repo, &out, &ctx) {
        Ok(errors) => {
            for e in &errors {
                eprintln!("doc_links: {name}: {e}");
            }
            i32::from(!errors.is_empty())
        }
        Err(e) => {
            eprintln!("doc_links: {name}: {e}");
            1
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn refused(source: &str, link: &str, reason: &str) -> Vec<String> {
        vec![format!("{source}: {link}: {reason}")]
    }

    fn ok(s: &str) -> (String, Vec<String>) {
        (s.to_string(), vec![])
    }

    fn bad(s: &str, errs: Vec<String>) -> (String, Vec<String>) {
        (s.to_string(), errs)
    }

    fn touch(root: &Path, files: &[&str]) {
        for f in files {
            let p = root.join(f);
            std::fs::create_dir_all(p.parent().unwrap()).unwrap();
            std::fs::write(p, "# t\n").unwrap();
        }
    }

    #[test]
    fn rewrites_a_repo() {
        let sha = "a".repeat(40);
        let gh = format!("https://github.com/marola-dev/marola-ml/blob/{sha}");
        let tree = format!("https://github.com/marola-dev/marola-ml/tree/{sha}");
        let tmp = tempfile::tempdir().unwrap();
        let repo = tmp.path().join("marola-ml");
        touch(
            &repo,
            &[
                "docs/x.md",
                "docs/sub/index.md",
                "docs/guide/page.md",
                "docs/img/a.png",
                "docs/benchmarks/2026-09-05.md",
                "docs/benchmarks/plot.png",
                "AGENTS.md",
                "scripts/x.sh",
                "knowledge/tides.md",
            ],
        );
        std::fs::create_dir(repo.join("docs/empty")).unwrap();
        std::fs::write(
            repo.join("README.md"),
            "# Repo\n\n## 🌊 Run it!\n\n```\n## Not a heading\n```\n",
        )
        .unwrap();
        let ctx = Context {
            name: "marola-ml".into(),
            sha: sha.clone(),
            exclude_docs: vec!["benchmarks/".into(), "superpowers/".into()],
            ..Default::default()
        };
        let page = |text: &str, source: &str| rewrite(text, source, &repo, &ctx);
        let readme = |text: &str| page(text, "README.md");

        assert_eq!(
            readme(
                "[a](docs/x.md) [b](./docs/x.md) [c](https://x.example/docs/x.md) [d](mailto:a@b)"
            ),
            ok("[a](x.md) [b](x.md) [c](https://x.example/docs/x.md) [d](mailto:a@b)")
        );
        assert_eq!(readme("[a](docs/x.md#sec)"), ok("[a](x.md#sec)"));
        assert_eq!(
            readme("[full docs](docs/) [d](docs)"),
            ok("[full docs](index.md) [d](index.md)")
        );
        assert_eq!(
            readme("[i](docs/index.md)"),
            bad(
                "[i](docs/index.md)",
                refused(
                    "README.md",
                    "docs/index.md",
                    "docs/index.md is banned (MIP-0074 D1); link docs/ for the landing"
                )
            )
        );
        assert_eq!(
            readme("[s](docs/sub/) [adr](docs/adr/)"),
            ok("[s](sub/index.md) [adr](adr/index.md)")
        );
        assert_eq!(
            readme("[e](docs/empty/)"),
            bad(
                "[e](docs/empty/)",
                refused("README.md", "docs/empty/", "a directory with no index.md")
            )
        );
        assert_eq!(
            readme(r#"![l](docs/img/a.png) <img alt="l" src="docs/img/a.png">"#),
            ok(r#"![l](img/a.png) <img alt="l" src="img/a.png">"#)
        );
        let outside = "an image outside docs/; move it under docs/";
        assert_eq!(
            readme("![l](assets/logo.png)"),
            bad(
                "![l](assets/logo.png)",
                refused("README.md", "assets/logo.png", outside)
            )
        );
        assert_eq!(
            readme(r#"<img src="logo.svg">"#),
            bad(
                r#"<img src="logo.svg">"#,
                refused("README.md", "logo.svg", outside)
            )
        );
        let off_site = "an image under exclude_docs; move it to a published docs/ path";
        assert_eq!(
            readme("![p](docs/benchmarks/plot.png)"),
            bad(
                "![p](docs/benchmarks/plot.png)",
                refused("README.md", "docs/benchmarks/plot.png", off_site)
            )
        );
        assert_eq!(
            readme(r#"<img src="docs/benchmarks/plot.png">"#),
            bad(
                r#"<img src="docs/benchmarks/plot.png">"#,
                refused("README.md", "docs/benchmarks/plot.png", off_site)
            )
        );
        assert_eq!(
            page("[r](../README.md) [a](../README.md#repo)", "docs/x.md"),
            ok("[r](index.md) [a](index.md#repo)")
        );
        assert_eq!(
            page("[r](../../README.md)", "docs/guide/page.md"),
            ok("[r](../index.md)")
        );
        assert_eq!(
            page("[r](../README.md#-run-it)", "docs/x.md"),
            ok("[r](index.md#run-it)")
        );
        assert_eq!(
            readme("[r](#-run-it) [s](#run-it)"),
            ok("[r](#run-it) [s](#run-it)")
        );
        assert_eq!(
            readme("[n](#not-a-heading)"),
            bad(
                "[n](#not-a-heading)",
                refused(
                    "README.md",
                    "#not-a-heading",
                    "no README heading has the anchor #not-a-heading"
                )
            )
        );
        let api = "/api/scala/core/marola.html";
        assert_eq!(
            page(&format!("[api]({api})"), "docs/x.md"),
            bad(
                &format!("[api]({api})"),
                refused("docs/x.md", api, "root-absolute; link the page relatively")
            )
        );
        assert_eq!(
            readme("[o](../other/x.md)"),
            bad(
                "[o](../other/x.md)",
                refused("README.md", "../other/x.md", "leaves the repo")
            )
        );
        assert_eq!(
            page("[o](../../x.md)", "docs/x.md"),
            bad(
                "[o](../../x.md)",
                refused("docs/x.md", "../../x.md", "leaves the repo")
            )
        );
        assert_eq!(
            readme(
                "[x]: docs/x.md \"Title\"\n<a href=\"docs/x.md#a\">x</a> <a href='docs/'>d</a>\n"
            ),
            ok("[x]: x.md \"Title\"\n<a href=\"x.md#a\">x</a> <a href='index.md'>d</a>\n")
        );
        let prose_attr = "Set href=\"/abs\" in the tag.\n";
        assert_eq!(readme(prose_attr), ok(prose_attr));
        let fenced_text = "```md\n[a](docs/x.md)\n```\n~~~\n![i](/abs.png)\n~~~\n";
        assert_eq!(
            readme(&format!(
                "`[a](docs/x.md)` [`b`](docs/x.md) ``[c](/abs)``\n{fenced_text}"
            )),
            ok(&format!(
                "`[a](docs/x.md)` [`b`](x.md) ``[c](/abs)``\n{fenced_text}"
            ))
        );
        let stray = "Type ` to start.\n\n[a](docs/x.md) and [b](/abs)\n\nlater ` here\n";
        let (text, errs) = readme(stray);
        assert_eq!(text, stray.replace("(docs/x.md)", "(x.md)"));
        assert_eq!(errs.len(), 1);
        // docs-lint closes a fence only on a bare fence line; ```bash inside it is still code.
        let nested = "```\n```bash\n[a](/abs)\n```\n";
        assert_eq!(readme(nested), ok(nested));

        assert_eq!(
            readme("[b](docs/benchmarks/2026-09-05.md#r) [d](docs/benchmarks/)"),
            ok(&format!(
                "[b]({gh}/docs/benchmarks/2026-09-05.md#r) [d]({tree}/docs/benchmarks)"
            ))
        );
        assert_eq!(
            page("[b](benchmarks/2026-09-05.md)", "docs/x.md"),
            ok(&format!("[b]({gh}/docs/benchmarks/2026-09-05.md)"))
        );
        assert_eq!(
            readme("[a](AGENTS.md) [s](./scripts/x.sh#L3) [p](AGENTS.md?plain=1#L5)"),
            ok(&format!(
                "[a]({gh}/AGENTS.md) [s]({gh}/scripts/x.sh#L3) [p]({gh}/AGENTS.md?plain=1#L5)"
            ))
        );
        assert_eq!(
            page(
                r#"[a](../AGENTS.md) <a href="../scripts/x.sh">s</a>"#,
                "docs/x.md"
            ),
            ok(&format!(
                r#"[a]({gh}/AGENTS.md) <a href="{gh}/scripts/x.sh">s</a>"#
            ))
        );
        assert_eq!(
            readme("[k](knowledge/) [k2](knowledge) [s](scripts/)"),
            ok(&format!(
                "[k]({tree}/knowledge) [k2]({tree}/knowledge) [s]({tree}/scripts)"
            ))
        );
        let own = "https://github.com/marola-dev/marola-ml";
        assert_eq!(
            readme(&format!(
                "[a]({own}/blob/main/AGENTS.md#x) [t]({own}/tree/feature-x/knowledge)\n[r]: {own}/blob/main/scripts/x.sh?plain=1\n"
            )),
            ok(&format!(
                "[a]({gh}/AGENTS.md#x) [t]({tree}/knowledge)\n[r]: {gh}/scripts/x.sh?plain=1\n"
            ))
        );
        assert_eq!(
            readme(&format!(
                "[t]({own}/tree/mip-0074/x/knowledge) [a]({own}/blob/mip-0074/k-y/AGENTS.md#L1)"
            )),
            ok(&format!("[t]({tree}/knowledge) [a]({gh}/AGENTS.md#L1)"))
        );
        for pinned in ["7ba0458", "v0.2.4"] {
            let l = format!("[a]({own}/blob/{pinned}/.claude/hooks/stop-gate.sh#L23)");
            assert_eq!(readme(&l), ok(&l));
        }
        assert_eq!(
            readme(&format!(
                "[t]({own}/tree/main/) [k]({own}/tree/main/knowledge/)"
            )),
            ok(&format!("[t]({tree}/) [k]({tree}/knowledge/)"))
        );
        let others = format!(
            "[a](https://github.com/marola-dev/marola-app/blob/main/README.md) [b](https://github.com/marola-dev/marola-ml-old/blob/main/AGENTS.md) [c]({own}/blob/{}/AGENTS.md)",
            "c".repeat(40)
        );
        assert_eq!(readme(&others), ok(&others));
        let absolute = format!(
            "[d](https://docs.marola.dev/5-Repos/marola-ml/) [m](mailto:a@b) [h](http://x.example/AGENTS.md) [i]({own}/issues/3) [r]({own})"
        );
        assert_eq!(readme(&absolute), ok(&absolute));
        let missing = format!("missing at {sha}");
        assert_eq!(
            readme("[m](scripts/nope.sh)"),
            bad(
                "[m](scripts/nope.sh)",
                refused("README.md", "scripts/nope.sh", &missing)
            )
        );
        assert_eq!(
            readme("[m](docs/benchmarks/nope.md)"),
            bad(
                "[m](docs/benchmarks/nope.md)",
                refused("README.md", "docs/benchmarks/nope.md", &missing)
            )
        );
        let l = format!("{own}/blob/main/nope.md");
        assert_eq!(
            readme(&format!("[m]({l})")),
            bad(&format!("[m]({l})"), refused("README.md", &l, &missing))
        );

        // mount: the landing, docs beside it, exclude_docs skipped, refused links reported.
        let out = tmp.path().join("out");
        let mut ctx2 = ctx.clone();
        ctx2.exclude_docs = vec!["benchmarks/".into()];
        assert_eq!(mount(&repo, &out, &ctx2).unwrap(), Vec::<String>::new());
        assert_eq!(
            std::fs::read_to_string(out.join("index.md"))
                .unwrap()
                .lines()
                .next(),
            Some("# Repo")
        );
        assert!(out.join("img/a.png").is_file() && out.join("sub/index.md").is_file());
        assert!(!out.join("benchmarks").exists());
        std::fs::write(repo.join("docs/bad.md"), "[x](/abs)\n").unwrap();
        let ctx3 = Context {
            exclude_docs: vec![],
            ..ctx.clone()
        };
        assert_eq!(
            mount(&repo, &out, &ctx3).unwrap(),
            vec!["docs/bad.md: /abs: root-absolute; link the page relatively"]
        );
    }

    #[test]
    fn rewrites_the_umbrella() {
        let (sha, app_sha) = ("a".repeat(40), "b".repeat(40));
        let tmp = tempfile::tempdir().unwrap();
        let umbrella = tmp.path().join("marola");
        touch(
            &umbrella,
            &[
                "docs/PHASES.md",
                "docs/3-Working/x.md",
                "docs/MIPs/MIP-0001.md",
                "docs/MIPs/MIP-0002.md",
                "marola-app/README.md",
                "marola-app/docs/x.md",
                "marola-app/core/A.scala",
                "marola-app/core/l.svg",
                "marola-app/assets/l.png",
            ],
        );
        let ctx = Context {
            name: "marola".into(),
            sha,
            umbrella: true,
            submodule_shas: HashMap::from([("marola-app".to_string(), app_sha.clone())]),
            ..Default::default()
        };
        let page = |t: &str, s: &str| rewrite(t, s, &umbrella, &ctx);
        assert_eq!(
            page(
                "[a](marola-app/README.md) [b](marola-app/docs/x.md#s) [c](marola-app/docs/)",
                "README.md"
            ),
            ok(
                "[a](5-Repos/marola-app/index.md) [b](5-Repos/marola-app/x.md#s) [c](5-Repos/marola-app/index.md)"
            )
        );
        assert_eq!(
            page("[b](../../marola-app/docs/x.md)", "docs/3-Working/x.md"),
            ok("[b](../5-Repos/marola-app/x.md)")
        );
        let app = "https://github.com/marola-dev/marola-app";
        assert_eq!(
            page(
                "[c](marola-app/core/A.scala#L2) [d](marola-app/core/)",
                "README.md"
            ),
            ok(&format!(
                "[c]({app}/blob/{app_sha}/core/A.scala#L2) [d]({app}/tree/{app_sha}/core)"
            ))
        );
        assert_eq!(
            page("[m](marola-app/core/Nope.scala)", "README.md"),
            bad(
                "[m](marola-app/core/Nope.scala)",
                refused(
                    "README.md",
                    "marola-app/core/Nope.scala",
                    &format!("missing at {app_sha}")
                )
            )
        );
        let outside = "an image outside docs/; move it under docs/";
        assert_eq!(
            page("![l](marola-app/assets/l.png)", "README.md"),
            bad(
                "![l](marola-app/assets/l.png)",
                refused("README.md", "marola-app/assets/l.png", outside)
            )
        );
        assert_eq!(
            page(r#"<img src="marola-app/core/l.svg">"#, "README.md"),
            bad(
                r#"<img src="marola-app/core/l.svg">"#,
                refused("README.md", "marola-app/core/l.svg", outside)
            )
        );
        assert_eq!(
            page("[m](docs/MIPs/MIP-0001.md#a)", "README.md"),
            ok("[m](6-MIPs/MIP-0001.md#a)")
        );
        assert_eq!(
            page("[m](MIPs/MIP-0001.md)", "docs/PHASES.md"),
            ok("[m](6-MIPs/MIP-0001.md)")
        );
        assert_eq!(
            page(
                "[p](../PHASES.md) [o](MIP-0002.md)",
                "docs/MIPs/MIP-0001.md"
            ),
            ok("[p](../PHASES.md) [o](MIP-0002.md)")
        );
    }

    #[test]
    fn lists_links_outside_code() {
        assert_eq!(
            links(
                "[a](x.md#s) [![i](img/a.png)](<y z.md>)\n[r]: ref.md\n`[c](span.md)` and `wrapped\n[d](span2.md)`\n\n````\n```bash\n[e](f.md)\n```\n````\n"
            ),
            vec!["ref.md", "x.md#s", "y z.md", "img/a.png"]
        );
    }

    #[test]
    fn adr_index_skips_excluded() {
        let tmp = tempfile::tempdir().unwrap();
        let adrs = tmp.path().join("adrs");
        for n in ["0001", "0002"] {
            std::fs::create_dir_all(adrs.join("docs/adr")).unwrap();
            std::fs::write(
                adrs.join(format!("docs/adr/{n}-x.md")),
                format!("# ADR-{n}: D{n}\n\n| **Status** | Accepted |\n"),
            )
            .unwrap();
        }
        std::fs::write(adrs.join("README.md"), "# adrs\n").unwrap();
        let out = tmp.path().join("out/adrs");
        let ctx = Context {
            name: "adrs".into(),
            sha: "a".repeat(40),
            exclude_docs: vec!["0002-*.md".into()],
            ..Default::default()
        };
        assert_eq!(mount(&adrs, &out, &ctx).unwrap(), Vec::<String>::new());
        let index = std::fs::read_to_string(out.join("adr/index.md")).unwrap();
        assert_eq!(
            index.lines().last(),
            Some("| [0001](0001-x.md) | D0001 | Accepted |")
        );
    }
}
