# marola — agent skills for working on this repo

Which Claude Code skills to use here, in-repo and from the **superpowers** plugin, and when. The
rule of thumb from `AGENTS.md`: every loaded skill costs context on every turn, so adopt the ones
that map to a real step of this repo's workflow and skip the rest.

Harness note: the in-repo skills (§1) are plain `SKILL.md` files that OpenCode also discovers
(`.claude/skills/` is on its search path); the superpowers plugin (§2) is Claude Code only — see
`docs/mips/MIP-0013-opencode-tryout.md`.

## 1. In-repo skills (`.claude/skills/`)

| Skill | Use when | Notes |
|---|---|---|
| `mip` | Any non-trivial change: new data source, integration, scoring change, user-visible output, autonomous behaviour | The plan layer. Produces `docs/mips/MIP-NNNN-*.md` with verified sources and open questions; implementation is a separate PR with a `Cost:` line |
| `mip-tasks` | An accepted MIP that is more than one PR of work | The delivery layer: `docs/mips/MIP-NNNN.tasks.md` (ordered tasks, each with its test) and stacked PRs, one per task, via `scripts/stack.sh start / pr / restack / status` |
| `site-frontend` | Any change to what a visitor sees on the static map (`site/static/`), or a request to make it "modern", "appealing", a better first impression | The product layer for the page: look at the built page first (its `site_check.js` stub-DOM harness runs `app.js` against `site/dist`), one type scale, the data colours untouched, a red-flags list for the generated look. Written with `writing-skills`: a baseline agent produced a gradient header, frosted pills, Tailwind hex codes and 250 unrendered CSS lines; the recipe targets exactly that |
| `voice-note-ingest` | A MIP or task references audio that needs a real transcript, or asked explicitly to transcribe a voice memo | Local Whisper only (`faster_whisper` preferred, `whisper` fallback), pt-BR default; writes `<audio>.txt` next to the file, never commits the audio, translates separately rather than via Whisper's `--task translate`, and anonymizes every name but the repo owner's by hand before the transcript reaches a tracked file. `scripts/transcribe.py --self-test` checks its own arg-parsing/output-path logic, not wired into `just quality` |
| `voice-to-feature` | Explicitly demoing "voice note to feature" end-to-end, or `/voice-to-feature` | **Demo-only, not the normal dev flow** (its own description says so): collapses transcribe → draft MIP → scaffold → Draft PR into one pass, gated at the one point that matters — `gh pr merge`/close are tool-level `disallowed-tools`, so nothing it does can reach `main` unattended. Real feature work still goes through `mip` then `mip-tasks` |

## 2. superpowers — what fits, what doesn't

The plugin (Jesse Vincent, `obra/superpowers`) ships workflow skills that activate from context.
It is **declared in the repo**, not installed by hand: `.claude/settings.json` has
`"enabledPlugins": {"superpowers@claude-plugins-official": true}`, so Claude Code installs and
enables it for whoever opens this folder and accepts the trust dialog — the nearest thing to
`nix develop` for plugins (plugin code is cached under `~/.claude/plugins`, not vendored; it is not
pinned to a version). Opt out on one machine with the same key set to `false` in
`.claude/settings.local.json`. Verify with `/plugin` → installed list. Manual install elsewhere:
`/plugin install superpowers@claude-plugins-official`. As of
2026-09-05 it lists these; the mapping to marola's workflow is ours:

