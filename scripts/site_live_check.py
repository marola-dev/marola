#!/usr/bin/env python3
"""Check what marola.dev actually serves, not what the build believed it wrote.

Two silent failures got past everything else and were both found by a human looking at the page:
docs/ sat on the site-data branch for hours while /docs 404'd, and every Florianópolis beach read
"no data" while the board still named IMA/SC as its source. Both builds were green.

The gap is that every existing gate checks inputs — fixtures, schemas, a stub DOM. Nothing looked
at the published result. This does, and it is deliberately about the *data being present*, not
about HTTP 200s.

    scripts/site_live_check.py                     # check https://marola.dev
    scripts/site_live_check.py --base http://localhost:8000
    scripts/site_live_check.py --self-test
"""

from __future__ import annotations

import argparse
import json
import sys
import urllib.error
import urllib.request

DEFAULT_BASE = "https://marola.dev"
SCHEMA = 1
# A beach that must exist and must carry water data, per area with a provider. Named rather than
# "any beach", so a board that silently shrinks to two beaches still fails.
CANARIES = {"floripa": "Praia do Campeche"}


def fetch(url: str, timeout: int = 30):
    with urllib.request.urlopen(url, timeout=timeout) as r:  # noqa: S310 - fixed https base
        return json.loads(r.read().decode("utf-8"))


def check_board(area: str, day: str, board: dict) -> list[str]:
    """Every invariant that should hold for one published day, as failure strings."""
    bad = []
    where = f"{area}/{day}"
    if board.get("schema") != SCHEMA:
        bad.append(f"{where}: schema {board.get('schema')}, the page understands {SCHEMA}")
    beaches = board.get("beaches") or []
    if not beaches:
        bad.append(f"{where}: no beaches")
        return bad

    water_source = (board.get("sources") or {}).get("water")
    if water_source:
        # The label SiteBuilder writes when the provider returned nothing — the outage marker.
        if "no data returned" in water_source:
            bad.append(f"{where}: water provider {water_source!r} returned nothing")
        with_water = [b for b in beaches if (b.get("water") or {}).get("points")]
        if not with_water:
            bad.append(
                f"{where}: sources.water is {water_source!r} but no beach has a sampling point"
            )

    canary = CANARIES.get(area)
    if canary:
        named = [b for b in beaches if b.get("name") == canary]
        if not named:
            bad.append(f"{where}: {canary} is missing from the board")
        elif water_source and not (named[0].get("water") or {}).get("points"):
            bad.append(f"{where}: {canary} has no water sampling point")

    for b in beaches:
        if not (b.get("best") or {}).get("hour"):
            bad.append(f"{where}: {b.get('name')} has no best hour")
            break
    return bad


def check_site(base: str, get=fetch) -> list[str]:
    bad = []
    try:
        areas = get(f"{base}/data/areas.json")["areas"]
    except Exception as e:  # noqa: BLE001 - any failure here is the same story
        return [f"cannot read {base}/data/areas.json: {e}"]
    if not areas:
        return [f"{base}: areas.json lists no areas"]
    for a in areas:
        area = a["id"]
        try:
            latest = get(f"{base}/data/{area}/latest.json")
            for d in latest.get("days") or []:
                board = get(f"{base}/data/{area}/{d['file']}")
                bad += check_board(area, d["day"], board)
        except Exception as e:  # noqa: BLE001
            bad.append(f"{area}: cannot read a board: {e}")
    return bad


def self_test() -> int:
    fails = 0

    def ok(got, want, label):
        nonlocal fails
        if got == want:
            print(f"  ok   {label}")
        else:
            fails += 1
            print(f"  FAIL {label} — got {got!r}, want {want!r}")

    good = {
        "schema": 1,
        "sources": {"water": "IMA/SC"},
        "beaches": [
            {
                "name": "Praia do Campeche",
                "best": {"hour": "10:00"},
                "water": {"points": [{"id": "P06"}]},
            }
        ],
    }
    ok(check_board("floripa", "d", good), [], "a healthy board passes")

    # The exact shape marola.dev served while IMA was down: a named provider, zero points.
    blank = json.loads(json.dumps(good))
    blank["beaches"][0]["water"]["points"] = []
    msgs = check_board("floripa", "d", blank)
    ok(len(msgs), 2, "a provider with no points fails, and the canary fails too")
    ok(
        any("no beach has a sampling point" in m for m in msgs),
        True,
        "and names the provider it expected points from",
    )

    outage = json.loads(json.dumps(blank))
    outage["sources"]["water"] = "IMA/SC (no data returned)"
    ok(
        any("returned nothing" in m for m in check_board("floripa", "d", outage)),
        True,
        "SiteBuilder's own outage label is recognised",
    )

    nowater = json.loads(json.dumps(good))
    nowater["sources"]["water"] = None
    nowater["beaches"][0]["water"]["points"] = []
    ok(
        check_board("rio", "d", nowater),
        [],
        "an area with no water provider is not expected to have points",
    )

    missing = json.loads(json.dumps(good))
    missing["beaches"][0]["name"] = "Somewhere Else"
    ok(
        any("missing from the board" in m for m in check_board("floripa", "d", missing)),
        True,
        "the canary beach disappearing is a failure, not a shrug",
    )

    ok(
        any("schema" in m for m in check_board("floripa", "d", {**good, "schema": 2})),
        True,
        "a schema the page cannot read fails",
    )
    ok(
        any("no beaches" in m for m in check_board("x", "d", {"schema": 1})),
        True,
        "an empty board fails",
    )

    def broken(_url):
        raise urllib.error.URLError("down")

    ok(
        len(check_site("https://example.invalid", get=broken)),
        1,
        "an unreachable site is one clear failure",
    )

    if fails:
        print(f"site_live_check self-test: {fails} failure(s)", file=sys.stderr)
        return 1
    print("site_live_check self-test: ok")
    return 0


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--base", default=DEFAULT_BASE)
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args(argv)
    if args.self_test:
        return self_test()

    bad = check_site(args.base.rstrip("/"))
    if not bad:
        print(f"site_live_check: {args.base} ok")
        return 0
    print(f"site_live_check: {len(bad)} problem(s) at {args.base}", file=sys.stderr)
    for m in bad:
        print(f"  - {m}", file=sys.stderr)
    return 1


if __name__ == "__main__":
    sys.exit(main())
