//! .zenodo.json is the one hand-edited citation file; CITATION.cff is generated from it.
//!
//! Zenodo reads only .zenodo.json when both exist, and GitHub's "Cite this repository" reads only
//! CITATION.cff, so the CFF is derived here, with the BibTeX, APA and ABNT references between the
//! READMEs' citation markers, and --check fails when any of them drifts (MIP-0079).
//!
//!     marola-tools citation                 # validate .zenodo.json, rewrite CITATION.cff and the READMEs' block
//!     marola-tools citation --check         # validate; exit 1 if any generated text is stale
//!     marola-tools citation add --name "Family, Given" [--orcid ID] [--affiliation TEXT]
//!                           [--type Researcher | --author]

use crate::util::{dumps, pyrepr, pyrepr_str, repo_root};
use regex::Regex;
use serde_json::{Map, Value, json};
use std::path::{Path, PathBuf};
use std::sync::LazyLock;

const START: &str = "<!-- citation:start -->";
const END: &str = "<!-- citation:end -->";

// Filled in by the PR that follows the first archived release (MIP-0079 §5.5).
pub const CONCEPT_DOI: &str = "10.5281/zenodo.23224155";
const HOMEPAGE: &str = "https://marola.dev";
const YEAR: &str = "2026";

// developers.zenodo.org, deposit metadata, checked 2026-10-07.
const RELATIONS: &[&str] = &[
    "isCitedBy",
    "cites",
    "isSupplementTo",
    "isSupplementedBy",
    "isContinuedBy",
    "continues",
    "isDescribedBy",
    "describes",
    "hasMetadata",
    "isMetadataFor",
    "isNewVersionOf",
    "isPreviousVersionOf",
    "isPartOf",
    "hasPart",
    "isReferencedBy",
    "references",
    "isDocumentedBy",
    "documents",
    "isCompiledBy",
    "compiles",
    "isVariantFormOf",
    "isOriginalFormof",
    "isIdenticalTo",
    "isAlternateIdentifier",
    "isReviewedBy",
    "reviews",
    "isDerivedFrom",
    "isSourceOf",
    "requires",
    "isRequiredBy",
    "isObsoletedBy",
    "obsoletes",
];
pub const CONTRIBUTOR_TYPES: &[&str] = &[
    "ContactPerson",
    "DataCollector",
    "DataCurator",
    "DataManager",
    "Distributor",
    "Editor",
    "HostingInstitution",
    "Producer",
    "ProjectLeader",
    "ProjectManager",
    "ProjectMember",
    "RegistrationAgency",
    "RegistrationAuthority",
    "RelatedPerson",
    "Researcher",
    "ResearchGroup",
    "RightsHolder",
    "Supervisor",
    "Sponsor",
    "WorkPackageLeader",
    "Other",
];
const SPDX: &[(&str, &str)] = &[("mit", "MIT"), ("cc-by-4.0", "CC-BY-4.0")];
// Zenodo sets these itself (version from the tag, date from the release, DOI minted); a value
// here would override or break that.
const FORBIDDEN: &[&str] = &["version", "publication_date", "doi", "prereserve_doi"];

static NAME_RE: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"^[^,]+, [^,]+$").unwrap());
static ORCID_RE: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"^\d{4}-\d{4}-\d{4}-\d{3}[\dX]$").unwrap());
static DOI_RE: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"^10\.\d{4,9}/\S+$").unwrap());
static LANG_RE: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"^[a-z]{3}$").unwrap());

fn spdx(license: &str) -> Option<&'static str> {
    SPDX.iter().find(|(k, _)| *k == license).map(|(_, v)| *v)
}

fn s<'a>(v: &'a Value, key: &str) -> &'a str {
    v.get(key).and_then(Value::as_str).unwrap_or("")
}

fn list<'a>(v: &'a Value, key: &str) -> &'a [Value] {
    v.get(key)
        .and_then(Value::as_array)
        .map_or(&[], Vec::as_slice)
}

