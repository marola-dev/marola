---
name: voice-to-feature
description: Presentation/demo pipeline — turn a recorded voice note directly into a scaffolded feature branch and a Draft PR, in one pass. Transcribe, draft a MIP, scaffold the implementation shape, open a Draft PR, stop. Use only when the user explicitly asks to demo turning a voice memo into a feature end-to-end, or invokes /voice-to-feature. NOT the normal marola dev flow — see the mip skill for that.
disable-model-invocation: true
allowed-tools: Bash(python3 ${CLAUDE_PROJECT_DIR}/.claude/skills/voice-note-ingest/scripts/transcribe.py *) Bash(git *) Bash(sbt *) Bash(just *) Bash(gh pr create *) Bash(gh pr edit *) Bash(gh project *) Bash(gh issue *)
disallowed-tools: Bash(gh pr merge*) Bash(gh pr close*)
---

Note: `${CLAUDE_PROJECT_DIR}` in the `allowed-tools` line above is substituted by the harness in
hooks and skills only, not in a statusLine command. This repo has already hit that mismatch once;
don't copy this pattern into `.claude/settings.json`'s `statusLine` expecting the same expansion.

# Voice → feature (demo pipeline)

## What this is, and what it deliberately gives up

marola's real dev flow (`DEV-FLOW.md`) is MIP → human reads it → Accepted → tasks → stacked
PRs → review → merge, on purpose kept as separate steps so a plausible design gets checked
before code exists. This skill collapses all of that into one pass, on purpose, to show what a
"say it, get a feature" pipeline looks like. **It is not a replacement for that flow. It is a
demonstration of the pipeline shape, gated at the one point that matters: nothing merges without
a human.** Say this to the room, don't just build it quietly.

```
🎙️ voice note  →  📝 transcript  →  📄 MIP (Draft)  →  🧬 scaffolded branch  →  🔀 Draft PR  →  👀 human
   (Whisper,          (this          (mip skill's         (structure + stubs        (gh pr        review,
    local)            session)        template)            from the MIP's §5,        create        merge,
                                                             not working logic)       --draft)      or reject
```

## Steps

1. **Transcribe.** Reuse the `voice-note-ingest` skill's script rather than duplicating it:
   ```bash
   python3 ${CLAUDE_PROJECT_DIR}/.claude/skills/voice-note-ingest/scripts/transcribe.py <audio>
   ```
   Read the resulting `.txt`. Translate to English yourself if needed (see that skill's own
   note on why Whisper's built-in translation isn't used). Anonymize any name beyond the
   repo owner's, per that skill's default.

2. **Draft the MIP.** Use the `mip` skill's template and process in full: number, slug, the
   required reading (`AGENTS.md`, `ARCHITECTURE.md` §5/§11, `FUTURE-WORK.md`), verify every
   external claim before naming it. Status starts `Draft`. If the voice note doesn't describe a
   real feature (it's a status update, a question, small talk), say so and stop here: this
   pipeline is for feature ideas, not everything that gets recorded.

3. **Scaffold, don't implement.** From the MIP's own §5 (Design), create the files and
   signatures it names (new case classes, trait methods, a new module file), following
   `AGENTS.md`'s conventions (Scala 3 strictEquality, enum-over-exceptions, the Kyo boundary
   pattern). Method bodies that need real logic get `???` (idiomatic Scala for "not yet
   implemented," compiles cleanly) or a single obviously-fake return value, never invented
   correct-looking logic: a scaffold that *looks* done is worse than one that visibly isn't.
   Add one pending or `???`-bodied test per new piece, not a full suite.

4. **Prove it compiles, not that it works.** Run `just build`. A scaffold with `???` bodies
   should compile; it should not pass `just test` yet, and that's expected: don't force tests
   green by writing fake-passing assertions to make the demo look further along than it is.

5. **Branch and Draft PR, never further.** `git checkout -b voice-feature/<slug>`, commit,
   push, then:
   ```bash
   gh pr create --draft --title "Scaffold: <feature>, from voice note (MIP-NNNN)" \
     --body "Auto-scaffolded from a voice memo via MIP-NNNN. Structure and stubs only —
   needs a human to write the actual logic, then normal review. Not ready to merge."
   ```
   If a GitHub Project board is set up (see the earlier `gh project` conversation), add the PR
   to it and set its status to something like "Needs implementation": `gh project item-add`,
   `gh project item-edit`. **Never call `gh pr merge` or mark the PR ready-for-review on the
   user's behalf**: `disallowed-tools` above blocks the merge call at the tool level, not just
   by instruction, matching the project-level `gh pr merge` deny rule from MIP-0011.

6. **Narrate the stop.** End by stating plainly what exists (a Draft PR with scaffolded
   structure) and what doesn't (working logic, tests, review). The whole point of the demo is
   that step 5 is where the machine's part ends, not a soft suggestion to look at it later.

## Why this is safe to demo

- Nothing reaches `main`: it's a Draft PR, and the merge tool is blocked outright, not just
  discouraged.
- Nothing pretends to be more finished than it is: `???` bodies and a failing `just test` are
  the honest state, not smoothed over for the demo.
- Nothing about a person is invented: the anonymization step from `voice-note-ingest` still
  applies; a live demo audience seeing an unredacted name would be the actual failure mode here.

## When not to use this

Real marola feature work. Use the `mip` skill, get it read and Accepted, then `mip-tasks` for
the stacked-PR breakdown. This skill exists to be shown, not to become how the repo actually
grows.
</content>
