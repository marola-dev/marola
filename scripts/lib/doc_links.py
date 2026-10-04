#!/usr/bin/env python3
"""Rewrite one repo's README and docs/** links for its mount on the docs site (MIP-0074 Appendix A).

scripts/lib/doc_links.py --repo DIR --name NAME --sha SHA --out DIR [--umbrella]
    [--exclude PATTERN]... [--submodule NAME=SHA]...
scripts/lib/doc_links.py --links FILE...   # FILE<TAB>link per link outside code
scripts/lib/doc_links.py --self-test
"""

from __future__ import annotations

import argparse
import contextlib
import fnmatch
import io
import posixpath
import re
import shutil
import sys
import tempfile
import unicodedata
from dataclasses import dataclass, field
from pathlib import Path
from urllib.parse import unquote

# Fences as docs-lint (marola-devkit scripts/docs_lint.py) sees them, so the two never disagree on
# what is code: any indent, and only a bare fence line closes.
FENCE = re.compile(r"^\s*(`{3,}|~{3,})")
# A code span may wrap lines but never crosses a blank line (CommonMark), so a lone ` can't mask
# later paragraphs.
CODE_SPAN = re.compile(r"(?<!`)(`+)(?!`)(?:(?!\n[ \t]*\n).)*?(?<!`)\1(?!`)", re.S)
INLINE = re.compile(
    r"(!?)\[((?:[^\[\]]|\[[^\[\]]*\])*)\]"
    r"\(\s*(<[^>]*>|[^)\s]+)((?:\s+(?:\"[^\"]*\"|'[^']*'|\([^)]*\)))?\s*)\)"
)
REFDEF = re.compile(r"^( {0,3}\[[^\]]+\]:[ \t]*)(<[^>]*>|\S+)", re.M)
MASK = re.compile(r"\0(\d+)\0")
HTML_TAG = re.compile(r"<[A-Za-z][A-Za-z0-9-]*\s[^<>]*>")
HTML_ATTR = re.compile(r"(?<=\s)(href|src)(\s*=\s*)([\"'])([^\"']*)\3", re.I)
SCHEME = re.compile(r"^(?:[a-zA-Z][a-zA-Z0-9+.-]*:|//)")
HEADING = re.compile(r"^ {0,3}#{1,6}[ \t]+(.*?)(?:[ \t]+#+)?[ \t]*$")
IMAGE_SUFFIXES = {".png", ".jpg", ".jpeg", ".gif", ".svg", ".webp", ".avif", ".ico"}
# A GitHub permalink sha (7-40 hex, abbreviated or full) or a version tag: already pinned, so left
# exactly as written, #L anchors included, with no existence check against ctx.sha.
PINNED_REF = re.compile(r"[0-9a-f]{7,40}|v\d[\w.\-]*")


@dataclass(frozen=True)
class Context:
    """The mounted repo: `repo` is checked out at `sha`."""

    name: str
    sha: str
    umbrella: bool = False
    # mkdocs.yml's patterns are relative to the site root; matched against the repo's docs/ they
    # agree only while unanchored (benchmarks/), which is all mkdocs.yml has.
    exclude_docs: tuple[str, ...] = ()
    # Umbrella only; each submodule must be checked out at its sha under repo/<name>, where the
    # existence checks read it.
    submodule_shas: dict[str, str] = field(default_factory=dict)


def _fenced(text: str) -> list[tuple[bool, str]]:
    """Split into (is_fence, chunk)."""
    out: list[tuple[bool, str]] = []
    prose: list[str] = []
    fence = ""
    for line in text.splitlines(keepends=True):
        m = FENCE.match(line)
        if fence:
            out.append((True, line))
            if re.match(rf"^\s*{fence[0]}{{{len(fence)},}}\s*$", line):
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