fn truthy(v: Option<&Value>) -> bool {
    match v {
        None | Some(Value::Null) => false,
        Some(Value::Bool(b)) => *b,
        Some(Value::String(s)) => !s.is_empty(),
        Some(Value::Array(a)) => !a.is_empty(),
        Some(Value::Object(o)) => !o.is_empty(),
        Some(Value::Number(n)) => n.as_f64() != Some(0.0),
    }
}

pub fn orcid_ok(orcid: &str) -> bool {
    if !ORCID_RE.is_match(orcid) {
        return false;
    }
    let digits: Vec<char> = orcid.chars().filter(|c| *c != '-').collect();
    let mut total: u32 = 0;
    for ch in &digits[..digits.len() - 1] {
        total = (total + ch.to_digit(10).unwrap_or(0)) * 2;
    }
    let check = (12 - total % 11) % 11;
    let want = if check == 10 {
        'X'
    } else {
        char::from_digit(check, 10).unwrap()
    };
    digits[digits.len() - 1] == want
}

fn person_errors(p: &Value, at: &str) -> Vec<String> {
    let mut errs = Vec::new();
    if !NAME_RE.is_match(s(p, "name")) {
        errs.push(format!(
            "{at}: name {} is not 'Family, Given'",
            pyrepr(p.get("name"))
        ));
    }
    if let Some(orcid) = p.get("orcid")
        && !orcid.as_str().is_some_and(orcid_ok)
    {
        errs.push(format!(
            "{at}: orcid {} is not a valid bare ORCID iD",
            pyrepr(Some(orcid))
        ));
    }
    errs
}

pub fn validate(meta: &Value) -> Vec<String> {
    let mut errs = Vec::new();
    for key in [
        "title",
        "description",
        "creators",
        "license",
        "keywords",
        "language",
    ] {
        if !truthy(meta.get(key)) {
            errs.push(format!("missing {key}"));
        }
    }
    if !matches!(s(meta, "upload_type"), "software" | "dataset") {
        errs.push("upload_type must be software or dataset".into());
    }
    if s(meta, "access_right") != "open" {
        errs.push("access_right must be open".into());
    }
    if spdx(s(meta, "license")).is_none() || !meta.get("license").is_some_and(Value::is_string) {
        errs.push(format!(
            "license {} has no SPDX mapping here",
            pyrepr(meta.get("license"))
        ));
    }
    if !LANG_RE.is_match(s(meta, "language")) {
        errs.push("language must be an ISO 639-3 code such as eng".into());
    }
    for k in FORBIDDEN {
        if meta.get(*k).is_some() {
            errs.push(format!("{k} is set by Zenodo; remove it"));
        }
    }
    for (i, p) in list(meta, "creators").iter().enumerate() {
        errs.extend(person_errors(p, &format!("creators[{i}]")));
    }
    for (i, p) in list(meta, "contributors").iter().enumerate() {
        errs.extend(person_errors(p, &format!("contributors[{i}]")));
        if !p
            .get("type")
            .and_then(Value::as_str)
            .is_some_and(|t| CONTRIBUTOR_TYPES.contains(&t))
        {
            errs.push(format!(
                "contributors[{i}]: type {} is not Zenodo's",
                pyrepr(p.get("type"))
            ));
        }
    }
    for (i, r) in list(meta, "related_identifiers").iter().enumerate() {
        if !r
            .get("relation")
            .and_then(Value::as_str)
            .is_some_and(|t| RELATIONS.contains(&t))
        {
            errs.push(format!(
                "related_identifiers[{i}]: relation {} unknown",
                pyrepr(r.get("relation"))
            ));
        }
        let ident = s(r, "identifier");
        match r.get("scheme").and_then(Value::as_str) {
            Some("doi") if !DOI_RE.is_match(ident) => errs.push(format!(
                "related_identifiers[{i}]: {} is not a bare DOI",
                pyrepr_str(ident)
            )),
            Some("url") if !ident.starts_with("https://") => errs.push(format!(
                "related_identifiers[{i}]: {} is not an https URL",
                pyrepr_str(ident)
            )),
            Some("doi" | "url") => {}
            _ => errs.push(format!(
                "related_identifiers[{i}]: scheme must be doi or url"
            )),
        }
    }
    errs
}

