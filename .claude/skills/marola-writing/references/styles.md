# The styles: paths, rules, checks

Adapted from ouroboric-dev/ouroboric@b355b1d, `docs/4-ways-of-working/writing-styles.md`: one
style id for each document, the humanizer rule, and the house rules of `agent-facing`, `procedure`,
`reference` and `research`. The path table is marola's own. Licences: the root `REUSE.toml`.

Paths are from an umbrella checkout. A path that starts with `marola-<name>/` is in that
submodule. Read that repo's `AGENTS.md` first, because it can override this table.

## 1. Path defaults

Use the first row that matches. The default for all other prose is `guide`.

| Path or kind of text | Style |
|---|---|
| `AGENTS.md`, `CLAUDE.md`, `.claude/skills/**`, `.claude/rules/**`, `repomix-instruction*.md`, and the same files in a submodule | `agent-facing` |
| `docs/MIPs/MIP-NNNN.tasks.md`; the acceptance criteria of an issue | `tasks` |
| `docs/MIPs/MIP-NNNN-<slug>.md` | `mip` |
| `*.easy.md`, `*.easy.pt-BR.md` (the plain-language version of a document) | `plain` |
| `docs/MIPs/README.md`, `docs/MIPs/CANDIDATES.md`, `docs/MIPs/TEMPLATE.md`, `docs/PHASES.md`, `docs/2-Building-marola/REPOS.md`, a code repo's `4-reference` pages | `reference` |
| `docs/1-Using-marola/RUN-LOCALLY.md`, `DOCKER.md`, `TELEGRAM-SETUP.md`; `docs/3-Ways-of-working/NEW-REPO.md` | `procedure` |
| `docs/4-Research-and-plans/**`, `papers/**`, the marola-research repo | `research` |
| marola-site: `site/i18n/*.json`, `site/static/about.html`, `site/static/support.html`, `site/content/news/*.md`; the text that the bot sends to a user (marola-app) | `public-copy` |
| a commit message, a PR body, an issue comment | `change-record` |
| `README.md`, `README.pt-BR.md`, the other pages of `docs/1-Using-marola/`, `docs/2-Building-marola/` and `docs/3-Ways-of-working/`, a code repo's `docs/` and `adr/` | `guide` |

A numbered sequence of steps in a `guide` page obeys the `procedure` rules for those steps only.

## 2. Rules for each style

### `agent-facing` (readers: models)

- Use the imperative. Write one instruction in each sentence.
- Write a map, not a manual. Point to the doc that holds a rule. Do not copy the rule.
- State the hard rules of `AGENTS.md` once, in `AGENTS.md`. A rules file is an excerpt with a link.
- A skill description says when to load the skill. Do not put a model or an effort in it.
- STE100 is the default (`asd-ste100`). A humanizer pass is never permitted.

### `tasks` (readers: the agent that does the task, and its reviewer)

- One row is one PR. Each row names what it delivers and the tests that must exist first.
- Keep the columns of the existing `.tasks.md` files. The devkit's `mip-tasks` skill owns the shape.
- STE100 is the default for the cell text. A humanizer pass is never permitted.

### `mip` (readers: the owner, reviewers, and the agents that build it)

- `docs/MIPs/TEMPLATE.md` is the shape. The devkit's `mip` skill says how to fill each section.
- A `Draft` or `Accepted` MIP describes something that is not built. Do not use the present tense
  for it as if it exists.
- Each claim about a source, an API or a licence gives the URL and the date of the check.
- STE100 is not the default. Convert a MIP only when the owner asks, with `references/ste100.md`.
- A humanizer pass is permitted, last. It is not permitted on a MIP that is in STE100.

### `plain` (readers: a person outside the field, frequently in Portuguese)

- Give the answer first. Use the words that a swimmer on the beach knows.
- Keep each fact, number and limit of the source document. Link to the source document.
- Do not add a fact that the source document does not have.
- Use the Writing Skills plugin's `plain-language` for English.
- A humanizer pass is permitted, last: `marola-humanizar` for Portuguese, `humanizer` for English.

### `reference` (readers: a person or an agent that looks something up)

- Keep one copy of a table. Other files link to it (`scripts/agents_repos_check.sh` enforces this
  for the routing table of `REPOS.md`).
- Do not edit a generated block by hand. Run the recipe that writes it.
- Use the same columns, field names and status words in each row.
- STE100 is the default for new sentences. A humanizer pass is never permitted.

### `procedure` (readers: a person who follows steps)

- Number the steps. Put one action in each step.
- Put a warning before the step it applies to. Do not put an explanation in a step.
- Name only the recipes that `.claude/rules/docs.md` permits, with the checkout marker.
- STE100 is the default. A humanizer pass is never permitted.

### `research` (readers: a person who checks the claims)

- Obey the scientific-writing rule of `AGENTS.md` ("Reading and writing"). Each claim names what
  checked it, or says that nothing checked it. Each number has its unit and its source.
- Keep facts and analysis apart.
- STE100 is not the default. Convert a paper only when the owner names the file.
- A humanizer pass is permitted, last. It does not change a claim, a number, a marker or a
  citation.

### `public-copy` (readers: visitors and bot users)

- In marola-site, start with its `site-frontend` skill. It orders `ptbr-humanizer` and `news-post`.
- Do not write safety text or a marine fact. `docs/3-Ways-of-working/AGENT-SKILLS.md` §4 gives
  the reason. A fact comes from marola-corpus, through its `corpus-doc` skill.
- An error message says what occurred and what the user can do.
- Use the Writing Skills plugin's `plain-language` for English.
- A humanizer pass is permitted, last.

### `change-record` (readers: reviewers, and the person who reads `git log` later)

- Obey the "Attribution" rule of `AGENTS.md`: a body, `Tested:` and the co-author trailer.
- Do not write the PR body by hand. `just pr` writes it from `.github/PULL_REQUEST_TEMPLATE.md`.
- Put the reason for the change in the commit body, not in a code comment.
- A humanizer pass is permitted, in the skill's embedded mode.

### `guide` (readers: developers and contributors)

- Obey the prose rule of `AGENTS.md` ("Code style"): write only what the code cannot say.
- Obey `.claude/rules/docs.md` for links, landing pages, recipes and diagrams.
- Use the Writing Skills plugin's `google-dev-docs`.
- A README has a twin: change `README.md` and `README.pt-BR.md` together.
- A humanizer pass is permitted, last.

## 3. Checks that exist

| Check | What it covers |
|---|---|
| `just quality` (`docs-lint`, the scripts' self-tests) | recipes, checkout markers and repo gates; not prose style |
| `just docs` (`mkdocs --strict`) | links and anchors in the built site |
| `scripts/ste_check.py` in this skill | sentence and paragraph length, when you run it by hand |

## 4. Writing Skills skills that marola does not route to

`AGENTS.md` ("Reading and writing") names three skills of the plugin: `asd-ste100`,
`google-dev-docs` and `plain-language`. Ouroboric also maps `journalism-ap` to research,
`gov-uk-style` to public help, `business-writing` to direction documents, and `orwells-rules` and
`scott-adams` to its journal and marketing. Marola did not adopt those. Do not load one unless
the owner asks.

## 5. No prose linter

Marola has no prose linter. Ouroboric plans Vale for this; marola does not run it. Do not tell
the user that an automated check enforces a style.