def _excluded(rel: str, patterns: tuple[str, ...], is_dir: bool) -> bool:
    """A gitignore-style subset: `*` globs, a trailing / for directories, a / inside anchors."""
    parts = rel.split("/")
    for pattern in patterns:
        anchored = "/" in pattern.rstrip("/")
        dir_only, pattern = pattern.endswith("/"), pattern.strip("/")
        for i in range(1, len(parts) + 1):
            if not pattern or (dir_only and i == len(parts) and not is_dir):
                continue
            if fnmatch.fnmatchcase("/".join(parts[:i]) if anchored else parts[i - 1], pattern):
                return True
    return False


def _docs_mount(rel: str, ctx: Context) -> str:
    """Where docs/<rel> lands, relative to the mount."""
    if ctx.umbrella and (rel == "MIPs" or rel.startswith("MIPs/")):
        return "6-" + rel
    return rel


def _out_path(source: str, ctx: Context) -> str:
    return "index.md" if source == "README.md" else _docs_mount(source.removeprefix("docs/"), ctx)


def rewrite(text: str, source: str, repo: Path, *, ctx: Context) -> tuple[str, list[str]]:
    """Rewrite `text`, the repo-relative file `source` (README.md or docs/**), for the mount.

    `repo` is the checkout at `ctx.sha`. Returns the new text and one error per link Appendix A
    refuses; a refused link is left as written.
    """
    errors: list[str] = []
    anchors: dict[str, str] | None = None
    own_github = re.compile(
        rf"https://github\.com/marola-dev/{re.escape(ctx.name)}/(?:blob|tree)/([^?#]+)(.*)"
    )

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

        def github(name: str, sha: str, checkout: Path, path: str, suffix: str) -> str:
            if not (checkout / unquote(path)).exists():
                return fail(f"missing at {sha}")
            kind = "tree" if (checkout / unquote(path)).is_dir() else "blob"
            return f"https://github.com/marola-dev/{name}/{kind}/{sha}/{path}{suffix}"

        if not link:
            return link
        if SCHEME.match(link):
            m = own_github.fullmatch(link)
            if not m or PINNED_REF.fullmatch(m.group(1).split("/")[0]):
                return link
            # A branch may hold slashes (mip-NNNN/k-*): like GitHub, take the shortest ref whose
            # remaining path exists. A slashed ref with no path at all is refused as ambiguous.
            parts = m.group(1).split("/")
            for i in range(1, len(parts) + 1):
                rest = "/".join(parts[i:])
                if (rest or i == 1) and (repo / unquote(rest)).exists():
                    ref = "/".join(parts[:i])
                    return link[: m.start(1)] + ctx.sha + link[m.start(1) + len(ref) :]
            return fail(f"missing at {ctx.sha}")
        path, hash_, anchor = link.partition("#")
        path, qmark, query = path.partition("?")
        query = qmark + query
        if path.startswith("/"):
            return fail("root-absolute; link the page relatively")
        if not path:
            if hash_ and source == "README.md":
                anchor = readme_anchor(link, anchor) or anchor
            return query + hash_ + anchor
        resolved = posixpath.normpath(posixpath.join(posixpath.dirname(source), path))
        if resolved == ".." or resolved.startswith("../"):
            return fail("leaves the repo")
        is_dir = path.endswith("/") or (repo / unquote(resolved)).is_dir()
        image = image or posixpath.splitext(resolved)[1].lower() in IMAGE_SUFFIXES
        head, _, rest = resolved.partition("/")
        if ctx.umbrella and head in ctx.submodule_shas:
            if rest not in ("", "README.md", "docs") and not rest.startswith("docs/"):
                if image:
                    return fail("an image outside docs/; move it under docs/")
                return github(
                    head, ctx.submodule_shas[head], repo / head, rest, query + hash_ + anchor
                )
            rel = "" if rest == "README.md" else rest.removeprefix("docs").removeprefix("/")
            mount = f"5-Repos/{head}/" + (
                f"{rel}/index.md" if rel and is_dir else rel or "index.md"
            )
        elif resolved != "docs" and not resolved.startswith("docs/"):
            if image:
                return fail("an image outside docs/; move it under docs/")
            if resolved != "README.md":
                return github(ctx.name, ctx.sha, repo, resolved, query + hash_ + anchor)
            mount = "index.md"
            if hash_:
                anchor = readme_anchor(link, anchor) or anchor
        else:
            rel = resolved.removeprefix("docs").removeprefix("/")
            if rel and _excluded(rel, ctx.exclude_docs, is_dir):
                if image:
                    return fail("an image under exclude_docs; move it to a published docs/ path")
                return github(ctx.name, ctx.sha, repo, resolved, query + hash_ + anchor)
            if rel == "index.md":
                return fail("docs/index.md is banned (MIP-0074 D1); link docs/ for the landing")
            if not rel:
                mount = "index.md"
            elif is_dir:
                # adr/index.md is generated by prepare-docs, so it never exists in the repo.
                if rel != "adr" and not (repo / resolved / "index.md").is_file():
                    return fail("a directory with no index.md")
                mount = f"{_docs_mount(rel, ctx)}/index.md"
            else:
                mount = _docs_mount(rel, ctx)
        start = posixpath.dirname(_out_path(source, ctx)) or "."
        return posixpath.relpath(mount, start) + query + hash_ + anchor

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

        def attr(m: re.Match[str]) -> str:
            name, eq, quote, link = m.groups()
            return f"{name}{eq}{quote}{target(link, name.lower() == 'src')}{quote}"

        chunk = HTML_TAG.sub(lambda t: HTML_ATTR.sub(attr, t.group(0)), chunk)
        return MASK.sub(lambda m: spans[int(m.group(1))], chunk)

    return "".join(c if is_fence else prose(c) for is_fence, c in _fenced(text)), errors


