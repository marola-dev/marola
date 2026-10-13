# Agent skills

Which Claude Code skills marola uses, which repo has each, and when to reach for them. Every
loaded skill costs context on every turn, so adopt the ones that map to a real step of the
workflow and skip the rest.

A repo's own skills are plain `SKILL.md` files under its `.claude/skills/`, which OpenCode also
discovers; plugins (marola-devkit, superpowers, skill-creator and the pinned ones in §2.3) are
Claude Code only ([MIP-0013](../MIPs/MIP-0013-opencode-tryout.md)). A skill loads only in a
session started in the repo that has it, so start the agent in the repo you are changing.

## 1. Skills across the org

| Where | Skills and agents | Declared in |
|---|---|---|
| The `marola-devkit` plugin | `mip`, `mip-tasks`, `mip-solve-perpetual`, `triage`, `voice-note-ingest`, `voice-to-feature`, `humanizer`, `ponytail`, `ponytail-review`, `ponytail-audit`, `sharingan`/`skill-copy`, `obsidian-vault`; the agents `mip-reviewer` and `mip-claims-auditor` | every repo's `.claude/settings.json`, at the devkit tag |
| marola-site | `site-frontend`, the entry point for anything a visitor sees, which orders the others: `ptbr-humanizer`, `citizen-science-site`, and vendored design, testing and `mapbox-*` skills | its `.claude/skills/` |
| marola-corpus | `corpus-doc` (adding a document) | its `.claude/skills/` |
| marola-app | no skills; the Scala and Kyo rules (`.claude/rules/scala.md`) and the `jar-verifier` agent | its `.claude/` |
| the umbrella | `eli5` (explaining a sea or marola topic from zero; it reads the corpus and the app through the submodules); `architecture-diagram` (a README diagram as a hand-written SVG, adapted from Cocoon-AI/architecture-diagram-generator@4b9087d, MIT); `zenodo-release` (cutting a citable release and keeping `.zenodo.json` right, MIP-0079, adapted from h0ffmann/ww3-gpu's `release`); `citation-cff` (exporting and checking the citation, vendored unchanged from zircote/github-social@4fa6579, MIT); `marola-writing` (resolving a document's writing style and its rules, ASD-STE100 among them); `marola-humanizar` (humanizing pt-BR prose and writing a MIP's `*.easy.pt-BR.md` plain-language version); `marola-sharingan` (Read-Evaluate-Adopt from another repo, with a licence scan) | its `.claude/skills/` |
| marola-ml, marola-oods | no skills of their own | — |
| superpowers, skill-creator | §2 and §2.2 | the umbrella's and marola-app's `.claude/settings.json` |
| writing-skills and the `marola-pins` plugins | §2.3 | the umbrella's `.claude/settings.json` |

Each repo's `AGENTS.md` says when its skills apply. A skill vendored byte for byte from another
repository gets a row in a per-repo `.claude/skills/skills.lock` (`skills-vendor check` verifies
the copy) and a weekly reviewed update PR from that repo's `skills.yml`
([MIP-0080](../MIPs/MIP-0080-vendored-skills-lock.md), Draft; the umbrella's row is
`citation-cff`). corpus's skill runs marola's recipes, so those
steps need a marola-app checkout pointed at the corpus with `MAROLA_KNOWLEDGE_DIR`, as its
`AGENTS.md` says.

## 1.1 marola-devkit plugin skills

What each plugin skill and agent is for, and which a person must start by hand, is on the devkit's
[plugin](https://docs.marola.dev/5-Repos/marola-devkit/4-reference_plugin/) page. Where a vendored
skill disagrees with a repo's `AGENTS.md`, `AGENTS.md` wins.

## 2. superpowers — what fits, what doesn't

The plugin (Jesse Vincent, `obra/superpowers`) ships workflow skills that activate from context. It
is **declared in the repo**, not installed by hand: `.claude/settings.json` has `"enabledPlugins":
{"superpowers@claude-plugins-official": true}`, so Claude Code installs and enables it for whoever
opens the umbrella or marola-app and accepts the trust dialog: the nearest thing to `nix develop`
for plugins (plugin code is cached under `~/.claude/plugins`, not vendored; it is not pinned to a
version). Opt out on one machine with the same key set to `false` in `.claude/settings.local.json`.
Verify with `/plugin` → installed list. Manual install elsewhere: `/plugin install
superpowers@claude-plugins-official`. As of 2026-09-05 it lists these; the mapping to marola's
workflow is ours:

| superpowers skill | Fit for marola | How it slots in |
|---|---|---|
| **brainstorming** | Yes — before a MIP | Socratic refinement of a raw idea (a WhatsApp voice note, a friend's suggestion) *into* the MIP's §1-§3. Stop when the MIP template's sections have answers. |
| **writing-plans** | Partly — overlaps the MIP | A MIP *is* the plan. Use `writing-plans` only to break an accepted MIP into ordered implementation tasks (the §7 verification plan already lists the tests). Don't produce a second plan document. |
| **executing-plans** | Yes — the execute session | Run an accepted MIP's tasks in a fresh session (`/clear`, `/rename <branch>`), with the human checkpoints at: after the first failing test, before any paid cloud resource, before a push. |
| **test-driven-development** | Yes — the testing rule already | `AGENTS.md` asks for a failing test before a fix; marola-app's golden fixture suite and scripted-LLM specs are the harness. Red-green-refactor maps 1:1. |
| **systematic-debugging** | Yes | Especially for the effect/typing errors Kyo produces and for "works live, fails offline" fixture drift. Phase 1 of it (reproduce) = a golden test. |
| **verification-before-completion** | Yes — hard rule | Matches "report outcomes faithfully": the repo's gates, then a live run when data paths changed, then the `Tested:` line. |
| **requesting-code-review** / **receiving-code-review** | Yes — the final review, **on request only** | Reviewer subagent per PR of a stack (BASE = the PR's base branch, HEAD = the task branch, plan = the task row); author verifies findings before acting. Pre-PR checklist = docs updated in the same change, MIP status flipped. [DEV-FLOW §5](DEV-FLOW.md#5-final-review-only-when-asked). |
| **using-git-worktrees** | Yes, for parallel work | One worktree per MIP implementation; pairs with "one feature, one session". The ai-jail sandbox maps the repo directory, so worktrees must live *inside* it or be mapped. |
| **finishing-a-development-branch** | Yes | Squash-merge is the habit; rebuild follow-ups on `origin/main` via cherry-pick rather than stacking. |
| **dispatching-parallel-agents** / **subagent-driven-development** | Selectively | Subagents are where Fable's cost is saved: research, doc review, fixture recording on Sonnet/Haiku. Not for the core scoring/safety code, which the human and the main session should read. |
| **writing-skills** | Used | marola-site's `site-frontend` was written with it (a baseline run without it, then with it); candidates for more below. |
| **using-superpowers** | Read once | Framework intro. |

## 2.1 Using superpowers here — one MIP, start to finish

Superpowers activates from context, so mostly you just work; but here is the explicit sequence
for a MIP, with which skill does what and which session it runs in:

```mermaid
%%{init: {"flowchart": {"nodeSpacing": 15, "rankSpacing": 25}}}%%
flowchart LR
  subgraph sessionA["Session A (plan, Fable)"]
    direction TB
    a1["/brainstorming<br/>(until §1-§3<br/>have answers)"]
    a2["/marola-devkit:mip: write<br/>MIP-NNNN-*.md<br/>(writing-plans skipped)"]
    a3["mip-tasks step 1:<br/>MIP-NNNN.tasks.md"]
    a4["/clear"]
    a1 --> a2 --> a3 --> a4
  end
  subgraph sessionBN["Session B..N (execute, one per task, Sonnet)"]
    direction TB
    b1["/rename +<br/>stack start"]
    b2["executing-plans<br/>(task row = plan)"]
    b3["TDD -><br/>debugging -><br/>verification"]
    b4["commit (Tested:) -><br/>stack pr"]
    b5["requesting-code-review<br/>(self-checklist) -> /clear"]
    b1 --> b2 --> b3 --> b4 --> b5
  end
  subgraph reviewSession["Review (on request)"]
    direction TB
    r1["requesting-code-review:<br/>reviewer subagent,<br/>bottom-up"]
    r2["receiving-code-review:<br/>verify -> fix -><br/>commit -> push -> uprds"]
    r1 --> r2
  end
  subgraph afterMerge["After each merge"]
    direction TB
    m1["stack restack"]
    m2["finishing-a-<br/>development-branch"]
    m1 --> m2
  end
  sessionA --> sessionBN --> reviewSession --> afterMerge
```

The whole loop (including how a MIP gets *accepted* and what GitHub shows for a stack) is
written once in [DEV-FLOW](DEV-FLOW.md); this section is the skill-by-skill view of it.

What you don't need to invoke by name: superpowers' skills trigger on phrases like "let's plan",
"write the test first", "it's still failing": say what you're doing and the right one loads.
`using-git-worktrees` is optional: with one task per session, a plain branch switch is enough;
use worktrees when two tasks of the same stack are in flight at once.

## 2.2 skill-creator — declared like superpowers

`skill-creator@claude-plugins-official` (Anthropic, Apache-2.0) is in `enabledPlugins` next to
superpowers rather than vendored: its SKILL.md, scripts and eval viewer track upstream, and a copy
here would drift. Use it for the parts of skill work `writing-skills` leaves to you:

- **Evals** — skill-creator's two formats: `{prompt, expected_output, assertions}` output cases in
  `evals/evals.json`, `{query, should_trigger}` trigger sets in `evals/trigger-evals.json`
  (marola-corpus's `corpus-doc` predates the split and keeps its trigger set in `evals.json`).
  `scripts/quick_validate.py` checks frontmatter against the portable spec only, so it flags
  Claude Code's own keys (`model`, `effort`, `argument-hint`, …) — expected.
- **Description tuning** — its trigger-rate loop, when a skill fires too often or never.
- **A new skill from scratch.** Porting one from another repo is `marola-sharingan` here and the
  devkit's `sharingan` elsewhere, which hands off to skill-creator for the evals step when it is
  installed.

## 2.3 Skills from outside marola

Every skill set the umbrella takes from another repository. None is copied into the tree: Claude
Code fetches each one at the pin, so no row needs `skills.lock`
([MIP-0080](../MIPs/MIP-0080-vendored-skills-lock.md) covers the copied ones).

| Skill or plugin | Upstream | Pin | Licence | `settings.json` | For |
|---|---|---|---|---|---|
| `superpowers` | [obra/superpowers](https://github.com/obra/superpowers) | unpinned (official marketplace) | MIT | declared | the workflow skills of §2 |
| `skill-creator` | [anthropics/claude-plugins-official](https://github.com/anthropics/claude-plugins-official) | unpinned (official marketplace) | Apache-2.0 | declared | skill evals and description tuning (§2.2) |
| `marola-devkit` | [marola-dev/marola-devkit](https://github.com/marola-dev/marola-devkit) | `v0.8.3` | MIT | declared | the plugin skills of §1.1 |
| `writing-skills` (16 skills) | [surendranb/writing-skills](https://github.com/surendranb/writing-skills) | `v0.6.0` (`2e692b8`) | MIT | declared | `asd-ste100`, `google-dev-docs`, `plain-language` |
| `doc-coauthoring` | [anthropics/skills](https://github.com/anthropics/skills) | `dbd4588` | none in the skill; the README says "many skills" are Apache-2.0 | declared | drafting a MIP or a doc in three stages, the last a test by a reader with no context |
| `andrej-karpathy-skills` (`karpathy-guidelines`) | [forrestchang/andrej-karpathy-skills](https://github.com/forrestchang/andrej-karpathy-skills) | `2c60614` | MIT (README and `plugin.json`; no `LICENSE` file) | declared | small, surgical changes with stated assumptions |
| `emil-design-eng`, `review-animations` | [emilkowalski/skill](https://github.com/emilkowalski/skill) | `2d22863` | MIT | declared | UI polish and an animation review, for the docs site and what marola-site shows |
| `grill-me`, `grill-with-docs`, `grilling`, `domain-modeling` | [mattpocock/skills](https://github.com/mattpocock/skills) | `49dd158` | MIT | declared | an interview that settles a plan before the MIP, and the glossary and ADRs it produces |
| `writing-for-agents` | mattpocock/skills | `49dd158` | MIT | declared | editing `AGENTS.md`, `CLAUDE.md` and skills |
| `fact-check` | [ArchieSA/skills](https://github.com/ArchieSA/skills) | `71c4b63` | MIT | declared | each technical claim in a text against a primary source or a command, with a verdict and the source per claim, and the unverified ones named |
| `scientific-writing` | [K-Dense-AI/claude-scientific-skills](https://github.com/K-Dense-AI/claude-scientific-skills) | `a1043ef` | MIT | declared | a paper's structure with every claim and number tied to a source record, and local checks of numbers and references |
| `mattpocock-skills` (all 27) | mattpocock/skills | `c55ee46`, set by the official marketplace | MIT | listed | `/plugin install mattpocock-skills@claude-plugins-official` |
| `example-skills` (12, with `internal-comms` and `brand-guidelines`) | anthropics/skills | none | Apache-2.0 in 11 of the 12 | listed | `/plugin marketplace add anthropics/skills`, then `/plugin install example-skills@anthropic-agent-skills` |
| `engineering`, `marketing`, `product-management` (8 to 10 skills each) | [anthropics/knowledge-work-plugins](https://github.com/anthropics/knowledge-work-plugins) | none | Apache-2.0 | listed | `/plugin marketplace add anthropics/knowledge-work-plugins`, then `/plugin install engineering@knowledge-work-plugins` |

The pinned rows are one inline marketplace, `marola-pins`, in `.claude/settings.json`: each skill
is a plugin of its own, a `git-subdir` source with the commit in `sha`, so a session loads those
eleven descriptions (about 640 tokens) and not every skill their repositories hold. A
skill is then `/<plugin>:<skill>`, for example `/grill-me:grill-me`. A marketplace `ref` takes a
branch or a tag, not a commit: pin a commit in a plugin's `sha`. To move a pin, change the `sha`
and this table together. A cloud session does not install a project's plugins (MIP-0080 §9).

`AGENTS.md` wins where a pinned skill differs from it. `scientific-writing` tells the agent to
add K-Dense's own paper to a manuscript's references: do that only when an author asks. Its
reporting guidelines are biomedical (CONSORT, PRISMA, STROBE); an ocean-forecasting paper uses
its claim and number records, not those checklists. `fact-check` checks any text; the devkit's
`mip-claims-auditor` stays the audit of a MIP's §4, with its limit of three fetches.

## 3. Skills still to write (candidates for `writing-skills`)

Proposed, with hooks, rules, subagents and a permission allowlist, as
[MIP-0011](../MIPs/MIP-0011-claude-code-best-practices.md) (task 8 is these four skills). Each
would live in the repo whose code it touches.

- **`fixture-refresh`** (marola-app): re-record the golden fixtures
  ([marola-app's testing](https://docs.marola.dev/5-Repos/marola-app/3-development/#re-recording-the-fixtures))
  and bump the pinned date in `PipelineGoldenSpec`; the most repeated manual procedure.
- **`benchmark-compare`** (marola-ml): run the benchmark twice at temperature 0, diff against
  marola-ml's kept runs, and write the comparison paragraph a PR needs when it touches prompts,
  corpus or embedder.
- **`corpus-doc`**: written, in marola-corpus: a document with a title, one `Source:` URL and
  paragraphs, then one question to marola that should cite it.
- **`water-provider`** (marola-app): probe a new agency feed the way MIP-0001 §4.1 probed IMA, and
  scaffold a `WaterQualityClient` + fixture + spec.

## 4. Not adopted, and why

- Any skill that generates safety text or marine facts: violates the "no unsourced text reaches a
  user" rule (`/marola-devkit:mip`).
- Pocock's `tdd` and `diagnosing-bugs`: superpowers' `test-driven-development` and
  `systematic-debugging` already hold those steps (§2), and two skills on one trigger compete.
  `to-spec`, `to-tickets` and `triage` overlap the devkit's `mip`, `mip-tasks` and `triage`.
- Anthropic's `internal-comms` (company status formats), `brand-guidelines` (Anthropic's own
  brand) and `document-skills` (`docx`, `pdf`, `pptx`, `xlsx`: source-available, not open source,
  and no marola document is in those formats).
- Anthropic's knowledge-work plugins: one writing skill in each (`documentation`, `draft-content`,
  `write-spec`) beside seven to nine that no marola step uses, and `writing-skills`, `news-post`
  and `mip` already cover them.
- The rest of Emil Kowalski's set (animation building, Swift, Expo): marola-site vendors the ones
  it uses.
- Other fact-check skills: `mronkko/claude-academic-research`'s checks citations against Zotero
  and needs its whole plugin, API keys and two more skills; `petar-nauka/fact-check-skill` and
  `cellear/claude-fact-check-skill` are for news and social-media claims;
  `FuturizeRush/fact-check-skill` does the same work as the one adopted in four times the text.
- Other scientific-writing skills: `dvdsosa/scientific-writing-skills` has oceanography examples,
  but its `sciwrite-manuscript` needs `sciwrite-prose` for its checks and does not tie a claim to
  a source; Orchestra-Research's `ml-paper-writing` is for ML conferences and its citation step
  needs network packages. K-Dense's `citation-management` calls outside search services, and
  `scientific-writing` does not need it.
