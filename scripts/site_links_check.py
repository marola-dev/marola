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
import contextlib
import io
import re
import sys
import tempfile
from pathlib import Path
from urllib.parse import unquote, urlsplit

SITE = "https://docs.marola.dev"
# The quote is captured once and matched again (`(?P=q)`) so the value must be exactly SITE,
# optionally followed by a `/...` path — not merely start with SITE, which would also take
# https://docs.marola.dev.example/ as this site.
HREF = re.compile(
    r'(?<=\s)href\s*=\s*(?P<q>["\'])(?P<url>' + re.escape(SITE) + r'(?:/[^"\']*)?)(?P=q)', re.I
)
LOC = re.compile(r"<loc>\s*([^<\s]+)\s*</loc>", re.I)
# mkdocs/overrides/404.html's inline array (MIP-0074 Appendix B, task 3's file) — read, not owned.
MOVES_BLOCK = re.compile(r"\bmoves\s*=\s*\[(.*?)\]\s*\.\s*sort", re.S)
MOVE_PAIR = re.compile(r"""\[\s*(['"])(.*?)\1\s*,\s*(['"])(.*?)\3\s*\]""")


def forward_prefixes(overrides_404: Path) -> list[tuple[str, str]]:
    """The old->new URL pairs overrides/404.html forwards, longest old first — same tie-break
    its own inline script uses, so a path under two prefixes matches the narrower one."""
    html = overrides_404.read_text(encoding="utf-8")
    block = MOVES_BLOCK.search(html)
    body = block.group(1) if block else ""
    pairs = [(m.group(2), m.group(4)) for m in MOVE_PAIR.finditer(body)]
    return sorted(pairs, key=lambda pair: len(pair[0]), reverse=True)


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


def check_hrefs(site_dir: Path) -> tuple[int, list[str]]:
    """(how many docs.marola.dev hrefs are under `site_dir`, which of them don't resolve in it)."""
    total = 0
    broken = []
    for page in sorted(site_dir.rglob("*.html")):
        html = page.read_text(encoding="utf-8", errors="ignore")
        for m in HREF.finditer(html):
            total += 1
            url = m.group("url")
            if resolve(unquote(urlsplit(url).path), site_dir) is None:
                broken.append(f"{page.relative_to(site_dir)}: {url}")
    return total, broken


