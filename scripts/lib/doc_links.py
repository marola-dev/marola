#!/usr/bin/env python3
"""Rewrite one repo's README and docs/** links for its mount on the docs site (MIP-0074 Appendix A).

scripts/lib/doc_links.py --self-test
"""

from __future__ import annotations

import argparse
import posixpath
import re
import sys
import tempfile
import unicodedata
from pathlib import Path

FENCE = re.compile(r"^ {0,3}(`{3,}|~{3,})")
CODE_SPAN = re.compile(r"(?<!`)(`+)(?!`).*?(?<!`)\1(?!`)", re.S)
INLINE = re.compile(
    r"(!?)\[((?:[^\[\]]|\[[^\[\]]*\])*)\]"
    r"\(\s*(<[^>]*>|[^)\s]+)((?:\s+(?:\"[^\"]*\"|'[^']*'|\([^)]*\)))?\s*)\)"
)
REFDEF = re.compile(r"^( {0,3}\[[^\]]+\]:[ \t]*)(<[^>]*>|\S+)", re.M)
MASK = re.compile(r"\0(\d+)\0")
HTML_ATTR = re.compile(r"(?<=\s)(href|src)(\s*=\s*)([\"'])([^\"']*)\3", re.I)
SCHEME = re.compile(r"^(?:[a-zA-Z][a-zA-Z0-9+.-]*:|//)")
HEADING = re.compile(r"^ {0,3}#{1,6}[ \t]+(.*?)(?:[ \t]+#+)?[ \t]*$")
IMAGE_SUFFIXES = {".png", ".jpg", ".jpeg", ".gif", ".svg", ".webp", ".avif", ".ico"}


def _fenced(text: str) -> list[tuple[bool, str]]:
    """Split into (is_fence, chunk)."""
    out: list[tuple[bool, str]] = []
    prose: list[str] = []
    fence = ""
    for line in text.splitlines(keepends=True):
        m = FENCE.match(line)
        if fence:
            out.append((True, line))
            if m and m.group(1)[0] == fence[0] and len(m.group(1)) >= len(fence):
                fence = ""
        elif m:
            out.append((False, "".join(prose)))
            prose = []
            fence = m.group(1)
            out.append((True, line))
        else:
            prose.append(line)
    out.append((False, "".join(prose)))
    return out


def _unique(slug: str, seen: dict[str, int], sep: str) -> str:
    n = seen.get(slug, 0)
    seen[slug] = n + 1
    return f"{slug}{sep}{n}" if n else slug


def _readme_anchors(readme: str) -> dict[str, str]:
    """GitHub's anchor (and toc's own, for links already written for the site) -> toc's anchor."""
    anchors: dict[str, str] = {}
    gh_seen: dict[str, int] = {}
    toc_seen: dict[str, int] = {}
    for is_fence, chunk in _fenced(readme):
        for line in [] if is_fence else chunk.splitlines():
            m = HEADING.match(line)
            if not m:
                continue
            title = re.sub(r"!?\[([^\]]*)\]\([^)]*\)", r"\1", m.group(1))
            title = re.sub(r"<[^>]+>|[`*]", "", title)
            gh = re.sub(r"[^\w\- ]", "", title.lower()).replace(" ", "-")
            # Python-Markdown toc's default slugify, which mkdocs uses (mkdocs.yml sets none).
            ascii_ = unicodedata.normalize("NFKD", title).encode("ascii", "ignore").decode()
            toc = re.sub(r"[-\s]+", "-", re.sub(r"[^\w\s-]", "", ascii_).strip().lower())
            toc = _unique(toc, toc_seen, "_")
            anchors[_unique(gh, gh_seen, "-")] = toc
            anchors.setdefault(toc, toc)
    return anchors


def _out_path(source: str) -> str:
    return "index.md" if source == "README.md" else source.removeprefix("docs/")