fn q(s: &str) -> String {
    serde_json::to_string(s).expect("a string serializes")
}

fn plain(description: &str) -> String {
    static PARA: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"</p>\s*<p>").unwrap());
    static TAG: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"<[^>]+>").unwrap());
    let text = PARA.replace_all(description, "\n\n");
    let text = TAG.replace_all(&text, "");
    html_escape::decode_html_entities(&text).trim().to_string()
}

fn repo_url(meta: &Value) -> String {
    list(meta, "related_identifiers")
        .iter()
        .find(|r| {
            s(r, "relation") == "isSupplementTo"
                && s(r, "identifier").starts_with("https://github.com/")
        })
        .map(|r| s(r, "identifier").to_string())
        .expect("validate() guarantees a GitHub isSupplementTo identifier")
}

fn wrap(text: &str, width: usize) -> Vec<String> {
    let mut lines = Vec::new();
    let mut line = String::new();
    for word in text.split_whitespace() {
        if !line.is_empty() && line.chars().count() + 1 + word.chars().count() > width {
            lines.push(std::mem::take(&mut line));
            line.push_str(word);
        } else if line.is_empty() {
            line.push_str(word);
        } else {
            line.push(' ');
            line.push_str(word);
        }
    }
    if !line.is_empty() {
        lines.push(line);
    }
    lines
}

fn split_name(name: &str) -> (&str, &str) {
    name.split_once(", ").unwrap_or((name, ""))
}

pub fn render_cff(meta: &Value, concept_doi: &str) -> String {
    let repo = repo_url(meta);
    let mut out = vec![
        "# Generated from .zenodo.json by scripts/marola-tools citation; edit that file, then `just citation`.".to_string(),
        "cff-version: 1.2.0".into(),
        "message: \"If you use marola, please cite it using the metadata below.\"".into(),
        format!("type: {}", s(meta, "upload_type")),
        format!("title: {}", q(s(meta, "title"))),
        "abstract: >-".into(),
    ];
    for para in plain(s(meta, "description")).split("\n\n") {
        out.extend(wrap(para, 96).into_iter().map(|l| format!("  {l}")));
        out.push(String::new());
    }
    out.pop();
    out.push("authors:".into());
    for p in list(meta, "creators") {
        let (family, given) = split_name(s(p, "name"));
        out.push(format!("  - family-names: {}", q(family)));
        out.push(format!("    given-names: {}", q(given)));
        if p.get("orcid").is_some() {
            out.push(format!(
                "    orcid: \"https://orcid.org/{}\"",
                s(p, "orcid")
            ));
        }
        if p.get("affiliation").is_some() {
            out.push(format!("    affiliation: {}", q(s(p, "affiliation"))));
        }
    }
    out.push(format!("repository-code: \"{repo}\""));
    out.push(format!("url: \"{HOMEPAGE}\""));
    if !concept_doi.is_empty() {
        out.push(format!("doi: {concept_doi}"));
        out.push("identifiers:".into());
        out.push("  - type: doi".into());
        out.push(format!("    value: {concept_doi}"));
        out.push("    description: Concept DOI, resolves to the latest release".into());
    }
    out.push(format!(
        "license: {}",
        spdx(s(meta, "license")).unwrap_or("")
    ));
    out.push("keywords:".into());
    for k in list(meta, "keywords") {
        out.push(format!("  - {}", q(k.as_str().unwrap_or(""))));
    }
    out.join("\n") + "\n"
}

pub fn add_person(
    meta: &mut Value,
    name: &str,
    orcid: Option<&str>,
    affiliation: Option<&str>,
    ctype: &str,
    author: bool,
) -> Result<(), String> {
    let mut person = Map::new();
    person.insert("name".into(), json!(name));
    if let Some(a) = affiliation.filter(|a| !a.is_empty()) {
        person.insert("affiliation".into(), json!(a));
    }
    if let Some(o) = orcid.filter(|o| !o.is_empty()) {
        person.insert("orcid".into(), json!(o));
    }
    if !author {
        person.insert("type".into(), json!(ctype));
    }
    let listed = list(meta, "creators")
        .iter()
        .chain(list(meta, "contributors"))
        .any(|p| s(p, "name") == name);
    if listed {
        return Err(format!("{name} is already listed"));
    }
    let key = if author { "creators" } else { "contributors" };
    let obj = meta
        .as_object_mut()
        .ok_or(".zenodo.json is not an object")?;
    obj.entry(key)
        .or_insert_with(|| json!([]))
        .as_array_mut()
        .ok_or(format!("{key} is not a list"))?
        .push(Value::Object(person));
    Ok(())
}

