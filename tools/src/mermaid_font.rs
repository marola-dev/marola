//! Pin the font Kroki measures Mermaid labels in. See #511.
//!
//! Every top-level ```mermaid fence gets an init line naming a font Kroki actually has, so it
//! never measures label width in a fallback the kroki plugin's own styles injection cannot reach.
//! It was an mkdocs hook; it now rewrites the staged copy `scripts/mkdocs.sh` bakes into the
//! image, before mkdocs and the kroki plugin ever read it.
//!
//!     marola-tools mermaid-font DIR...   # rewrite every .md under each DIR in place

use crate::anyhow_lite;
use fancy_regex::Regex;
use std::path::PathBuf;
use std::sync::LazyLock;

const FONT_FAMILY: &str = r#""Open Sans", "trebuchet ms", verdana, arial, sans-serif"#;

fn init_line() -> String {
    let font = serde_json::to_string(FONT_FAMILY).expect("a string serializes");
    format!("%%{{init: {{\"fontFamily\": {font}}}}}%%")
}

// Mirrors mkdocs_kroki_plugin's own kroki/parsing.py _FENCE_RE (1.7.0): requiring the closing
// fence to repeat the opening's exact indent and backtick/tilde run is what keeps a ```mermaid```
// example quoted inside a longer fence (DIAGRAMS.md's "Source" blocks) from being rewritten too.
static FENCE_RE: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(
        r"(?ism)(?P<fence>^(?P<indent>[ ]*)(?:````*|~~~~*))[ ]*(?P<lang>[\w#.+-]*)[^\n]*\n(?P<code>.*?)(?<=\n)(?P=fence)[ ]*$",
    )
    .expect("valid regex")
});

/// Prepend the font-pin init line to every top-level ```mermaid fence.
pub fn pin_mermaid_font(markdown: &str) -> String {
    let init = init_line();
    let mut out = String::with_capacity(markdown.len());
    let mut last_end = 0;
    for caps in FENCE_RE.captures_iter(markdown) {
        let caps = caps.expect("the fence regex does not hit the backtrack limit");
        let whole = caps.get(0).expect("group 0");
        out.push_str(&markdown[last_end..whole.start()]);
        let lang = caps
            .name("lang")
            .map_or("", |m| m.as_str())
            .trim()
            .to_lowercase();
        let code = caps.name("code").expect("code group");
        if lang == "mermaid" && !code.as_str().starts_with(&init) {
            let indent = caps.name("indent").map_or("", |m| m.as_str());
            out.push_str(&markdown[whole.start()..code.start()]);
            out.push_str(indent);
            out.push_str(&init);
            out.push('\n');
            out.push_str(code.as_str());
            out.push_str(&markdown[code.end()..whole.end()]);
        } else {
            out.push_str(whole.as_str());
        }
        last_end = whole.end();
    }
    out.push_str(&markdown[last_end..]);
    out
}

pub fn run(dirs: &[PathBuf]) -> anyhow_lite::Result {
    let mut changed = 0;
    for dir in dirs {
        if !dir.is_dir() {
            return Err(format!("mermaid-font: no such directory: {}", dir.display()).into());
        }
        for entry in walkdir::WalkDir::new(dir).sort_by_file_name() {
            let entry = entry.map_err(|e| e.to_string())?;
            let path = entry.path();
            if !path.is_file() || path.extension().is_none_or(|e| e != "md") {
                continue;
            }
            let text =
                std::fs::read_to_string(path).map_err(|e| format!("{}: {e}", path.display()))?;
            let pinned = pin_mermaid_font(&text);
            if pinned != text {
                std::fs::write(path, pinned).map_err(|e| e.to_string())?;
                changed += 1;
            }
        }
    }
    println!("mermaid-font: pinned the Mermaid font in {changed} page(s)");
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    const SAMPLE: &str = r##"# Sample

```mermaid
flowchart TD
  a --> b
```

````markdown
```mermaid
flowchart TD
  a --> b
```
````

```mermaid
%%{init: {"themeVariables": {"git0": "#1ac5da"}}}%%
gitGraph
  commit
```
"##;

    #[test]
    fn pins_each_real_fence_once() {
        let out = pin_mermaid_font(SAMPLE);
        assert_eq!(out.matches("fontFamily").count(), 2, "{out}");
        assert_eq!(out.lines().filter(|l| *l == "```").count(), 3);
        assert_eq!(out.matches("````markdown").count(), 1);
        assert_eq!(out.matches("git0").count(), 1);
        let after_open: Vec<&str> = out
            .lines()
            .collect::<Vec<_>>()
            .windows(2)
            .filter(|w| w[0] == "```mermaid")
            .map(|w| w[1])
            .collect();
        assert_eq!(after_open.len(), 3);
        assert_eq!(
            after_open
                .iter()
                .filter(|l| l.contains("Open Sans"))
                .count(),
            2
        );
        assert_eq!(
            init_line(),
            r#"%%{init: {"fontFamily": "\"Open Sans\", \"trebuchet ms\", verdana, arial, sans-serif"}}%%"#
        );
    }

    #[test]
    fn idempotent() {
        let once = pin_mermaid_font(SAMPLE);
        assert_eq!(pin_mermaid_font(&once), once);
    }
}
