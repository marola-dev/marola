#!/usr/bin/env python3
"""Licence and tooling inventory of local checkouts, for marola-sharingan.

    licence_scan.py --dest REPO [--dest-path REL] DIR...   one JSON object per checkout, on stdout
    licence_scan.py --into SPDX-ID DIR...                  the same, naming the licence by hand
    licence_scan.py --dest REPO [--dest-path REL]          only the destination path's licence
    licence_scan.py --matrix                               the verdict matrix as the Markdown table
    licence_scan.py --self-test

--dest reads the destination's own licence for the path the item will land at (REUSE.toml first,
then the nearest LICENSE), so nothing here assumes which licence the destination has.

It reads files only. It never runs, installs or imports anything from the checkouts, so it is
safe on an untrusted clone (run it with `python3 -I`). Stdlib only, Python 3.11+ for tomllib.
"""

from __future__ import annotations

import argparse
import fnmatch
import json
import os
import re
import subprocess
import sys
import tempfile
from pathlib import Path

try:
    import tomllib
except ModuleNotFoundError:  # Python < 3.11: REUSE.toml and pyproject.toml are reported unparsed
    tomllib = None

# Split so that `reuse lint` does not read these strings as this file's own licence tags.
TAG_LICENSE = "SPDX-" + "License-Identifier"
TAG_COPYRIGHT = "SPDX-" + "FileCopyrightText"

SKIP_DIRS = {
    ".git",
    "node_modules",
    "vendor/bundle",
    ".venv",
    "venv",
    "target",
    "dist",
    "build",
    "result",
    "__pycache__",
    ".direnv",
    "_build",
}
MAX_BYTES = 256 * 1024

# --- licence classes and the verdict matrix --------------------------------------------------

CLASSES = [
    "permissive",
    "Apache-2.0",
    "MPL-2.0",
    "LGPL",
    "GPL-2.0-only",
    "GPL-2.0-or-later",
    "GPL-3.0",
    "AGPL-3.0",
    "CC-BY-4.0",
    "CC-BY-SA-4.0",
    "CC-NC-ND",
    "source-available",
    "none",
    "unknown",
]
DESTS = ["permissive", "Apache-2.0", "GPL-3.0", "AGPL-3.0", "CC-BY-4.0"]

# reuse: copy it, keep the notice. reuse-file: copy it, but that file keeps its own licence and
# notice (REUSE annotation, NOTICE, LICENSES/ text). borrow: re-implement the idea in your own
# words and code, with a credit line; copy nothing. cite: link and describe only.
# references/licences.md carries this table verbatim; --self-test fails if they differ.
MATRIX = {
    "permissive": ["reuse", "reuse", "reuse", "reuse", "reuse-file"],
    "Apache-2.0": ["reuse-file", "reuse", "reuse", "reuse", "reuse-file"],
    "MPL-2.0": ["reuse-file", "reuse-file", "reuse-file", "reuse-file", "reuse-file"],
    "LGPL": ["borrow", "borrow", "reuse-file", "reuse-file", "borrow"],
    "GPL-2.0-only": ["borrow", "borrow", "borrow", "borrow", "borrow"],
    "GPL-2.0-or-later": ["borrow", "borrow", "reuse", "reuse", "borrow"],
    "GPL-3.0": ["borrow", "borrow", "reuse", "reuse", "borrow"],
    "AGPL-3.0": ["borrow", "borrow", "reuse-file", "reuse", "borrow"],
    "CC-BY-4.0": ["reuse-file", "reuse-file", "reuse-file", "reuse-file", "reuse"],
    "CC-BY-SA-4.0": ["reuse-file", "reuse-file", "reuse-file", "reuse-file", "reuse-file"],
    "CC-NC-ND": ["cite", "cite", "cite", "cite", "cite"],
    "source-available": ["cite", "cite", "cite", "cite", "cite"],
    "none": ["cite", "cite", "cite", "cite", "cite"],
    "unknown": ["cite", "cite", "cite", "cite", "cite"],
}
RANK = {"cite": 0, "borrow": 1, "reuse-file": 2, "reuse": 3}

PERMISSIVE = {
    "MIT",
    "MIT-0",
    "BSD-2-Clause",
    "BSD-3-Clause",
    "ISC",
    "0BSD",
    "Zlib",
    "Unlicense",
    "CC0-1.0",
    "BSL-1.0",
    "PSF-2.0",
    "Python-2.0",
    "WTFPL",
    "X11",
    "Artistic-2.0",
}
SOURCE_AVAILABLE = {
    "BUSL-1.1",
    "SSPL-1.0",
    "Elastic-2.0",
    "Commons-Clause",
    "PolyForm",
    "LicenseRef-Commons-Clause",
}


