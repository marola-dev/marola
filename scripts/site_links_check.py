#!/usr/bin/env python3
"""Check that every docs.marola.dev link in a built site resolves, and that the previous live
sitemap's URLs still land somewhere, so a page move can't silently drop a URL (MIP-0074 §5.3, §7).

Stdlib only, no network: a built site is already on disk, and the "old" sitemap is a file the
caller fetched beforehand (docs.yml curls the live one before a build that might change it).

    scripts/site_links_check.py mkdocs/generated-docs
    scripts/site_links_check.py mkdocs/generated-docs --old-sitemap old-sitemap.xml
    scripts/site_links_check.py --self-test
"""

from __future__ import annotations

import argparse
import re
import sys
import tempfile
from pathlib import Path
from urllib.parse import urlsplit

SITE = "https://docs.marola.dev"
HREF = re.compile(r'(?<=\s)href\s*=\s*["\'](' + re.escape(SITE) + r'[^"\']*)["\']', re.I)
LOC = re.compile(r"<loc>\s*([^<\s]+)\s*</loc>", re.I)
# mkdocs/overrides/404.html's inline array (MIP-0074 Appendix B, task 3's file) — read, not owned.
MOVES_BLOCK = re.compile(r"\bmoves\s*=\s*\[(.*?)\]\s*\.\s*sort", re.S)
MOVE_PAIR = re.compile(r"""\[\s*(['"])(.*?)\1\s*,\s*(['"])(.*?)\3\s*\]""")


def forward_prefixes(overrides_404: Path) -> list[str]:
    """The old-URL prefixes overrides/404.html forwards, longest first — same tie-break its own
    inline script uses, so a path under two prefixes matches the narrower one."""
    html = overrides_404.read_text(encoding="utf-8")
    block = MOVES_BLOCK.search(html)
    body = block.group(1) if block else ""
    prefixes = [m.group(2) for m in MOVE_PAIR.finditer(body)]
    return sorted(prefixes, key=len, reverse=True)


def resolve(url_path: str, site_dir: Path) -> Path | None:
    """The file in `site_dir` that `url_path` (directory-url style) serves, if any."""
    rel = url_path.lstrip("/")
    if ".." in Path(rel).parts:
        return None
    if not rel or rel.endswith("/"):
        candidates = [site_dir / rel / "index.html"]
    else:
        candidates = [site_dir / rel, site_dir / rel / "index.html"]
    return next((c for c in candidates if c.is_file()), None)


def check_hrefs(site_dir: Path) -> list[str]:
    """Every https://docs.marola.dev/... href under `site_dir` that does not resolve inside it."""
    broken = []
    for page in sorted(site_dir.rglob("*.html")):
        html = page.read_text(encoding="utf-8", errors="ignore")
        for m in HREF.finditer(html):
            url = m.group(1)
            if resolve(urlsplit(url).path, site_dir) is None:
                broken.append(f"{page.relative_to(site_dir)}: {url}")
    return broken


def check_old_sitemap(site_dir: Path, sitemap: Path, prefixes: list[str]) -> list[str]:
    """Every <loc> of the old `sitemap` not now a page, a redirect stub (both just a file in
    `site_dir`) or covered by a 404-forward prefix."""
    dropped = []
    xml = sitemap.read_text(encoding="utf-8", errors="ignore")
    for loc in LOC.findall(xml):
        path = urlsplit(loc).path
        if resolve(path, site_dir) is not None:
            continue
        if any(path.startswith(p) for p in prefixes):
            continue
        dropped.append(loc)
    return dropped


