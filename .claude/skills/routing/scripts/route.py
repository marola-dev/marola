#!/usr/bin/env python3
"""Route a "where is it" question with no Nix and no graphify: the code graph and the wiring
tables the umbrella's CI publishes on its `routing-bundle` branch (MIP-0084 §5.2).

route query "<identifiers>" [--budget 400]   label-matched start nodes, a breadth-first walk
route query --meaning "<question>"           nodes ranked by BM25 over their text (MIP-0085 §5.7)
route explain <node>  |  route path <a> <b>  a node's neighbours / the shortest path
route wiring <term>                          the wiring rows that mention <term>
route impact <node|file> [--depth 2]         what calls it, by file; its contract copies and readers
route why "<words>"                          the MIPs' decision rows, ranked by BM25 (MIP-0085 §5.6)
route docs <symbol|path>  |  route code <MIP-NNNN|doc>   the docs naming it / the code it names
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
    "their them they about any all some work works working a an of in on to by it do entry point "
    "qual quais quando onde como por para uma não são tem".split()
)
ENTRY_WORDS = frozenset({"entry", "entrypoint", "start", "starts", "main"})
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
    """Words, with identifiers split on case: `OpenMeteoClient` → open, meteo, client (§5.2)."""
    s = unicodedata.normalize("NFKD", str(text))
    s = "".join(c for c in s if not unicodedata.combining(c))
    s = re.sub(r"([a-z0-9])([A-Z])|([A-Z])([A-Z][a-z])", r"\1\3 \2\4", s)
    return re.findall(r"[^\W_]+", s.lower())


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
        wants_entry = bool(ENTRY_WORDS & set(raw))
        scored, best = [], {}
        for nid, d in self.nodes.items():
            nl = norm(d.get("label") or "")
            bare = nl.rstrip("()")
            ltoks = tokens(d.get("label") or "")
            lt = " ".join(ltoks)
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
                elif nl.startswith(t) or bare.startswith(t) or t in ltoks:
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
            # Tagged at build (graph.sh); only among nodes the question already matched.
            if score > 0 and wants_entry and d.get("entry"):
                score += PREFIX * 10 * jw
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
    for c, v in sorted(w.get("contracts", {}).items()):
        rows.append(f"contract {c}  copied to {cell(v.get('copies'))} · {v.get('note', '')}")
    return [clean(r) for r in rows]


def match_rows(w: dict, term: str) -> list[str]:
    words = [t.lower() for t in term.split()]
    return [r for r in wiring_rows(w) if all(x in r.lower() for x in words)]


# ---------------------------------------------------------------- impact (MIP-0085 §5.5)

# `references` too: graphify ≥0.9.74 keeps one edge per pair, often the type use over the call.
IMPACT_RELS = frozenset({"calls", "imports", "indirect_call", "references"})
LOWER_BOUND = "(lower bound: name-resolved calls only)"
FILEISH = re.compile(r"[/.][A-Za-z0-9_-]+$")


def impact_row(kind: str, value: str) -> str:
    return clean(f"  {kind:<12}  {value}")


def cell_path(e: str) -> tuple[str, str]:
    """A wiring cell `marola-ml `scripts/x.sh` (…)` → (repo, path)."""
    m = re.match(r"(\S+) `([^`]+)`", e)
    return (m[1], m[2]) if m else (e.split(" ", 1)[0], "")


def names_entry(name: str, e: str) -> bool:
    repo, path = cell_path(e)
    full = f"{repo}/{path}"
    # A workflow cell says `ci.yml`, the file is .github/workflows/ci.yml.
    return bool(path) and (
        name == full
        or full.endswith("/" + name)
        or (name.startswith(repo + "/") and name.endswith("/" + path))
    )


def reverse_walk(g: Graph, start: list[str], depth: int) -> dict[str, int]:
    callers: dict[str, list[str]] = {}
    for e in g.edges.values():
        if e.get("relation") in IMPACT_RELS:
            callers.setdefault(e["target"], []).append(e["source"])
    dist = {n: 0 for n in start}
    frontier = list(start)
    for hop in range(1, depth + 1):
        nxt = []
        for n in frontier:
            for m in callers.get(n, []):
                if m not in dist:
                    dist[m] = hop
                    nxt.append(m)
        frontier = nxt
    return dist


def code_impact(g: Graph, start: list[str], depth: int) -> list[str]:
    own = {g.nodes[n].get("source_file") for n in start}
    files: dict[str, list[tuple[int, str]]] = {}
    for n, d in reverse_walk(g, start, depth).items():
        if d:
            files.setdefault(str(g.nodes[n].get("source_file") or "?"), []).append((d, g.lab(n)))
    if not files:
        return ["(nothing reaches it by calls, imports, indirect_call or references)"]
    out = []
    for f, hits in sorted(
        files.items(), key=lambda x: (min(x[1])[0], x[0] in own, -len(x[1]), x[0])
    ):
        labs = [lab for _, lab in sorted(hits)]
        more = f" +{len(labs) - 4} more" if len(labs) > 4 else ""
        out.append(clean(f"  {min(hits)[0]}  {f}  {', '.join(labs[:4])}{more}"))
    return out


def wiring_impact(w: dict, names: list[str]) -> list[str]:
    out, arts = [], w.get("artifacts") or {}
    for c, v in sorted((w.get("contracts") or {}).items()):
        copies = v.get("copies") or []
        if not any(p == n or p.endswith("/" + n) for p in [c, *copies] for n in names):
            continue
        out.append(clean(f"contract  {c}"))
        out += [impact_row("copied to", p) for p in copies]
        # A copy reaches its repo through an artifact of the canonical's repo that the copy's repo
        # reads; the wiring cannot say which reader takes this file, so one named after it leads.
        home = c.split("/", 1)[0]
        there = {p.split("/", 1)[0] for p in copies} - {home}
        stem = set(tokens(Path(c).stem))
        readers, pins = [], []
        for key, a in sorted(arts.items()):
            if home not in (a.get("repos") or []):
                continue
            readers += [(e, key) for e in a.get("read") or [] if cell_path(e)[0] in there]
            pins += [e for e in a.get("pin") or [] if cell_path(e)[0] in there]
        readers.sort(key=lambda r: -len(stem & set(tokens(r[0]))))
        out += [impact_row("read by", f"{e} (wiring: reads {key})") for e, key in readers]
        out += [impact_row("pinned in", e) for e in pins]
    for key, a in sorted(arts.items()):
        hit = [
            e
            for e in (a.get("pin") or []) + (a.get("pub") or [])
            if any(names_entry(n, e) for n in names)
        ]
        if not hit:
            continue
        out.append(clean(f"artifact  {key}"))
        out += [impact_row("read by", e) for e in a.get("read") or []]
        out += [impact_row("pinned in", e) for e in a.get("pin") or []]
        out += [impact_row("published by", e) for e in a.get("pub") or []]
        out += [impact_row("bumped by", b) for e in hit for b in (w.get("bumps") or {}).get(e, [])]
    return out


def render_impact(g: Graph | None, w: dict, x: str, depth: int = 2) -> str:
    """Reverse calls, imports, indirect_call and references from a node or every node of a file, grouped by file,
    then the wiring: a contract's copies and readers, an artifact's readers and pins."""
    out, start, names = [], [], [x]
    if g:
        files = sorted(
            {
                str(sf)
                for d in g.nodes.values()
                if (sf := d.get("source_file")) and (sf == x or str(sf).endswith("/" + x))
            }
        )
        if files:
            start = [n for n, d in g.nodes.items() if d.get("source_file") in files]
            names = files
            out.append(clean(f"impact of {', '.join(files)} · {len(start)} nodes · depth {depth}"))
        elif not FILEISH.search(x) and not wiring_impact(w, names) and (n := g.find(x)):
            start = [n]
            names = [str(g.nodes[n].get("source_file") or x)]
            sf = g.nodes[n].get("source_file", "")
            out.append(clean(f"impact of {g.lab(n)}  {sf} · depth {depth}"))
    if start:
        out += code_impact(g, start, depth)
    out += wiring_impact(w, names)
    if not out:
        out.append(clean(f"no graph node or wiring row names {x}"))
    return "\n".join([*out, LOWER_BOUND])


# ---------------------------------------------------------------- decisions (MIP-0085 §5.6)


def bm25(docs: list[list[str]], query: list[str], k1: float = 1.5, b: float = 0.75) -> list[float]:
    n = len(docs)
    avg = sum(map(len, docs)) / n if n else 1.0
    df: dict[str, int] = {}
    for d in docs:
        for t in set(d):
            df[t] = df.get(t, 0) + 1
    scores = []
    for d in docs:
        tf: dict[str, int] = {}
        for t in d:
            tf[t] = tf.get(t, 0) + 1
        s = 0.0
        for t in set(query) & set(tf):
            idf = math.log(1 + (n - df[t] + 0.5) / (df[t] + 0.5))
            s += idf * tf[t] * (k1 + 1) / (tf[t] + k1 * (1 - b + b * len(d) / (avg or 1.0)))
        scores.append(s)
    return scores


def why_lines(data: dict | None, question: str, k: int = 5) -> list[str]:
    rows = data.get("rows") if isinstance(data, dict) else None
    rows = [r for r in rows if isinstance(r, dict)] if isinstance(rows, list) else []
    raw = tokens(question)
    q = [t for t in raw if t not in STOP] or raw
    docs = [tokens(f"{r.get('mip', '')} {r.get('text', '')}") for r in rows]
    ranked = sorted((-s, i) for i, s in enumerate(bm25(docs, q)) if s > 0)
    return [
        clean(
            f"{r.get('mip', '?')} §{r.get('section', '?')}  {r.get('text', '')} [{r.get('status', '?')}]"
        )
        for r in (rows[i] for _, i in ranked[:k])
    ]


# ---------------------------------------------------------------- doc↔code (MIP-0085 §5.4)

DOC_CODE = "doc_code.json"
DOC_CODE_MAX = 20
_doc_code: dict[int, dict] = {}


def bare(s: str) -> str:
    return str(s).strip().strip("`").lstrip(".").removesuffix("()")


def names_it(link: dict, file: str, want: str) -> bool:
    base = file.rsplit("/", 1)[-1]
    return (
        want in (file, base, base.rsplit(".", 1)[0], bare(link.get("token", "")))
        or file.endswith("/" + want)
        or (link.get("symbol") is not None and bare(link["symbol"]) == bare(want))
    )


def ranked(groups: dict[str, list[dict]], key=None) -> list[str]:
    return sorted(groups, key=key or (lambda k: (-len(groups[k]), k)))


def uniq(xs) -> list[str]:
    return list(dict.fromkeys(x for x in xs if x))


def more(lines: list[str], n: int, what: str) -> list[str]:
    return lines if n <= DOC_CODE_MAX else [*lines, f"… and {n - DOC_CODE_MAX} more {what}"]


def docs_lines(data: dict, arg: str) -> list[str]:
    """`route docs`: the docs that name a symbol or a file, the most mentions first and, among
    equals, a doc in the file's own repo."""
    want = arg.strip().strip("`").removeprefix("./")
    subs = set(data.get("repos", []))

    def repo(p: str) -> str:
        head = p.split("/", 1)[0]
        return head if head in subs else ""

    def same(tok: str) -> bool:
        tail = bare(want).rsplit("/", 1)[-1]
        return bare(tok) == bare(want) or any(w.rsplit("/", 1)[-1] == tail for w in tok.split())

    hits: dict[str, list[dict]] = {}
    amb: dict[str, list[dict]] = {}
    for doc, d in data.get("docs", {}).items():
        for x in d.get("links", []):
            if names_it(x, str(x.get("file", "")), want):
                hits.setdefault(doc, []).append(x)
        for x in d.get("ambiguous", []):
            if same(str(x.get("token", ""))):
                amb.setdefault(doc, []).append(x)

    def own(doc: str) -> bool:
        return any(repo(doc) == repo(str(x.get("file", ""))) for x in hits[doc])

    out = []
    for doc in ranked(hits, lambda k: (-len(hits[k]), not own(k), k))[:DOC_CODE_MAX]:
        secs = " ".join(uniq(x.get("section") for x in hits[doc])[:3])
        toks = ", ".join(f"`{t}`" for t in uniq(x.get("token") for x in hits[doc])[:3])
        out.append(clean(f"{doc} {secs}  {toks}" if secs else f"{doc}  {toks}"))
    out = more(out, len(hits), "docs")
    for doc in ranked(amb)[:DOC_CODE_MAX]:
        x = amb[doc][0]
        cands = ", ".join(x.get("candidates", []))
        out.append(clean(f"{doc} {x.get('section', '')}  `{x.get('token')}` ambiguous: {cands}"))
    return out or [f"no doc names {arg!r}"]