def classify(spdx: str | None) -> str:
    """One SPDX id (no operators) to its class."""
    if not spdx:
        return "none"
    s = spdx.strip().removesuffix("+")
    if s in PERMISSIVE:
        return "permissive"
    if s == "Apache-2.0":
        return "Apache-2.0"
    if s.startswith("MPL-2.0"):
        return "MPL-2.0"
    if s.startswith("LGPL-"):
        return "LGPL"
    if s.startswith("AGPL-3.0"):
        return "AGPL-3.0"
    if s.startswith("GPL-3.0"):
        return "GPL-3.0"
    if s == "GPL-2.0-or-later" or spdx.strip() == "GPL-2.0+":
        return "GPL-2.0-or-later"
    if s.startswith("GPL-2.0"):  # a bare GPL-2.0 text says nothing about "or later": assume -only
        return "GPL-2.0-only"
    if re.fullmatch(r"CC-BY-(NC|ND|NC-SA|NC-ND)-[\d.]+", s):
        return "CC-NC-ND"
    if s.startswith("CC-BY-SA-4.0"):
        return "CC-BY-SA-4.0"
    if s.startswith("CC-BY-4.0"):
        return "CC-BY-4.0"
    if s in SOURCE_AVAILABLE or s.startswith("PolyForm") or s.startswith("BUSL"):
        return "source-available"
    if s == "NONE":
        return "none"
    return "unknown"


def base_id(spdx: str) -> str:
    """A licence text cannot say -only or -or-later; compare ids without it."""
    return re.sub(r"(-only|-or-later|\+)$", "", spdx.strip())


def dest_column(spdx: str) -> int:
    c = classify(spdx)
    if c == "permissive":
        return 0
    if c in ("Apache-2.0", "GPL-3.0", "AGPL-3.0", "CC-BY-4.0"):
        return DESTS.index(c)
    raise SystemExit(
        f"--into {spdx}: no column in the matrix; decide by hand (references/licences.md)"
    )


def verdict(expr: str | None, into: str) -> str:
    """Verdict for an SPDX expression. OR: the best alternative. AND: the worst part.

    A destination that is itself an expression takes the worst verdict over its parts.
    """
    ids = [p.strip("() ") for p in re.split(r"\s+(?:AND|OR)\s+", into.strip(), flags=re.I)]
    return min((_verdict(expr, dest_column(i)) for i in ids), key=RANK.get)


def _verdict(expr: str | None, col: int) -> str:
    if not expr:
        return MATRIX["none"][col]
    expr = re.sub(r"\s+WITH\s+\S+", "", expr.strip().strip("()"))
    best = "cite"
    for alt in re.split(r"\s+OR\s+", expr, flags=re.I):
        parts = [p.strip("() ") for p in re.split(r"\s+AND\s+", alt, flags=re.I)]
        worst = min((MATRIX[classify(p)][col] for p in parts), key=RANK.get)
        best = max(best, worst, key=RANK.get)
    return best


def destination_licence(dest: Path, rel: str | None = None) -> tuple[str, str]:
    """Licence of one destination path, which need not exist yet, and where it was read from."""
    dest = dest.resolve()
    anns, problem = read_reuse_toml(dest)
    if problem:
        raise SystemExit(f"--dest {dest}: {problem}; name the licence with --into")
    if rel is None:
        ids = sorted({a["licence"] for a in anns if a["licence"]})
        if len(ids) > 1:
            raise SystemExit(
                f"--dest {dest}: REUSE.toml has {ids}; say where the item lands with --dest-path"
            )
        rel = "."
    target = (dest / rel).resolve()
    if not target.is_relative_to(dest):
        raise SystemExit(f"--dest-path {rel}: outside {dest}")
    rel = target.relative_to(dest).as_posix()
    hit = None
    for a in anns:
        if any(g.match(rel) for g in a["globs"]):
            hit = a  # the last matching annotation wins
    if hit and hit["precedence"] == "override":
        return hit["licence"], "REUSE.toml (override)"
    if target.is_file():
        m = TAG_RE.search(read(target)[:4000])
        if m:
            return m.group(1).strip().rstrip("-").strip(), "file tag"
    if hit and hit["licence"]:
        return hit["licence"], "REUSE.toml"
    d = target if target.is_dir() else target.parent
    while True:
        if d.is_dir():
            for f in sorted(d.iterdir()):
                if f.is_file() and LICENCE_FILE.match(f.name):
                    return detect_text(read(f)), f.relative_to(dest).as_posix()
        if d == dest:
            raise SystemExit(f"--dest {dest}: no REUSE.toml annotation or LICENSE covers {rel}")
        d = d.parent


