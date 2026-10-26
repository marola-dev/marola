//! Check that every docs.marola.dev link in a built site resolves, and that the previous live
//! sitemap's URLs still land somewhere, so a page move can't silently drop a URL (MIP-0074 §5.3, §7).
//!
//! No network: a built site is already on disk, and the "old" sitemap is a file the caller
//! fetched beforehand (docs.yml curls the live one before a build that might change it).
//!
//!     marola-tools site-links-check mkdocs/generated-docs
//!     marola-tools site-links-check mkdocs/generated-docs --old-sitemap old-sitemap.xml

use crate::util::{read_lossy, repo_root, unquote, url_path};
use fancy_regex::Regex as FancyRegex;
use regex::Regex;
use std::path::{Component, Path, PathBuf};
use std::sync::LazyLock;

const SITE: &str = "https://docs.marola.dev";

// The quote is captured once and matched again so the value must be exactly SITE, optionally
// followed by a `/...` path — not merely start with SITE, which would also take
// https://docs.marola.dev.example/ as this site.
static HREF: LazyLock<FancyRegex> = LazyLock::new(|| {
    FancyRegex::new(&format!(
        r#"(?i)(?<=\s)href\s*=\s*(?P<q>["'])(?P<url>{}(?:/[^"']*)?)(?P=q)"#,
        regex::escape(SITE)
    ))
    .unwrap()
});
static LOC: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"(?i)<loc>\s*([^<\s]+)\s*</loc>").unwrap());
// mkdocs/overrides/404.html's inline array (MIP-0074 Appendix B) — read, not owned.
static MOVES_BLOCK: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"(?s)\bmoves\s*=\s*\[(.*?)\]\s*\.\s*sort").unwrap());
static MOVE_PAIR: LazyLock<FancyRegex> =
    LazyLock::new(|| FancyRegex::new(r#"\[\s*(['"])(.*?)\1\s*,\s*(['"])(.*?)\3\s*\]"#).unwrap());

/// The old->new URL pairs overrides/404.html forwards, longest old first — same tie-break its own
/// inline script uses, so a path under two prefixes matches the narrower one.
pub fn forward_prefixes(html: &str) -> Vec<(String, String)> {
    let body = MOVES_BLOCK
        .captures(html)
        .map_or("", |c| c.get(1).unwrap().as_str());
    let mut pairs: Vec<(String, String)> = MOVE_PAIR
        .captures_iter(body)
        .filter_map(Result::ok)
        .map(|c| (c[2].to_string(), c[4].to_string()))
        .collect();
    pairs.sort_by_key(|p| std::cmp::Reverse(p.0.chars().count()));
    pairs
}

/// The file in `site_dir` that `url_path` (directory-url style) serves, if any.
pub fn resolve(url_path: &str, site_dir: &Path) -> Option<PathBuf> {
    let rel = url_path.trim_start_matches('/');
    if Path::new(rel)
        .components()
        .any(|c| c == Component::ParentDir)
    {
        return None;
    }
    let candidates = if rel.is_empty() || rel.ends_with('/') {
        vec![site_dir.join(rel).join("index.html")]
    } else {
        vec![site_dir.join(rel), site_dir.join(rel).join("index.html")]
    };
    candidates.into_iter().find(|c| c.is_file())
}

fn html_pages(site_dir: &Path) -> Vec<PathBuf> {
    let mut pages: Vec<PathBuf> = walkdir::WalkDir::new(site_dir)
        .into_iter()
        .filter_map(Result::ok)
        .map(|e| e.into_path())
        .filter(|p| p.is_file() && p.extension().is_some_and(|e| e == "html"))
        .collect();
    pages.sort();
    pages
}

/// (how many docs.marola.dev hrefs are under `site_dir`, which of them don't resolve in it).
pub fn check_hrefs(site_dir: &Path) -> (usize, Vec<String>) {
    let mut total = 0;
    let mut broken = Vec::new();
    for page in html_pages(site_dir) {
        let Ok(html) = read_lossy(&page) else {
            continue;
        };
        for c in HREF.captures_iter(&html).filter_map(Result::ok) {
            total += 1;
            let url = &c["url"];
            if resolve(&unquote(url_path(url)), site_dir).is_none() {
                let rel = page.strip_prefix(site_dir).unwrap_or(&page);
                broken.push(format!("{}: {url}", rel.display()));
            }
        }
    }
    (total, broken)
}

/// (how many <loc> the old sitemap had, which of them are now dropped — not a page, a redirect
/// stub, nor forwarded by a 404-forward prefix to a page that still exists). The count is the
/// caller's signal that the sitemap was real: zero must never read as "nothing dropped".
pub fn check_old_sitemap(
    site_dir: &Path,
    xml: &str,
    moves: &[(String, String)],
) -> (usize, Vec<String>) {
    let locs: Vec<&str> = LOC
        .captures_iter(xml)
        .map(|c| c.get(1).unwrap().as_str())
        .collect();
    let mut dropped = Vec::new();
    for loc in &locs {
        let path = unquote(url_path(loc));
        if resolve(&path, site_dir).is_some() {
            continue;
        }
        let forwarded = moves
            .iter()
            .find(|(old, _)| path.starts_with(old.as_str()))
            .and_then(|(old, new)| resolve(&format!("{new}{}", &path[old.len()..]), site_dir));
        if forwarded.is_none() {
            dropped.push(loc.to_string());
        }
    }
    (locs.len(), dropped)
}

pub fn main(site_dir: &Path, old_sitemap: Option<&Path>) -> i32 {
    if !site_dir.is_dir() {
        eprintln!(
            "site_links_check: no such directory: {}",
            site_dir.display()
        );
        return 1;
    }
    let (href_total, broken) = check_hrefs(site_dir);
    let (mut old_total, mut dropped) = (0, Vec::new());
    if let Some(sitemap) = old_sitemap {
        let overrides = repo_root().join("mkdocs/overrides/404.html");
        let moves = std::fs::read_to_string(&overrides)
            .map(|h| forward_prefixes(&h))
            .unwrap_or_default();
        let xml = read_lossy(sitemap).unwrap_or_default();
        (old_total, dropped) = check_old_sitemap(site_dir, &xml, &moves);
    }
    for b in &broken {
        eprintln!("broken href: {b}");
    }
    for d in &dropped {
        eprintln!("dropped url: {d}");
    }
    let sitemap_empty = old_sitemap.is_some() && old_total == 0;
    if sitemap_empty {
        eprintln!(
            "site_links_check: {} has no <loc> at all — an empty fetch, an error page, or a sitemap format this check doesn't parse; not a clean sitemap",
            old_sitemap.unwrap().display()
        );
    }
    if !broken.is_empty() || !dropped.is_empty() || sitemap_empty {
        eprintln!(
            "site_links_check: {} broken href(s), {} dropped url(s)",
            broken.len(),
            dropped.len()
        );
        return 1;
    }
    if old_sitemap.is_some() {
        println!("site_links_check: ok ({href_total} href(s), {old_total} old url(s) checked)");
    } else {
        println!("site_links_check: ok ({href_total} href(s) checked)");
    }
    0
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs::{create_dir_all, write};

    #[test]
    fn reads_the_404_moves() {
        let html = "<script>\nvar moves = [\n  ['/repos/marola-ml/api/', '/5-Repos/marola-ml/api-docs/python/'],\n  ['/repos/', '/5-Repos/'],\n  ['/MIPs/', '/6-MIPs/'],\n  ['/api/', '/5-Repos/marola-app/api-docs/']\n].sort(function (a, b) { return b[0].length - a[0].length; });\n</script>";
        let pairs = forward_prefixes(html);
        let olds: Vec<&str> = pairs.iter().map(|p| p.0.as_str()).collect();
        assert_eq!(
            olds,
            ["/repos/marola-ml/api/", "/repos/", "/MIPs/", "/api/"]
        );
        assert_eq!(
            pairs.iter().find(|p| p.0 == "/MIPs/").unwrap().1,
            "/6-MIPs/"
        );
        let real = std::fs::read_to_string(repo_root().join("mkdocs/overrides/404.html")).unwrap();
        assert!(
            !forward_prefixes(&real).is_empty(),
            "the committed 404.html still parses"
        );
    }

    #[test]
    fn resolves() {
        let t = tempfile::tempdir().unwrap();
        let site = t.path();
        create_dir_all(site.join("a")).unwrap();
        write(site.join("a/index.html"), "<html>a</html>").unwrap();
        write(site.join("index.html"), "<html>root</html>").unwrap();
        write(site.join("sitemap.xml"), "<urlset></urlset>").unwrap();
        assert_eq!(
            resolve("/a/", site),
            Some(site.join("a/").join("index.html"))
        );
        assert!(resolve("/a", site).is_some());
        assert_eq!(resolve("/", site), Some(site.join("").join("index.html")));
        assert_eq!(
            resolve("/sitemap.xml", site),
            Some(site.join("sitemap.xml"))
        );
        assert_eq!(resolve("/missing/", site), None);
        assert_eq!(resolve("/../../etc/passwd", site), None);
    }

    #[test]
    fn hrefs() {
        let t = tempfile::tempdir().unwrap();
        let site = t.path();
        create_dir_all(site.join("a")).unwrap();
        write(site.join("a/index.html"), "<html></html>").unwrap();
        create_dir_all(site.join("a b")).unwrap();
        write(site.join("a b/index.html"), "<html></html>").unwrap();
        write(
            site.join("index.html"),
            concat!(
                r#"<html><a href="https://docs.marola.dev/a/">a</a>"#,
                r#"<a href="https://docs.marola.dev/gone/">gone</a>"#,
                r#"<a href="https://docs.marola.dev/a%20b/">encoded space</a>"#,
                r#"<a href="https://docs.marola.dev.example/evil/">not this site</a>"#,
                r#"<a href="https://example.com/other/">other</a></html>"#
            ),
        )
        .unwrap();
        let (total, broken) = check_hrefs(site);
        assert_eq!(total, 3, "the lookalike host is not this site");
        assert_eq!(broken.len(), 1);
        assert!(broken[0].contains("gone"));
    }

    #[test]
    fn old_sitemap() {
        let t = tempfile::tempdir().unwrap();
        let site = t.path();
        create_dir_all(site.join("unchanged")).unwrap();
        write(site.join("unchanged/index.html"), "<html>still here</html>").unwrap();
        create_dir_all(site.join("moved-old")).unwrap();
        write(
            site.join("moved-old/index.html"),
            r#"<meta http-equiv="refresh" content="0; url=../moved-new/">"#,
        )
        .unwrap();
        create_dir_all(site.join("5-Repos/marola-site")).unwrap();
        write(
            site.join("5-Repos/marola-site/index.html"),
            "<html>new home</html>",
        )
        .unwrap();
        let xml = concat!(
            "<urlset>",
            "<url><loc>https://docs.marola.dev/unchanged/</loc></url>",
            "<url><loc>https://docs.marola.dev/moved-old/</loc></url>",
            "<url><loc>https://docs.marola.dev/repos/marola-site/</loc></url>",
            "<url><loc>https://docs.marola.dev/repos/marola-ml/</loc></url>",
            "<url><loc>https://docs.marola.dev/gone/</loc></url>",
            "</urlset>"
        );
        let (total, dropped) =
            check_old_sitemap(site, xml, &[("/repos/".into(), "/5-Repos/".into())]);
        assert_eq!(total, 5);
        assert_eq!(
            dropped,
            [
                "https://docs.marola.dev/repos/marola-ml/",
                "https://docs.marola.dev/gone/"
            ]
        );
    }

    #[test]
    fn an_empty_sitemap_fails() {
        let t = tempfile::tempdir().unwrap();
        let site = t.path().join("site");
        create_dir_all(&site).unwrap();
        write(site.join("index.html"), "<html></html>").unwrap();
        let garbage = t.path().join("garbage.xml");
        write(&garbage, "<html>404 Not Found</html>").unwrap();
        assert_eq!(main(&site, Some(&garbage)), 1);
        assert_eq!(main(&site, None), 0);
    }
}