def links(text: str) -> list[str]:
    """Every reference definition, then inline link, target outside code, as written."""
    found: list[str] = []

    def inline(chunk: str) -> None:
        for m in INLINE.finditer(chunk):
            found.append(m.group(3).removeprefix("<").removesuffix(">"))
            inline(m.group(2))

    for is_fence, chunk in _fenced(text):
        if not is_fence:
            chunk = CODE_SPAN.sub("", chunk)
            found += [
                m.group(2).removeprefix("<").removesuffix(">") for m in REFDEF.finditer(chunk)
            ]
            inline(chunk)
    return found


def mount(repo: Path, out: Path, ctx: Context) -> list[str]:
    """Write README.md as out/index.md and docs/** beside it, skipping exclude_docs; the errors."""
    errors: list[str] = []
    docs = sorted(p.relative_to(repo).as_posix() for p in (repo / "docs").rglob("*") if p.is_file())
    sources = ["README.md", *docs]
    for source in sources:
        if source != "README.md" and _excluded(source[len("docs/") :], ctx.exclude_docs, False):
            continue
        dest = out / _out_path(source, ctx)
        dest.parent.mkdir(parents=True, exist_ok=True)
        if source.endswith(".md"):
            text, errs = rewrite((repo / source).read_text(encoding="utf-8"), source, repo, ctx=ctx)
            dest.write_text(text, encoding="utf-8")
            errors += errs
        else:
            shutil.copyfile(repo / source, dest)
    return errors


