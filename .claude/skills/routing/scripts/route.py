#!/usr/bin/env python3
"""Route a "where is it" question with no Nix and no graphify: the code graph and the wiring
tables the umbrella's CI publishes on its `routing-bundle` branch (MIP-0084 §5.2).

route query "<identifiers>" [--budget 400]   label-matched start nodes, a breadth-first walk
route explain <node>  |  route path <a> <b>  a node's neighbours / the shortest path
route wiring <term>                          the wiring rows that mention <term>
route fetch                                  the bundle into refs/marola/routing-bundle
route status [--line]                        the bundle's commit and age, and which graph answers
route bench [file]                           right-first per question (JSON, a YAML subset)

Which graph answers: a local `graph build` (~/.cache/marola-graph/<repo>) whose build.json matches
HEAD and the submodules, else refs/marola/routing-bundle, else origin/routing-bundle. The first line
of every answer says which. MAROLA_ROUTING_REMOTE overrides the umbrella URL `fetch` reads.
"""

from __future__ import annotations

import json
import math
import os
import re
import subprocess
import sys
import time
import unicodedata
from collections import deque
from pathlib import Path

REMOTE = "https://github.com/marola-dev/marola"
REF = "refs/marola/routing-bundle"
REFS = (REF, "refs/remotes/origin/routing-bundle")
MAX_BYTES = 20 * 1024 * 1024
NO_BUNDLE = "no routing bundle; use `git grep` (or `route fetch` once online)"
EXACT, PREFIX, SUBSTR, SOURCE = 1000.0, 100.0, 1.0, 0.5
STOP = frozenset(
    "how what why when where which who does did is are was were be been can could should would "
    "will has have had the and but not for from with into that this these those there here its "
    "their them they about any all some work works working a an of in on to by it do "
    "qual quais quando onde como por para uma não são tem".split()
)
CTRL = re.compile(r"[\x00-\x1f\x7f]")


class Refused(Exception):
    pass


def clean(s: object) -> str:
    # The bundle is data from a branch: never let it print escape codes into an agent's context.
    return CTRL.sub(" ", str(s))


def git(*args: str, cwd: str | None = None, timeout: float = 30) -> str | None:
    try:
        r = subprocess.run(
            ["git", *args],
            cwd=cwd,
            capture_output=True,
            text=True,
            timeout=timeout,
            env={**os.environ, "GIT_TERMINAL_PROMPT": "0"},
        )
    except (OSError, subprocess.TimeoutExpired):
        return None
    return r.stdout if r.returncode == 0 else None


def tokens(text: str) -> list[str]:
    s = unicodedata.normalize("NFKD", str(text))
    s = "".join(c for c in s if not unicodedata.combining(c)).lower()
    return re.findall(r"[^\W_]+", s)


def norm(label: str) -> str:
    s = unicodedata.normalize("NFKD", str(label))
    return "".join(c for c in s if not unicodedata.combining(c)).lower()


# ---------------------------------------------------------------- where the graph comes from


class Source:
    """One place a graph and its wiring are read from: a git ref, or a local graph directory."""

    def __init__(self, kind: str, where: str, cwd: str | None, meta: dict):
        self.kind, self.where, self.cwd, self.meta = kind, where, cwd, meta

    def read(self, name: str) -> dict | None:
        if self.kind == "local":
            p = Path(self.where) / name
            if not p.is_file():
                return None
            if p.stat().st_size > MAX_BYTES:
                raise Refused(f"{name} is over {MAX_BYTES // 2**20} MB; refused")
            return json.loads(p.read_text(encoding="utf-8"))
        size = git("cat-file", "-s", f"{self.where}:{name}", cwd=self.cwd)
        if size is None:
            return None
        if int(size) > MAX_BYTES:
            raise Refused(f"{name} is over {MAX_BYTES // 2**20} MB; refused")
        text = git("show", f"{self.where}:{name}", cwd=self.cwd)
        return None if text is None else json.loads(text)

    def label(self) -> str:
        head = (self.meta.get("head") or "")[:7] or "?"
        if self.kind == "local":
            return f"answers from your local graph @{head} (graph build)"
        return f"answers from main@{head} (bundle)"


def subs_of(text: str) -> dict[str, str]:
    """`git submodule status` text → {path: sha}; a `+` (off the recorded commit) stays visible."""
    out = {}
    for line in (text or "").splitlines():
        f = line.split()
        if len(f) >= 2:
            out[f[1]] = f[0]
    return out