def code_lines(data: dict, arg: str) -> list[str]:
    """`route code`: the files a MIP (all its docs) or one doc names, by tf·idf, so a file every
    doc names (`AppConfig`, `Main`) ranks below the ones this doc is about."""
    want = arg.strip().strip("`").removeprefix("./")
    alld = data.get("docs", {})
    docs = [
        d
        for doc, d in alld.items()
        if (d.get("mip") or "").upper() == want.upper()
        or want in (doc, doc.rsplit("/", 1)[-1])
        or doc.endswith("/" + want)
    ]
    if not docs:
        return [f"no doc matches {arg!r}"]
    files: dict[str, list[dict]] = {}
    for d in docs:
        for x in d.get("links", []):
            files.setdefault(str(x.get("file", "")), []).append(x)
    code = data.get("code", {})

    def weight(f: str) -> float:
        df = len({x.get("doc") for x in code.get(f, [])}) or 1
        return (1 + math.log(len(files[f]))) * math.log(1 + len(alld) / df)

    out = []
    for f in ranked(files, lambda k: (-weight(k), k))[:DOC_CODE_MAX]:
        toks = ", ".join(f"`{t}`" for t in uniq(x.get("token") for x in files[f])[:3])
        out.append(clean(f"{f} · {toks}"))
    out = more(out, len(files), "files")
    amb: dict[tuple, str] = {}
    for d in docs:
        for x in d.get("ambiguous", []):
            cands = tuple(x.get("candidates", []))
            amb.setdefault(cands, f"ambiguous `{x.get('token')}`: {', '.join(cands)}")
    out += [clean(a) for a in list(amb.values())[:DOC_CODE_MAX]]
    return out or [f"{arg} names no code file"]


