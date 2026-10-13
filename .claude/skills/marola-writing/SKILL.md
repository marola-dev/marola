---
name: marola-writing
description: "Resolve the writing style of a marola document, apply its rules, and route to the right companion skill. Use before you write or revise any prose in a marola repo: a MIP or its tasks file, a page under docs/, a README, AGENTS.md, a skill or rules file, a paper, a PR body or commit message, site copy, a plain-language (easy) version. Use it also when asked to convert a document to ASD-STE100 Simplified Technical English, to shorten or simplify sentences, or to decide whether a humanizer pass is permitted."
---

# marola-writing

Each document has one **style**. The style names the readers, the rules, and whether a humanizer
pass is permitted. Resolve the style first, then write.

Adapted from ouroboric-dev/ouroboric@b355b1d, `docs/4-ways-of-working/writing-styles.md` (style
ids, resolution order, humanizer rule), and from h0ffmann/picasso@2d6f8d8,
`.claude/commands/ste100.md` (the STE100 procedure). Licences: the root `REUSE.toml`.

## 1. Resolve the style

Use the first source that gives a style:

1. The owner's instruction in this session ("put MIP-0091 in STE100").
2. A marker in the document: `style: <id>` in its YAML front matter. A file with no front matter
   has the comment `<!-- style: <id> -->` below its title.
3. The path default in the table of §2.
4. `guide`.

Add `+ste100` to the id when a document outside the STE100 defaults is in STE100
(`<!-- style: mip+ste100 -->`). Write the marker when you convert a document. Keep the marker's
style when you edit a document that has one.

Apply the style to the text that you write or change. Do not convert an existing file to its
default style unless the owner asks.

## 2. The styles

| Style | Readers | Where (short form) | STE100 | Humanizer |
|---|---|---|---|---|
| `agent-facing` | models | `AGENTS.md`, `CLAUDE.md`, `.claude/skills/`, `.claude/rules/` | default | never |
| `tasks` | the agent that builds, its reviewer | `docs/MIPs/MIP-NNNN.tasks.md`, acceptance criteria | default | never |
| `procedure` | a person who follows steps | the setup pages of `docs/1-Using-marola/`, `NEW-REPO.md`, numbered steps in any page | default | never |
| `reference` | a person who looks something up | `REPOS.md`, the MIP index, `PHASES.md`, `4-reference` pages | default | never |
| `mip` | owner, reviewers, agents | `docs/MIPs/MIP-NNNN-<slug>.md` | on the owner's request | last; never after STE100 |
| `guide` | developers, contributors | READMEs, the other `docs/` pages, ADRs | no | last |
| `research` | a person who checks claims | `docs/4-Research-and-plans/`, `papers/` | only when the owner names the file | last; claims do not change |
| `plain` | a person outside the field | `*.easy.md`, `*.easy.pt-BR.md` | no | last |
| `public-copy` | visitors, bot users | marola-site copy and news, the bot's text | no | last |
| `change-record` | reviewers | commit messages, PR bodies | no | last |

Read `references/styles.md` for the full path table and the rules of each style. Read it before
you write in a style for the first time in a session.

## 3. Rules for all styles

- `AGENTS.md` wins over this skill. The `AGENTS.md` of a submodule wins in that submodule.
- Write only what the reader cannot get from the code or from a linked doc (`AGENTS.md`, "Code
  style").
- Do not write a fact that you did not check. Say "not checked" where that is true.
- Obey `.claude/rules/docs.md` for anything under `docs/`: links, MIP status, diagrams, recipes.
- A doc change is a deploy. `docs/3-Ways-of-working/DOCS-SITE.md` says how the site is built.
- Write Portuguese text from its meaning. Do not translate word by word.

## 4. STE100

STE100 is the default for `agent-facing`, `tasks`, `procedure` and `reference` text. For a `mip`
or a `research` document, it applies only when the owner asks for that document.

1. Load the Writing Skills plugin's `asd-ste100` skill. It has the rules of the standard.
2. Read `references/ste100.md`. It has the items that do not change and the procedure. It also
   has the marola rules that win over the plugin skill.
3. Do the rewrite, then the check pass.
4. Run `python3 .claude/skills/marola-writing/scripts/ste_check.py <file>`.
5. Report each sentence that stays outside the rules, with its reason.
6. Report each requirement whose meaning or force possibly changed. The owner confirms those.

The script counts words and sentences only. Marola has no automated STE100 or style check.

## 5. The humanizer pass

- A humanizer pass removes the signs of AI text. Run it last, after all other edits.
- Run it only where the table of §2 permits it.
- Never run it on `agent-facing`, `tasks`, `procedure` or `reference` text.
- Never run it on a document in STE100. It replaces exact words with natural ones.
- It does not change a fact, a number, a name, a quotation or a citation.

| Text | Skill |
|---|---|
| English | `humanizer` (the `marola-devkit` plugin) |
| Portuguese | `marola-humanizar` (this repo) |
| Portuguese that a visitor reads on marola.dev | `ptbr-humanizer` (marola-site) |

## 6. Routing

Load the skill that owns the task. This skill gives the style; that skill gives the method.

| Task | Skill |
|---|---|
| Write or revise a MIP | `mip` (devkit plugin), with `docs/MIPs/TEMPLATE.md` |
| Write a MIP's task list | `mip-tasks` (devkit plugin) |
| Explain a sea topic or a part of marola from zero | `eli5` (this repo) |
| Take a text, a skill or a pattern from another repo | `marola-sharingan` (this repo) |
| Draw a README diagram | `architecture-diagram` (this repo) |
| Change citation or release metadata | `zenodo-release` (this repo) |
| Change what a visitor sees on marola.dev | `site-frontend` (marola-site) |
| Write a news post | `news-post` (marola-site) |
| Add a document to the knowledge corpus | `corpus-doc` (marola-corpus) |

A skill of another repo loads only in a session that starts in that repo.

The Writing Skills plugin (`writing-skills`, pinned in `.claude/settings.json`) holds the rules of
each formal style. Load its skill for the style. Do not write those rules from memory.

| Style | Writing Skills skill |
|---|---|
| `agent-facing`, `tasks`, `procedure`, `reference`, and any `+ste100` document | `asd-ste100` |
| `guide` | `google-dev-docs` |
| `plain`, `public-copy` (English) | `plain-language` |
| `mip`, `research`, `change-record` | none; the rules are in `references/styles.md` |

Never use the plugin's character voices (`yoda`, `shrek` and the others). `AGENTS.md` forbids them.

<!-- declared-plugins:start — writing plugins declared in .claude/settings.json; the coordinator adds rows -->
**Other declared plugins.** None are routed here yet.
<!-- declared-plugins:end -->

## 7. Before you finish

1. State the style that you resolved, and the source that gave it.
2. Name each skill of §5 and §6 that the task needed and that did not load. Do not skip this step;
   `AGENTS.md` ("Reading and writing") requires it.
3. Run the gates of the repo that you changed (`just quality` in the umbrella).
4. Do not say that a style check passed. Say which commands you ran.
