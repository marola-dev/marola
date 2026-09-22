# MIP-0022: The safety footer — every answer grounded in a safety document ends with lifeguard/193/SAMU 192, appended after the model, never by it

| | |
|---|---|
| **Status** | Implemented (Phase 0 surfaces: `--ask`, `ask_ocean_question`) — merged to main via PR #195 (`749ec54`); the `mip-0022/1-safety-footer` branch is now stale/superseded by that squash-merge |
| **Author** | Claude Fable 5.1, for M. Hoffmann (candidate K6 of the 2026-09-06 external consolidation, `docs/ROADMAP.md` §7 — the *rule* half; the corpus half is the `corpus-doc` skill, §9) |
| **Created** | 2026-09-06 |
| **Phase** | 0 (`--ask`, `ask_ocean_question`) → 1 (the bot's `/perguntar`, MIP-0002) |
| **Related** | MIP-0001 (the curated corpus and its citation rule), `core/knowledge/OceanQa.scala` (`Answer`, `NoAnswerSentinel`, `Fallback`), `core/knowledge/Corpus.scala` (`CorpusChunk`), `knowledge/README.md` (the "wrong sentence becomes a confidently wrong answer" warning), `.claude/skills/corpus-doc/SKILL.md`, `AI-103-MAPPING.md` §1 responsible AI |
| **Effort** | S — one boolean threaded from the corpus loader to `Answer`, one pure `SafetyFooter` renderer called by the three surfaces, specs; no dependency, no prompt change |
| **Gain** | user value (a first-aid or rip-current answer always ends with who to call); exam coverage (AI-103 §1 responsible AI — a deterministic safety layer the model cannot remove) |
| **Effort vs Gain** | do next — the corpus is about to gain safety documents via `corpus-doc`; the footer must exist before the first one lands, or the highest-stakes answers ship without it |
| **Depends on** | nothing for the CLI/MCP surfaces; MIP-0002 for the bot surface. Blocks: the first `knowledge/safety/*.md` PR should not merge before this does |
| **Risk** | a footer on every safety answer becomes wallpaper users skip; mitigated by keeping it to two short lines and only on answers actually grounded in a safety document, not on every answer |
| **Cost so far** | ~$73.38 (measured, `scripts/cost-split.py --estimate --json`) |

## 1. Summary

When an `--ask` / `ask_ocean_question` / `/perguntar` answer was grounded in a document under
`knowledge/safety/`, the rendered reply ends with a fixed two-line footer, who to call (guarda-
vidas / Bombeiros 193, SAMU 192) and "this does not replace professional help", appended by the
rendering code **after** the model's text. The model never sees the footer, cannot rephrase it,
and cannot drop it: a test proves it is present even when the model's output already contains a
look-alike, and even when the model returned the no-answer sentinel.

## 2. Motivation

`knowledge/README.md` already says why the corpus is strict: "a wrong sentence here becomes a
confidently wrong answer". Safety documents (rip-current escape, jellyfish first aid — the next
`corpus-doc` additions, `docs/ROADMAP.md` §7 K6) raise the stakes from *wrong* to *harmful*: a
correct paragraph about treating a sting is still not a substitute for calling for help, and a
user reading marola on the sand may not know the numbers. Today `OceanQa.answer` returns the
model's text plus the passages it used; nothing in the pipeline knows that a passage was a safety
passage, and the only place a footer could come from is the prompt — which puts safety text under
the model's control, exactly what MIP-0001's citation rule was written to avoid.

## 3. User-visible change

```
$ just ask "what do I do if I'm caught in a rip current?"
Don't fight the current: swim parallel to the shore until you are out of it, then angle back in [1].
If you can't, float and signal for help [1].

⚠️ Em emergência na água: acione os guarda-vidas ou ligue 193 (Bombeiros) / 192 (SAMU).
Esta resposta não substitui socorro profissional.
[1] https://…rip-currents…
```

- The footer appears only when at least one **used** passage came from `knowledge/safety/`. A
  question about whale season never gets it.