def rewrite(text: str, source: str, repo: Path) -> tuple[str, list[str]]:
    """Rewrite `text`, the repo-relative file `source` (README.md or docs/**), for the mount.

    Returns the new text and one error per link Appendix A refuses. Links to files outside
    docs/ other than README.md and images are left for the blob/tree rows (MIP-0074 task 6).
    """
    errors: list[str] = []
    anchors: dict[str, str] | None = None

    def readme_anchor(link: str, anchor: str) -> str | None:
        nonlocal anchors
        if anchors is None:
            readme = repo / "README.md"
            anchors = (
                _readme_anchors(readme.read_text(encoding="utf-8")) if readme.is_file() else {}
            )
        if anchor not in anchors:
            errors.append(f"{source}: {link}: no README heading has the anchor #{anchor}")
            return None
        return anchors[anchor]

    def target(link: str, image: bool) -> str:
        def fail(reason: str) -> str:
            errors.append(f"{source}: {link}: {reason}")
            return link

        if not link or SCHEME.match(link):
            return link
        path, hash_, anchor = link.partition("#")
        if path.startswith("/"):
            return fail("root-absolute; link the page relatively")
        if not path:
            if hash_ and source == "README.md":
                anchor = readme_anchor(link, anchor) or anchor
            return path + hash_ + anchor
        resolved = posixpath.normpath(posixpath.join(posixpath.dirname(source), path))
        if resolved == ".." or resolved.startswith("../"):
            return fail("leaves the repo")
        if resolved != "docs" and not resolved.startswith("docs/"):
            if image or posixpath.splitext(resolved)[1].lower() in IMAGE_SUFFIXES:
                return fail("an image outside docs/; move it under docs/")
            if resolved != "README.md":
                return link
            mount = "index.md"
            if hash_:
                anchor = readme_anchor(link, anchor) or anchor
        else:
            rel = resolved.removeprefix("docs").removeprefix("/")
            if rel == "index.md":
                return fail("docs/index.md is banned (MIP-0074 D1); link docs/ for the landing")
            if not rel:
                mount = "index.md"
            elif path.endswith("/") or (repo / resolved).is_dir():
                # adr/index.md is generated by prepare-docs, so it never exists in the repo.
                if rel != "adr" and not (repo / resolved / "index.md").is_file():
                    return fail("a directory with no index.md")
                mount = f"{rel}/index.md"
            else:
                mount = rel
        start = posixpath.dirname(_out_path(source)) or "."
        return posixpath.relpath(mount, start) + hash_ + anchor

    def bracketed(link: str, image: bool) -> str:
        if link.startswith("<") and link.endswith(">"):
            return f"<{target(link[1:-1], image)}>"
        return target(link, image)

    def inline(m: re.Match[str]) -> str:
        bang, label, link, title = m.groups()
        label = INLINE.sub(inline, label)
        return f"{bang}[{label}]({bracketed(link, bool(bang))}{title})"

    def prose(chunk: str) -> str:
        # Masked, not split out: a code span can be a link's label, as in [`AGENTS.md`](…).
        spans: list[str] = []

        def mask(m: re.Match[str]) -> str:
            spans.append(m.group(0))
            return f"\0{len(spans) - 1}\0"

        chunk = CODE_SPAN.sub(mask, chunk)
        chunk = REFDEF.sub(lambda m: m.group(1) + bracketed(m.group(2), False), chunk)
        chunk = INLINE.sub(inline, chunk)
        chunk = HTML_ATTR.sub(
            lambda m: (
                m.group(1)
                + m.group(2)
                + m.group(3)
                + target(m.group(4), m.group(1).lower() == "src")
                + m.group(3)
            ),
            chunk,
        )
        return MASK.sub(lambda m: spans[int(m.group(1))], chunk)

    return "".join(c if is_fence else prose(c) for is_fence, c in _fenced(text)), errors


