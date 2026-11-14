//! Shared helpers: the repo root, JSON written byte-for-byte as Python's `json.dumps` wrote it
//! (the files these tools publish were Python's before, and a reader may grep them), and the
//! `posixpath`/`fnmatch` pieces the link rewriter needs.

use serde_json::Value;
use std::fmt::Write as _;
use std::path::{Path, PathBuf};

/// The umbrella's checkout: this crate lives at `<root>/tools`, and is always built in place.
pub fn repo_root() -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR"))
        .parent()
        .expect("tools/ has a parent")
        .to_path_buf()
}

/// A file as text, invalid UTF-8 dropped (Python's `errors="ignore"`).
pub fn read_lossy(path: &Path) -> std::io::Result<String> {
    let bytes = std::fs::read(path)?;
    let mut out = String::with_capacity(bytes.len());
    for chunk in bytes.utf8_chunks() {
        out.push_str(chunk.valid());
    }
    Ok(out)
}

/// `json.dumps(value, indent=..., ensure_ascii=...)`: `", "`/`": "` separators when compact,
/// `","` + newline and `": "` when indented, and non-ASCII escaped as `\uXXXX` when asked.
pub fn dumps(value: &Value, indent: Option<usize>, ensure_ascii: bool) -> String {
    let mut out = String::new();
    write_value(&mut out, value, indent, ensure_ascii, 0);
    out
}

fn write_value(out: &mut String, v: &Value, indent: Option<usize>, ascii: bool, depth: usize) {
    match v {
        Value::Null => out.push_str("null"),
        Value::Bool(b) => out.push_str(if *b { "true" } else { "false" }),
        Value::Number(n) => out.push_str(&n.to_string()),
        Value::String(s) => write_str(out, s, ascii),
        Value::Array(items) => {
            if items.is_empty() {
                out.push_str("[]");
                return;
            }
            out.push('[');
            for (i, item) in items.iter().enumerate() {
                sep(out, i, indent, depth + 1);
                write_value(out, item, indent, ascii, depth + 1);
            }
            close(out, indent, depth);
            out.push(']');
        }
        Value::Object(map) => {
            if map.is_empty() {
                out.push_str("{}");
                return;
            }
            out.push('{');
            for (i, (k, item)) in map.iter().enumerate() {
                sep(out, i, indent, depth + 1);
                write_str(out, k, ascii);
                out.push_str(": ");
                write_value(out, item, indent, ascii, depth + 1);
            }
            close(out, indent, depth);
            out.push('}');
        }
    }
}

fn sep(out: &mut String, i: usize, indent: Option<usize>, depth: usize) {
    match indent {
        Some(n) => {
            if i > 0 {
                out.push(',');
            }
            out.push('\n');
            out.push_str(&" ".repeat(n * depth));
        }
        None if i > 0 => out.push_str(", "),
        None => {}
    }
}

fn close(out: &mut String, indent: Option<usize>, depth: usize) {
    if let Some(n) = indent {
        out.push('\n');
        out.push_str(&" ".repeat(n * depth));
    }
}

fn write_str(out: &mut String, s: &str, ascii: bool) {
    out.push('"');
    for c in s.chars() {
        match c {
            '"' => out.push_str("\\\""),
            '\\' => out.push_str("\\\\"),
            '\n' => out.push_str("\\n"),
            '\r' => out.push_str("\\r"),
            '\t' => out.push_str("\\t"),
            '\u{8}' => out.push_str("\\b"),
            '\u{c}' => out.push_str("\\f"),
            c if (c as u32) < 0x20 => {
                let _ = write!(out, "\\u{:04x}", c as u32);
            }
            c if ascii && !c.is_ascii() => {
                let mut buf = [0u16; 2];
                for unit in c.encode_utf16(&mut buf) {
                    let _ = write!(out, "\\u{unit:04x}");
                }
            }
            c => out.push(c),
        }
    }
    out.push('"');
}

/// Python's `repr()` of an optional string, for messages that quoted a value with `!r`.
pub fn pyrepr(v: Option<&Value>) -> String {
    match v {
        None | Some(Value::Null) => "None".into(),
        Some(Value::String(s)) => pyrepr_str(s),
        Some(other) => other.to_string(),
    }
}

pub fn pyrepr_str(s: &str) -> String {
    let quote = if s.contains('\'') && !s.contains('"') {
        '"'
    } else {
        '\''
    };
    let mut out = String::new();
    out.push(quote);
    for c in s.chars() {
        match c {
            '\\' => out.push_str("\\\\"),
            '\n' => out.push_str("\\n"),
            '\r' => out.push_str("\\r"),
            '\t' => out.push_str("\\t"),
            c if c == quote => {
                out.push('\\');
                out.push(c);
            }
            c => out.push(c),
        }
    }
    out.push(quote);
    out
}