pub fn write_json(path: &Path, meta: &Value) -> std::io::Result<()> {
    std::fs::write(path, dumps(meta, Some(2), false) + "\n")
}

pub fn render_refs(meta: &Value, concept_doi: &str) -> String {
    let people: Vec<(&str, &str)> = list(meta, "creators")
        .iter()
        .map(|p| split_name(s(p, "name")))
        .collect();
    let repo = repo_url(meta);
    let title = s(meta, "title");
    let (short, sub) = title.split_once(": ").unwrap_or((title, ""));
    let slug: String = short
        .to_lowercase()
        .chars()
        .filter(|c| c.is_ascii_lowercase() || c.is_ascii_digit())
        .collect();
    let authors = people
        .iter()
        .map(|(f, g)| format!("{f}, {g}"))
        .collect::<Vec<_>>()
        .join(" and ");
    let mut bib = vec![
        format!("@software{{{}_{YEAR}_{slug},", people[0].0.to_lowercase()),
        format!("  author    = {{{authors}}},"),
        format!("  title     = {{{{{title}}}}},"),
        format!("  year      = {{{YEAR}}},"),
    ];
    if !concept_doi.is_empty() {
        bib.push("  publisher = {Zenodo},".into());
        bib.push(format!("  doi       = {{{concept_doi}}},"));
    }
    bib.push(format!("  url       = {{{repo}}}"));
    bib.push("}".into());

    let initials = |given: &str| {
        given
            .split_whitespace()
            .map(|part| format!("{}.", part.chars().next().unwrap_or_default()))
            .collect::<Vec<_>>()
            .join(" ")
    };
    let apa_names: Vec<String> = people
        .iter()
        .map(|(f, g)| format!("{f}, {}", initials(g)))
        .collect();
    let apa_authors = if apa_names.len() == 1 {
        apa_names[0].clone()
    } else {
        format!(
            "{}, & {}",
            apa_names[..apa_names.len() - 1].join(", "),
            apa_names[apa_names.len() - 1]
        )
    };
    let place = if concept_doi.is_empty() {
        repo.clone()
    } else {
        format!("Zenodo. https://doi.org/{concept_doi}")
    };
    let apa = format!("{apa_authors} ({YEAR}). *{title}* [Computer software]. {place}");
    let abnt_authors = people
        .iter()
        .map(|(f, g)| format!("{}, {g}", f.to_uppercase()))
        .collect::<Vec<_>>()
        .join("; ");
    let abnt_title = if sub.is_empty() {
        format!("**{title}**")
    } else {
        format!("**{short}**: {sub}")
    };
    let abnt = if concept_doi.is_empty() {
        format!("{abnt_authors}. {abnt_title}. [S. l.]: GitHub, {YEAR}. Disponível em: {repo}.")
    } else {
        format!("{abnt_authors}. {abnt_title}. [S. l.]: Zenodo, {YEAR}. DOI {concept_doi}.")
    };
    let mut lines: Vec<String> = vec![
        START.into(),
        "".into(),
        "BibTeX:".into(),
        "".into(),
        "```bibtex".into(),
    ];
    lines.extend(bib);
    lines.extend([
        "```".into(),
        "".into(),
        "APA:".into(),
        "".into(),
        format!("> {apa}"),
        "".into(),
        "ABNT (NBR 6023):".into(),
        "".into(),
        format!("> {abnt}"),
        "".into(),
        END.into(),
    ]);
    lines.join("\n")
}

pub fn splice(text: &str, block: &str) -> Result<String, String> {
    let missing = || format!("no {START} ... {END} block");
    let (head, rest) = text.split_once(START).ok_or_else(missing)?;
    let (_, tail) = rest.split_once(END).ok_or_else(missing)?;
    Ok(format!("{head}{block}{tail}"))
}

