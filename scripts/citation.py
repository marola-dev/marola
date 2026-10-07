#!/usr/bin/env python3
"""citation — .zenodo.json is the one hand-edited citation file; CITATION.cff is generated from it.

Zenodo reads only .zenodo.json when both exist, and GitHub's "Cite this repository" reads only
CITATION.cff, so the CFF is derived here and --check fails when they drift (MIP-0079).

    scripts/citation.py                  # validate .zenodo.json, rewrite CITATION.cff
    scripts/citation.py --check          # validate; exit 1 if CITATION.cff is stale
    scripts/citation.py add --name "Family, Given" [--orcid ID] [--affiliation TEXT]
                        [--type Researcher | --author]
    scripts/citation.py --self-test
"""

from __future__ import annotations

import argparse
import html
import json
import re
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ZENODO = ROOT / ".zenodo.json"
CFF = ROOT / "CITATION.cff"

# Filled in by the PR that follows the first archived release (MIP-0079 §5.4).
CONCEPT_DOI = ""
HOMEPAGE = "https://marola.dev"

# developers.zenodo.org, deposit metadata, checked 2026-10-07.
RELATIONS = {
    "isCitedBy", "cites", "isSupplementTo", "isSupplementedBy", "isContinuedBy", "continues",
    "isDescribedBy", "describes", "hasMetadata", "isMetadataFor", "isNewVersionOf",
    "isPreviousVersionOf", "isPartOf", "hasPart", "isReferencedBy", "references",
    "isDocumentedBy", "documents", "isCompiledBy", "compiles", "isVariantFormOf",
    "isOriginalFormof", "isIdenticalTo", "isAlternateIdentifier", "isReviewedBy", "reviews",
    "isDerivedFrom", "isSourceOf", "requires", "isRequiredBy", "isObsoletedBy", "obsoletes",
}  # fmt: skip
CONTRIBUTOR_TYPES = {
    "ContactPerson", "DataCollector", "DataCurator", "DataManager", "Distributor", "Editor",
    "HostingInstitution", "Producer", "ProjectLeader", "ProjectManager", "ProjectMember",
    "RegistrationAgency", "RegistrationAuthority", "RelatedPerson", "Researcher", "ResearchGroup",
    "RightsHolder", "Supervisor", "Sponsor", "WorkPackageLeader", "Other",
}  # fmt: skip
SPDX = {"mit": "MIT"}
# Zenodo sets these itself (version from the tag, date from the release, DOI minted); a value
# here would override or break that.
FORBIDDEN = ("version", "publication_date", "doi", "prereserve_doi")
NAME_RE = re.compile(r"^[^,]+, [^,]+$")
ORCID_RE = re.compile(r"^\d{4}-\d{4}-\d{4}-\d{3}[\dX]$")
DOI_RE = re.compile(r"^10\.\d{4,9}/\S+$")


def orcid_ok(orcid: str) -> bool:
    if not ORCID_RE.match(orcid):
        return False
    digits = orcid.replace("-", "")
    total = 0
    for ch in digits[:-1]:
        total = (total + int(ch)) * 2
    check = (12 - total % 11) % 11
    return digits[-1] == ("X" if check == 10 else str(check))


def _person_errors(p: dict, where: str) -> list[str]:
    errs = []
    if not NAME_RE.match(p.get("name", "")):
        errs.append(f"{where}: name {p.get('name')!r} is not 'Family, Given'")
    if "orcid" in p and not orcid_ok(p["orcid"]):
        errs.append(f"{where}: orcid {p['orcid']!r} is not a valid bare ORCID iD")
    return errs