/// `str.splitlines(keepends=True)`: every line boundary Python knows, not only `\n`.
pub fn splitlines_keepends(text: &str) -> Vec<&str> {
    let mut out = Vec::new();
    let mut start = 0;
    let mut chars = text.char_indices().peekable();
    while let Some((i, c)) = chars.next() {
        let end = match c {
            '\r' => match chars.peek() {
                Some((_, '\n')) => {
                    chars.next();
                    i + 2
                }
                _ => i + 1,
            },
            '\n' | '\u{b}' | '\u{c}' | '\u{1c}' | '\u{1d}' | '\u{1e}' | '\u{85}' | '\u{2028}'
            | '\u{2029}' => i + c.len_utf8(),
            _ => continue,
        };
        out.push(&text[start..end]);
        start = end;
    }
    if start < text.len() {
        out.push(&text[start..]);
    }
    out
}

/// `str.splitlines()`: the same lines, boundaries dropped.
pub fn splitlines(text: &str) -> Vec<&str> {
    splitlines_keepends(text)
        .into_iter()
        .map(|l| {
            let mut end = l.len();
            if l.ends_with("\r\n") {
                end -= 2;
            } else if let Some(c) = l.chars().last()
                && matches!(
                    c,
                    '\r' | '\n'
                        | '\u{b}'
                        | '\u{c}'
                        | '\u{1c}'
                        | '\u{1d}'
                        | '\u{1e}'
                        | '\u{85}'
                        | '\u{2028}'
                        | '\u{2029}'
                )
            {
                end -= c.len_utf8();
            }
            &l[..end]
        })
        .collect()
}

/// `urllib.parse.unquote`: percent-decoded as UTF-8, a bad sequence replaced.
pub fn unquote(s: &str) -> String {
    percent_encoding::percent_decode_str(s)
        .decode_utf8_lossy()
        .into_owned()
}

/// `urllib.parse.urlencode` over `quote_plus`.
pub fn urlencode(pairs: &[(&str, String)]) -> String {
    pairs
        .iter()
        .map(|(k, v)| format!("{}={}", quote_plus(k), quote_plus(v)))
        .collect::<Vec<_>>()
        .join("&")
}

fn quote_plus(s: &str) -> String {
    let mut out = String::new();
    for b in s.bytes() {
        match b {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'_' | b'.' | b'-' | b'~' => {
                out.push(b as char)
            }
            b' ' => out.push('+'),
            _ => {
                let _ = write!(out, "%{b:02X}");
            }
        }
    }
    out
}

/// The path part of an absolute URL, as `urlsplit(url).path` reads it.
pub fn url_path(url: &str) -> &str {
    let rest = match url.find("://") {
        Some(i) => &url[i + 3..],
        None => return url.split(['?', '#']).next().unwrap_or(""),
    };
    let rest = rest.split(['?', '#']).next().unwrap_or("");
    match rest.find('/') {
        Some(i) => &rest[i..],
        None => "",
    }
}

pub mod posix {
    /// `posixpath.normpath`.
    pub fn normpath(path: &str) -> String {
        if path.is_empty() {
            return ".".into();
        }
        let initial = if path.starts_with("//") && !path.starts_with("///") {
            2
        } else if path.starts_with('/') {
            1
        } else {
            0
        };
        let mut parts: Vec<&str> = Vec::new();
        for comp in path.split('/') {
            if comp.is_empty() || comp == "." {
                continue;
            }
            if comp != ".." || (initial == 0 && parts.is_empty()) || parts.last() == Some(&"..") {
                parts.push(comp);
            } else if !parts.is_empty() {
                parts.pop();
            }
        }
        let joined = parts.join("/");
        let out = format!("{}{}", "/".repeat(initial), joined);
        if out.is_empty() { ".".into() } else { out }
    }

    /// `posixpath.dirname`.
    pub fn dirname(path: &str) -> &str {
        match path.rfind('/') {
            Some(i) => {
                let head = &path[..=i];
                let trimmed = head.trim_end_matches('/');
                if trimmed.is_empty() { head } else { trimmed }
            }
            None => "",
        }
    }

    /// `posixpath.join(a, b)`.
    pub fn join(a: &str, b: &str) -> String {
        if b.starts_with('/') || a.is_empty() {
            b.to_string()
        } else if a.ends_with('/') {
            format!("{a}{b}")
        } else {
            format!("{a}/{b}")
        }
    }

    /// `posixpath.splitext(path)[1]`.
    pub fn extension(path: &str) -> &str {
        let base = &path[path.rfind('/').map_or(0, |i| i + 1)..];
        let leading = base.len() - base.trim_start_matches('.').len();
        match base[leading..].rfind('.') {
            Some(i) => &base[leading + i..],
            None => "",
        }
    }

    /// `posixpath.relpath(path, start)` for two relative paths under one directory.
    pub fn relpath(path: &str, start: &str) -> String {
        let p = normpath(path);
        let s = normpath(start);
        let pc: Vec<&str> = p
            .split('/')
            .filter(|c| !c.is_empty() && *c != ".")
            .collect();
        let sc: Vec<&str> = s
            .split('/')
            .filter(|c| !c.is_empty() && *c != ".")
            .collect();
        let common = pc.iter().zip(&sc).take_while(|(a, b)| a == b).count();
        let mut out: Vec<&str> = vec![".."; sc.len() - common];
        out.extend(&pc[common..]);
        if out.is_empty() {
            ".".into()
        } else {
            out.join("/")
        }
    }
}