fn name_of(p: &Path) -> String {
    p.file_name()
        .map(|n| n.to_string_lossy().into_owned())
        .unwrap_or_default()
}

pub fn run(zenodo: &Path, cff: &Path, check: bool, readmes: &[PathBuf]) -> i32 {
    let meta: Value = match std::fs::read_to_string(zenodo)
        .map_err(|e| e.to_string())
        .and_then(|t| serde_json::from_str(&t).map_err(|e| e.to_string()))
    {
        Ok(m) => m,
        Err(e) => {
            eprintln!("citation: {}: {e}", zenodo.display());
            return 1;
        }
    };
    let errs = validate(&meta);
    if !errs.is_empty() {
        eprintln!(
            "citation: .zenodo.json is invalid:\n  {}",
            errs.join("\n  ")
        );
        return 1;
    }
    let block = render_refs(&meta, CONCEPT_DOI);
    let mut wanted: Vec<(PathBuf, String)> =
        vec![(cff.to_path_buf(), render_cff(&meta, CONCEPT_DOI))];
    for readme in readmes {
        let spliced = std::fs::read_to_string(readme)
            .map_err(|e| e.to_string())
            .and_then(|t| splice(&t, &block));
        match spliced {
            Ok(t) => wanted.push((readme.clone(), t)),
            Err(e) => {
                eprintln!("citation: {}: {e}", name_of(readme));
                return 1;
            }
        }
    }
    let stale: Vec<&(PathBuf, String)> = wanted
        .iter()
        .filter(|(p, text)| std::fs::read_to_string(p).map_or(true, |t| &t != text))
        .collect();
    if check {
        if !stale.is_empty() {
            let names: Vec<String> = stale.iter().map(|(p, _)| name_of(p)).collect();
            eprintln!("citation: stale: {}; run `just citation`", names.join(", "));
            return 1;
        }
        eprintln!("citation: .zenodo.json valid, generated text current");
        return 0;
    }
    for (p, text) in stale {
        if let Err(e) = std::fs::write(p, text) {
            eprintln!("citation: {}: {e}", p.display());
            return 1;
        }
        eprintln!("citation: wrote {}", name_of(p));
    }
    0
}

pub struct AddArgs<'a> {
    pub name: &'a str,
    pub orcid: Option<&'a str>,
    pub affiliation: Option<&'a str>,
    pub ctype: &'a str,
    pub author: bool,
}