def matrix_markdown() -> str:
    rows = ["| from \\ into | " + " | ".join(DESTS) + " |", "|---" * (len(DESTS) + 1) + "|"]
    for c in CLASSES:
        rows.append(f"| {c} | " + " | ".join(MATRIX[c]) + " |")
    return "\n".join(rows)


# --- detection --------------------------------------------------------------------------------


def detect_text(text: str) -> str:
    """SPDX id of a licence file's text, from its distinctive phrases."""
    t = re.sub(r"\s+", " ", text[:20000]).lower()
    if "gnu affero general public license" in t:
        return "AGPL-3.0"
    if "gnu lesser general public license" in t or "gnu library general public license" in t:
        return "LGPL-2.1" if "version 2.1" in t or "version 2," in t else "LGPL-3.0"
    if "gnu general public license" in t:
        if "version 3" in t:
            return "GPL-3.0"
        if "version 2" in t:
            later = "version 2 of the license, or (at your option) any later version" in t
            return "GPL-2.0-or-later" if later else "GPL-2.0"
    if "mozilla public license" in t and "2.0" in t:
        return "MPL-2.0"
    if "apache license" in t and "version 2.0" in t:
        return "Apache-2.0"
    if "business source license" in t:
        return "BUSL-1.1"
    if "server side public license" in t:
        return "SSPL-1.0"
    if "elastic license 2.0" in t:
        return "Elastic-2.0"
    if "polyform" in t:
        return "PolyForm"
    if "commons clause" in t:
        return "Commons-Clause"
    if "creative commons" in t or "attribution" in t and "international" in t:
        for name, spdx in (
            ("attribution-noncommercial-noderivatives", "CC-BY-NC-ND-4.0"),
            ("attribution-noncommercial-sharealike", "CC-BY-NC-SA-4.0"),
            ("attribution-noncommercial", "CC-BY-NC-4.0"),
            ("attribution-noderivatives", "CC-BY-ND-4.0"),
            ("attribution-sharealike 4.0", "CC-BY-SA-4.0"),
            ("attribution 4.0 international", "CC-BY-4.0"),
            ("cc0 1.0", "CC0-1.0"),
        ):
            if name in t:
                return spdx
    if "cc0 1.0 universal" in t:
        return "CC0-1.0"
    if "this is free and unencumbered software released into the public domain" in t:
        return "Unlicense"
    if "boost software license" in t:
        return "BSL-1.0"
    if "permission is hereby granted, free of charge" in t:
        return "MIT"
    if "redistribution and use in source and binary forms" in t:
        return (
            "BSD-3-Clause"
            if "neither the name" in t or "names of its contributors" in t
            else "BSD-2-Clause"
        )
    if "permission to use, copy, modify, and/or distribute this software for any purpose" in t:
        return "ISC" if "provided that the above copyright notice" in t else "0BSD"
    if "this software is provided 'as-is'" in t and "permission is granted to anyone" in t:
        return "Zlib"
    return "unknown"