/// `fnmatch.fnmatchcase`: `*`, `?`, `[seq]` and `[!seq]`; `*` crosses `/`.
pub fn fnmatchcase(name: &str, pattern: &str) -> bool {
    let n: Vec<char> = name.chars().collect();
    let p: Vec<char> = pattern.chars().collect();
    fn class(p: &[char], i: usize, c: char) -> Option<(bool, usize)> {
        // Returns (matched, index just past `]`), or None when the `[` is a literal.
        let mut j = i + 1;
        let negate = j < p.len() && p[j] == '!';
        if negate {
            j += 1;
        }
        let first = j;
        let mut matched = false;
        while j < p.len() && (p[j] != ']' || j == first) {
            if j + 2 < p.len() && p[j + 1] == '-' && p[j + 2] != ']' {
                if p[j] <= c && c <= p[j + 2] {
                    matched = true;
                }
                j += 3;
            } else {
                if p[j] == c {
                    matched = true;
                }
                j += 1;
            }
        }
        if j >= p.len() {
            return None;
        }
        Some((matched != negate, j + 1))
    }
    fn go(n: &[char], p: &[char]) -> bool {
        let (mut ni, mut pi) = (0, 0);
        let mut star: Option<(usize, usize)> = None;
        while ni < n.len() {
            if pi < p.len() {
                match p[pi] {
                    '*' => {
                        star = Some((pi, ni));
                        pi += 1;
                        continue;
                    }
                    '?' => {
                        ni += 1;
                        pi += 1;
                        continue;
                    }
                    '[' => match class(p, pi, n[ni]) {
                        Some((true, next)) => {
                            ni += 1;
                            pi = next;
                            continue;
                        }
                        Some((false, _)) => {}
                        None if n[ni] == '[' => {
                            ni += 1;
                            pi += 1;
                            continue;
                        }
                        None => {}
                    },
                    c if c == n[ni] => {
                        ni += 1;
                        pi += 1;
                        continue;
                    }
                    _ => {}
                }
            }
            match star {
                Some((sp, sn)) => {
                    pi = sp + 1;
                    ni = sn + 1;
                    star = Some((sp, sn + 1));
                }
                None => return false,
            }
        }
        while pi < p.len() && p[pi] == '*' {
            pi += 1;
        }
        pi == p.len()
    }
    go(&n, &p)
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn dumps_matches_python() {
        let v = json!({"a": 1, "b": [1, "é"], "c": {}, "d": []});
        assert_eq!(
            dumps(&v, None, false),
            r#"{"a": 1, "b": [1, "é"], "c": {}, "d": []}"#
        );
        assert_eq!(
            dumps(&v, None, true),
            "{\"a\": 1, \"b\": [1, \"\\u00e9\"], \"c\": {}, \"d\": []}"
        );
        assert_eq!(
            dumps(&json!({"a": [1, 2]}), Some(1), true),
            "{\n \"a\": [\n  1,\n  2\n ]\n}"
        );
        assert_eq!(dumps(&json!("\u{1f30a}"), None, true), "\"\\ud83c\\udf0a\"");
    }

    #[test]
    fn posix_helpers() {
        use posix::*;
        assert_eq!(normpath("docs/../x.md"), "x.md");
        assert_eq!(normpath("../a/./b"), "../a/b");
        assert_eq!(normpath("a/../.."), "..");
        assert_eq!(normpath(""), ".");
        assert_eq!(dirname("docs/x.md"), "docs");
        assert_eq!(dirname("README.md"), "");
        assert_eq!(join("", "x"), "x");
        assert_eq!(extension("a/b.PNG"), ".PNG");
        assert_eq!(extension("a/.hidden"), "");
        assert_eq!(relpath("5-Repos/x/index.md", "."), "5-Repos/x/index.md");
        assert_eq!(relpath("index.md", "guide"), "../index.md");
        assert_eq!(relpath("sub/index.md", "sub"), "index.md");
    }

    #[test]
    fn fnmatch() {
        assert!(fnmatchcase("benchmarks", "benchmarks"));
        assert!(fnmatchcase("0002-x.md", "0002-*.md"));
        assert!(!fnmatchcase("0001-x.md", "0002-*.md"));
        assert!(fnmatchcase("a/b", "a*"));
        assert!(fnmatchcase("x1", "x[0-9]"));
        assert!(!fnmatchcase("x1", "x[!0-9]"));
    }

    #[test]
    fn lines() {
        assert_eq!(splitlines_keepends("a\nb\r\nc"), vec!["a\n", "b\r\n", "c"]);
        assert_eq!(splitlines("a\nb\r\nc\n"), vec!["a", "b", "c"]);
        assert_eq!(url_path("https://docs.marola.dev/a%20b/?x#y"), "/a%20b/");
        assert_eq!(url_path("https://docs.marola.dev"), "");
    }
}