def doc_code_answer(src: Source | None, verb: str, arg: str) -> list[str]:
    if src is None:
        return []
    if id(src) not in _doc_code:
        _doc_code[id(src)] = src.read(DOC_CODE) or {}
    data = _doc_code[id(src)]
    return (docs_lines if verb == "docs" else code_lines)(data, arg) if data else []


# ---------------------------------------------------------------- meaning (MIP-0085 §5.7)

MEANING_K1 = 1.2  # the one setting the meaning spike ran; nothing was tuned (MIP-0085 Appendix)
NO_MEANING = "the bundle has no meaning.json yet; ask `route query` without --meaning, with the code's identifiers, or git grep"


def meaning_index(*srcs: Source | None) -> tuple[Source, dict, Graph | None] | None:
    """The first source with a meaning.json, and that source's graph: the ids are that graph's."""
    seen = set()
    for s in srcs:
        if s is None or (s.kind, s.where) in seen:
            continue
        seen.add((s.kind, s.where))
        data = s.read("meaning.json")
        if isinstance(data, dict) and isinstance(data.get("nodes"), dict):
            return s, data, load_graph(s)
    return None


def meaning_lines(data: dict, g: Graph | None, question: str, k: int = 10) -> list[str]:
    """The nodes whose text (meaning_text.py's label, docstring or comment, and path) ranks top by
    BM25, as NODE lines."""
    ids = list(data["nodes"])
    raw = tokens(question)
    q = [t for t in raw if t not in STOP] or raw
    docs = [tokens(data["nodes"][n]) for n in ids]
    ranked = sorted((-s, i) for i, s in enumerate(bm25(docs, q, k1=MEANING_K1)) if s > 0)
    return [
        g.node_line(ids[i]) if g and ids[i] in g.nodes else clean(f"NODE {ids[i]}")
        for _, i in ranked[:k]
    ]