def check_old_sitemap(
    site_dir: Path, sitemap: Path, moves: list[tuple[str, str]]
) -> tuple[int, list[str]]:
    """(how many <loc> the old `sitemap` had, which of them are now dropped — not a page, a
    redirect stub (both just a file in `site_dir`), nor forwarded by a 404-forward prefix to a
    page that still exists there). The count is the caller's signal that the sitemap was real:
    zero must never read as "nothing dropped"."""
    xml = sitemap.read_text(encoding="utf-8", errors="ignore")
    locs = LOC.findall(xml)
    dropped = []
    for loc in locs:
        path = unquote(urlsplit(loc).path)
        if resolve(path, site_dir) is not None:
            continue
        forwarded = None
        for old, new in moves:
            if path.startswith(old):
                forwarded = resolve(new + path[len(old) :], site_dir)
                break
        if forwarded is not None:
            continue
        dropped.append(loc)
    return len(locs), dropped


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
        pairs = forward_prefixes(overrides)
        ok(
            [old for old, _new in pairs] == ["/repos/marola-ml/api/", "/repos/", "/MIPs/", "/api/"],
            "forward_prefixes reads the four Appendix B pairs out of 404.html, longest old first",
        )
        ok(
            dict(pairs)["/MIPs/"] == "/6-MIPs/",
            "...and keeps each old prefix's matching new one",
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
        (site / "a b").mkdir()
        (site / "a b" / "index.html").write_text("<html></html>", encoding="utf-8")
        (site / "index.html").write_text(
            '<html><a href="https://docs.marola.dev/a/">a</a>'
            '<a href="https://docs.marola.dev/gone/">gone</a>'
            '<a href="https://docs.marola.dev/a%20b/">encoded space</a>'
            '<a href="https://docs.marola.dev.example/evil/">not this site</a>'
            '<a href="https://example.com/other/">other</a></html>',
            encoding="utf-8",
        )
        total, broken = check_hrefs(site)
        ok(
            total == 3,
            "only genuine docs.marola.dev hrefs are counted — the lookalike host is not one",
        )
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
        (site / "5-Repos" / "marola-site").mkdir(parents=True)
        (site / "5-Repos" / "marola-site" / "index.html").write_text(
            "<html>new home</html>", encoding="utf-8"
        )
        sitemap = Path(tmp) / "old-sitemap.xml"
        sitemap.write_text(
            "<urlset>"
            "<url><loc>https://docs.marola.dev/unchanged/</loc></url>"
            "<url><loc>https://docs.marola.dev/moved-old/</loc></url>"
            "<url><loc>https://docs.marola.dev/repos/marola-site/</loc></url>"
            "<url><loc>https://docs.marola.dev/repos/marola-ml/</loc></url>"
            "<url><loc>https://docs.marola.dev/gone/</loc></url>"
            "</urlset>",
            encoding="utf-8",
        )
        total, dropped = check_old_sitemap(site, sitemap, moves=[("/repos/", "/5-Repos/")])
        ok(total == 5, "every <loc> in the old sitemap is counted")
        ok("https://docs.marola.dev/unchanged/" not in dropped, "an unmoved page resolves")
        ok("https://docs.marola.dev/moved-old/" not in dropped, "a redirect stub resolves")
        ok(
            "https://docs.marola.dev/repos/marola-site/" not in dropped,
            "a 404-forward prefix whose target page still exists resolves",
        )
        ok(
            "https://docs.marola.dev/repos/marola-ml/" in dropped,
            "...but one whose target page is gone is reported dropped, not silently covered",
        )
        ok(
            "https://docs.marola.dev/gone/" in dropped,
            "a url with no page, stub or forward at all is reported dropped",
        )
        ok(len(dropped) == 2, "...and nothing else is wrongly flagged")

    with tempfile.TemporaryDirectory() as tmp:
        site = Path(tmp) / "site"
        site.mkdir()
        (site / "index.html").write_text("<html></html>", encoding="utf-8")
        garbage = Path(tmp) / "garbage-sitemap.xml"
        garbage.write_text("<html>404 Not Found</html>", encoding="utf-8")
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf), contextlib.redirect_stderr(buf):
            code = main([str(site), "--old-sitemap", str(garbage)])
        ok(
            code == 1,
            "an old sitemap with zero <loc> entries (an error page, a captive portal, a format "
            "this regex doesn't parse) fails the run rather than passing it clean",
        )

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

    href_total, broken = check_hrefs(args.site_dir)
    dropped: list[str] = []
    old_total = 0
    if args.old_sitemap:
        overrides = Path(__file__).resolve().parent.parent / "mkdocs" / "overrides" / "404.html"
        moves = forward_prefixes(overrides) if overrides.is_file() else []
        old_total, dropped = check_old_sitemap(args.site_dir, args.old_sitemap, moves)

    for b in broken:
        print(f"broken href: {b}", file=sys.stderr)
    for d in dropped:
        print(f"dropped url: {d}", file=sys.stderr)
    sitemap_empty = bool(args.old_sitemap) and old_total == 0
    if sitemap_empty:
        print(
            f"site_links_check: {args.old_sitemap} has no <loc> at all — an empty fetch, an "
            "error page, or a sitemap format this script doesn't parse; not a clean sitemap",
            file=sys.stderr,
        )
    if broken or dropped or sitemap_empty:
        print(
            f"site_links_check: {len(broken)} broken href(s), {len(dropped)} dropped url(s)",
            file=sys.stderr,
        )
        return 1
    if args.old_sitemap:
        print(f"site_links_check: ok ({href_total} href(s), {old_total} old url(s) checked)")
    else:
        print(f"site_links_check: ok ({href_total} href(s) checked)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
