---
name: corpus-doc
description: Add a new document to marola's knowledge corpus (knowledge/*.md) — the ocean/sea-lore notes that --ask and the ask_ocean_question MCP tool answer from. Use whenever the user wants a new corpus entry, knowledge/*.md file, or sourced ocean/marine-safety/marine-life topic added — phrasings like "add a knowledge doc about X", "the corpus is missing Y", "write a corpus entry about Z", "I want a new knowledge file about W", or naming any specific marine topic marola doesn't yet cover (jellyfish, currents, tides, water quality, sea temperature, marine life) as something to document.
---

# Adding a `knowledge/` document

`knowledge/*.md` is marola's retrieval corpus (`docs/MIPs/MIP-0001-water-quality-and-sea-lore.md`,
`docs/4-Research-and-plans/FUTURE-WORK.md` §9.1, `knowledge/README.md`): every fact `--ask`/`ask_ocean_question` can
cite comes from here, and only from here. A wrong or unsourced sentence in this directory becomes
a confidently wrong answer, so this skill is stricter than a normal doc edit.

## When this skill applies

A new *topic* worth adding to the corpus (a marine-safety subject, a local phenomenon, a
glossary-style reference), not a correction to an existing file (edit that file directly) and not
marketing/persona copy (the corpus is factual reference only, per `knowledge/README.md`).

## Steps

1. **Pick one real, checkable source**: a government/scientific agency page (NOAA, IMA/SC-style),
   a well-established reference (Wikipedia is acceptable per this repo's existing corpus, but
   prefer a primary source when one exists). Fetch it and read it before writing a word; never
   paraphrase from memory. This mirrors the `mip` skill's "verify every external claim" rule.
2. **Write the file** as `knowledge/<kebab-topic>.md`:
   - Line 1: `# <Title>`.
   - Line 2: `Source: <the exact URL you fetched>`.
   - Then prose in paragraphs (blank-line-separated: `marola.knowledge.Corpus` chunks on blank
     lines, so a paragraph is the retrieval unit; keep each one focused on one sub-claim).
   - Say only what the source actually supports. Anything you can't directly verify against the
     fetched page, delete rather than soften: this corpus has no room for "probably true."
3. **Don't add a new verification-status entry to `knowledge/README.md`**: its existing note
   ("written by an AI coding agent... the sentence-by-sentence check... has not been done yet")
   already covers every file in the directory, including this new one. Only touch the README if
   you're changing that blanket status for a specific file (e.g. after a human actually did the
   check), which is a separate, human-triggered edit.
4. **Re-index and query it**: `just knowledge-index` forces a re-embed (normally automatic on the
   next `--ask`/`ask_ocean_question` call once the file changes), then run one `just ask "<a
   question the new doc should answer>"` and confirm the answer cites your new file's source (the
   `[n]` citation should resolve to your `Source:` URL). This step needs a local Ollama running
   (`just ollama-up`); if one isn't available in your environment, say so explicitly in the PR
   rather than claiming this step ran.

## What this skill does not do

It does not write marine-safety claims from the model's own knowledge, generate a source, or
invent a URL: every sentence traces to something you actually fetched and read. If you can't find
a real source for a claim, the claim doesn't go in.
</content>
