@AGENTS.md

Read AGENTS.md before doing anything in this umbrella: it holds the org invariants, where a
change belongs and the submodule mechanics, and links the repo inventory. The line above imports
it for Claude Code; tools that read this file as plain text must open AGENTS.md themselves.

## Claude Code-specific additions (MIP-0011 §5 item 10)

- **`/clear` between features, `/rename` to the branch name.** Restated from `AGENTS.md`'s
  "One feature, one session" rule because it's a session-hygiene habit, not just a cost-tracking
  one: a fresh context per feature keeps a reviewer subagent's read of `git diff` uncontaminated
  by an earlier, unrelated task's reasoning still sitting in the transcript.
- **On compaction, preserve: which files were modified, which test commands were run (and their
  result), and the `Cost:` figure so far.** These three are exactly what a resumed session needs
  to avoid re-deriving state or re-running gates that already passed. Everything else (the
  back-and-forth that got there) is fine to lose.
- **Start Claude Code in the repo you are changing.** A submodule has its own `CLAUDE.md`,
  `.claude/settings.json` and repo-only skills; started from the umbrella, its `CLAUDE.md` loads
  only once files under it are read, and its settings and hooks not at all.
- **`CLAUDE.local.md`** (gitignored, see `.gitignore`) is for genuinely personal,
  machine-specific overrides, such as a local Ollama port or a preferred jail flag, that shouldn't
  be forced on every clone of this repo the way `.claude/settings.json` is. Don't put anything here
  that changes shared behavior (hooks, permissions, attribution); those belong in the committed
  `.claude/settings.json` per `AGENTS.md`'s own reasoning for why that file is committed.
</content>
