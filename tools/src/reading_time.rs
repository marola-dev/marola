//! Every README carries its own reading time, computed here and never by hand (AGENTS.md).
//!
//!     marola-tools reading-time            # print each README's estimate
//!     marola-tools reading-time --write    # write the badge line into each README
//!     marola-tools reading-time --check    # exit 1 if a README's badge line is missing or stale
//!
//! The READMEs are every tracked `README*.md` of this repo (the submodules carry their own). The
//! estimate counts the words a person reads: fenced code, HTML comments, images and badges, tags
//! and URLs are dropped, a link keeps its text. Silent reading speed is Brysbaert (2019), *Journal
//! of Memory and Language* 109, 104047: 238 words per minute for English non-fiction and 181 for
//! Portuguese, plus 15 seconds per code block, rounded up to whole minutes.
//!
//! A README is English when its name ends in `.en.md` or when its text has more English than
//! Portuguese function words; otherwise it is pt-BR. The badge links to the section of the
//! umbrella README that explains the discipline (`#tempo-de-leitura`, `#reading-time`).

use crate::util::{posix, repo_root};
use regex::Regex;
use std::path::Path;
use std::process::Command;
use std::sync::LazyLock;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Lang {
    PtBr,
    En,
}

#[derive(Debug, PartialEq, Eq)]
pub struct Estimate {
    pub words: usize,
    pub code_blocks: usize,
    pub minutes: u32,
}

const WPM_PT: f64 = 181.0;
const WPM_EN: f64 = 238.0;
const CODE_BLOCK_MIN: f64 = 0.25;
const COLOR: &str = "1682D4";

static COMMENT: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"(?s)<!--.*?-->").unwrap());
static IMAGE: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"!\[[^\]]*\]\([^)]*\)").unwrap());
static LINK: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"\[([^\]]*)\]\([^)]*\)").unwrap());
static TAG: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"<[^>\n]+>").unwrap());
static URL: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"https?://\S+").unwrap());
static BADGE: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r#"^<a href="[^"]*"><img src="[^"]*" alt="(tempo de leitura|reading time): "#)
        .unwrap()
});

/// The prose of a Markdown file, and how many fenced code blocks it had.
pub fn prose(text: &str) -> (String, usize) {
    let mut out = String::new();
    let mut blocks = 0;
    let mut fence: Option<String> = None;
    for line in text.lines() {
        let t = line.trim_start();
        let marker: String = t.chars().take_while(|&c| c == '`' || c == '~').collect();
        match &fence {
            Some(f)
                if t.starts_with(f.as_str())
                    && t.trim_end().chars().all(|c| c == '`' || c == '~') =>
            {
                fence = None
            }
            Some(_) => {}
            None if marker.len() >= 3
                && marker.chars().all(|c| c == marker.chars().next().unwrap()) =>
            {
                blocks += 1;
                fence = Some(marker);
            }
            None => {
                out.push_str(line);
                out.push('\n');
            }
        }
    }
    let out = COMMENT.replace_all(&out, " ");
    let out = IMAGE.replace_all(&out, " ");
    let out = LINK.replace_all(&out, "$1");
    let out = TAG.replace_all(&out, " ");
    let out = URL.replace_all(&out, " ");
    (out.into_owned(), blocks)
}

fn words(prose: &str) -> Vec<&str> {
    prose
        .split_whitespace()
        .filter(|w| w.chars().any(char::is_alphanumeric))
        .collect()
}

pub fn lang_of(path: &str, text: &str) -> Lang {
    if path.ends_with(".en.md") {
        return Lang::En;
    }
    let (p, _) = prose(text);
    let (mut en, mut pt) = (0, 0);
    for w in words(&p) {
        match w
            .to_lowercase()
            .trim_matches(|c: char| !c.is_alphanumeric())
        {
            "the" | "and" | "of" | "is" | "to" | "with" => en += 1,
            "de" | "que" | "não" | "um" | "uma" | "com" | "para" => pt += 1,
            _ => {}
        }
    }
    if en > pt { Lang::En } else { Lang::PtBr }
}

pub fn estimate(text: &str, lang: Lang) -> Estimate {
    let (p, code_blocks) = prose(text);
    let words = words(&p).len();
    let wpm = if lang == Lang::En { WPM_EN } else { WPM_PT };
    let minutes = (words as f64 / wpm + code_blocks as f64 * CODE_BLOCK_MIN)
        .ceil()
        .max(1.0) as u32;
    Estimate {
        words,
        code_blocks,
        minutes,
    }
}

/// The badge line for the README at `path` (relative to the repo root).
pub fn badge(path: &str, lang: Lang, minutes: u32) -> String {
    let (label, alt, target) = match lang {
        Lang::PtBr => (
            "tempo%20de%20leitura",
            "tempo de leitura",
            "README.md#tempo-de-leitura",
        ),
        Lang::En => (
            "reading%20time",
            "reading time",
            "README.en.md#reading-time",
        ),
    };
    let href = if posix::dirname(path).is_empty() && target.starts_with(path) {
        format!("#{}", target.split_once('#').unwrap().1)
    } else {
        let (file, anchor) = target.split_once('#').unwrap();
        format!("{}#{anchor}", posix::relpath(file, posix::dirname(path)))
    };
    format!(
        r#"<a href="{href}"><img src="https://img.shields.io/badge/{label}-~{minutes}%20min-{COLOR}" alt="{alt}: ~{minutes} min" /></a>"#
    )
}

