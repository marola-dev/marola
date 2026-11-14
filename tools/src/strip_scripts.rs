//! Make generated API docs obey the site's `script-src 'self'` CSP (MIP-0044 §5.6).
//!
//! scaladoc emits four third-party `<script src="https://...">` tags on every page — dagre-d3,
//! graphlib-dot, d3 and scastie — and one inline `var pathToRoot = "..."`. A site that advertises no
//! third-party requests cannot serve them. Losing dagre/d3 loses inheritance diagrams and losing
//! scastie loses "run this snippet"; neither is used by this codebase's docs.
//!
//! `pathToRoot` is kept, moved to a `data-path-to-root` attribute on <body> that scaladoc's own
//! scripts read via a tiny same-origin shim, so relative links still resolve without an inline script.
//!
//!     marola-tools strip-external-scripts out/scala out/python
//!     marola-tools strip-external-scripts --check mkdocs/generated-docs

use crate::util::read_lossy;
use regex::Regex;
use std::path::{Path, PathBuf};
use std::sync::LazyLock;

static EXTERNAL_SCRIPT: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r#"(?i)<script[^>]*\ssrc=["']https?://[^"']*["'][^>]*>\s*</script>"#).unwrap()
});
static PATH_TO_ROOT: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r#"(?i)<script[^>]*>\s*var\s+pathToRoot\s*=\s*["']([^"']*)["']\s*;?\s*</script>"#)
        .unwrap()
});
static BODY: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"<body([^>]*)>").unwrap());

const SHIM_JS: &str =
    "// Restores the `pathToRoot` global scaladoc's own scripts expect, without an inline
// <script> — read from <body data-path-to-root>, so the page keeps script-src 'self'. MIP-0044.
var pathToRoot = document.body.getAttribute(\"data-path-to-root\") || \"\";
";

/// Remove third-party scripts and inline pathToRoot; return the new html and how many went.
pub fn strip(html: &str) -> (String, usize) {
    let mut html = html.to_string();
    if let Some(m) = PATH_TO_ROOT.captures(&html) {
        let root = m[1].to_string();
        html = PATH_TO_ROOT.replacen(&html, 1, "").into_owned();
        html = BODY
            .replacen(&html, 1, |c: &regex::Captures| {
                format!(r#"<body{} data-path-to-root="{root}">"#, &c[1])
            })
            .into_owned();
        let shim = format!(r#"<script src="{root}scripts/path-to-root.js"></script>"#);
        html = html.replacen("</head>", &format!("{shim}</head>"), 1);
    }
    let removed = EXTERNAL_SCRIPT.find_iter(&html).count();
    (EXTERNAL_SCRIPT.replace_all(&html, "").into_owned(), removed)
}

fn html_files(dir: &Path) -> Vec<PathBuf> {
    walkdir::WalkDir::new(dir)
        .into_iter()
        .filter_map(Result::ok)
        .map(|e| e.into_path())
        .filter(|p| p.is_file() && p.extension().is_some_and(|e| e == "html"))
        .collect()
}

/// Report any real external <script src> left in the tree, using EXTERNAL_SCRIPT itself.
///
/// The workflow used to grep for the looser `src="http`, which matched this tool's own
/// documentation once rendered — escaped text about script tags, not a script tag.
pub fn check(dirs: &[PathBuf]) -> i32 {
    let offenders: Vec<PathBuf> = dirs
        .iter()
        .filter(|d| d.is_dir())
        .flat_map(|d| html_files(d))
        .filter(|f| read_lossy(f).is_ok_and(|t| EXTERNAL_SCRIPT.is_match(&t)))
        .collect();
    for f in offenders.iter().take(5) {
        eprintln!("external script src survives in {}", f.display());
    }
    if !offenders.is_empty() {
        eprintln!("{} file(s) would violate the site CSP", offenders.len());
        return 1;
    }
    println!("no external script src in the generated tree");
    0
}

pub fn process(dirs: &[PathBuf]) -> i32 {
    let (mut total_files, mut total_removed) = (0, 0);
    for d in dirs {
        if !d.is_dir() {
            eprintln!("strip_external_scripts: no such directory: {}", d.display());
            return 1;
        }
        let scripts = d.join("scripts");
        if std::fs::create_dir_all(&scripts)
            .and_then(|_| std::fs::write(scripts.join("path-to-root.js"), SHIM_JS))
            .is_err()
        {
            eprintln!("strip_external_scripts: cannot write {}", scripts.display());
            return 1;
        }
        for f in html_files(d) {
            let Ok(html) = read_lossy(&f) else { continue };
            let (new, removed) = strip(&html);
            if new != html && std::fs::write(&f, &new).is_err() {
                eprintln!("strip_external_scripts: cannot write {}", f.display());
                return 1;
            }
            total_files += 1;
            total_removed += removed;
        }
    }
    println!("stripped {total_removed} external <script> tags across {total_files} html files");
    0
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn strips() {
        let page = concat!(
            r#"<html><head><script type="text/javascript" src="https://d3js.org/d3.v6.min.js"></script>"#,
            r#"<script src="https://scastie.scala-lang.org/embedded.js"></script>"#,
            r#"<script src="scripts/ux.js"></script></head>"#,
            r#"<body class="theme"><script>var pathToRoot = "../";</script>hi</body></html>"#
        );
        let (out, removed) = strip(page);
        assert_eq!(removed, 2, "both third-party scripts are removed");
        assert!(!out.contains("d3js.org"), "no https:// script src survives");
        assert!(
            out.contains(r#"src="scripts/ux.js""#),
            "the doc's own scripts are kept"
        );
        assert!(
            !out.contains("var pathToRoot"),
            "the inline pathToRoot script is gone"
        );
        assert!(
            out.contains(r#"data-path-to-root="../""#),
            "pathToRoot is kept as data"
        );
        assert!(
            out.contains(r#"class="theme""#),
            "existing body attributes survive"
        );
        assert_eq!(
            out.matches("path-to-root.js").count(),
            1,
            "the shim is linked once"
        );
        assert_eq!(strip("<html><body>plain</body></html>").1, 0);
        assert_eq!(
            strip(r#"<script src="http://x.example/a.js"></script>"#).1,
            1
        );
    }

    #[test]
    fn check_tree() {
        let tmp = tempfile::tempdir().unwrap();
        let d = tmp.path().to_path_buf();
        std::fs::write(d.join("clean.html"), "<html><body>no scripts</body></html>").unwrap();
        assert_eq!(check(std::slice::from_ref(&d)), 0);
        std::fs::write(
            d.join("doc.html"),
            concat!(
                r#"<html><body><code>EXTERNAL_SCRIPT = &lt;script src="https://x/a.js"&gt;</code>"#,
                r#"<p>strips &lt;script src=&quot;https://...&quot;&gt; tags</p></body></html>"#
            ),
        )
        .unwrap();
        assert_eq!(
            check(std::slice::from_ref(&d)),
            0,
            "docs about script tags are not one"
        );
        std::fs::write(
            d.join("real.html"),
            r#"<html><head><script src="https://d3js.org/d3.v6.min.js"></script></head></html>"#,
        )
        .unwrap();
        assert_eq!(
            check(std::slice::from_ref(&d)),
            1,
            "a real external script is caught"
        );
    }
}
