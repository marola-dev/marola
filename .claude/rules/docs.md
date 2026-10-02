---
paths: ["docs/**"]
---

# Writing docs in this repo

## MIPs (`docs/MIPs/`)

Design a non-trivial change here first, via the `/marola-devkit:mip` skill (the marola-devkit plugin),
before building it; see that skill for when a MIP is warranted, what to verify before writing
one, and the required template sections.

Status vocabulary (`docs/MIPs/README.md`'s own convention): **Draft → Accepted → Implemented**
(or **Rejected** / **Superseded**). A MIP built in stages before every task lands carries
**Partially implemented (tasks a–b of N — #PR #PR)**, naming exactly which tasks are done and what's
still missing in its own status row; never a bare "Implemented" until the whole task list
(`MIP-NNNN.tasks.md`, when it exists) is merged, and never a stale "Accepted"/"Draft" once at least
one task has actually landed.

## Diagrams

A flow, lifecycle, schema or multi-actor exchange gets a rendered diagram, not an arrow chain in
prose or box-drawing in a bare fence (MIP-0068; examples in
`docs/3-Working-on-the-repo/DIAGRAMS.md`):

| Picture | Fence |
|---|---|
| flow, decision tree, DAG, lifecycle, sequence, ER, class, gantt, git history | `mermaid` (also renders on GitHub) |
| layered or zoned architecture, the module map | `d2`, or `c4plantuml` with `UpdateRelStyle($textColor="#ffffff", $lineColor="#1ac5da")` after its `!include` |
| a schema from DDL | `dbml {bg-dark=white}` |
| a chart from a results table | `vegalite {bg-dark=white}` |
| a sketch or wireframe | `excalidraw {bg-dark=white}` with `@from_file:assets/diagrams/<name>.excalidraw` |

Draw only what the prose beside it already says, and keep the prose: the text is the source of
truth. Keep a diagram under about 15 nodes; split it rather than grow it. Plain ` ```mermaid `,
never ` ```kroki-mermaid ` (`fence_prefix: ""`). `bpmn` and `diagramsnet` are off (their fences stay
code blocks). Other Kroki dialects are not colour-injected: stay inside the table. A `gitGraph` needs the
`%%{init}%%` line from `DIAGRAMS.md` (black branches otherwise); a `gantt`, `axisFormat %d %b`.
Reach for `excalidraw` only when a sketch is genuinely clearer than the ASCII/prose it would
replace (MIP-0068 §5.3) — it is not a default register, and the scene lives out of the Markdown as
a `.excalidraw` file, editable in the Excalidraw app.
Every `mermaid` fence's font is pinned automatically (`mkdocs/hooks/mermaid_font.py`, #511) —
nothing for an author to add.

## Everything else under `docs/`

`docs/index.md` indexes every doc and marks which ideas are MIP material; check it before
assuming something is undecided or unbuilt. When something in a doc turns out to be wrong (an
API/limit/version changes, a pinned dependency or tool moves on, etc.),
update it in the same change that discovers the problem: these are living reference docs, not a
historical snapshot of what was true when they were written.
</content>
