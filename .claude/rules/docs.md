---
paths: ["docs/**"]
---

# Writing docs in this repo

## MIPs (`docs/mips/`)

Design a non-trivial change here first, via the `mip` skill (`.claude/skills/mip/SKILL.md`),
before building it; see that skill for when a MIP is warranted, what to verify before writing
one, and the required template sections.

Status vocabulary (`docs/mips/README.md`'s own convention): **Draft → Accepted → Implemented**
(or **Rejected** / **Superseded**). A MIP built in stages before every task lands carries
**Partially implemented (tasks a–b of N — #PR #PR)**, naming exactly which tasks are done and what's
still missing in its own status row; never a bare "Implemented" until the whole task list
(`MIP-NNNN.tasks.md`, when it exists) is merged, and never a stale "Accepted"/"Draft" once at least
one task has actually landed.

## Everything else under `docs/`

`docs/README.md` indexes every doc and marks which ideas are MIP material; check it before
assuming something is undecided or unbuilt. When something in a doc turns out to be wrong (an
Azure API/limit/version changes, a library moves past the version pinned in `build.sbt`, etc.),
update it in the same change that discovers the problem: these are living reference docs, not a
historical snapshot of what was true when they were written.
</content>