/// `text` with its badge line set to `line`: replaced in place, or put after the license badge, or
/// else after the first heading.
pub fn with_badge(text: &str, line: &str) -> String {
    let mut lines: Vec<&str> = text.lines().collect();
    if let Some(i) = lines.iter().position(|l| BADGE.is_match(l)) {
        lines[i] = line;
    } else if let Some(i) = lines
        .iter()
        .position(|l| l.contains("img.shields.io/badge/license-"))
    {
        lines.insert(i + 1, line);
    } else {
        let i = lines
            .iter()
            .position(|l| l.starts_with("# "))
            .map_or(0, |i| i + 1);
        lines.splice(i..i, ["", line]);
    }
    let mut out = lines.join("\n");
    if text.ends_with('\n') {
        out.push('\n');
    }
    out
}

fn readmes(root: &Path) -> Result<Vec<String>, String> {
    let out = Command::new("git")
        .args(["ls-files", "--", "README*.md", "**/README*.md"])
        .current_dir(root)
        .output()
        .map_err(|e| format!("reading-time: git: {e}"))?;
    let mut files: Vec<String> = String::from_utf8_lossy(&out.stdout)
        .lines()
        .map(str::to_string)
        .collect();
    files.sort();
    files.dedup();
    Ok(files)
}

pub fn run(write: bool, check: bool) -> crate::anyhow_lite::Result {
    let root = repo_root();
    let mut stale = Vec::new();
    for rel in readmes(&root)? {
        let path = root.join(&rel);
        let text = std::fs::read_to_string(&path)?;
        let lang = lang_of(&rel, &text);
        let e = estimate(&text, lang);
        let line = badge(&rel, lang, e.minutes);
        let want = with_badge(&text, &line);
        if want != text {
            if write {
                std::fs::write(&path, &want)?;
            } else {
                stale.push(rel.clone());
            }
        }
        let (w, b) = if lang == Lang::En {
            ("words", "code blocks")
        } else {
            ("palavras", "blocos de código")
        };
        println!(
            "{rel}: ~{} min ({} {w}, {} {b})",
            e.minutes, e.words, e.code_blocks
        );
    }
    if check && !stale.is_empty() {
        return Err(format!(
            "reading-time: the badge of {} is missing or stale; run just reading-time",
            stale.join(", ")
        )
        .into());
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn counts_only_prose() {
        let md = "# Título\n\n<!-- não conta -->\n![badge](https://x/y.svg) [o mapa](https://marola.dev/) é <b>bom</b>\n\n```sh\nnão conta nada\n```\n\nVeja https://marola.dev — fim.\n";
        let e = estimate(md, Lang::PtBr);
        assert_eq!((e.words, e.code_blocks, e.minutes), (7, 1, 1));
        assert_eq!(estimate(&"palavra ".repeat(363), Lang::PtBr).minutes, 3);
        assert_eq!(estimate(&"word ".repeat(476), Lang::En).minutes, 2);
        assert_eq!(estimate("", Lang::En).minutes, 1);
    }

    #[test]
    fn tells_the_language() {
        assert_eq!(lang_of("README.en.md", "o mar de que"), Lang::En);
        assert_eq!(
            lang_of(
                "README.md",
                "O mar de Floripa é bom para nadar com a família."
            ),
            Lang::PtBr
        );
        assert_eq!(
            lang_of(
                "docs/X/README.md",
                "The index of the design docs, with the status of each."
            ),
            Lang::En
        );
    }

    #[test]
    fn links_each_badge_to_the_discipline() {
        assert_eq!(
            badge("README.md", Lang::PtBr, 7),
            r##"<a href="#tempo-de-leitura"><img src="https://img.shields.io/badge/tempo%20de%20leitura-~7%20min-1682D4" alt="tempo de leitura: ~7 min" /></a>"##
        );
        assert!(badge("README.en.md", Lang::En, 2).starts_with(r##"<a href="#reading-time">"##));
        assert!(
            badge("docs/MIPs/README.md", Lang::En, 2)
                .starts_with(r#"<a href="../../README.en.md#reading-time">"#)
        );
    }

    #[test]
    fn places_and_replaces_the_badge() {
        let line = badge("README.md", Lang::PtBr, 3);
        let top = "<p>\n<a href=\"./LICENSE\"><img src=\"https://img.shields.io/badge/license-CC\" /></a>\n</p>\n";
        let placed = with_badge(top, &line);
        assert_eq!(placed.lines().nth(2), Some(line.as_str()));
        assert_eq!(with_badge(&placed, &line), placed);
        let newer = badge("README.md", Lang::PtBr, 4);
        assert_eq!(
            with_badge(&placed, &newer)
                .matches("tempo de leitura")
                .count(),
            1
        );
        let doc = "# MIP index\n\nDesign docs.\n";
        assert_eq!(with_badge(doc, "B"), "# MIP index\n\nB\n\nDesign docs.\n");
    }
}