def local_graph(top: str) -> Source | None:
    cache = Path(os.environ.get("XDG_CACHE_HOME") or Path.home() / ".cache") / "marola-graph"
    d = cache / Path(top).name
    try:
        meta = json.loads((d / "build.json").read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None
    head = (git("rev-parse", "HEAD", cwd=top) or "").strip()
    status = git("submodule", "status", cwd=top) or ""
    if meta.get("head") != head or subs_of(meta.get("submodules", "")) != subs_of(status):
        return None
    return Source("local", str(d), top, meta) if (d / "graph.json").is_file() else None


def bundle(cwd: str | None) -> Source | None:
    for ref in REFS:
        if git("rev-parse", "--verify", "-q", f"{ref}^{{commit}}", cwd=cwd):
            src = Source("bundle", ref, cwd, {})
            try:
                src.meta = src.read("build.json") or {}
            except (Refused, ValueError):
                src.meta = {}
            return src
    return None


def toplevel() -> str | None:
    out = git("rev-parse", "--show-toplevel")
    return out.strip() if out else None


def fetch(cwd: str | None, quiet: bool = False) -> bool:
    remote = os.environ.get("MAROLA_ROUTING_REMOTE", REMOTE)
    ok = git("fetch", "-q", "--depth", "1", remote, f"+routing-bundle:{REF}", cwd=cwd, timeout=60)
    if not quiet:
        print(
            "route: fetched the routing bundle"
            if ok is not None
            else f"route: could not fetch routing-bundle from {remote}; route answers from what is here"
        )
    return ok is not None


def pick(cwd: str | None, graph: bool = True) -> Source | None:
    """The local graph for graph questions when it matches; otherwise the bundle, fetched once."""
    if graph and cwd:
        src = local_graph(cwd)
        if src:
            return src
    src = bundle(cwd)
    if src is None and cwd and fetch(cwd, quiet=True):
        src = bundle(cwd)
    return src


# ---------------------------------------------------------------- the graph


class Graph:
    def __init__(self, data: dict):
        self.nodes: dict[str, dict] = {}
        for n in data.get("nodes", []):
            if isinstance(n, dict) and "id" in n:
                self.nodes[str(n["id"])] = n
        self.adj: dict[str, list[tuple[str, dict]]] = {n: [] for n in self.nodes}
        self.edges: dict[frozenset, dict] = {}
        for e in data.get("links", data.get("edges", [])):
            s, t = str(e.get("source")), str(e.get("target"))
            if s in self.nodes and t in self.nodes and s != t:
                key = frozenset((s, t))
                if key not in self.edges:
                    self.edges[key] = {**e, "source": s, "target": t}
                    self.adj[s].append((t, self.edges[key]))
                    self.adj[t].append((s, self.edges[key]))
        self._idf: dict[str, float] = {}

    def deg(self, n: str) -> int:
        return len(self.adj[n])

    def lab(self, n: str) -> str:
        return str(self.nodes[n].get("label") or n)

    def idf(self, t: str) -> float:
        if t not in self._idf:
            df = sum(1 for d in self.nodes.values() if t in norm(d.get("label") or ""))
            self._idf[t] = math.log(1 + len(self.nodes) / (1 + df))
        return self._idf[t]

    def score(self, question: str) -> tuple[list[tuple[float, str]], dict[str, str]]:
        """graphify's tiers: exact > prefix > substring per term, weighted by IDF and squared term
        coverage, plus a whole-query tier and a source-path hint; and each term's best node."""
        raw = tokens(question)
        terms = list(dict.fromkeys(t for t in raw if t not in STOP)) or list(dict.fromkeys(raw))
        if not terms:
            return [], {}
        joined = " ".join(terms)
        jw = max(self.idf(t) for t in terms)
        scored, best = [], {}
        for nid, d in self.nodes.items():
            nl = norm(d.get("label") or "")
            bare = nl.rstrip("()")
            lt = " ".join(tokens(d.get("label") or ""))
            src = str(d.get("source_file") or "").lower()
            score = 0.0
            if joined in (nl, bare, lt, nid.lower()):
                score += EXACT * 10 * jw
            elif nl.startswith(joined) or bare.startswith(joined) or lt.startswith(joined):
                score += PREFIX * 10 * jw
            matched, tiered = 0, 0.0
            for t in terms:
                w = self.idf(t)
                tier = sub = 0.0
                if t in (nl, bare):
                    tier = EXACT * w
                elif nl.startswith(t) or bare.startswith(t):
                    tier = PREFIX * w
                elif t in nl:
                    sub = SUBSTR * w
                if tier or sub:
                    matched += 1
                srcv = SOURCE * w if t in src else 0.0
                score += sub + srcv
                tiered += tier
                single = tier + sub + srcv
                if t in (nl, bare, lt, nid.lower()):
                    single += EXACT * 10 * w
                elif nl.startswith(t) or bare.startswith(t) or lt.startswith(t):
                    single += PREFIX * 10 * w
                if single > 0:
                    key = (-single, -self.deg(nid), len(self.lab(nid)), nid)
                    if t not in best or key < best[t][0]:
                        best[t] = (key, nid)
            if tiered:
                score += tiered * (matched / len(terms)) ** 2
            if score > 0:
                scored.append((score, nid))
        scored.sort(key=lambda s: (-s[0], len(self.lab(s[1])), s[1]))
        return scored, {t: v[1] for t, v in best.items()}

    def seeds(
        self, scored: list[tuple[float, str]], per_term: dict[str, str], k: int = 3
    ) -> list[str]:
        if not scored:
            return []
        top, out, seen = scored[0][0], [], set()
        for s, nid in scored:
            if len(out) >= k or (out and s < top * 0.2):
                break
            key = norm(self.lab(nid))
            if key not in seen:
                seen.add(key)
                out.append(nid)
        for nid in per_term.values():
            key = norm(self.lab(nid))
            if key not in seen:
                seen.add(key)
                out.append(nid)
        return out

    def walk(self, start: list[str], depth: int = 2) -> dict[str, int]:
        """Hop distance from the seeds, not expanding through hubs (degree at the 99th percentile,
        at least 50) unless the hub is a seed."""
        degs = sorted(self.deg(n) for n in self.nodes) or [0]
        hub = max(50, degs[int(len(degs) * 0.99)] if len(degs) > 1 else 0)
        dist = {n: 0 for n in start}
        frontier = list(start)
        for hop in range(1, depth + 1):
            nxt = []
            for n in frontier:
                if n not in start and self.deg(n) >= hub:
                    continue
                for m, _ in self.adj[n]:
                    if m not in dist:
                        dist[m] = hop
                        nxt.append(m)
            frontier = nxt
        return dist

    def node_line(self, n: str) -> str:
        d = self.nodes[n]
        comm = d.get("community_name") or d.get("community", "")
        return clean(
            f"NODE {self.lab(n)} [src={d.get('source_file', '')} loc={d.get('source_location', '')} community={comm}]"
        )

    def edge_line(self, e: dict) -> str:
        at = (
            f" at={e.get('source_file', '')}:{e['source_location']}"
            if e.get("source_location")
            else ""
        )
        return clean(
            f"EDGE {self.lab(e['source'])} --{e.get('relation', '')}--> {self.lab(e['target'])}{at}"
        )

    def find(self, name: str) -> str | None:
        want = norm(name).rstrip("()")
        exact = [
            n
            for n, d in self.nodes.items()
            if norm(d.get("label") or "").lstrip(".").rstrip("()") == want or n == name
        ]
        if exact:
            return min(exact, key=lambda n: (-self.deg(n), n))
        scored, _ = self.score(name)
        return scored[0][1] if scored else None


def render_query(g: Graph, question: str, budget: int) -> str:
    scored, per_term = g.score(question)
    start = g.seeds(scored, per_term)
    if not start:
        return f"no node matches {question!r}; ask with the code's identifiers, or git grep"
    dist = g.walk(start)
    order = start + sorted(set(dist) - set(start), key=lambda n: (dist[n], -g.deg(n), n))
    lines = [g.node_line(n) for n in order]
    inside = set(order)
    lines += [g.edge_line(e) for k, e in g.edges.items() if k <= inside]
    head = f"start: {', '.join(clean(g.lab(n)) for n in start)} · {len(order)} nodes"
    chars, out, shown = budget * 3, [], 0
    for i, line in enumerate(lines):
        if i >= len(start) and sum(len(x) + 1 for x in out) + len(line) > chars:
            break
        out.append(line)
        shown += line.startswith("NODE ")
    if shown < len(order):
        head += f" · showing {shown} (raise --budget for more)"
    return "\n".join([head, *out])


def render_explain(g: Graph, name: str) -> str:
    n = g.find(name)
    if n is None:
        return f"no node matches {name!r}"
    out = [g.node_line(n)]
    for _m, e in sorted(g.adj[n], key=lambda x: (x[1].get("relation", ""), g.lab(x[0]))):
        out.append(g.edge_line(e))
    return "\n".join(out)


def render_path(g: Graph, a: str, b: str) -> str:
    s, t = g.find(a), g.find(b)
    if s is None or t is None:
        return f"no node matches {a if s is None else b!r}"
    prev: dict[str, str | None] = {s: None}
    q = deque([s])
    while q and t not in prev:
        n = q.popleft()
        for m, _ in g.adj[n]:
            if m not in prev:
                prev[m] = n
                q.append(m)
    if t not in prev:
        return f"no path between {clean(g.lab(s))} and {clean(g.lab(t))}"
    chain, n = [], t
    while n is not None:
        chain.append(n)
        n = prev[n]
    chain.reverse()
    out = [g.node_line(n) for n in chain]
    out += [g.edge_line(g.edges[frozenset((x, y))]) for x, y in zip(chain, chain[1:], strict=False)]
    return "\n".join(out)


# ---------------------------------------------------------------- wiring


def wiring_rows(w: dict) -> list[str]:
    def cell(xs) -> str:
        return ", ".join(xs) if xs else "—"

    rows = []
    for key, a in sorted(w.get("artifacts", {}).items()):
        rows.append(
            f"{key}  published by {cell(a.get('pub'))} · pinned in {cell(a.get('pin'))} · read by {cell(a.get('read'))}"
        )
    for t, d in sorted(w.get("dispatch", {}).items()):
        rows.append(
            f"dispatch {t}  sent by {cell(d.get('sent'))} · triggers {cell(d.get('listen'))}"
        )
    for p, wf in sorted(w.get("bumps", {}).items()):
        rows.append(f"pin bump {p}  by {cell(wf)}")
    for wf, site in w.get("deploys", []):
        rows.append(f"deploy {wf}  → {site}")
    for repo, pins in sorted(w.get("devkit", {}).items()):
        rows.append(f"marola-devkit pin in {repo}  {cell(pins)}")
    return [clean(r) for r in rows]


def match_rows(w: dict, term: str) -> list[str]:
    words = [t.lower() for t in term.split()]
    return [r for r in wiring_rows(w) if all(x in r.lower() for x in words)]


# ---------------------------------------------------------------- commands


def age(seconds: float) -> str:
    h = seconds / 3600
    return (
        f"{int(seconds // 60)} min" if h < 1 else f"{int(h)} h" if h < 48 else f"{int(h // 24)} d"
    )


def status_line(cwd: str | None) -> str:
    src = bundle(cwd)
    if src is None:
        return "▸ routing: no bundle yet; route fetch"
    ct = git("log", "-1", "--format=%ct", src.where, cwd=cwd)
    old = age(time.time() - int(ct)) if ct and ct.strip().isdigit() else "?"
    line = f"▸ routing: bundle main@{(src.meta.get('head') or '?')[:7]} ({old} old)"
    # The bundle records the SHA it graphed for this repo; a newer origin/main means it lags.
    top = cwd or ""
    remote = (git("remote", "get-url", "origin", cwd=cwd) or "").strip()
    repo = re.sub(r"\.git$", "", remote.rstrip("/").rsplit("/", 1)[-1]) or Path(top).name
    recorded = (
        src.meta.get("head")
        if repo == "marola"
        else subs_of(src.meta.get("submodules", "")).get(repo, "")
    )
    mine = (git("rev-parse", "-q", "--verify", "refs/remotes/origin/main", cwd=cwd) or "").strip()
    if recorded and mine and recorded.lstrip("+-U") != mine:
        line += " · stale; route fetch refreshes it"
    return line


def cmd_status(cwd: str | None, brief: bool) -> int:
    print(status_line(cwd))
    if brief:
        return 0
    src = bundle(cwd)
    if src:
        print(
            f"ref {src.where}; graphify {src.meta.get('graphify', '?')}; bench {src.meta.get('bench', '?')}"
        )
        for path, sha in subs_of(src.meta.get("submodules", "")).items():
            print(f"  {path} {sha[:9]}")
    local = local_graph(cwd) if cwd else None
    print(
        "a local graph matching HEAD answers graph questions"
        if local
        else "no local graph matching HEAD"
    )
    return 0


def load_graph(src: Source) -> Graph | None:
    data = src.read("graph.json")
    return Graph(data) if data else None


def bench(
    src_graph: Source | None, src_wiring: Source | None, path: str | None
) -> tuple[int, int, list[str]]:
    qs = json.loads(Path(path).read_text(encoding="utf-8")) if path else json.loads(BENCH)
    g = load_graph(src_graph) if src_graph else None
    w = (src_wiring.read("wiring.json") if src_wiring else None) or {}
    right, lines = 0, []
    for q in qs.get("questions", qs) if isinstance(qs, dict) else qs:
        if q.get("via") == "wiring":
            got = "\n".join(match_rows(w, q["ask"]))
        else:
            got = render_query(g, q["ask"], 400) if g else ""
            # Right first: an expected file in the first three NODE lines' src=.
            got = "\n".join([x for x in got.splitlines() if x.startswith("NODE ")][:3])
        ok = any(e in got for e in q["expect"])
        right += ok
        lines.append(
            f"{'right' if ok else 'MISS '}  {q.get('id', '?')}  {q.get('via', 'query')} {q['ask']!r}"
        )
    return right, len(lines), lines


# MIP-0076 §Appendix's 8 questions, asked the way MIP-0084 §3 asks them: the code's identifiers for a
# symbol, the artifact or dispatch name for the wiring. The umbrella's routing-bench.yaml replaces it.
BENCH = json.dumps(
    [
        {
            "id": 1,
            "via": "query",
            "ask": "Swimability score",
            "expect": ["scoring/Swimability.scala"],
        },
        {"id": 2, "via": "wiring", "ask": "marola-image", "expect": ["board-schema.sh"]},
        {"id": 3, "via": "wiring", "ask": "corpus", "expect": ["corpus.version"]},
        {"id": 4, "via": "wiring", "ask": "ml-resources", "expect": ["resources-fetch"]},
        {"id": 5, "via": "wiring", "ask": "submodule-updated", "expect": ["pointer-sync.yml"]},
        {
            "id": 6,
            "via": "query",
            "ask": "Main KyoApp",
            "expect": ["cli/src/main/scala/marola/Main.scala"],
        },
        {"id": 7, "via": "wiring", "ask": "submodule-docs-updated", "expect": ["docs.yml"]},
        {
            "id": 8,
            "via": "query",
            "ask": "OpenMeteoClient",
            "expect": ["conditions/OpenMeteoClient.scala"],
        },
    ]
)


def main(argv: list[str]) -> int:
    if not argv or argv[0] in ("-h", "--help"):
        print(__doc__.strip())
        return 0
    verb, rest = argv[0], argv[1:]
    cwd = toplevel()
    try:
        if verb == "fetch":
            if cwd is None:
                print("route: not in a git checkout")
                return 1
            return 0 if fetch(cwd) else 1
        if verb == "status":
            return cmd_status(cwd, "--line" in rest)
        if verb == "bench":
            g, w = pick(cwd), pick(cwd, graph=False)
            if g is None and w is None:
                print(NO_BUNDLE)
                return 0
            right, total, lines = bench(g, w, rest[0] if rest else None)
            print(g.label() if g else NO_BUNDLE)
            print("\n".join(lines))
            print(f"right first: {right}/{total}")
            return 0
        if verb == "wiring":
            if not rest:
                print("usage: route wiring <term>")
                return 2
            src = pick(cwd, graph=False)
            data = src.read("wiring.json") if src else None
            if not data:
                print(NO_BUNDLE)
                return 0
            print(src.label())
            rows = match_rows(data, " ".join(rest))
            print("\n".join(rows) if rows else f"no wiring row mentions {' '.join(rest)!r}")
            return 0
        if verb in ("query", "explain", "path"):
            budget = 400
            if "--budget" in rest:
                i = rest.index("--budget")
                budget = int(rest[i + 1])
                rest = rest[:i] + rest[i + 2 :]
            need = 2 if verb == "path" else 1
            if len(rest) < need:
                print(f"usage: route {verb} " + ("<a> <b>" if verb == "path" else "<identifiers>"))
                return 2
            src = pick(cwd)
            g = load_graph(src) if src else None
            if g is None:
                print(NO_BUNDLE)
                return 0
            print(src.label())
            if verb == "query":
                print(render_query(g, " ".join(rest), budget))
            elif verb == "explain":
                print(render_explain(g, " ".join(rest)))
            else:
                print(render_path(g, rest[0], rest[1]))
            return 0
    except Refused as e:
        print(f"route: {e}")
        return 1
    except (ValueError, KeyError, TypeError) as e:
        print(f"route: the bundle could not be read ({type(e).__name__}); use `git grep`")
        return 1
    print(f"route: unknown command {verb!r}; route --help")
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
