//! CLAUDE_PROJECT.config.md has its sections and routines, and leaks no id.
//!
//!     marola-tools claude-project-check

use crate::util::{pyrepr_str, repo_root};
use regex::Regex;
use std::sync::LazyLock;

const SECTIONS: [&str; 8] = [
    "1. Project",
    "2. Cloud environment",
    "3. Plugins, skills and connectors",
    "4. Routines",
    "5. Joining as a new member",
    "6. Restoring the project",
    "7. Not in this file",
    "8. Keeping it current",
];
// AGENTS.md points CLI agents away from this file; the banner says the same to whoever opens it.
const BANNER: &str = "must not read it";

// The service returns the owner's e-mail, account UUIDs and session ids; none is committed.
static LEAKS: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(concat!(
        r"[\w.+-]+@[\w-]+\.[\w.]+",
        r"|[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}",
        r"|\b(?:session|cse|cmsg|env|chan|user)_0[0-9A-Za-z]{20,}"
    ))
    .unwrap()
});

pub fn check(text: &str) -> Vec<String> {
    let mut errors = Vec::new();
    if !text.contains(BANNER) {
        errors.push(format!("no banner saying a CLI agent {BANNER}"));
    }
    for s in SECTIONS {
        if !text.contains(&format!("\n## {s}\n")) {
            errors.push(format!("no section '## {s}'"));
        }
    }
    let routines = text
        .split_once("\n## 4. Routines\n")
        .map_or(text, |(_, r)| r);
    let routines = routines.split_once("\n## 5.").map_or(routines, |(r, _)| r);
    for block in routines.split("\n### ").skip(1) {
        let name = block.lines().next().unwrap_or("");
        if !block.contains("**Schedule (cron)**") {
            errors.push(format!("routine '{name}': no cron"));
        }
        if !block.contains("```text\n") {
            errors.push(format!("routine '{name}': no prompt block"));
        }
    }
    if let Some(m) = LEAKS.find(text) {
        errors.push(format!(
            "{} looks like an e-mail or an id",
            pyrepr_str(m.as_str())
        ));
    }
    errors
}

pub fn run() -> i32 {
    let path = repo_root().join("CLAUDE_PROJECT.config.md");
    let text = match std::fs::read_to_string(&path) {
        Ok(t) => t,
        Err(e) => {
            eprintln!("claude_project_check: {}: {e}", path.display());
            return 1;
        }
    };
    let errors = check(&text);
    for e in &errors {
        eprintln!("claude_project_check: {e}");
    }
    i32::from(!errors.is_empty())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn good() -> String {
        let mut g = format!("> An agent {BANNER}.\n");
        for s in SECTIONS {
            g.push_str(&format!("\n## {s}\n\nx\n"));
        }
        g.replace(
            "## 4. Routines\n\nx\n",
            "## 4. Routines\n\n### A\n\n| **Schedule (cron)** | `5 * * * *` |\n\n```text\nhi\n```\n",
        )
    }

    #[test]
    fn checks() {
        let good = good();
        assert_eq!(check(&good), Vec::<String>::new());
        assert_eq!(
            check(&good.replace(BANNER, "may read it")),
            vec![format!("no banner saying a CLI agent {BANNER}")]
        );
        assert_eq!(
            check(&good.replace("## 6. Restoring the project", "## 6. Restore")),
            vec!["no section '## 6. Restoring the project'"]
        );
        assert_eq!(
            check(&good.replace("```text\nhi\n```", "hi")),
            vec!["routine 'A': no prompt block"]
        );
        let leaked = check(&format!("{good}ping someone@example.org\n"));
        assert!(
            leaked.len() == 1 && leaked[0].contains("e-mail"),
            "{leaked:?}"
        );
        assert_eq!(
            check(&format!("{good}wakes session_01ExampleExampleExample00\n")).len(),
            1
        );
    }

    #[test]
    fn the_committed_file_passes() {
        let text = std::fs::read_to_string(repo_root().join("CLAUDE_PROJECT.config.md")).unwrap();
        assert_eq!(check(&text), Vec::<String>::new());
    }
}