def cmd_meaning(cwd: str | None, question: str) -> int:
    gs, ws = pick(cwd), pick(cwd, graph=False)
    if gs is None and ws is None:
        print(NO_BUNDLE)
        return 0
    found = meaning_index(gs, ws)
    print((found[0] if found else gs or ws).label())
    if found is None:
        print(NO_MEANING)
        return 0
    lines = meaning_lines(found[1], found[2], question)
    print(
        "\n".join(lines)
        if lines
        else f"no node's text matches {question!r}; try `route query` without --meaning, or git grep"
    )
    return 0


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
    items = qs.get("questions", qs) if isinstance(qs, dict) else qs
    meaning = any(q.get("via") == "meaning" for q in items) and meaning_index(src_graph, src_wiring)
    for q in items:
        if q.get("via") == "wiring":
            got = "\n".join(match_rows(w, q["ask"]))
        elif q.get("via") == "impact":
            # Right first: an expected path in the first three rows under impact's headers (a file
            # reached, a copy, a reader, a pin), never in the header naming the asked-about X.
            got = render_impact(g, w, q["ask"])
            got = "\n".join([x for x in got.splitlines() if x.startswith("  ")][:3])
        elif q.get("via") == "why":
            # Right first: an expected MIP in the first three answer lines.
            dec = src_wiring.read("decisions.json") if src_wiring else None
            got = "\n".join(why_lines(dec, q["ask"])[:3])
        elif q.get("via") in ("docs", "code"):
            got = "\n".join(doc_code_answer(src_wiring, q["via"], q["ask"])[:3])
        elif q.get("via") == "meaning" and meaning:
            # Right first as for a query; with no meaning.json, the query below answers it.
            got = "\n".join(meaning_lines(meaning[1], meaning[2], q["ask"])[:3])
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
        if verb == "impact":
            depth = 2
            if "--depth" in rest:
                i = rest.index("--depth")
                if i + 1 >= len(rest) or not rest[i + 1].isdigit():
                    print("usage: route impact <node|file> [--depth N]")
                    return 2
                depth = int(rest[i + 1])
                rest = rest[:i] + rest[i + 2 :]
            if not rest:
                print("usage: route impact <node|file> [--depth N]")
                return 2
            gs, ws = pick(cwd), pick(cwd, graph=False)
            if gs is None and ws is None:
                print(NO_BUNDLE)
                return 0
            g = load_graph(gs) if gs else None
            w = (ws.read("wiring.json") if ws else None) or {}
            label = (gs or ws).label()
            if gs and ws and gs.kind != ws.kind:
                label += f"; the wiring {ws.label()}"
            print(label)
            print(render_impact(g, w, " ".join(rest), depth))
            return 0
        if verb == "why":
            if not rest:
                print('usage: route why "<words>"')
                return 2
            src = pick(cwd, graph=False)
            if src is None:
                print(NO_BUNDLE)
                return 0
            data = src.read("decisions.json")
            print(src.label())
            if not data:
                print(
                    "the bundle has no decisions.json yet; read docs/MIPs/DECISIONS.md or git grep"
                )
                return 0
            lines = why_lines(data, " ".join(rest))
            print("\n".join(lines) if lines else f"no decision row matches {' '.join(rest)!r}")
            return 0
        if verb in ("docs", "code"):
            if not rest:
                print(
                    f"usage: route {verb} "
                    + ("<symbol|path>" if verb == "docs" else "<MIP-NNNN|doc>")
                )
                return 2
            src = pick(cwd, graph=False)
            if src is None:
                print(NO_BUNDLE)
                return 0
            if not src.read(DOC_CODE):
                print(f"route: the bundle has no {DOC_CODE} yet (MIP-0085 §5.4); use `git grep`")
                return 0
            print(src.label())
            print("\n".join(doc_code_answer(src, verb, " ".join(rest))))
            return 0
        if verb in ("query", "explain", "path"):
            budget = 400
            if "--budget" in rest:
                i = rest.index("--budget")
                budget = int(rest[i + 1])
                rest = rest[:i] + rest[i + 2 :]
            meaning = verb == "query" and "--meaning" in rest
            rest = [x for x in rest if not meaning or x != "--meaning"]
            need = 2 if verb == "path" else 1
            if len(rest) < need:
                print(f"usage: route {verb} " + ("<a> <b>" if verb == "path" else "<identifiers>"))
                return 2
            if meaning:
                return cmd_meaning(cwd, " ".join(rest))
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
