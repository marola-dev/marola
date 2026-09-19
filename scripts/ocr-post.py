#!/usr/bin/env python3
"""Post one Open Code Review run as a single advisory PR review (MIP-0060 §5.2 "Post").

Advisory means: exactly one `COMMENT` review, never `REQUEST_CHANGES`, and exit 0 whatever the
JSON looks like — a broken review tool must not turn a PR red.

Input schema read from alibaba/open-code-review at tag **v1.12.7**:
`internal/model/review.go` (`LlmComment`: path, content, suggestion_code, existing_code,
start_line, end_line, category, severity), `cmd/opencodereview/output.go` (`jsonOutput`:
status, llm{provider,model}, message, summary{files_reviewed,comments,elapsed,…}, comments,
warnings), and upstream's own consumer `scripts/github-actions/post-review-comments.js`, which
is where the posting rules come from: a finding is inline-able when start_line or end_line ≥ 1,
multi-line uses start_line + line on side RIGHT, and a ```suggestion block is emitted only when
suggestion_code *and* existing_code are both present.

    ocr-post.py --json ocr.json --repo owner/name --pr 42 --head-sha "$SHA"
    ocr-post.py --json ocr.json --repo owner/name --pr 42 --head-sha x --diff d.patch --dry-run
    ocr-post.py --self-test
"""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
from pathlib import Path

MARKER = "<!-- marola-ocr -->"
# MIP-0060 §8: low recall is by design, so silence must never read as a pass.
QUIET_NOT_APPROVAL = "A quiet run is **not** an approval — this reviewer's recall is low by design."
SEVERITY_RANK = {"critical": 4, "high": 3, "medium": 2, "low": 1}
MAX_BODY = 1200
MAX_CODE = 800
HUNK = re.compile(r"^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@")
MENTION = re.compile(r"(?<![\w/`])@([A-Za-z0-9][-A-Za-z0-9]{0,38})")
FIXTURES = Path(__file__).resolve().parent / "fixtures" / "ocr"


class GhError(RuntimeError):
    def __init__(self, status: int, message: str):
        super().__init__(message)
        self.status = status


def gh_api(method: str, path: str, payload=None, accept: str | None = None):
    """Every GitHub read and write goes through here, so --self-test can swap in a fake."""
    cmd = ["gh", "api", "-X", method, path]
    if accept:
        cmd += ["-H", f"Accept: {accept}"]
    if payload is not None:
        cmd += ["--input", "-"]
    p = subprocess.run(
        cmd,
        input=json.dumps(payload) if payload is not None else None,
        capture_output=True,
        text=True,
        check=False,
    )
    if p.returncode != 0:
        m = re.search(r"HTTP (\d{3})", p.stderr)
        raise GhError(int(m.group(1)) if m else 0, (p.stderr or "gh api failed").strip())
    if accept and "json" not in accept:
        return p.stdout
    return json.loads(p.stdout) if p.stdout.strip() else None


# --- the OCR JSON -----------------------------------------------------------------


def _int(v) -> int:
    return v if isinstance(v, int) and not isinstance(v, bool) else 0


def normalise(c: dict) -> dict:
    return {
        "path": str(c.get("path") or "").strip().lstrip("./"),
        "content": str(c.get("content") or ""),
        "start_line": _int(c.get("start_line")),
        "end_line": _int(c.get("end_line")),
        "severity": str(c.get("severity") or "").strip().lower(),
        "category": str(c.get("category") or "").strip().lower(),
        "suggestion_code": str(c.get("suggestion_code") or ""),
        "existing_code": str(c.get("existing_code") or ""),
    }