| superpowers skill | Fit for marola | How it slots in |
|---|---|---|
| **brainstorming** | Yes — before a MIP | Socratic refinement of a raw idea (a WhatsApp voice note, a friend's suggestion) *into* the MIP's §1-§3. Stop when the MIP template's sections have answers. |
| **writing-plans** | Partly — overlaps the MIP | A MIP *is* the plan. Use `writing-plans` only to break an accepted MIP into ordered implementation tasks (the §7 verification plan already lists the tests). Don't produce a second plan document. |
| **executing-plans** | Yes — the execute session | Run an accepted MIP's tasks in a fresh session (`/clear`, `/rename <branch>`), with the human checkpoints at: after the first failing test, before any Azure resource, before a push. |
| **test-driven-development** | Yes — this repo's testing rule already | `AGENTS.md` asks for a failing test before a fix; the golden fixture suite and scripted-LLM specs are the harness. Red-green-refactor maps 1:1. |
| **systematic-debugging** | Yes | Especially for the effect/typing errors Kyo produces and for "works live, fails offline" fixture drift. Phase 1 of it (reproduce) = a golden test. |
| **verification-before-completion** | Yes — hard rule | Matches "report outcomes faithfully": `just build && just test && just quality`, then a live run when data paths changed, then the `Cost:` line. |
| **requesting-code-review** / **receiving-code-review** | Yes — the final review, **on request only** | Reviewer subagent per PR of a stack (BASE = the PR's base branch, HEAD = the task branch, plan = the task row); author verifies findings before acting. Pre-PR checklist = the `Cost:` line, docs updated in the same change, MIP status flipped, `FABLE_REVIEW.md` item closed if one applies. `docs/DEV-FLOW.md` §5. |
| **using-git-worktrees** | Yes, for parallel work | One worktree per MIP implementation; pairs with "one feature, one session". The ai-jail sandbox maps the repo directory, so worktrees must live *inside* it or be mapped. |
| **finishing-a-development-branch** | Yes | Squash-merge is the repo's habit; rebuild follow-ups on `origin/main` via cherry-pick rather than stacking (memory: single PR per deliverable). |
| **dispatching-parallel-agents** / **subagent-driven-development** | Selectively | Subagents are where Fable's cost is saved: research, doc review, fixture recording on Sonnet/Haiku. Not for the core scoring/safety code, which the human and the main session should read. |
| **writing-skills** | Used | `site-frontend` was the second in-repo skill (a baseline run without it, then with it); candidates for more below. |
| **using-superpowers** | Read once | Framework intro. |

## 2.1 Using superpowers here — one MIP, start to finish

Superpowers activates from context, so mostly you just work; but here is the explicit sequence
for a MIP, with which skill does what and which session it runs in:

```
Session A (plan, Fable):
  /brainstorming            → refine the raw idea until §1-§3 of a MIP have answers
  /mip                      → write docs/mips/MIP-NNNN-*.md (sources verified, open questions listed)
  (superpowers: writing-plans is skipped — the MIP is the plan)
  mip-tasks, step 1         → docs/mips/MIP-NNNN.tasks.md: ordered tasks, each with its test
  /clear

Session B..N (execute, one per task, Sonnet is usually enough):
  /rename mip-nnnn/k-slug
  scripts/stack.sh start MIP-NNNN k slug
  superpowers: executing-plans     → the task row is the plan; checkpoints = first failing test, before push
  superpowers: test-driven-development → red (the named test) → green → refactor
  superpowers: systematic-debugging    → when green won't come: reproduce as a golden/scripted test first
  superpowers: verification-before-completion → just build && just test && just quality, live check if data changed
  commit with a Cost: trailer
  scripts/stack.sh pr              → stacked PR on the previous task (base = mip-nnnn/(k-1)-*)
  superpowers: requesting-code-review → self-checklist before asking for review
  /clear

Review session (only when the human asks — "review the stack", "claude review #21"):
  per PR, bottom-up, against its own base:
  superpowers: requesting-code-review → reviewer subagent with BASE_SHA = origin/<base>, HEAD_SHA = origin/<branch>,
                                        PLAN = the task row + MIP §6/§7; fix Critical/Important, note Minor
  or /code-review <PR#> [--comment], or /code-review ultra <PR#> for the riskiest PR (user-triggered, billed)
  superpowers: receiving-code-review  → verify each finding before acting; fix → commit (Cost:) → push → just uprds

After each merge (bottom of the stack first):
  scripts/stack.sh restack         → next branch rebased onto main; scripts/stack.sh status (or just stack-sync / stack-view)
  superpowers: finishing-a-development-branch → delete the merged branch, flip the MIP when the last task lands
```

The whole loop — including how a MIP gets *accepted* and what GitHub shows for a stack — is
written once in `docs/DEV-FLOW.md`; this section is the skill-by-skill view of it.

What you don't need to invoke by name: superpowers' skills trigger on phrases like "let's plan",
"write the test first", "it's still failing" — say what you're doing and the right one loads.
`using-git-worktrees` is optional: with one task per session, a plain branch switch is enough;
use worktrees when two tasks of the same stack are in flight at once.

## 3. Skills this repo could still write (candidates for `writing-skills`)

Proposed, with hooks, rules, subagents and a permission allowlist, as
`docs/mips/MIP-0011-claude-code-best-practices.md` (task 8 is these four skills).

- **`fixture-refresh`** — re-record the golden fixtures (`docs/RUN-LOCALLY.md` §7) and bump the
  pinned date in `PipelineGoldenSpec`; the most repeated manual procedure here.
- **`benchmark-compare`** — run `just benchmark` twice at temperature 0, diff against
  `docs/benchmarks/`, and write the comparison paragraph a PR needs when it touches prompts, corpus
  or embedder.
- **`corpus-doc`** — add a `knowledge/*.md` document: title, one `Source:` URL, paragraphs, then the
  human-check note in `knowledge/README.md`, then re-index and one `just ask` that should now cite it.
- **`water-provider`** — probe a new agency feed the way MIP-0001 §4.1 probed IMA, and scaffold a
  `WaterQualityClient` + fixture + spec.

## 4. Not adopted, and why

- "Frontend design" style skills: relevant only once MIP-0005's map page exists; add then.
- Any skill that generates safety text or marine facts: violates the "no unsourced text reaches a
  user" rule (`.claude/skills/mip/SKILL.md`).