def self_test() -> int:
    fails = 0

    def case(name, got, want):
        nonlocal fails
        if got == want:
            print(f"  ok   {name}")
        else:
            fails += 1
            print(f"  FAIL {name} — got {got!r}, want {want!r}")

    def refused(source, link, reason):
        return [f"{source}: {link}: {reason}"]

    sha, app_sha = "a" * 40, "b" * 40
    gh = f"https://github.com/marola-dev/marola-ml/blob/{sha}"
    tree = f"https://github.com/marola-dev/marola-ml/tree/{sha}"

    with tempfile.TemporaryDirectory() as tmp:
        repo = Path(tmp) / "marola-ml"
        for f in (
            "docs/x.md",
            "docs/sub/index.md",
            "docs/guide/page.md",
            "docs/img/a.png",
            "docs/benchmarks/2026-09-05.md",
            "docs/benchmarks/plot.png",
            "AGENTS.md",
            "scripts/x.sh",
            "knowledge/tides.md",
        ):
            (repo / f).parent.mkdir(parents=True, exist_ok=True)
            (repo / f).write_text("# t\n", encoding="utf-8")
        (repo / "docs/empty").mkdir()
        (repo / "README.md").write_text(
            "# Repo\n\n## 🌊 Run it!\n\n```\n## Not a heading\n```\n", encoding="utf-8"
        )
        ctx = Context("marola-ml", sha, exclude_docs=("benchmarks/", "superpowers/"))

        def page(text, source):
            return rewrite(text, source, repo, ctx=ctx)

        def readme(text):
            return page(text, "README.md")

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
        case(
            "docs_index_link_fails",
            readme("[i](docs/index.md)"),
            (
                "[i](docs/index.md)",
                refused(
                    "README.md",
                    "docs/index.md",
                    "docs/index.md is banned (MIP-0074 D1); link docs/ for the landing",
                ),
            ),
        )
        case(
            "dir_link_to_index",
            readme("[s](docs/sub/) [adr](docs/adr/)"),
            ("[s](sub/index.md) [adr](adr/index.md)", []),
        )
        case(
            "dir_link_without_index_fails",
            readme("[e](docs/empty/)"),
            (
                "[e](docs/empty/)",
                refused("README.md", "docs/empty/", "a directory with no index.md"),
            ),
        )
        case(
            "image_md_and_html_rewritten",
            readme('![l](docs/img/a.png) <img alt="l" src="docs/img/a.png">'),
            ('![l](img/a.png) <img alt="l" src="img/a.png">', []),
        )
        outside = "an image outside docs/; move it under docs/"
        case(
            "image_outside_docs_fails",
            [readme("![l](assets/logo.png)"), readme('<img src="logo.svg">')],
            [
                ("![l](assets/logo.png)", refused("README.md", "assets/logo.png", outside)),
                ('<img src="logo.svg">', refused("README.md", "logo.svg", outside)),
            ],
        )
        off_site = "an image under exclude_docs; move it to a published docs/ path"
        case(
            "image_outside_docs_fails",
            [
                readme("![p](docs/benchmarks/plot.png)"),
                readme('<img src="docs/benchmarks/plot.png">'),
            ],
            [
                (
                    "![p](docs/benchmarks/plot.png)",
                    refused("README.md", "docs/benchmarks/plot.png", off_site),
                ),
                (
                    '<img src="docs/benchmarks/plot.png">',
                    refused("README.md", "docs/benchmarks/plot.png", off_site),
                ),
            ],
        )
        case(
            "docs_page_parent_readme_to_index",
            [
                page("[r](../README.md) [a](../README.md#repo)", "docs/x.md"),
                page("[r](../../README.md)", "docs/guide/page.md"),
            ],
            [("[r](index.md) [a](index.md#repo)", []), ("[r](../index.md)", [])],
        )
        case(
            "readme_anchor_emoji_slug",
            [
                page("[r](../README.md#-run-it)", "docs/x.md"),
                readme("[r](#-run-it) [s](#run-it)"),
                readme("[n](#not-a-heading)"),
            ],
            [
                ("[r](index.md#run-it)", []),
                ("[r](#run-it) [s](#run-it)", []),
                (
                    "[n](#not-a-heading)",
                    refused(
                        "README.md",
                        "#not-a-heading",
                        "no README heading has the anchor #not-a-heading",
                    ),
                ),
            ],
        )
        api = "/api/scala/core/marola.html"
        case(
            "root_absolute_fails",
            page(f"[api]({api})", "docs/x.md"),
            (
                f"[api]({api})",
                refused("docs/x.md", api, "root-absolute; link the page relatively"),
            ),
        )
        case(
            "parent_escape_fails",
            [readme("[o](../other/x.md)"), page("[o](../../x.md)", "docs/x.md")],
            [
                ("[o](../other/x.md)", refused("README.md", "../other/x.md", "leaves the repo")),
                ("[o](../../x.md)", refused("docs/x.md", "../../x.md", "leaves the repo")),
            ],
        )
        case(
            "reference_definition_and_html_href",
            readme('[x]: docs/x.md "Title"\n<a href="docs/x.md#a">x</a> <a href=\'docs/\'>d</a>\n'),
            ('[x]: x.md "Title"\n<a href="x.md#a">x</a> <a href=\'index.md\'>d</a>\n', []),
        )
        prose_attr = 'Set href="/abs" in the tag.\n'
        case("reference_definition_and_html_href", readme(prose_attr), (prose_attr, []))
        fenced = "```md\n[a](docs/x.md)\n```\n~~~\n![i](/abs.png)\n~~~\n"
        case(
            "code_span_and_fence_untouched",
            readme("`[a](docs/x.md)` [`b`](docs/x.md) ``[c](/abs)``\n" + fenced),
            ("`[a](docs/x.md)` [`b`](x.md) ``[c](/abs)``\n" + fenced, []),
        )
        stray = "Type ` to start.\n\n[a](docs/x.md) and [b](/abs)\n\nlater ` here\n"
        case(
            "code_span_and_fence_untouched",
            readme(stray)[0],
            stray.replace("(docs/x.md)", "(x.md)"),
        )
        case("code_span_and_fence_untouched", len(readme(stray)[1]), 1)
        # docs-lint closes a fence only on a bare fence line; ```bash inside it is still code.
        nested = "```\n```bash\n[a](/abs)\n```\n"
        case("code_span_and_fence_untouched", readme(nested), (nested, []))

        case(
            "excluded_path_to_blob_at_sha",
            [
                readme("[b](docs/benchmarks/2026-09-05.md#r) [d](docs/benchmarks/)"),
                page("[b](benchmarks/2026-09-05.md)", "docs/x.md"),
            ],
            [
                (
                    f"[b]({gh}/docs/benchmarks/2026-09-05.md#r) [d]({tree}/docs/benchmarks)",
                    [],
                ),
                (f"[b]({gh}/docs/benchmarks/2026-09-05.md)", []),
            ],
        )
        case(
            "code_file_to_blob_at_sha",
            [
                readme("[a](AGENTS.md) [s](./scripts/x.sh#L3) [p](AGENTS.md?plain=1#L5)"),
                page('[a](../AGENTS.md) <a href="../scripts/x.sh">s</a>', "docs/x.md"),
            ],
            [
                (
                    f"[a]({gh}/AGENTS.md) [s]({gh}/scripts/x.sh#L3) [p]({gh}/AGENTS.md?plain=1#L5)",
                    [],
                ),
                (f'[a]({gh}/AGENTS.md) <a href="{gh}/scripts/x.sh">s</a>', []),
            ],
        )
        case(
            "code_dir_to_tree_at_sha",
            readme("[k](knowledge/) [k2](knowledge) [s](scripts/)"),
            (f"[k]({tree}/knowledge) [k2]({tree}/knowledge) [s]({tree}/scripts)", []),
        )
        own = "https://github.com/marola-dev/marola-ml"
        case(
            "own_github_main_link_pinned",
            readme(
                f"[a]({own}/blob/main/AGENTS.md#x) [t]({own}/tree/feature-x/knowledge)\n"
                f"[r]: {own}/blob/main/scripts/x.sh?plain=1\n"
            ),
            (
                f"[a]({gh}/AGENTS.md#x) [t]({tree}/knowledge)\n[r]: {gh}/scripts/x.sh?plain=1\n",
                [],
            ),
        )
        # GitHub splits ref/path at the shortest ref whose remainder exists; branches here are
        # routinely mip-NNNN/k-*.
        case(
            "own_github_main_link_pinned",
            readme(
                f"[t]({own}/tree/mip-0074/x/knowledge) [a]({own}/blob/mip-0074/k-y/AGENTS.md#L1)"
            ),
            (f"[t]({tree}/knowledge) [a]({gh}/AGENTS.md#L1)", []),
        )
        short_sha = "7ba0458"
        case(
            "own_github_main_link_pinned",
            readme(f"[a]({own}/blob/{short_sha}/.claude/hooks/stop-gate.sh#L23)"),
            (f"[a]({own}/blob/{short_sha}/.claude/hooks/stop-gate.sh#L23)", []),
        )
        tag = "v0.2.4"
        case(
            "own_github_main_link_pinned",
            readme(f"[a]({own}/blob/{tag}/.claude/hooks/stop-gate.sh#L23)"),
            (f"[a]({own}/blob/{tag}/.claude/hooks/stop-gate.sh#L23)", []),
        )
        # Locks in trailing-slash handling on the still-rewritten (unpinned branch) path, so the
        # pinned-ref bypass above can't be confused with it.
        case(
            "own_github_main_link_pinned",
            readme(f"[t]({own}/tree/main/) [k]({own}/tree/main/knowledge/)"),
            (f"[t]({tree}/) [k]({tree}/knowledge/)", []),
        )
        others = (
            "[a](https://github.com/marola-dev/marola-app/blob/main/README.md) "
            "[b](https://github.com/marola-dev/marola-ml-old/blob/main/AGENTS.md) "
            f"[c]({own}/blob/{'c' * 40}/AGENTS.md)"
        )
        case("other_repo_github_link_untouched", readme(others), (others, []))
        absolute = (
            "[d](https://docs.marola.dev/5-Repos/marola-ml/) [m](mailto:a@b) "
            f"[h](http://x.example/AGENTS.md) [i]({own}/issues/3) [r]({own})"
        )
        case("absolute_urls_untouched", readme(absolute), (absolute, []))
        missing = "missing at " + sha
        case(
            "missing_path_fails",
            [
                readme("[m](scripts/nope.sh)"),
                readme("[m](docs/benchmarks/nope.md)"),
                readme(f"[m]({own}/blob/main/nope.md)"),
            ],
            [
                ("[m](scripts/nope.sh)", refused("README.md", "scripts/nope.sh", missing)),
                (
                    "[m](docs/benchmarks/nope.md)",
                    refused("README.md", "docs/benchmarks/nope.md", missing),
                ),
                (
                    f"[m]({own}/blob/main/nope.md)",
                    refused("README.md", f"{own}/blob/main/nope.md", missing),
                ),
            ],
        )

        umbrella = Path(tmp) / "marola"
        for f in (
            "docs/PHASES.md",
            "docs/3-Working/x.md",
            "docs/MIPs/MIP-0001.md",
            "docs/MIPs/MIP-0002.md",
            "marola-app/README.md",
            "marola-app/docs/x.md",
            "marola-app/core/A.scala",
            "marola-app/core/l.svg",
            "marola-app/assets/l.png",
        ):
            (umbrella / f).parent.mkdir(parents=True, exist_ok=True)
            (umbrella / f).write_text("# t\n", encoding="utf-8")
        uctx = Context("marola", sha, umbrella=True, submodule_shas={"marola-app": app_sha})

        def upage(text, source):
            return rewrite(text, source, umbrella, ctx=uctx)

        case(
            "umbrella_submodule_docs_to_site_path",
            [
                upage(
                    "[a](marola-app/README.md) [b](marola-app/docs/x.md#s) [c](marola-app/docs/)",
                    "README.md",
                ),
                upage("[b](../../marola-app/docs/x.md)", "docs/3-Working/x.md"),
            ],
            [
                (
                    "[a](5-Repos/marola-app/index.md) [b](5-Repos/marola-app/x.md#s) "
                    "[c](5-Repos/marola-app/index.md)",
                    [],
                ),
                ("[b](../5-Repos/marola-app/x.md)", []),
            ],
        )
        app = "https://github.com/marola-dev/marola-app"
        case(
            "umbrella_submodule_code_to_blob",
            [
                upage("[c](marola-app/core/A.scala#L2) [d](marola-app/core/)", "README.md"),
                upage("[m](marola-app/core/Nope.scala)", "README.md"),
            ],
            [
                (f"[c]({app}/blob/{app_sha}/core/A.scala#L2) [d]({app}/tree/{app_sha}/core)", []),
                (
                    "[m](marola-app/core/Nope.scala)",
                    refused("README.md", "marola-app/core/Nope.scala", "missing at " + app_sha),
                ),
            ],
        )
        case(
            "image_outside_docs_fails",
            [
                upage("![l](marola-app/assets/l.png)", "README.md"),
                upage('<img src="marola-app/core/l.svg">', "README.md"),
            ],
            [
                (
                    "![l](marola-app/assets/l.png)",
                    refused("README.md", "marola-app/assets/l.png", outside),
                ),
                (
                    '<img src="marola-app/core/l.svg">',
                    refused("README.md", "marola-app/core/l.svg", outside),
                ),
            ],
        )
        case(
            "mips_link_to_6_mips",
            [
                upage("[m](docs/MIPs/MIP-0001.md#a)", "README.md"),
                upage("[m](MIPs/MIP-0001.md)", "docs/PHASES.md"),
                upage("[p](../PHASES.md) [o](MIP-0002.md)", "docs/MIPs/MIP-0001.md"),
            ],
            [
                ("[m](6-MIPs/MIP-0001.md#a)", []),
                ("[m](6-MIPs/MIP-0001.md)", []),
                ("[p](../PHASES.md) [o](MIP-0002.md)", []),
            ],
        )

        case(
            "links_outside_code",
            links(
                "[a](x.md#s) [![i](img/a.png)](<y z.md>)\n[r]: ref.md\n"
                "`[c](span.md)` and `wrapped\n[d](span2.md)`\n\n````\n```bash\n[e](f.md)\n```\n````\n"
            ),
            ["ref.md", "x.md#s", "y z.md", "img/a.png"],
        )

        out = Path(tmp) / "out"
        args = ["--repo", str(repo), "--name", "marola-ml", "--sha", sha, "--out", str(out)]
        err = io.StringIO()
        with contextlib.redirect_stderr(err):
            rc = main([*args, "--exclude", "benchmarks/"])
        case(
            "mount_writes_landing_and_docs",
            (
                rc,
                (out / "index.md").read_text(encoding="utf-8").splitlines()[0],
                (out / "img/a.png").is_file(),
                (out / "sub/index.md").is_file(),
                (out / "benchmarks").exists(),
            ),
            (0, "# Repo", True, True, False),
        )
        (repo / "docs/bad.md").write_text("[x](/abs)\n", encoding="utf-8")
        err = io.StringIO()
        with contextlib.redirect_stderr(err):
            rc = main(args)
        case(
            "mount_reports_refused_link",
            (rc, err.getvalue()),
            (
                1,
                "doc_links: marola-ml: docs/bad.md: /abs: root-absolute; link the page relatively\n",
            ),
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
    ap.add_argument("--repo", type=Path)
    ap.add_argument("--name")
    ap.add_argument("--sha")
    ap.add_argument("--out", type=Path)
    ap.add_argument("--umbrella", action="store_true")
    ap.add_argument("--exclude", action="append", default=[])
    ap.add_argument("--submodule", action="append", default=[], metavar="NAME=SHA")
    ap.add_argument("--links", nargs="+", type=Path, metavar="FILE")
    args = ap.parse_args(argv)
    if args.self_test:
        return self_test()
    if args.links:
        for f in args.links:
            for link in links(f.read_text(encoding="utf-8")):
                print(f"{f}\t{link}")
        return 0
    if not (args.repo and args.name and args.sha and args.out):
        ap.print_help()
        return 2
    ctx = Context(
        args.name,
        args.sha,
        umbrella=args.umbrella,
        exclude_docs=tuple(args.exclude),
        submodule_shas=dict(s.split("=", 1) for s in args.submodule),
    )
    errors = mount(args.repo, args.out, ctx)
    for error in errors:
        print(f"doc_links: {args.name}: {error}", file=sys.stderr)
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