pub fn main(check: bool, add: Option<AddArgs>) -> i32 {
    let root = repo_root();
    let zenodo = root.join(".zenodo.json");
    if let Some(a) = add {
        let mut meta: Value = match std::fs::read_to_string(&zenodo)
            .map_err(|e| e.to_string())
            .and_then(|t| serde_json::from_str(&t).map_err(|e| e.to_string()))
        {
            Ok(m) => m,
            Err(e) => {
                eprintln!("citation: {e}");
                return 1;
            }
        };
        if let Err(e) = add_person(&mut meta, a.name, a.orcid, a.affiliation, a.ctype, a.author) {
            eprintln!("citation: {e}");
            return 1;
        }
        let errs = validate(&meta);
        if !errs.is_empty() {
            eprintln!("citation: not added:\n  {}", errs.join("\n  "));
            return 1;
        }
        if let Err(e) = write_json(&zenodo, &meta) {
            eprintln!("citation: {e}");
            return 1;
        }
    }
    let readmes = [root.join("README.md"), root.join("README.en.md")];
    run(&zenodo, &root.join("CITATION.cff"), check, &readmes)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn good() -> Value {
        json!({
            "title": "t",
            "upload_type": "software",
            "description": "<p>One &amp; two.</p><p>Three.</p>",
            "creators": [{"name": "Santos, Matheus Hoffmann Fernandes", "orcid": "0009-0009-1056-7661"}],
            "license": "mit",
            "access_right": "open",
            "language": "eng",
            "keywords": ["ocean"],
            "related_identifiers": [
                {"identifier": "https://github.com/x/y", "relation": "isSupplementTo", "scheme": "url"},
                {"identifier": "10.5281/zenodo.23221352", "relation": "references", "scheme": "doi"}
            ]
        })
    }

    #[test]
    fn validates_and_renders() {
        let mut good = good();
        assert_eq!(validate(&good), Vec::<String>::new());
        assert!(orcid_ok("0000-0002-1825-0097") && !orcid_ok("0000-0002-1825-0098"));
        let mut bad = good.clone();
        bad["version"] = json!("v1");
        bad["creators"][0]["name"] = json!("Matheus Santos");
        bad["related_identifiers"][1]["relation"] = json!("isRelatedTo");
        assert_eq!(validate(&bad).len(), 3, "{:?}", validate(&bad));
        let cff = render_cff(&good, "");
        assert!(
            cff.contains("family-names: \"Santos\"")
                && cff.contains("orcid.org/0009-0009-1056-7661")
        );
        assert!(cff.contains("  One & two.\n\n  Three.\n") && !cff.contains("doi:"));
        assert!(render_cff(&good, "10.5281/zenodo.1").contains("doi: 10.5281/zenodo.1\n"));
        add_person(
            &mut good,
            "Valério, Bruno",
            None,
            None,
            "ProjectMember",
            false,
        )
        .unwrap();
        assert_eq!(
            good["contributors"],
            json!([{"name": "Valério, Bruno", "type": "ProjectMember"}])
        );
        assert_eq!(validate(&good), Vec::<String>::new());
        assert!(add_person(&mut good, "Valério, Bruno", None, None, "Other", true).is_err());
        let refs = render_refs(&good, "");
        assert!(
            refs.contains("author    = {Santos, Matheus Hoffmann Fernandes}")
                && !refs.contains("doi")
        );
        assert!(refs.contains(
            "> Santos, M. H. F. (2026). *t* [Computer software]. https://github.com/x/y"
        ));
        good["creators"]
            .as_array_mut()
            .unwrap()
            .push(json!({"name": "Valério, Bruno"}));
        good["title"] = json!("m: a sub");
        let refs = render_refs(&good, "10.5281/zenodo.1");
        assert!(refs.contains("Santos, M. H. F., & Valério, B. (2026)"));
        assert!(refs.contains("doi       = {10.5281/zenodo.1}"));
        assert!(refs.contains("SANTOS, Matheus Hoffmann Fernandes; VALÉRIO, Bruno. **m**: a sub."));
        assert!(
            refs.contains("DOI 10.5281/zenodo.1.") && refs.contains("@software{santos_2026_m,")
        );
        assert_eq!(
            splice(&format!("a\n{START}\nold\n{END}\nb"), "NEW").unwrap(),
            "a\nNEW\nb"
        );
    }

    #[test]
    fn run_round_trip() {
        let mut good = good();
        add_person(
            &mut good,
            "Valério, Bruno",
            None,
            None,
            "ProjectMember",
            false,
        )
        .unwrap();
        let d = tempfile::tempdir().unwrap();
        let (z, c, r) = (
            d.path().join(".zenodo.json"),
            d.path().join("CITATION.cff"),
            d.path().join("README.md"),
        );
        write_json(&z, &good).unwrap();
        std::fs::write(&r, format!("# x\n\n{START}\n{END}\n\n## y\n")).unwrap();
        let readmes = [r.clone()];
        assert_eq!(run(&z, &c, true, &readmes), 1);
        assert_eq!(run(&z, &c, false, &readmes), 0);
        assert_eq!(run(&z, &c, true, &readmes), 0);
        let text = std::fs::read_to_string(&r).unwrap();
        assert!(text.contains("BibTeX:") && text.ends_with("\n\n## y\n"));
        std::fs::write(&r, "no markers\n").unwrap();
        assert_eq!(run(&z, &c, true, &readmes), 1);
    }

    #[test]
    fn the_committed_files_are_current() {
        let root = repo_root();
        let readmes = [root.join("README.md"), root.join("README.en.md")];
        assert_eq!(
            run(
                &root.join(".zenodo.json"),
                &root.join("CITATION.cff"),
                true,
                &readmes
            ),
            0
        );
    }
}