def load_findings(path: str) -> tuple[dict, list[dict], str]:
    """(document, findings, reason). A non-empty reason means: degrade to summary-only."""
    try:
        raw = Path(path).read_text(encoding="utf-8")
    except OSError as e:
        return {}, [], f"cannot read {path} ({e.strerror or e})"
    if not raw.strip():
        return {}, [], f"{Path(path).name} is empty — ocr wrote no output"
    try:
        doc = json.loads(raw)
    except json.JSONDecodeError as e:
        return {}, [], f"{Path(path).name} is not valid JSON ({e.msg}, line {e.lineno})"
    if not isinstance(doc, dict):
        return {}, [], "top-level JSON is not an object — unknown output shape"
    if not isinstance(doc.get("comments"), list):
        return doc, [], "no `comments` array in the output — unknown output shape"
    findings = [normalise(c) for c in doc["comments"] if isinstance(c, dict)]
    return doc, [f for f in findings if f["path"]], ""


# --- the PR diff ------------------------------------------------------------------


def right_lines(diff_text: str) -> dict[str, set[int]]:
    """Line numbers addressable on the RIGHT side of a unified diff, per path."""
    out: dict[str, set[int]] = {}
    path, new_no, new_left, old_left = None, 0, 0, 0
    for line in diff_text.splitlines():
        if line.startswith("+++ "):
            p = line[4:].split("\t")[0].strip()
            path = None if p == "/dev/null" else (p[2:] if p.startswith("b/") else p)
            new_left = old_left = 0
            continue
        m = HUNK.match(line)
        if m:
            new_no = int(m.group(3))
            new_left = int(m.group(4) or 1)
            old_left = int(m.group(2) or 1)
            continue
        if path is None or (new_left <= 0 and old_left <= 0):
            continue
        if line.startswith("\\"):
            continue
        if line.startswith("+"):
            out.setdefault(path, set()).add(new_no)
            new_no += 1
            new_left -= 1
        elif line.startswith("-"):
            old_left -= 1
        elif line.startswith(" ") or line == "":
            out.setdefault(path, set()).add(new_no)
            new_no += 1
            new_left -= 1
            old_left -= 1
        else:
            new_left = old_left = 0
    return out


def inline_position(f: dict, lines: dict[str, set[int]]) -> dict | None:
    known = lines.get(f["path"])
    if not known:
        return None
    s, e = f["start_line"], f["end_line"]
    if 1 <= s < e and s in known and e in known:
        return {"line": e, "start_line": s, "side": "RIGHT", "start_side": "RIGHT"}
    one = e if e >= 1 else s
    return {"line": one, "side": "RIGHT"} if one >= 1 and one in known else None


# --- text the model wrote: never trusted -------------------------------------------


def sanitise(text: str, limit: int = MAX_BODY) -> str:
    """Strip HTML comments (a finding must not forge the sticky marker) and defuse @mentions."""
    t = re.sub(r"<!--.*?-->", "", text, flags=re.S)
    t = t.replace("<!--", "&lt;!--").replace("-->", "--&gt;")
    t = MENTION.sub(r"`@\1`", t)
    if len(t) > limit:
        t = t[: limit - 16].rstrip() + "\n\n…(truncated)"
    return t.strip()


def fence_for(code: str) -> str:
    longest = max((len(r) for r in re.findall(r"`+", code)), default=0)
    return "`" * max(3, longest + 1)


def label(f: dict) -> str:
    tag = "/".join(x for x in (f["category"], f["severity"]) if x)
    return f"[{tag}]" if tag else ""


def comment_body(f: dict) -> str:
    body = sanitise(f["content"]) or "_(the model wrote no text)_"
    if label(f):
        body = f"**{label(f)}** {body}"
    # Upstream's own condition for treating the field as a code suggestion.
    if f["suggestion_code"] and f["existing_code"]:
        code = re.sub(r"<!--.*?-->", "", f["suggestion_code"], flags=re.S)[:MAX_CODE]
        fence = fence_for(code)
        body += f"\n\n{fence}suggestion\n{code.rstrip()}\n{fence}"
    return body


def one_line(f: dict, n: int = 140) -> str:
    text = sanitise(f["content"], n + 40).replace("\n", " ").strip()
    return (text[: n - 1] + "…") if len(text) > n else text