def self_test() -> int:
    fails = 0

    def ok(cond: bool, label: str) -> None:
        nonlocal fails
        if cond:
            print(f"  ok   {label}")
        else:
            fails += 1
            print(f"  FAIL {label}")

    moves_404 = (
        "<script>\nvar moves = [\n"
        "  ['/repos/marola-ml/api/', '/5-Repos/marola-ml/api-docs/python/'],\n"
        "  ['/repos/', '/5-Repos/'],\n"
        "  ['/MIPs/', '/6-MIPs/'],\n"
        "  ['/api/', '/5-Repos/marola-app/api-docs/']\n"
        "].sort(function (a, b) { return b[0].length - a[0].length; });\n</script>"
    )
    with tempfile.TemporaryDirectory() as tmp:
        overrides = Path(tmp) / "404.html"
        overrides.write_text(moves_404, encoding="utf-8")
        prefixes = forward_prefixes(overrides)
        ok(
            prefixes == ["/repos/marola-ml/api/", "/repos/", "/MIPs/", "/api/"],
            "forward_prefixes reads the four Appendix B prefixes out of 404.html, longest first",
        )

    with tempfile.TemporaryDirectory() as tmp:
        site = Path(tmp)
        (site / "a").mkdir()
        (site / "a" / "index.html").write_text("<html>a</html>", encoding="utf-8")
        (site / "index.html").write_text("<html>root</html>", encoding="utf-8")
        (site / "sitemap.xml").write_text("<urlset></urlset>", encoding="utf-8")
        ok(
            resolve("/a/", site) == site / "a" / "index.html",
            "a directory-url path resolves to its index.html",
        )
        ok(
            resolve("/a", site) == site / "a" / "index.html",
            "the same path with no trailing slash still resolves",
        )
        ok(
            resolve("/", site) == site / "index.html",
            "the root path resolves to the site's index.html",
        )
        ok(
            resolve("/sitemap.xml", site) == site / "sitemap.xml",
            "a non-html file resolves by its exact name",
        )
        ok(resolve("/missing/", site) is None, "a path with no file anywhere is not resolved")
        ok(
            resolve("/../../etc/passwd", site) is None,
            "a path that would escape the site is never resolved",
        )

    with tempfile.TemporaryDirectory() as tmp:
        site = Path(tmp)
        (site / "a").mkdir()
        (site / "a" / "index.html").write_text("<html></html>", encoding="utf-8")
        (site / "index.html").write_text(
            '<html><a href="https://docs.marola.dev/a/">a</a>'
            '<a href="https://docs.marola.dev/gone/">gone</a>'
            '<a href="https://example.com/other/">other</a></html>',
            encoding="utf-8",
        )
        broken = check_hrefs(site)
        ok(len(broken) == 1, "only the one broken docs.marola.dev href is reported")
        ok(broken and "gone" in broken[0], "...and it names the offending href")

    with tempfile.TemporaryDirectory() as tmp:
        site = Path(tmp)
        (site / "unchanged").mkdir()
        (site / "unchanged" / "index.html").write_text("<html>still here</html>", encoding="utf-8")
        (site / "moved-old").mkdir()
        (site / "moved-old" / "index.html").write_text(
            '<html><head><meta http-equiv="refresh" content="0; url=../moved-new/"></head></html>',
            encoding="utf-8",
        )
        sitemap = Path(tmp) / "old-sitemap.xml"
        sitemap.write_text(
            "<urlset>"
            "<url><loc>https://docs.marola.dev/unchanged/</loc></url>"
            "<url><loc>https://docs.marola.dev/moved-old/</loc></url>"
            "<url><loc>https://docs.marola.dev/repos/marola-site/</loc></url>"
            "<url><loc>https://docs.marola.dev/gone/</loc></url>"
            "</urlset>",
            encoding="utf-8",
        )
        dropped = check_old_sitemap(site, sitemap, prefixes=["/repos/"])
        ok("https://docs.marola.dev/unchanged/" not in dropped, "an unmoved page resolves")
        ok("https://docs.marola.dev/moved-old/" not in dropped, "a redirect stub resolves")
        ok(
            "https://docs.marola.dev/repos/marola-site/" not in dropped,
            "a 404-forward prefix resolves with no file on disk",
        )
        ok(
            "https://docs.marola.dev/gone/" in dropped,
            "a url with no page, stub or forward is reported dropped",
        )
        ok(len(dropped) == 1, "...and nothing else is wrongly flagged")

    if fails:
        print(f"site_links_check self-test: {fails} failure(s)", file=sys.stderr)
        return 1
    print("site_links_check self-test: ok")
    return 0


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    ap.add_argument(
        "site_dir", nargs="?", type=Path, help="a built site, e.g. mkdocs/generated-docs"
    )
    ap.add_argument(
        "--old-sitemap", type=Path, metavar="FILE", help="the previous live sitemap.xml"
    )
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args(argv)
    if args.self_test:
        return self_test()
    if args.site_dir is None:
        ap.print_help()
        return 2
    if not args.site_dir.is_dir():
        print(f"site_links_check: no such directory: {args.site_dir}", file=sys.stderr)
        return 1

    broken = check_hrefs(args.site_dir)
    dropped: list[str] = []
    if args.old_sitemap:
        overrides = Path(__file__).resolve().parent.parent / "mkdocs" / "overrides" / "404.html"
        prefixes = forward_prefixes(overrides) if overrides.is_file() else []
        dropped = check_old_sitemap(args.site_dir, args.old_sitemap, prefixes)

    for b in broken:
        print(f"broken href: {b}", file=sys.stderr)
    for d in dropped:
        print(f"dropped url: {d}", file=sys.stderr)
    if broken or dropped:
        print(
            f"site_links_check: {len(broken)} broken href(s), {len(dropped)} dropped url(s)",
            file=sys.stderr,
        )
        return 1
    suffix = ", old sitemap ok" if args.old_sitemap else ""
    print(f"site_links_check: ok{suffix}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