def validate(meta: dict) -> list[str]:
    errs = []
    for key in ("title", "description", "creators", "license", "keywords", "language"):
        if not meta.get(key):
            errs.append(f"missing {key}")
    if meta.get("upload_type") not in ("software", "dataset"):
        errs.append("upload_type must be software or dataset")
    if meta.get("access_right") != "open":
        errs.append("access_right must be open")
    if meta.get("license") not in SPDX:
        errs.append(f"license {meta.get('license')!r} has no SPDX mapping here")
    if not re.fullmatch(r"[a-z]{3}", meta.get("language", "")):
        errs.append("language must be an ISO 639-3 code such as eng")
    errs += [f"{k} is set by Zenodo; remove it" for k in FORBIDDEN if k in meta]
    for i, p in enumerate(meta.get("creators", [])):
        errs += _person_errors(p, f"creators[{i}]")
    for i, p in enumerate(meta.get("contributors", [])):
        errs += _person_errors(p, f"contributors[{i}]")
        if p.get("type") not in CONTRIBUTOR_TYPES:
            errs.append(f"contributors[{i}]: type {p.get('type')!r} is not Zenodo's")
    for i, r in enumerate(meta.get("related_identifiers", [])):
        if r.get("relation") not in RELATIONS:
            errs.append(f"related_identifiers[{i}]: relation {r.get('relation')!r} unknown")
        scheme, ident = r.get("scheme"), r.get("identifier", "")
        if scheme == "doi" and not DOI_RE.match(ident):
            errs.append(f"related_identifiers[{i}]: {ident!r} is not a bare DOI")
        elif scheme == "url" and not ident.startswith("https://"):
            errs.append(f"related_identifiers[{i}]: {ident!r} is not an https URL")
        elif scheme not in ("doi", "url"):
            errs.append(f"related_identifiers[{i}]: scheme must be doi or url")
    return errs


def _q(s: str) -> str:
    return json.dumps(s, ensure_ascii=False)


def _plain(description: str) -> str:
    text = re.sub(r"</p>\s*<p>", "\n\n", description)
    return html.unescape(re.sub(r"<[^>]+>", "", text)).strip()


def render_cff(meta: dict, concept_doi: str = CONCEPT_DOI) -> str:
    repo = next(
        r["identifier"]
        for r in meta["related_identifiers"]
        if r["relation"] == "isSupplementTo" and r["scheme"] == "url"
    )
    out = [
        "# Generated from .zenodo.json by scripts/citation.py; edit that file, then `just citation`.",
        "cff-version: 1.2.0",
        'message: "If you use marola, please cite it using the metadata below."',
        f"type: {meta['upload_type']}",
        f"title: {_q(meta['title'])}",
        "abstract: >-",
    ]
    for para in _plain(meta["description"]).split("\n\n"):
        out += [f"  {line}" for line in _wrap(para, 96)] + [""]
    out[-1:] = []
    out.append("authors:")
    for p in meta["creators"]:
        family, given = p["name"].split(", ", 1)
        out += [f"  - family-names: {_q(family)}", f"    given-names: {_q(given)}"]
        if "orcid" in p:
            out.append(f'    orcid: "https://orcid.org/{p["orcid"]}"')
        if "affiliation" in p:
            out.append(f"    affiliation: {_q(p['affiliation'])}")
    out += [f'repository-code: "{repo}"', f'url: "{HOMEPAGE}"']
    if concept_doi:
        out += [
            f"doi: {concept_doi}",
            "identifiers:",
            "  - type: doi",
            f"    value: {concept_doi}",
            "    description: Concept DOI, resolves to the latest release",
        ]
    out += [f"license: {SPDX[meta['license']]}", "keywords:"]
    out += [f"  - {_q(k)}" for k in meta["keywords"]]
    return "\n".join(out) + "\n"


def _wrap(text: str, width: int) -> list[str]:
    lines, line = [], ""
    for word in text.split():
        if line and len(line) + 1 + len(word) > width:
            lines.append(line)
            line = word
        else:
            line = f"{line} {word}" if line else word
    return lines + [line] if line else lines


def add_person(meta: dict, name: str, orcid, affiliation, ctype, author: bool) -> dict:
    person = {"name": name}
    if affiliation:
        person["affiliation"] = affiliation
    if orcid:
        person["orcid"] = orcid
    key = "creators" if author else "contributors"
    if not author:
        person["type"] = ctype
    if any(p["name"] == name for p in meta.get("creators", []) + meta.get("contributors", [])):
        raise ValueError(f"{name} is already listed")
    meta.setdefault(key, []).append(person)
    return meta


def write_json(path: Path, meta: dict) -> None:
    path.write_text(json.dumps(meta, indent=2, ensure_ascii=False) + "\n")