- It appears in the language of the surface (`pt-BR` on the bot, `en` on the CLI/MCP — the CLI's
  own convention today), from a fixed string table, not from the model.
- It is present even when the answer is the strict-mode "no passages cover this" reply to a
  safety question, so a user who asked about a sting and got no answer still gets the numbers.

## 4. Data sources and dependencies reviewed

**No new source.** Emergency numbers: 193 (Corpo de Bombeiros, the service that runs the
guarda-vidas in Santa Catarina, CBMSC) and 192 (SAMU) are the national numbers; **not fetched
for this draft** — §11.1 requires the implementing PR to cite the CBMSC/SAMU pages in the string
table's comment, the same "verify, don't guess" rule the corpus itself follows.

**Code touched (read 2026-09-06):** `Corpus.chunkDocument`/`load` produce `CorpusChunk(docTitle,
source, text)` from `knowledge/*.md` — the loader knows the file path, so a directory-based flag
costs one field. `OceanQa.answer` returns `Answer(text, passages)` and the three surfaces render
it: `cli/Main.scala:177` (`--ask`), `SwimConditionsMcpServer.scala:258` (`ask_ocean_question`),
and MIP-0002's future handler. `OceanQa.NoAnswerSentinel`/`NoPassagesReply` handle the
no-answer path; `Fallback.General` prefixes `GeneralKnowledgeLabel` — a general-knowledge answer
to a safety question is exactly where the footer matters most.

## 5. Design

**Where "safety" is decided: the directory, not a marker.** A document is a safety document iff
its path is under `knowledge/safety/`. Reasons: the corpus already keys everything on file layout
(`# Title` + `Source:` lines, `.md` in the directory); a front-matter marker would need a parser
change and is invisible in `ls`; a directory is reviewable in a PR's file list and `corpus-doc`
can say "put first-aid docs here" in one sentence. `knowledge/README.md` gains the rule.

**Scala, `core/knowledge` (deterministic, tested).**

- `CorpusChunk` gains `safety: Boolean`; `Corpus.load` sets it from the relative path
  (`safety/` prefix). The index fingerprint changes (a new field) → one automatic re-embed, as any
  corpus change causes today.
- `OceanQa.Answer` gains `safety: Boolean = passages.exists(_.chunk.safety)`, computed from the
  passages actually retained after `minScore`, i.e. the ones the model was shown.
- `object SafetyFooter { enum Lang { PtBr, En }; def render(lang: Lang): String }`, a pure
  string table, two lines, the numbers as literals with a source comment. `def append(text:
  String, safety: Boolean, lang: Lang): String` = `text` when `!safety`, else `text` + blank line
  + `render(lang)`. No trimming or matching of the model text: if the model already wrote
  something footer-like, the real footer still follows — duplicate beats absent.
- The three surfaces call `SafetyFooter.append` on `answer.text` before printing/returning;
  citations (`[n]` list) print after the footer on the CLI as today's layout dictates, or the
  footer is the last block; decided by the golden output in §7, not by taste.
- The prompt (`buildMessages`) is **unchanged**. The model is not told about the footer.

**What goes through the LLM: nothing new.** The footer is the one string in the reply the model
cannot influence.

## 6. Scoring / safety impact

No change to `Swimability`. Safety text changes: a fixed, sourced footer is added to answers
grounded in safety documents; nothing is removed or reworded. The property to protect is
*monotonic*: from this MIP on, no code path may return a safety-grounded answer without the
footer. The spec in §7 is the guard, and `corpus-doc`'s SKILL.md gets a line pointing at it.

## 7. Verification plan

- `SafetyFooterSpec` (pure): `append` is identity when `safety=false`; appends exactly
  `render(lang)` when true; a text that already ends with the footer's first line still gets the
  full footer appended (no dedup); `render` for both langs contains `193` and `192`.
