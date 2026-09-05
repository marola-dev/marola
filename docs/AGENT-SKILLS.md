# marola — agent skills for working on this repo

Which Claude Code skills to use here, in-repo and from the **superpowers** plugin, and when. The
rule of thumb from `AGENTS.md`: every loaded skill costs context on every turn, so adopt the ones
that map to a real step of this repo's workflow and skip the rest.

## 1. In-repo skills (`.claude/skills/`)

| Skill | Use when | Notes |
|---|---|---|
| `mip` | Any non-trivial change: new data source, integration, scoring change, user-visible output, autonomous behaviour | The plan layer. Produces `docs/mips/MIP-NNNN-*.md` with verified sources and open questions; implementation is a separate PR with a `Cost:` line |

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
| **requesting-code-review** / **receiving-code-review** | Yes, lightweight | Pre-PR checklist = the `Cost:` line, docs updated in the same change, MIP status flipped, `FABLE_REVIEW.md` item closed if one applies. |
| **using-git-worktrees** | Yes, for parallel work | One worktree per MIP implementation; pairs with "one feature, one session". The ai-jail sandbox maps the repo directory, so worktrees must live *inside* it or be mapped. |
| **finishing-a-development-branch** | Yes | Squash-merge is the repo's habit; rebuild follow-ups on `origin/main` via cherry-pick rather than stacking (memory: single PR per deliverable). |
| **dispatching-parallel-agents** / **subagent-driven-development** | Selectively | Subagents are where Fable's cost is saved: research, doc review, fixture recording on Sonnet/Haiku. Not for the core scoring/safety code, which the human and the main session should read. |
| **writing-skills** | Later | When a second in-repo skill is needed — candidates below. |
| **using-superpowers** | Read once | Framework intro. |

## 3. Skills this repo could still write (candidates for `writing-skills`)

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
