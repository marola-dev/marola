---
name: marola-humanizar
description: "Make Brazilian Portuguese (pt-BR) text that people read in the marola umbrella sound like a person from here wrote it, without changing a fact, a number or a safety statement; and write the plain-language Portuguese companion of an English document (`*.easy.pt-BR.md`). Use for pt-BR docs, READMEs, a MIP's easy version, papers and outreach, and when asked to \"humanizar\", \"deixa mais natural\", \"tira a cara de IA\", \"parece tradução\", \"versão fácil\", \"easy pt-BR\" or \"linguagem simples\". Not for marola.dev site copy (marola-site's `ptbr-humanizer`), English text (`humanizer`), or code, specs, procedures and agent-facing files."
---

# marola-humanizar

Edit Portuguese that a person will read. Remove the phrasing that marks a text as machine-written
or translated from English. Keep everything the text says.

Adapted from `humanizar` (fabricioctelles/skills@c107bdd, Apache-2.0, `LICENSE.humanizar` beside
this file) as vendored in h0ffmann/ww3-gpu@fe472ab, and from `ptbr-humanizer`
(marola-dev/marola-site@3f05563). [Credits](#credits) lists what came from where and what changed.

These instructions are in English, as marola's other skills and marola-site's `ptbr-humanizer`
are. The catalogue, the templates and every example are in Portuguese.

## 1. Check that this skill applies

1. Load `marola-writing` first. It resolves the document's style and says whether a humanizer
   pass is allowed. If it says no, stop and tell the user why.
2. If `marola-writing` is not available, apply this table.

| Text | Skill |
|---|---|
| pt-BR prose in this repo for a human reader: `README.pt-BR.md`, `papers/*ptbr*.md`, a pt-BR doc under `docs/`, an `*.easy.pt-BR.md`, outreach, an issue or PR text in Portuguese | this skill |
| Anything a visitor reads on marola.dev (`site/i18n/pt-BR.json`, the pt-BR articles, meta descriptions) | `ptbr-humanizer`, in a session started in marola-site |
| English prose | `humanizer` (the `marola-devkit` plugin) |
| A spec, a procedure or requirement sentences written in ASD-STE100 | none. Do not run a humanizer |
| `AGENTS.md`, `CLAUDE.md`, a `SKILL.md`, `.claude/rules/*.md`, a prompt, a `.tasks.md` | none. These are agent-facing |
| Text marola shows to a user as a fact about the sea, or a corpus note | none. That text is curated and sourced; do not generate or reword it |

3. In a file with both languages, edit only the pt-BR prose.
4. Choose the mode: [normal pass](#3-normal-mode-a-pass-over-existing-pt-br-prose) for existing
   Portuguese, [easy version](#4-easy-version-mode-easypt-brmd) for a new companion file.

## 2. Hard limits

These apply in both modes. Where an example or a reference file seems to disagree, these win.

1. Run last. Finish structure, content, links and diagrams first.
2. Do not edit: code, commands, paths, URLs, link targets, identifiers, citation keys
   (`[@key]`), wikilinks, YAML front matter, HTML, the content of a code fence or a diagram.
3. Do not change a number, a unit, a date, a version, a coordinate, a threshold or a range. Do
   not round. Do not convert a unit.
4. Do not change a quotation, a title of a cited work, a proper name, or an agency's verdict.
   `PRÓPRIA` and `IMPRÓPRIA` stay verbatim and in capitals.
5. Do not change a table's structure. Edit the prose inside a cell only.
6. Do not change the words of a heading in an existing document unless you also fix every link to
   its anchor. A change of case alone is safe.
7. Do not add a fact, a source, an example, an analogy that carries a claim, an opinion, or an
   emotion that the original lacks. If the text needs a detail it does not have, write
   `[FALTA DADO: …]` and report it.
8. Do not change a safety statement or a limitation. Do not shorten it, soften it, or move it out
   of the place where the reader meets it.
9. Do not change modality. "pode causar" stays "pode causar". "ainda não foi rodado" stays that.
10. Do not translate or remove the verification marks: `(v)`, `⚠`, *verified live*,
    *written, not run*, *not checked*, and their Portuguese forms in the document.
11. Do not aim at an AI detector, and do not use a detector's score as evidence.

## 3. Normal mode: a pass over existing pt-BR prose

1. Read the whole text. List the protected spans of section 2 before you edit.
2. Find the tells with `references/catalogo-pt-br.md`. One tell alone proves nothing; act where
   several occur together or one repeats.
3. Rewrite sentence by sentence. Apply [the marola voice](#5-the-marola-voice).
4. Leave what is already good. A text with a strong personal voice is the author's sample: keep
   its regionalisms, contractions and asides, and report tells instead of removing them.
5. Do not mark as a tell: correct grammar, a formal register the genre requires, a term of art,
   one dash, one connective, a limitation that has a function, an English word the field uses
   daily (`deploy`, `pipeline`, `ensemble`, `benchmark`, `pull request`).
6. Verify with [the final check](#6-final-check).
7. Return the rewritten text and a short list: what kind of change you made, and anything you
   could not fix without a fact from the author.

## 4. Easy-version mode: `*.easy.pt-BR.md`

An easy version is a plain-language Portuguese companion of one English document. The English
file stays the source of truth. The easy file says so and links to it.

1. Read `references/versao-facil.md`. It has the contract, the header block and the outline.
2. Name the file `<original name without .md>.easy.pt-BR.md`, beside the original. For a MIP, name
   it `MIP-NNNN.easy.pt-BR.md`, as `MIP-NNNN.tasks.md` is named: a file that matches
   `MIP-NNNN-*.md` is taken for the MIP by `just context-mip` and the devkit's MIP lookup.
3. Write from the meaning, not sentence by sentence. Do not translate the document.
4. Keep every decision, every number with its unit, every name, every MIP, issue and PR
   reference, every status, every safety statement, every limitation and every open question.
5. Shorten or drop derivations, implementation detail, test names and rejected alternatives. Each
   time, point to the section of the original that has the rest.
6. Explain a term of art once: the Portuguese term, then the English term in italics in
   parentheses. After that, use the one Portuguese term everywhere.
7. Start the file with the required header block. Do not use a bold-key table in it.
8. Write a decimal with a comma, as `papers/politica-previsor-marola-ptbr.md` does: `2.5 m`
   becomes "2,5 m". This is the only change a number may have. Versions, coordinates and
   identifiers keep the point.
9. Run section 3 on your own draft, then [the final check](#6-final-check).

## 5. The marola voice

Each rule comes from `docs/3-Ways-of-working/PHILOSOPHY.md` or from marola's own Portuguese
(`README.pt-BR.md`, `papers/politica-previsor-marola-ptbr.md`). `references/voz-marola.md` has the
source of each rule and examples.

1. **Do not sound more certain than the source.** Keep "proposto", "escrito, não rodado", "não
   conferido" exactly as strong as they are. (*What this is not*)
2. **Name the stage.** Say what is built, what is proposed and what is parked. A MIP that is
   Draft or Accepted is "proposto na MIP-NNNN", never present tense. (*Why it was built, and the
   three pillars*)
3. **Say the hard part plainly.** A cost, a limit, a "não sabemos" goes in a direct sentence,
   without a cushion before or after it. (*The one decision*)
4. **Rules decide safety; the model only writes.** Never reword a sentence so that "a IA" or "o
   modelo" decides whether the sea is safe. (*Why LLMs, and where they are not allowed*)
5. **Nothing unsourced.** Keep every source name next to the claim it supports: Open-Meteo,
   OpenStreetMap, IMA/SC, INEA, INEMA. Never "segundo especialistas". (*Why LLMs…*)
6. **Small and concrete.** Prefer one beach, one hour, one number with its unit over a general
   statement. Use only details the text already has. (*Why marola*)
7. **A sentence a person will read.** Short sentences, direct order, active voice, "você" for
   the reader. (*Why marola*)
8. **Give the reason with the rule.** Keep the "porque". Do not cut a reason to save words.
   (the page's opening paragraph)
9. **No verdict on the alternatives beyond what the source gives.** Do not add praise or scorn
   for a tool, a model or an institution. (*What this is not*)
10. **The name.** Write "o marola", lowercase, with the masculine article, also after a full
    stop when the sentence can be reordered. Keep a capital where the source has one as part of
    a title ("o previsor Marola"). Repository names stay as written: `marola-app`.

Register: a friend who knows the sea and is precise about numbers. In a README or outreach text,
"a gente" and "dá pra" are fine if the text already uses them. In a paper or an easy version of a
MIP, write plain standard Portuguese without slang. Do not force either register onto the other.

## 6. Final check

1. Extract every number with its unit from the original and from the result. The two lists must
   match. In an easy version, each number in the result must exist in the original.
2. Compare every safety statement, limitation and status word. None is missing or weaker.
3. Compare links, references (`MIP-NNNN`, `#NNN`, `owner/repo#N`) and source names. In normal
   mode none is missing or new. In an easy version none is new.
4. Confirm that no protected span of section 2 changed: `git diff --word-diff` on the file.
5. Read the result as a Brazilian reader would say it aloud. Rewrite what sounds dubbed.
6. Confirm headings are in sentence case and no em dash was added.
7. Run `just quality` in the umbrella when you changed or added a file under `docs/`: the docs
   build is strict about links.

## Credits

| Source | Licence | What came from it |
|---|---|---|
| `humanizar`, fabricioctelles/skills@c107bdd (`skills/humanizar`), © 2025 ft.ia.br, read through the patched copy in h0ffmann/ww3-gpu@fe472ab | Apache-2.0, `LICENSE.humanizar` | The factual lock (section 2, items 3, 7, 9), the false-positive guard (section 3, item 5), the pattern families of the catalogue, and the plain-Portuguese operations in `references/versao-facil.md`. Changed: rewritten in English, cut from about 3,500 lines to this folder, the voice profiles, modes, scoring and emoji removed, every example replaced with a marola one |
| `ptbr-humanizer`, marola-dev/marola-site@3f05563, © 2026 marola.dev | MIT | Rules 1, 2, 3, 6 and 9 of that skill: say it the way people say it, name things concretely, no em dash, keep what is data or a name, honesty over polish |
| `writing-styles.md`, ouroboric-dev/ouroboric@b355b1d | CC BY 4.0 | The rule that a humanizer runs last and never on spec, procedure, reference or agent-facing text |
| `humanizer`, blader/humanizer@8039b2f (the copy in marola-dev/marola-devkit@0749822) | MIT | Nothing copied. It is the English sibling this skill points to |
