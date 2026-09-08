#!/usr/bin/env python3
"""Make generated API docs obey the site's `script-src 'self'` CSP (MIP-0044 §5.6).

scaladoc emits four third-party `<script src="https://...">` tags on every page — dagre-d3,
graphlib-dot, d3 and scastie — and one inline `var pathToRoot = "..."`. A site that advertises no
third-party requests cannot serve them. Losing dagre/d3 loses inheritance diagrams and losing
scastie loses "run this snippet"; neither is used by this codebase's docs.

`pathToRoot` is kept, moved to a `data-path-to-root` attribute on <body> that scaladoc's own
scripts read via a tiny same-origin shim, so relative links still resolve without an inline script.

    scripts/strip_external_scripts.py out/scala out/python
    scripts/strip_external_scripts.py --self-test
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

EXTERNAL_SCRIPT = re.compile(
    r"""<script[^>]*\ssrc=["']https?://[^"']*["'][^>]*>\s*</script>""", re.I
)
PATH_TO_ROOT = re.compile(
    r"""<script[^>]*>\s*var\s+pathToRoot\s*=\s*["']([^"']*)["']\s*;?\s*</script>""", re.I
)
SHIM = '<script src="{root}scripts/path-to-root.js"></script>'


def strip(html: str) -> tuple[str, int]:
    """Remove third-party scripts and inline pathToRoot; return the new html and how many went."""
    removed = 0
    root = ""
    m = PATH_TO_ROOT.search(html)
    if m:
        root = m.group(1)
        html = PATH_TO_ROOT.sub("", html, count=1)
        html = re.sub(r"<body([^>]*)>", rf'<body\1 data-path-to-root="{root}">', html, count=1)
        html = html.replace("</head>", SHIM.format(root=root) + "</head>", 1)

    def drop(_match: re.Match[str]) -> str:
        nonlocal removed
        removed += 1
        return ""

    return EXTERNAL_SCRIPT.sub(drop, html), removed


SHIM_JS = """// Restores the `pathToRoot` global scaladoc's own scripts expect, without an inline
// <script> — read from <body data-path-to-root>, so the page keeps script-src 'self'. MIP-0044.
var pathToRoot = document.body.getAttribute("data-path-to-root") || "";
"""


def check(dirs: list[Path]) -> int:
    """Report any real external <script src> left in the tree, using EXTERNAL_SCRIPT itself.

    The workflow used to grep for the looser `src="http`, which matched this file's own
    documentation once pdoc rendered it — escaped text about script tags, not a script tag.
    """
    offenders = [
        f
        for d in dirs
        if d.is_dir()
        for f in d.rglob("*.html")
        if EXTERNAL_SCRIPT.search(f.read_text(encoding="utf-8", errors="ignore"))
    ]
    for f in offenders[:5]:
        print(f"external script src survives in {f}", file=sys.stderr)
    if offenders:
        print(f"{len(offenders)} file(s) would violate the site CSP", file=sys.stderr)
        return 1
    print("no external script src in the generated tree")
    return 0


def process(dirs: list[Path]) -> int:
    total_files = total_removed = 0
    for d in dirs:
        if not d.is_dir():
            print(f"strip_external_scripts: no such directory: {d}", file=sys.stderr)
            return 1
        (d / "scripts").mkdir(parents=True, exist_ok=True)
        (d / "scripts" / "path-to-root.js").write_text(SHIM_JS, encoding="utf-8")
        for f in d.rglob("*.html"):
            html = f.read_text(encoding="utf-8", errors="ignore")
            new, removed = strip(html)
            if new != html:
                f.write_text(new, encoding="utf-8")
            total_files += 1
            total_removed += removed
    print(f"stripped {total_removed} external <script> tags across {total_files} html files")
    return 0


def self_test() -> int:
    fails = 0

    def ok(got, want, label):
        nonlocal fails
        if got == want:
            print(f"  ok   {label}")
        else:
            fails += 1
            print(f"  FAIL {label} — got {got!r}, want {want!r}")

    page = (
        '<html><head><script type="text/javascript" src="https://d3js.org/d3.v6.min.js"></script>'
        '<script src="https://scastie.scala-lang.org/embedded.js"></script>'
        '<script src="scripts/ux.js"></script></head>'
        '<body class="theme"><script>var pathToRoot = "../";</script>hi</body></html>'
    )
    out, removed = strip(page)
    ok(removed, 2, "both third-party scripts are removed")
    ok("d3js.org" in out, False, "no https:// script src survives")
    ok('src="scripts/ux.js"' in out, True, "the doc's own same-origin scripts are kept")
    ok("var pathToRoot" in out, False, "the inline pathToRoot script is gone")
    ok('data-path-to-root="../"' in out, True, "pathToRoot is preserved as a data attribute")
    ok('class="theme"' in out, True, "existing body attributes survive the rewrite")
    ok(out.count("path-to-root.js"), 1, "the same-origin shim is linked once")
    ok(strip("<html><body>plain</body></html>")[1], 0, "a page with no scripts is untouched")
    # a protocol-relative or http src must go too
    ok(
        strip('<script src="http://x.example/a.js"></script>')[1],
        1,
        "plain http src is removed as well",
    )
    import tempfile

    with tempfile.TemporaryDirectory() as tmp:
        d = Path(tmp)
        (d / "clean.html").write_text("<html><body>no scripts</body></html>", encoding="utf-8")
        ok(check([d]), 0, "--check passes a tree with no external script")
        # What pdoc emits for this very file: text *about* script tags, with < escaped.
        (d / "pdoc.html").write_text(
            '<html><body><code>EXTERNAL_SCRIPT = &lt;script src="https://x/a.js"&gt;</code>'
            "<p>strips &lt;script src=&quot;https://...&quot;&gt; tags</p></body></html>",
            encoding="utf-8",
        )
        ok(check([d]), 0, "documentation about script tags is not mistaken for one")
        (d / "real.html").write_text(
            '<html><head><script src="https://d3js.org/d3.v6.min.js"></script></head></html>',
            encoding="utf-8",
        )
        ok(check([d]), 1, "a real external script tag is still caught")

    if fails:
        print(f"strip_external_scripts self-test: {fails} failure(s)", file=sys.stderr)
        return 1
    print("strip_external_scripts self-test: ok")
    return 0


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    ap.add_argument("dirs", nargs="*", type=Path)
    ap.add_argument("--self-test", action="store_true")
    ap.add_argument("--check", action="store_true", help="report survivors, change nothing")
    args = ap.parse_args(argv)
    if args.self_test:
        return self_test()
    if not args.dirs:
        ap.print_help()
        return 2
    return check(args.dirs) if args.check else process(args.dirs)


if __name__ == "__main__":
    sys.exit(main())
