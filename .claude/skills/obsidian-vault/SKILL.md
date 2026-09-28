---
name: obsidian-vault
description: Save and resume marola work sessions through the maintainer's Obsidian vault, and keep a linked marola digest note there. Use when the user says "save this session", "write a handoff", "save context to obsidian", "load my last context", "what was I working on", "resume from the vault", "sync the vault" or "update obsidian". Not for recording a design decision (that is a MIP — the mip skill) or a convention/gotcha (AGENTS.md, .claude/rules or memory).
---

# obsidian-vault — session handoffs and a marola digest in Obsidian

Pattern from `edvmorango/nix-home-config@e541e6e` (no licence; rewritten, not copied).

The repo stays the source of truth. The vault holds two things the repo doesn't: handoff notes a
cold session can resume from, and one digest note that *links* to marola's own docs. No ADRs (a
MIP is marola's ADR), no `tasks.md` (MIP task lists and PRs), no `practices.md` (`AGENTS.md`).

## Where the vault is

```bash
V="${MAROLA_OBSIDIAN_VAULT:-$HOME/Documents/2nd-brain}"; D="$V/marola"
```

`$V` must already exist and contain `.obsidian/`. If it doesn't, say so and stop — never create
a vault anywhere else. Inside `just jail-claude` most of `$HOME` is not mapped, so the vault is
usually invisible there: add it to your own jail's `rw_maps`, or run the save from the host.
Create `$D/context/` on first write.

## Resume — "what was I working on", "load my last context"

1. **Try Claude Code first.** Same machine, conversation not cleared: that is
   `claude --continue` (latest session in this directory) or `claude --resume` (picker; sessions
   are `/rename`d to their branch). Say which fits and stop there if the user takes it — the
   full transcript beats any summary.
2. Otherwise list the newest five `$D/context/*.md` — date and topic from the filename, newest
   first — and let the user pick one or say "latest".
3. **Check it against the repo before summarizing** — handoffs go stale: does the branch still
   exist, what landed on `main` since the note's date (`git log --since`), are its PRs merged or
   closed (`gh pr view`, if `gh` works here).
4. Reply with the state, the next steps and the open questions, each marked still true / changed
   since. Don't paste the note.

## Save — "save this session", "write a handoff"

1. Topic = the branch name (sessions are named after it). Ask only when on `main`.
2. Write `$D/context/YYYY-MM-DD-<topic>.md`; the same topic on the same day updates that file.
3. For a reader with zero context, repo-relative paths, no secrets or `.env` values:

```markdown
---
project: marola
branch: <branch>
worktree: <path, if not the main checkout>
date: YYYY-MM-DD
tags: [marola, handoff]
---
# <topic>

Resume: `claude --resume <session name>` on <machine> · digest: [[marola]]

## Goal
## Done            — commit shas / PR numbers, what changed and why
## Current state   — files modified; test commands run and their result; Cost so far
## Next steps      — concrete, in order
## Open questions
## Don't retry     — what failed, and why
## Key files       — path — why it matters
```

"Cost so far" comes from `just cost-split` or `/usage`. If neither was measured, write "not
measured" rather than a guess.

## Sync — "sync the vault", "update obsidian"

Regenerate `$D/marola.md` from scratch each time; it is a view, never edited by hand:

- what marola is, one line from `AGENTS.md`, and the repo URL (`git remote get-url origin`);
- **in flight**: open PRs (`gh pr list`), local branches ahead of `origin/main`;
- **MIPs** not yet Implemented, from `docs/MIPs/README.md`, each linked to its file on GitHub;
- the last ten commits on `main`;
- the handoffs in `context/` as `[[wikilinks]]`, newest first.

If the user asks to record something the digest doesn't cover, send it to its real home: a
decision goes to the `mip` skill, a convention or gotcha becomes a proposed edit to `AGENTS.md`
or `.claude/rules/` (or a memory, if it is only personal).

## Offering

After a long session, before a `/clear`, or when a branch's PR goes up, offer once: "Save a
handoff to the vault (and refresh the digest)?" Write nothing without a yes.