LICENCE_FILE = re.compile(r"^(LICEN[CS]E|COPYING|UNLICENSE)([.-].*)?$", re.I)
NOTICE_FILE = re.compile(r"^NOTICE([.-].*)?$", re.I)
TAG_RE = re.compile(re.escape(TAG_LICENSE) + r"[ \t]*:[ \t]*([^\n*#<>\"']+)")
TOOLING = [
    ("skill", ["**/SKILL.md"]),
    ("agent", [".claude/agents/*.md", "agents/*.md", ".opencode/agent/*.md"]),
    ("command", [".claude/commands/*.md", "commands/*.md"]),
    (
        "agent-rules",
        [
            "AGENTS.md",
            "CLAUDE.md",
            ".cursorrules",
            ".cursor/rules/*",
            ".github/copilot-instructions.md",
        ],
    ),
    (
        "hook-or-settings",
        [
            ".claude/settings.json",
            ".claude/hooks/*",
            ".mcp.json",
            ".pre-commit-config.yaml",
            ".githooks/*",
            ".husky/*",
        ],
    ),
    ("plugin", [".claude-plugin/*.json", "**/.claude-plugin/plugin.json"]),
    (
        "workflow",
        [".github/workflows/*.yml", ".github/workflows/*.yaml", "**/action.yml", "**/action.yaml"],
    ),
    (
        "task-runner",
        ["justfile", "**/justfile", "Justfile", "Makefile", "**/Makefile", "Taskfile.yml"],
    ),
    ("nix", ["flake.nix", "**/flake.nix", "shell.nix", "default.nix"]),
    (
        "container",
        ["Dockerfile", "**/Dockerfile", ".devcontainer/*", "docker-compose.yml", "compose.yaml"],
    ),
    ("script", ["scripts/*", "bin/*", "tools/*", "**/*.sh"]),
    ("prompt", ["prompts/*", "**/*.prompt.md", "**/prompts/*.md"]),
    (
        "docs-standard",
        ["CONTRIBUTING.md", "**/*style-guide*.md", "**/STYLE*.md", "**/*writing-style*.md"],
    ),
    (
        "lint-config",
        [
            "ruff.toml",
            ".editorconfig",
            ".shellcheckrc",
            ".golangci.yml",
            "eslint.config.*",
            ".prettierrc*",
            "biome.json",
        ],
    ),
    (
        "template",
        [
            ".github/ISSUE_TEMPLATE/*",
            ".github/PULL_REQUEST_TEMPLATE.md",
            ".github/pull_request_template.md",
            "templates/**",
        ],
    ),
    ("citation", ["CITATION.cff", ".zenodo.json", "codemeta.json"]),
]


def walk(root: Path):
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = sorted(d for d in dirnames if d not in SKIP_DIRS)
        for f in sorted(filenames):
            yield Path(dirpath, f).relative_to(root)


def read(p: Path) -> str:
    try:
        if p.stat().st_size > MAX_BYTES:
            return ""
        return p.read_text(errors="replace")
    except OSError:
        return ""


def reuse_glob(pattern: str) -> re.Pattern:
    """REUSE.toml glob: * does not cross '/', ** does."""
    out, i = "", 0
    while i < len(pattern):
        if pattern.startswith("**/", i):
            out, i = out + "(?:.*/)?", i + 3
        elif pattern.startswith("**", i):
            out, i = out + ".*", i + 2
        elif pattern[i] == "*":
            out, i = out + "[^/]*", i + 1
        else:
            out, i = out + re.escape(pattern[i]), i + 1
    return re.compile(out + r"\Z")


def read_reuse_toml(root: Path) -> tuple[list, str | None]:
    p = root / "REUSE.toml"
    if not p.exists():
        return [], None
    if tomllib is None:
        return [], "REUSE.toml present but not parsed (Python < 3.11)"
    try:
        data = tomllib.loads(read(p))
    except tomllib.TOMLDecodeError as e:
        return [], f"REUSE.toml does not parse: {e}"
    anns = []
    for a in data.get("annotations", []):
        paths = a.get("path", [])
        paths = [paths] if isinstance(paths, str) else paths
        holders = a.get(TAG_COPYRIGHT, [])
        anns.append(
            {
                "globs": [reuse_glob(g) for g in paths],
                "paths": paths,
                "licence": a.get(TAG_LICENSE),
                "precedence": a.get("precedence", "closest"),
                "copyright": [holders] if isinstance(holders, str) else holders,
            }
        )
    return anns, None


def manifest_licences(root: Path, rel: Path) -> list[str]:
    name, p = rel.name, root / rel
    text = read(p)
    if not text:
        return []
    try:
        if name in ("package.json", "composer.json"):
            d = json.loads(text)
            lic = d.get("license") or d.get("licenses")
            if isinstance(lic, list):
                return [x.get("type", x) if isinstance(x, dict) else x for x in lic]
            return [lic] if isinstance(lic, str) else []
        if name in ("pyproject.toml", "Cargo.toml", "gleam.toml") and tomllib:
            d = tomllib.loads(text)
            sect = d.get("project") or d.get("package") or d.get("tool", {}).get("poetry") or d
            lic = sect.get("license") or sect.get("licences") or d.get("licences")
            if isinstance(lic, dict):
                lic = lic.get("text") or lic.get("file")
            out = lic if isinstance(lic, list) else ([lic] if isinstance(lic, str) else [])
            for c in sect.get("classifiers", []):
                m = re.match(r"License :: OSI Approved :: (.+)", c)
                if m:
                    out.append("classifier: " + m.group(1))
            return out
        if name == "setup.cfg" or name.endswith(".gemspec"):
            m = re.search(
                r"^\s*(?:license|s\.license|spec\.license)\s*[=:]\s*['\"]?([^'\"\n]+)", text, re.M
            )
            return [m.group(1).strip()] if m else []
    except (json.JSONDecodeError, ValueError, AttributeError):
        return ["unparsed"]
    return []