# --- the two things we post --------------------------------------------------------


def clean_field(v: str, n: int = 60) -> str:
    return re.sub(r"[`|\r\n]", "", str(v)).strip()[:n] or "unknown"


def header(doc: dict, args, n_findings: int) -> str:
    s = doc.get("summary") if isinstance(doc.get("summary"), dict) else {}
    llm = doc.get("llm") if isinstance(doc.get("llm"), dict) else {}
    model = clean_field(args.model or llm.get("model") or "unknown")
    provider = clean_field(args.provider or llm.get("provider") or "local", 24)
    files = _int(s.get("files_reviewed")) if isinstance(s.get("files_reviewed"), int) else "?"
    elapsed = clean_field(args.elapsed or s.get("elapsed") or "?", 24)
    return (
        f"`marola-ocr` · advisory · {model} ({provider}) · ocr "
        f"{clean_field(args.ocr_version or 'unknown', 24)} · "
        f"{files} files, {n_findings} findings, {elapsed}"
    )


def summary_body(head: str, posted: int, dropped: list[tuple[dict, str]], notes: list[str]) -> str:
    out = [MARKER, head, ""]
    out.append(f"{posted} finding(s) posted inline on this PR's diff.")
    if dropped:
        out += ["", f"Not posted inline ({len(dropped)}):"]
        for f, why in dropped:
            where = f"{f['path']}:{f['end_line'] or f['start_line']}"
            out.append(f"- `{where}` {label(f)} — {why} — {one_line(f)}".replace(" —  — ", " — "))
    for n in notes:
        out += ["", n]
    out += ["", QUIET_NOT_APPROVAL, ""]
    return "\n".join(out)


def post_review(api, repo: str, pr: int, payload: dict) -> int:
    """Returns how many inline comments landed; -1 if no review could be posted at all."""
    try:
        api("POST", f"repos/{repo}/pulls/{pr}/reviews", payload)
        return len(payload.get("comments", []))
    except GhError as e:
        if e.status != 422:
            print(f"ocr-post: review not posted ({e})", file=sys.stderr)
            return -1
        retry = {k: v for k, v in payload.items() if k != "comments"}
        try:
            api("POST", f"repos/{repo}/pulls/{pr}/reviews", retry)
        except GhError as e2:
            print(f"ocr-post: summary-only retry also failed ({e2})", file=sys.stderr)
            return -1
        return 0


def upsert_sticky(api, repo: str, pr: int, body: str) -> str:
    for page in range(1, 6):
        items = api("GET", f"repos/{repo}/issues/{pr}/comments?per_page=100&page={page}") or []
        for c in items:
            if MARKER in (c.get("body") or ""):
                api("PATCH", f"repos/{repo}/issues/comments/{c['id']}", {"body": body})
                return "updated"
        if len(items) < 100:
            break
    api("POST", f"repos/{repo}/issues/{pr}/comments", {"body": body})
    return "created"


# --- wiring ------------------------------------------------------------------------