- `OceanQaSpec` (fake `LlmClient`, fixture corpus with one `safety/` doc and one ordinary doc):
  a question whose retained passages include the safety doc yields `Answer.safety = true`; one
  retained from the ordinary doc only yields `false`; strict no-answer on a safety question
  yields `NoPassagesReply` with `safety = true`; `Fallback.General` on a safety question keeps
  `safety = true` (the passages were retrieved, the model declined them; the numbers still
  apply).
- Golden: `--ask` output for the rip-current question against the fixture corpus, byte-equal,
  footer as the last block before citations; the MCP `ask_ocean_question` result text ends with
  the footer.
- `corpus-doc` skill: one added line — "safety topics go under `knowledge/safety/`; the footer is
  automatic (MIP-0022), don't write one into the document".

## 8. Risks, limitations, and honest caveats

- **Footer fatigue.** Two lines, only on safety-grounded answers. If `knowledge/safety/` grows to
  dominate the corpus, most answers will carry it; acceptable, since the alternative is guessing which
  safety answers don't need it.
- **Retrieval decides, not the question.** A safety question whose passages all score below
  `minScore` and whose corpus has no safety doc yet gets no footer — the flag follows evidence.
  Until the first `knowledge/safety/` doc lands, the footer never appears; that is why this MIP
  must merge first, not why it can wait.
- **Language.** The CLI is English today and the numbers are Brazilian; the `en` footer says so
  ("in Brazil, call…"). A non-Brazil area (`site/areas.json` has Bahia/Rio — same numbers) is
  fine; a future non-Brazilian deployment needs its own table row, out of scope.
- The numbers are literals in code; a change (unlikely for 192/193) is a one-line PR with a
  source, not a config knob.

## 9. Alternatives considered

- **Put the footer in the prompt** ("always end safety answers with…"). The model can drop,
  reword or translate it, and a review-pass LLM could strip it as "repetitive". Rejected: safety
  text under model control is the thing MIP-0001 forbids.
- **Front-matter marker per document** instead of a directory. Needs a `Corpus` parser change,
  invisible in file listings, easy to forget on a new doc. Rejected for the directory.
- **Footer on every answer.** Simpler, but turns the numbers into wallpaper on whale-season
  questions. Rejected; evidence-gated instead.
- **Make the corpus part of this MIP.** No: adding sourced documents is exactly what the
  `corpus-doc` skill (MIP-0011 task 8) does, one PR per document with a fetched source; bundling
  them here would hide safety text inside a plumbing PR. This MIP is the rule those PRs rely on.
- **Do nothing.** The first first-aid document ships answers with no "call 193/192". Not chosen.

## 10. Exam-coverage mapping

`AI-103-MAPPING.md` §1 "Responsible AI": a deterministic safety layer applied after generation,
provably not removable by the model; the same argument as MIP-0008's labelled model text and
MIP-0009's deterministic labels, one step further (not just labelled, enforced).

## 11. Open questions

1. Confirm 193 as the number CBMSC wants the public to use for beach emergencies (vs 190/192
   routing); fetch and cite in the string table before merging. Proposal: 193 + 192 as drafted.
2. Should the footer also fire on `Fallback.General` answers to a safety question when *no*
   passage was retained (so `safety` would be false by the evidence rule)? Proposal: no in v1.
   The rule stays evidence-based; revisit if the benchmark shows safety questions falling to
   general mode often.
3. Placement on the CLI: footer before or after the `[n]` citation list? Proposal: before, so
   the last thing on screen is the source links, as today.
4. Should `Answer.safety` also gate the MCP tool's response metadata (a structured `safety: true`
   field) so an agent client can render it distinctly? Proposal: yes, additive, in the same PR.

## Appendix

**A. Footer strings (draft, to be sourced per §11.1).**
pt-BR: `⚠️ Em emergência na água: acione os guarda-vidas ou ligue 193 (Bombeiros) / 192 (SAMU).
Esta resposta não substitui socorro profissional.`
en: `⚠️ In a water emergency in Brazil: alert the lifeguards or call 193 (fire/rescue) / 192
(SAMU ambulance). This answer is not a substitute for professional help.`