def run(zenodo: Path, cff: Path, check: bool) -> int:
    meta = json.loads(zenodo.read_text())
    errs = validate(meta)
    if errs:
        print("citation: .zenodo.json is invalid:", *errs, sep="\n  ", file=sys.stderr)
        return 1
    want = render_cff(meta)
    if check:
        if not cff.exists() or cff.read_text() != want:
            print("citation: CITATION.cff is stale; run `just citation`", file=sys.stderr)
            return 1
        print("citation: .zenodo.json valid, CITATION.cff current", file=sys.stderr)
        return 0
    cff.write_text(want)
    print(f"citation: wrote {cff.name}", file=sys.stderr)
    return 0


def self_test() -> int:
    good = {
        "title": "t",
        "upload_type": "software",
        "description": "<p>One &amp; two.</p><p>Three.</p>",
        "creators": [
            {"name": "Santos, Matheus Hoffmann Fernandes", "orcid": "0009-0009-1056-7661"}
        ],
        "license": "mit",
        "access_right": "open",
        "language": "eng",
        "keywords": ["ocean"],
        "related_identifiers": [
            {"identifier": "https://github.com/x/y", "relation": "isSupplementTo", "scheme": "url"},
            {"identifier": "10.5281/zenodo.23221352", "relation": "references", "scheme": "doi"},
        ],
    }
    assert validate(good) == [], validate(good)
    assert orcid_ok("0000-0002-1825-0097") and not orcid_ok("0000-0002-1825-0098")
    bad = json.loads(json.dumps(good))
    bad["version"] = "v1"
    bad["creators"][0]["name"] = "Matheus Santos"
    bad["related_identifiers"][1]["relation"] = "isRelatedTo"
    assert len(validate(bad)) == 3, validate(bad)
    cff = render_cff(good)
    assert 'family-names: "Santos"' in cff and "orcid.org/0009-0009-1056-7661" in cff
    assert "  One & two.\n\n  Three.\n" in cff and "doi:" not in cff
    assert "doi: 10.5281/zenodo.1\n" in render_cff(good, "10.5281/zenodo.1")
    add_person(good, "Valério, Bruno", None, None, "ProjectMember", author=False)
    assert good["contributors"] == [{"name": "Valério, Bruno", "type": "ProjectMember"}]
    assert validate(good) == []
    try:
        add_person(good, "Valério, Bruno", None, None, "Other", author=True)
        raise AssertionError("duplicate accepted")
    except ValueError:
        pass
    with tempfile.TemporaryDirectory() as d:
        z, c = Path(d, ".zenodo.json"), Path(d, "CITATION.cff")
        write_json(z, good)
        assert run(z, c, check=True) == 1
        assert run(z, c, check=False) == 0 and run(z, c, check=True) == 0
    print("citation: self-test ok", file=sys.stderr)
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawTextHelpFormatter)
    ap.add_argument("--check", action="store_true")
    ap.add_argument("--self-test", action="store_true")
    sub = ap.add_subparsers(dest="cmd")
    add = sub.add_parser("add", help="add a contributor (or, with --author, a creator)")
    add.add_argument("--name", required=True, help='"Family, Given"')
    add.add_argument("--orcid", help="bare iD, 0000-0000-0000-0000")
    add.add_argument("--affiliation")
    add.add_argument("--type", default="ProjectMember", choices=sorted(CONTRIBUTOR_TYPES))
    add.add_argument("--author", action="store_true", help="list as a creator (cited author)")
    args = ap.parse_args()
    if args.self_test:
        return self_test()
    if args.cmd == "add":
        meta = json.loads(ZENODO.read_text())
        try:
            add_person(meta, args.name, args.orcid, args.affiliation, args.type, args.author)
        except ValueError as e:
            print(f"citation: {e}", file=sys.stderr)
            return 1
        errs = validate(meta)
        if errs:
            print("citation: not added:", *errs, sep="\n  ", file=sys.stderr)
            return 1
        write_json(ZENODO, meta)
    return run(ZENODO, CFF, args.check)


if __name__ == "__main__":
    sys.exit(main())