def run(args, api) -> int:
    doc, findings, reason = load_findings(args.json)
    if reason:
        head = header(doc, args, 0)
        body = f"{MARKER}\n{head}\n\nno review this run: {reason}\n\n{QUIET_NOT_APPROVAL}\n"
        if args.dry_run:
            print("(no review posted)")
            print(body)
        else:
            upsert_sticky(api, args.repo, args.pr, body)
        print(f"ocr-post: no review this run: {reason}", file=sys.stderr)
        return 0

    if args.diff:
        diff = Path(args.diff).read_text(encoding="utf-8")
    else:
        diff = api(
            "GET",
            f"repos/{args.repo}/pulls/{args.pr}",
            accept="application/vnd.github.v3.diff",
        )
    lines = right_lines(diff or "")

    ordered = sorted(findings, key=lambda f: -SEVERITY_RANK.get(f["severity"], 0))
    placed, dropped = [], []
    for f in ordered:
        pos = inline_position(f, lines)
        if pos:
            placed.append((f, pos))
        else:
            dropped.append((f, "outside this PR's diff"))
    over = placed[args.max_inline :]
    placed = placed[: args.max_inline]
    dropped += [(f, f"over the inline cap of {args.max_inline}") for f, _ in over]

    head = header(doc, args, len(findings))
    review = {
        "commit_id": args.head_sha,
        "event": "COMMENT",
        "body": f"{head}\n\nAdvisory only. {QUIET_NOT_APPROVAL}",
        "comments": [{"path": f["path"], "body": comment_body(f), **pos} for f, pos in placed],
    }

    notes = []
    if args.dry_run:
        print(json.dumps(review, indent=2, ensure_ascii=False))
        posted = len(placed)
    else:
        posted = post_review(api, args.repo, args.pr, review)
        if posted < len(placed):
            notes.append("The review carried no inline comments; every finding is listed here.")
            dropped += [(f, "position rejected by GitHub") for f, _ in placed]
            posted = max(posted, 0)

    body = summary_body(head, posted, dropped, notes)
    if args.dry_run:
        print(body)
    else:
        print(f"ocr-post: {upsert_sticky(api, args.repo, args.pr, body)} the sticky summary")
    return 0


def build_parser() -> argparse.ArgumentParser:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--json", help="ocr review --format json --output <this>")
    ap.add_argument("--repo", help="OWNER/NAME")
    ap.add_argument("--pr", type=int)
    ap.add_argument("--head-sha")
    ap.add_argument("--diff", help="unified diff file; default: fetch the PR's diff via gh")
    ap.add_argument("--model", default="")
    ap.add_argument("--provider", default="")
    ap.add_argument("--ocr-version", default="")
    ap.add_argument("--elapsed", default="")
    ap.add_argument("--max-inline", type=int, default=15)
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--self-test", action="store_true")
    return ap


def main(argv=None) -> int:
    args = build_parser().parse_args(argv)
    if args.self_test:
        return self_test()
    missing = [n for n in ("json", "repo", "pr", "head_sha") if not getattr(args, n)]
    if missing:
        print(
            f"ocr-post: missing --{', --'.join(m.replace('_', '-') for m in missing)}",
            file=sys.stderr,
        )
        return 2
    return run(args, gh_api)


# --- self-test ---------------------------------------------------------------------


class FakeGh:
    """The whole GitHub side: a diff to serve, an issue-comment store, reviews received."""

    def __init__(self, diff: str, reject_positions: bool = False):
        self.diff, self.reject = diff, reject_positions
        self.comments: list[dict] = []
        self.reviews: list[dict] = []
        self.patches = 0
        self.next_id = 100

    def __call__(self, method, path, payload=None, accept=None):
        if accept and "diff" in accept:
            return self.diff
        if method == "POST" and path.endswith("/reviews"):
            if self.reject and payload.get("comments"):
                raise GhError(422, "HTTP 422: line must be part of the diff")
            self.reviews.append(payload)
            return {"id": 1}
        if method == "GET" and "/issues/" in path:
            return self.comments if "page=1" in path else []
        if method == "PATCH" and "/issues/comments/" in path:
            self.patches += 1
            for c in self.comments:
                if c["id"] == int(path.rsplit("/", 1)[1]):
                    c["body"] = payload["body"]
            return {"id": 1}
        if method == "POST" and "/issues/" in path:
            c = {"id": self.next_id, "body": payload["body"]}
            self.next_id += 1
            self.comments.append(c)
            return c
        raise AssertionError(f"unexpected {method} {path}")


