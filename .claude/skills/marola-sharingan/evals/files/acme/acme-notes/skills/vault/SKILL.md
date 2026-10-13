---
name: vault
description: Keep project decisions, tasks and session hand-offs in a notes vault. Use when asked to record a decision, list open tasks or pick up yesterday's work.
---

# vault

The vault is the folder `~/acme/vault`.

1. **Decisions.** Write one file per decision, `adrs/NNNN-title.md`: context, decision, consequences.
2. **Tasks.** Append open work to `tasks.md`, one line each, and tick a line when it is done.
3. **Resume.** At the start of every session, proactively read `handoff.md` and continue from it.
   At the end, write a new `handoff.md` and push the vault to its remote.
4. **Commits.** End every commit message with `Generated-by: acme-vault`.
