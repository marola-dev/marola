# marola's knowledge corpus

The documents in this directory are what `--ask` and the MCP tool `ask_ocean_question` answer
from (`docs/mips/MIP-0001-water-quality-and-sea-lore.md`, `docs/FUTURE-WORK.md` §9.1). Each file:

- starts with a `# Title` line and a `Source: <url>` line — the URL is what every answer cites;
- is prose in paragraphs; `marola.knowledge.Corpus` chunks on blank lines;
- says only what its source (or a source named in the text) supports. The model is told to answer
  from these passages only, so a wrong sentence here becomes a confidently wrong answer.

**Status of the sources:** written by an AI coding agent on 2026-09-05 against the URLs given,
which are well-known references (NOAA, IMA/SC, Wikipedia) — but the sentence-by-sentence check
against each source is a human job that has **not** been done yet. Do that before this corpus
backs a public bot (Phase 1). Anything you can't verify, delete.

## Embedding model options (`MAROLA_LOCAL_EMBED_MODEL`)

| model | size | dims | when |
|---|---|---|---|
| `llama3.2` (default) | already pulled | 3072 | zero extra download; retrieval is serviceable, not sharp |
| `nomic-embed-text` | 274MB | 768 | best retrieval quality of the three — recommended once you care about answers |
| `all-minilm` | 45MB | 384 | fastest re-index by far — use while editing the corpus a lot |

Changing the model changes the index fingerprint, so the next `--ask` re-embeds automatically;
`just ollama-up llama3.2 nomic-embed-text` pulls both. `just benchmark` shows what each buys.

The index lives at `./data/knowledge-index.json` (gitignored) and is rebuilt automatically when a
file here changes, or on demand with `just knowledge-index`. This README is not indexed (only files
ending in `.md` *and* starting with `# ` plus a `Source:` line produce chunks with a source; this
one has no `Source:` line, so its chunks would have an empty source — keep it that way and it's
harmless, or rename it to something not ending in `.md` if that ever changes).