def git_commit(root: Path) -> str | None:
    try:
        r = subprocess.run(
            ["git", "-C", str(root), "rev-parse", "HEAD"],
            capture_output=True,
            text=True,
            timeout=10,
        )
        return r.stdout.strip() or None if r.returncode == 0 else None
    except (OSError, subprocess.TimeoutExpired):
        return None


def git_remote(root: Path) -> str | None:
    try:
        r = subprocess.run(
            ["git", "-C", str(root), "remote", "get-url", "origin"],
            capture_output=True,
            text=True,
            timeout=10,
        )
        return r.stdout.strip() or None if r.returncode == 0 else None
    except (OSError, subprocess.TimeoutExpired):
        return None


def scan(root: Path, into: str | None = None) -> dict:
    root = root.resolve()
    files = list(walk(root))
    licence_files, notices, tags, manifests, tooling, citation = {}, [], {}, {}, [], {}
    for rel in files:
        name = rel.name
        if LICENCE_FILE.match(name) or (
            rel.parent.name == "LICENSES" and rel.suffix in (".txt", "")
        ):
            licence_files[str(rel)] = (
                rel.stem if rel.parent.name == "LICENSES" else detect_text(read(root / rel))
            )
        elif NOTICE_FILE.match(name):
            notices.append(str(rel))
        if name in (
            "package.json",
            "composer.json",
            "pyproject.toml",
            "Cargo.toml",
            "gleam.toml",
            "setup.cfg",
        ) or name.endswith(".gemspec"):
            lic = manifest_licences(root, rel)
            if lic:
                manifests[str(rel)] = lic
        text = (
            read(root / rel)
            if (root / rel).suffix not in (".png", ".jpg", ".pdf", ".svg", ".zip", ".gz")
            else ""
        )
        m = TAG_RE.search(text[:4000]) if text else None
        if m:
            tags[str(rel)] = m.group(1).strip().rstrip("-").strip()
        if name == "CITATION.cff":
            doi = re.search(r"^\s*doi:\s*['\"]?([^'\"\s]+)", text, re.M)
            citation[str(rel)] = {"doi": doi.group(1) if doi else None}
        elif name in (".zenodo.json", "codemeta.json"):
            citation[str(rel)] = {"doi": None}
        for kind, globs in TOOLING:
            if any(fnmatch.fnmatch(str(rel), g) or ("/" not in g and name == g) for g in globs):
                tooling.append({"path": str(rel), "kind": kind})
                break

    anns, reuse_problem = read_reuse_toml(root)
    root_ids = sorted({v for k, v in licence_files.items() if "/" not in k})
    lic_dirs = {
        str(Path(k).parent): v for k, v in licence_files.items() if not k.startswith("LICENSES/")
    }

    def licence_of(rel: str) -> tuple[str | None, str]:
        """Most specific licence for a path: REUSE override > file tag > REUSE closest > nearest LICENSE."""
        hit = None
        for a in anns:
            if any(g.match(rel) for g in a["globs"]):
                hit = a  # the last matching annotation wins
        if hit and hit["precedence"] == "override":
            return hit["licence"], "REUSE.toml (override)"
        if rel in tags:
            return tags[rel], "file tag"
        if hit:
            return hit["licence"], "REUSE.toml"
        d = Path(rel).parent
        while True:
            key = str(d)
            if key in lic_dirs:
                return lic_dirs[key], f"{key}/LICENSE" if key != "." else "LICENSE"
            if key == ".":
                return None, "no licence found"
            d = d.parent

    for t in tooling:
        t["licence"], t["licence_from"] = licence_of(t["path"])
        if into:
            t["verdict"] = verdict(t["licence"], into)

    flags = []
    if not licence_files and not tags and not anns:
        flags.append("no-licence: all rights reserved; cite only, copy nothing")
    every = {classify(v) for v in licence_files.values()} | {classify(v) for v in tags.values()}
    every |= {classify(a["licence"]) for a in anns if a["licence"] and " " not in a["licence"]}
    if len(every - {"none"}) > 1:
        flags.append("mixed: licences differ by path; use each item's own licence, not the repo's")
    for path, lic in manifests.items():
        ids = {base_id(x) for x in lic if not x.startswith("classifier")}
        if root_ids and ids and not ids & {base_id(r) for r in root_ids}:
            flags.append(f"manifest-mismatch: {path} says {sorted(ids)}, LICENSE says {root_ids}")
    if notices:
        flags.append("notice: keep NOTICE text with anything reused (Apache-2.0 section 4d)")
    if "unknown" in every:
        flags.append("unknown-licence: a licence file was not recognised; a human reads it")
    if reuse_problem:
        flags.append(reuse_problem)

    out = {
        "path": str(root),
        "remote": git_remote(root),
        "commit": git_commit(root),
        "licence_files": licence_files,
        "root_licences": root_ids,
        "notices": notices,
        "file_tags": tags,
        "reuse_toml": [
            {
                "paths": a["paths"],
                "licence": a["licence"],
                "precedence": a["precedence"],
                "copyright": a["copyright"],
            }
            for a in anns
        ],
        "manifests": manifests,
        "citation": citation,
        "tooling": tooling,
        "flags": flags,
    }
    if into:
        out["into"] = into
        out["repo_verdict"] = verdict(" AND ".join(root_ids) if root_ids else None, into)
    return out