def self_test() -> int:
    fails = 0

    def case(name, got, want):
        nonlocal fails
        if got == want:
            print(f"  ok   {name}")
        else:
            fails += 1
            print(f"  FAIL {name} — got {got!r}, want {want!r}")

    with tempfile.TemporaryDirectory() as tmp:
        repo = Path(tmp)
        for f in ("docs/x.md", "docs/sub/index.md", "docs/guide/page.md", "docs/img/a.png"):
            (repo / f).parent.mkdir(parents=True, exist_ok=True)
            (repo / f).write_text("# t\n", encoding="utf-8")
        (repo / "docs/empty").mkdir()
        (repo / "README.md").write_text(
            "# Repo\n\n## 🌊 Run it!\n\n```\n## Not a heading\n```\n", encoding="utf-8"
        )

        def readme(text):
            return rewrite(text, "README.md", repo)

        def failed(result):
            return bool(result[1])

        case(
            "rewrite_docs_and_dot_docs",
            readme(
                "[a](docs/x.md) [b](./docs/x.md) [c](https://x.example/docs/x.md) [d](mailto:a@b)"
            ),
            ("[a](x.md) [b](x.md) [c](https://x.example/docs/x.md) [d](mailto:a@b)", []),
        )
        case("rewrite_keeps_anchor", readme("[a](docs/x.md#sec)"), ("[a](x.md#sec)", []))
        case(
            "bare_docs_link_to_index",
            readme("[full docs](docs/) [d](docs)"),
            ("[full docs](index.md) [d](index.md)", []),
        )
        case("docs_index_link_fails", failed(readme("[i](docs/index.md)")), True)
        case(
            "dir_link_to_index",
            readme("[s](docs/sub/) [adr](docs/adr/)"),
            ("[s](sub/index.md) [adr](adr/index.md)", []),
        )
        case("dir_link_without_index_fails", failed(readme("[e](docs/empty/)")), True)
        case(
            "image_md_and_html_rewritten",
            readme('![l](docs/img/a.png) <img alt="l" src="docs/img/a.png">'),
            ('![l](img/a.png) <img alt="l" src="img/a.png">', []),
        )
        case(
            "image_outside_docs_fails",
            [failed(readme("![l](assets/logo.png)")), failed(readme('<img src="logo.svg">'))],
            [True, True],
        )
        case(
            "docs_page_parent_readme_to_index",
            [
                rewrite("[r](../README.md) [a](../README.md#repo)", "docs/x.md", repo),
                rewrite("[r](../../README.md)", "docs/guide/page.md", repo),
            ],
            [("[r](index.md) [a](index.md#repo)", []), ("[r](../index.md)", [])],
        )
        case(
            "readme_anchor_emoji_slug",
            [
                rewrite("[r](../README.md#-run-it)", "docs/x.md", repo),
                readme("[r](#-run-it) [s](#run-it)"),
                failed(readme("[n](#not-a-heading)")),
            ],
            [("[r](index.md#run-it)", []), ("[r](#run-it) [s](#run-it)", []), True],
        )
        case(
            "root_absolute_fails",
            failed(rewrite("[api](/api/scala/core/marola.html)", "docs/x.md", repo)),
            True,
        )
        case(
            "parent_escape_fails",
            [
                failed(readme("[o](../other/x.md)")),
                failed(rewrite("[o](../../x.md)", "docs/x.md", repo)),
            ],
            [True, True],
        )
        case(
            "reference_definition_and_html_href",
            readme('[x]: docs/x.md "Title"\n<a href="docs/x.md#a">x</a> <a href=\'docs/\'>d</a>\n'),
            ('[x]: x.md "Title"\n<a href="x.md#a">x</a> <a href=\'index.md\'>d</a>\n', []),
        )
        fenced = "```md\n[a](docs/x.md)\n```\n~~~\n![i](/abs.png)\n~~~\n"
        case(
            "code_span_and_fence_untouched",
            readme("`[a](docs/x.md)` [`b`](docs/x.md) ``[c](/abs)``\n" + fenced),
            ("`[a](docs/x.md)` [`b`](x.md) ``[c](/abs)``\n" + fenced, []),
        )

    if fails:
        print(f"doc_links self-test: {fails} failure(s)", file=sys.stderr)
        return 1
    print("doc_links self-test: ok")
    return 0


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args(argv)
    if args.self_test:
        return self_test()
    ap.print_help()
    return 2


if __name__ == "__main__":
    sys.exit(main())
