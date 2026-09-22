# marola review rules

The full rules are in `AGENTS.md` and `.claude/rules/*.md`; this file is the subset a reviewer
can check from a diff. Report only findings that affect correctness, safety or a stated rule;
not style, not restating what a linter (scalafmt, scalafix, ruff, actionlint, hadolint) already
enforces.

## Flag (HIGH or CRITICAL)

- A hardcoded key, connection string, token or secret; a real value in `.env.example` (it holds
  placeholders only).
- Anything that provisions or deploys a paid cloud resource, or adds a workflow step that could,
  without a human gate.
- A caller typed to an implementation class (`LocalLlmClient`, `LocalVisionClient`) instead of
  its trait (`LlmClient`, `VisionClient`, `SightingStore`).
- `catch`/`Abort.catching[Throwable]` that discards the failure with a lone `case _ =>`.
- A bug fix with no test that reproduces the bug.
- Python/shell: an HTTP response parsed before its status is checked; unquoted variables in
  shell that carry paths.

## Flag (MEDIUM)

- Comments that restate the code, narrate the history of a fix, or re-explain a good name. A
  comment is justified only for a non-obvious *why*, a trap, or a pointer to a MIP/issue.
  A docstring longer than the function it documents is a defect.
- Exceptions used for an expected failure mode where an `enum` + exhaustive match fits.
- A new component that reads the wall clock, filesystem or an env var inline instead of taking
  it as a parameter with a real default.
- Kyo effects (`Sync`, `Abort`, `Env`) leaking into pure scoring/decision logic (e.g.
  `Swimability`); Kyo belongs at the I/O boundary only.
- A commit or PR body carrying attribution text other than the `Tested:`, `Cost:` and
  `Co-Authored-By: Claude` trailers.

## Context

- Scala 3 on JDK 25 with Kyo (pre-1.0; the pinned version's API may differ from current docs;
  do not flag a call as "nonexistent" from memory).
- Commit messages, not code comments, are where reasoning and evidence belong.
- A change under `docs/mips/` is a design document; review it for internal consistency and
  unsourced external claims (prices, limits, licence terms), not for code.
</content>