# --- self-test --------------------------------------------------------------------------------

MIT_TEXT = (
    "MIT License\n\nCopyright (c) 2026 Example\n\nPermission is hereby granted, free of "
    "charge, to any person obtaining a copy of this software...\n"
)


def self_test() -> None:
    fails = []

    def check(name, got, want):
        if got != want:
            fails.append(f"{name}: got {got!r}, want {want!r}")

    for text, want in [
        (MIT_TEXT, "MIT"),
        ("Apache License\nVersion 2.0, January 2004", "Apache-2.0"),
        ("GNU GENERAL PUBLIC LICENSE\nVersion 2, June 1991", "GPL-2.0"),
        ("GNU GENERAL PUBLIC LICENSE\nVersion 3, 29 June 2007", "GPL-3.0"),
        ("GNU AFFERO GENERAL PUBLIC LICENSE\nVersion 3", "AGPL-3.0"),
        ("GNU LESSER GENERAL PUBLIC LICENSE\nVersion 3", "LGPL-3.0"),
        ("Mozilla Public License Version 2.0", "MPL-2.0"),
        ("Attribution-ShareAlike 4.0 International\nCreative Commons", "CC-BY-SA-4.0"),
        ("Attribution-NonCommercial 4.0 International\nCreative Commons", "CC-BY-NC-4.0"),
        ("Attribution 4.0 International\nCreative Commons", "CC-BY-4.0"),
        ("Business Source License 1.1", "BUSL-1.1"),
        ("Redistribution and use in source and binary forms... Neither the name", "BSD-3-Clause"),
        ("This is free and unencumbered software released into the public domain.", "Unlicense"),
        ("All rights reserved.", "unknown"),
    ]:
        check(f"detect {want}", detect_text(text), want)

    for expr, into, want in [
        ("MIT", "AGPL-3.0-only", "reuse"),
        ("GPL-2.0", "AGPL-3.0-only", "borrow"),
        ("GPL-2.0-or-later", "AGPL-3.0-only", "reuse"),
        ("GPL-3.0-only", "MIT", "borrow"),
        ("MIT OR GPL-3.0-only", "MIT", "reuse"),
        ("MIT AND GPL-2.0-only", "AGPL-3.0-only", "borrow"),
        ("Apache-2.0", "MIT", "reuse-file"),
        ("CC-BY-SA-4.0", "CC-BY-4.0", "reuse-file"),
        ("CC-BY-NC-4.0", "CC-BY-4.0", "cite"),
        ("BUSL-1.1", "AGPL-3.0-only", "cite"),
        (None, "MIT", "cite"),
        ("GPL-2.0-only WITH Classpath-exception-2.0", "AGPL-3.0-only", "borrow"),
        ("AGPL-3.0-only", "AGPL-3.0-only", "reuse"),
        ("MIT", "AGPL-3.0-only AND MIT", "reuse"),
        ("AGPL-3.0-only", "AGPL-3.0-only AND MIT", "borrow"),
        ("Apache-2.0", "Apache-2.0 AND MIT", "reuse-file"),
        ("AGPL-3.0-only", "MIT", "borrow"),
        ("AGPL-3.0-only", "CC-BY-4.0", "borrow"),
        ("MIT", "MIT", "reuse"),
        ("MIT", "CC-BY-4.0", "reuse-file"),
        ("CC-BY-4.0", "CC-BY-4.0", "reuse"),
        ("CC-BY-4.0", "MIT", "reuse-file"),
        ("CC-BY-SA-4.0", "MIT", "reuse-file"),
        ("MPL-2.0", "MIT", "reuse-file"),
        ("LGPL-3.0-only", "MIT", "borrow"),
    ]:
        check(f"verdict {expr} into {into}", verdict(expr, into), want)

    check("REUSE **/ matches the root", bool(reuse_glob("**/*.md").match("AGENTS.md")), True)
    check("REUSE * stays in one directory", bool(reuse_glob("docs/*").match("docs/a/b.md")), False)

    doc = Path(__file__).resolve().parent.parent / "references" / "licences.md"
    m = re.search(r"<!-- matrix:start -->\n(.*?)\n<!-- matrix:end -->", doc.read_text(), re.S)
    check(
        "references/licences.md matrix matches MATRIX",
        m.group(1).strip() if m else None,
        matrix_markdown(),
    )

    with tempfile.TemporaryDirectory() as tmp:
        r = Path(tmp, "repo")
        (r / ".claude/skills/demo").mkdir(parents=True)
        (r / "vendor/gpl").mkdir(parents=True)
        (r / "LICENSE").write_text(MIT_TEXT)
        (r / "NOTICE").write_text("Example\n")
        (r / ".claude/skills/demo/SKILL.md").write_text("---\nname: demo\n---\n")
        (r / "vendor/gpl/LICENSE").write_text(
            "GNU GENERAL PUBLIC LICENSE\nVersion 3, 29 June 2007\n"
        )
        (r / "vendor/gpl/justfile").write_text("default:\n\techo hi\n")
        (r / "scripts").mkdir()
        (r / "scripts/tool.sh").write_text(f"#!/bin/sh\n# {TAG_LICENSE}: Apache-2.0\n")
        (r / "package.json").write_text('{"license": "ISC"}')
        (r / "CITATION.cff").write_text("cff-version: 1.2.0\ndoi: 10.5281/zenodo.1\n")
        res = scan(r, into="AGPL-3.0-only")
        tools = {t["path"]: t for t in res["tooling"]}
        check("skill found", tools.get(".claude/skills/demo/SKILL.md", {}).get("kind"), "skill")
        check("skill licence from root", tools[".claude/skills/demo/SKILL.md"]["licence"], "MIT")
        check("nested LICENSE wins", tools["vendor/gpl/justfile"]["licence"], "GPL-3.0")
        check("file tag wins", tools["scripts/tool.sh"]["licence"], "Apache-2.0")
        check("repo verdict", res["repo_verdict"], "reuse")
        check("doi", res["citation"]["CITATION.cff"]["doi"], "10.5281/zenodo.1")
        flags = " ".join(res["flags"])
        for f in ("mixed", "manifest-mismatch", "notice"):
            check(f"flag {f}", f in flags, True)

        (r / "REUSE.toml").write_text(
            'version = 1\n[[annotations]]\npath = ["scripts/**"]\nprecedence = "override"\n'
            f'{TAG_COPYRIGHT} = "2026 X"\n{TAG_LICENSE} = "MPL-2.0"\n'
        )
        if tomllib:
            res = scan(r, into="MIT")
            tools = {t["path"]: t for t in res["tooling"]}
            check("REUSE override beats file tag", tools["scripts/tool.sh"]["licence"], "MPL-2.0")
            check("MPL into MIT", tools["scripts/tool.sh"]["verdict"], "reuse-file")

        bare = Path(tmp, "bare")
        (bare / "prompts").mkdir(parents=True)
        (bare / "prompts/x.md").write_text("hello\n")
        (bare / "AGENTS.md").write_text("rules\n")
        res = scan(bare, into="CC-BY-4.0")
        check("no licence flagged", any(f.startswith("no-licence") for f in res["flags"]), True)
        check("no licence verdict", res["repo_verdict"], "cite")

        def refuses(*args) -> bool:
            try:
                destination_licence(*args)
            except SystemExit:
                return True
            return False

        mit = Path(tmp, "dest-mit")
        (mit / ".claude/skills").mkdir(parents=True)
        (mit / "LICENSE").write_text(MIT_TEXT)
        check("MIT destination, root", destination_licence(mit), ("MIT", "LICENSE"))
        check(
            "MIT destination, a path not written yet",
            destination_licence(mit, ".claude/skills/new/SKILL.md"),
            ("MIT", "LICENSE"),
        )
        check("--dest-path may not leave the destination", refuses(mit, "../x"), True)
        check("a destination with no licence is refused", refuses(bare), True)

        if tomllib:
            split = Path(tmp, "dest-split")
            (split / "scripts").mkdir(parents=True)
            (split / "LICENSE").write_text("Attribution 4.0 International\nCreative Commons\n")
            (split / "scripts/tagged.sh").write_text(f"#!/bin/sh\n# {TAG_LICENSE}: MIT\n")

            def ann(paths: str, licence: str, precedence: str = "closest") -> str:
                return (
                    f'[[annotations]]\npath = [{paths}]\nprecedence = "{precedence}"\n'
                    f'{TAG_COPYRIGHT} = "2026 X"\n{TAG_LICENSE} = "{licence}"\n\n'
                )

            (split / "REUSE.toml").write_text(
                "version = 1\n\n"
                + ann('"**"', "CC-BY-4.0")
                + ann('"scripts/**", ".github/**", "justfile"', "AGPL-3.0-only")
                + ann('".claude/skills/vendored/**"', "MIT", "override")
            )
            for rel, want in [
                ("docs/a.md", ("CC-BY-4.0", "REUSE.toml")),
                ("scripts/new.py", ("AGPL-3.0-only", "REUSE.toml")),
                (".github/workflows/release.yml", ("AGPL-3.0-only", "REUSE.toml")),
                ("justfile", ("AGPL-3.0-only", "REUSE.toml")),
                (".claude/skills/new/SKILL.md", ("CC-BY-4.0", "REUSE.toml")),
                (".claude/skills/vendored/SKILL.md", ("MIT", "REUSE.toml (override)")),
                ("scripts/tagged.sh", ("MIT", "file tag")),
            ]:
                check(f"split destination, {rel}", destination_licence(split, rel), want)
            check("split destination needs --dest-path", refuses(split), True)
            for rel, expr, want in [
                ("scripts/new.py", "AGPL-3.0-only", "reuse"),
                ("scripts/new.py", "GPL-2.0", "borrow"),
                ("scripts/new.py", "MIT", "reuse"),
                (".claude/skills/new/SKILL.md", "MIT", "reuse-file"),
                ("docs/a.md", "CC-BY-SA-4.0", "reuse-file"),
                ("docs/a.md", "AGPL-3.0-only", "borrow"),
            ]:
                into = destination_licence(split, rel)[0]
                check(f"{expr} into split {rel}", verdict(expr, into), want)

    if fails:
        print("self-test FAILED:\n  " + "\n  ".join(fails), file=sys.stderr)
        sys.exit(1)
    print("licence_scan self-test: ok")


