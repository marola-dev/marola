---
name: routing
description: Pick the cheapest source for a question about where something lives in marola, before reading any tree. Use for cross-repo questions (what produces, consumes, pins or triggers an image, release asset, pin file, dispatch or workflow; how a change in one repo reaches another or docs.marola.dev) and for "where is X" when the keyword or the repo is unfamiliar.
---

# Routing a "where is it" question

`route` is `python3 .claude/skills/routing/scripts/route.py` in a repo that vendors this skill, or
`route` (`just route`) inside `nix develop`. It needs no Nix and no graphify: it reads the code graph
and the wiring tables that the umbrella's CI publishes from `main` on its `routing-bundle` branch,
through git. The first line of each answer says which graph answered. With no bundle it prints one
line: run `route fetch` once, or use `git grep`. In a Claude Project thread, which starts above
every clone, run it from inside the umbrella's clone.

- **What produces, consumes, pins or triggers X?** `route wiring <term>`, or the generated wiring
  block (between `<!-- wiring:start -->` and `<!-- wiring:end -->`) in the umbrella's
  `docs/2-Building-marola/REPOS.md`, also at <https://docs.marola.dev/2-Building-marola/REPOS/>.
- **Where is a symbol, with the repo unfamiliar?** `route query "<identifiers>"`, then read only the
  files it names. Ask with the code's own words, a class, file or function name ("Telegram Main",
  "Recommender"), not a description: the graph matches symbol names, not meanings. `route explain
  <node>` lists a node's neighbours, `route path <a> <b>` the shortest path between two.
- **A concept or behaviour ("where is the safety veto applied?"), or a known keyword.** `git grep`
  the concept word first, with `--recurse-submodules` in the umbrella; query the graph with the
  identifiers it turns up.
- The bundle reflects `main`, not your branch. A local `graph build` whose `HEAD` matches wins over
  it, and `route status` says which one answers.
- Never read `graph.json`, `GRAPH_REPORT.md` or a bundle file whole.