def self_test() -> int:  # noqa: C901 - a flat list of assertions reads better than helpers
    fails = 0

    def ok(cond, what):
        nonlocal fails
        if not cond:
            fails += 1
            print(f"  FAIL: {what}", file=sys.stderr)

    diff = (FIXTURES / "diff.patch").read_text(encoding="utf-8")
    lines = right_lines(diff)
    ok(88 in lines["scripts/oods_ingest.py"], "an added line is addressable on the RIGHT side")
    ok(200 not in lines["scripts/oods_ingest.py"], "a line outside every hunk is not")
    ok(41 in lines["oods/src/main/scala/Planner.scala"], "the second file's hunk is parsed too")

    def go(fixture, **kw):
        argv = [
            "--json", str(FIXTURES / fixture),
            "--repo", "h0ffmann/marola", "--pr", "42", "--head-sha", "deadbee",
            "--diff", str(FIXTURES / "diff.patch"), "--ocr-version", "1.12.7",
        ]  # fmt: skip
        for k, v in kw.items():
            argv += [f"--{k}", str(v)]
        api = FakeGh(diff)
        return run(build_parser().parse_args(argv), api), api

    rc, api = go("normal.json")
    ok(rc == 0, "a normal run exits 0")
    ok(len(api.reviews) == 1 and api.reviews[0]["event"] == "COMMENT", "one COMMENT review")
    bodies = "\n".join(c["body"] for c in api.reviews[0]["comments"])
    ok("`@hoffmann`" in bodies and "\n@hoffmann" not in bodies, "an @mention cannot ping anyone")
    ok(MARKER not in bodies, "a finding cannot forge the sticky marker")
    ok(len(api.comments) == 1 and MARKER in api.comments[0]["body"], "one sticky summary")
    ok(QUIET_NOT_APPROVAL in api.comments[0]["body"], "the summary says a quiet run is no approval")

    rc, api = go("out-of-diff.json")
    ok(len(api.reviews[0]["comments"]) == 1, "the out-of-diff finding is not posted inline")
    sticky = api.comments[0]["body"]
    ok("Untouched.scala" in sticky and "outside this PR's diff" in sticky, "…it is counted instead")

    rc, api = go("many.json")
    posted = api.reviews[0]["comments"]
    ok(len(posted) == 15, "inline comments are capped at 15 by default")
    ranks = [
        SEVERITY_RANK.get(b["body"].split("[")[1].split("/")[1].split("]")[0], 0) for b in posted
    ]
    ok(ranks == sorted(ranks, reverse=True), "the cap keeps the most severe findings, in order")
    ok("over the inline cap of 15" in api.comments[0]["body"], "the rest are listed in the summary")
    rc, api = go("many.json", **{"max-inline": 3})
    ok(len(api.reviews[0]["comments"]) == 3, "--max-inline moves the cap")

    api = FakeGh(diff)
    argv = [
        "--json", str(FIXTURES / "normal.json"), "--repo", "h0ffmann/marola", "--pr", "42",
        "--head-sha", "deadbee", "--diff", str(FIXTURES / "diff.patch"),
    ]  # fmt: skip
    run(build_parser().parse_args(argv), api)
    run(build_parser().parse_args(argv), api)
    ok(len(api.comments) == 1 and api.patches == 1, "a second run PATCHes, never posts a twin")

    for fixture, why in (("malformed.json", "not valid JSON"), ("empty.json", "is empty")):
        rc, api = go(fixture)
        ok(rc == 0, f"{fixture} still exits 0 — the check is advisory")
        ok(not api.reviews, f"{fixture} posts no review")
        ok(why in api.comments[0]["body"], f"{fixture} explains itself in the summary")
        ok(
            QUIET_NOT_APPROVAL in api.comments[0]["body"],
            f"{fixture} still refuses to imply a pass",
        )

    api = FakeGh(diff, reject_positions=True)
    run(build_parser().parse_args(argv), api)
    ok(len(api.reviews) == 1 and "comments" not in api.reviews[0], "a 422 retries as summary-only")
    ok("position rejected" in api.comments[0]["body"], "…and the summary keeps every finding")

    print(f"ocr-post self-test: {'ok' if fails == 0 else f'{fails} FAILED'}")
    return 0 if fails == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