def main() -> None:
    ap = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    ap.add_argument("dirs", nargs="*", type=Path)
    ap.add_argument(
        "--into", help="the destination file's SPDX id (e.g. AGPL-3.0-only, CC-BY-4.0, MIT)"
    )
    ap.add_argument(
        "--dest", type=Path, help="the destination repo: read the licence from it instead of --into"
    )
    ap.add_argument(
        "--dest-path", help="where the item will land, relative to --dest (it need not exist)"
    )
    ap.add_argument("--matrix", action="store_true")
    ap.add_argument("--self-test", action="store_true")
    a = ap.parse_args()
    if a.self_test:
        return self_test()
    if a.matrix:
        return print(matrix_markdown())
    if a.into and a.dest:
        ap.error("--into and --dest are two ways to name the same licence; give one")
    if a.dest_path and not a.dest:
        ap.error("--dest-path needs --dest")
    into_from = None
    if a.dest:
        a.into, into_from = destination_licence(a.dest, a.dest_path)
    if a.into:
        verdict(None, a.into)  # exits here, before any scan, when the matrix has no column for it
    if a.dest and not a.dirs:
        return print(json.dumps({"into": a.into, "into_from": into_from}))
    if not a.dirs:
        ap.error("give at least one checkout directory")
    for d in a.dirs:
        res = scan(d, a.into)
        if into_from:
            res["into_from"] = into_from
        print(json.dumps(res, indent=1))


if __name__ == "__main__":
    main()
