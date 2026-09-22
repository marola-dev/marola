# MIP-0055: A vector store behind `KnowledgeStore`, and a chunker that knows what kind of ocean knowledge it is cutting

| | |
|---|---|
| **Status** | Draft |
| **Author** | Claude Fable 5.1, from M. Hoffmann's design note of 2026-09-13 (issue [#354](https://github.com/h0ffmann/marola/issues/354)) |
| **Created** | 2026-09-13 |
| **Phase** | 0/1 for the local default (`ARCHITECTURE.md` §11 — nothing waits on the Telegram bot); 2 for the Azure AI Search backend, which is documented here and provisioned only after a human go-ahead |
| **Related** | MIP-0001 (the first RAG cut this replaces the store of), MIP-0022 (the safety footer — its trigger is the `safety` flag that rides on every chunk), MIP-0032 (the model × strategy matrix — "marola-RAG" is the arm this sharpens), MIP-0045 §5.1 (a TF-IDF `KnowledgeStore`; Lucene's BM25 covers the same ground in the same index — coordinate, §9), MIP-0048 (the corpus, not the parameter count, is the ceiling), MIP-0054 §5.3 (the pt-BR corpus this must index), `FUTURE-WORK.md` §9.1, `AI-103-MAPPING.md` row "RAG" (its "Azure AI Search sibling not built" note), `ARCHITECTURE.md` §5h |
| **Effort** | L — one new dependency (Lucene) and one new `KnowledgeStore` in `local/`, a typed chunk record and a kind-aware chunker in `core/`, an offline ingest pass that writes metadata, a golden set with an expected-document column, and an Azure AI Search backend in `azure/` (written, provisioned only on request). No new module, no CI workflow |
| **Gain** | `user value` — answers that cite the right passage, in either language, for a corpus that is about to grow past what a flat JSON scan and a 3B model's own embeddings can rank; `infra/dev-loop` — recall@k on a golden set makes every chunking, embedder or corpus change a number instead of a read-through; `exam coverage (AI-103 "RAG" row — closes the "Azure AI Search sibling not built" note; AI-103 "Text analysis / entity extraction" — the ingest pass is one)` |
| **Effort vs Gain** | `do next` for the local half (§5.1-§5.4, §7's golden set) — it is what MIP-0054's Portuguese corpus needs to be findable and what MIP-0032's RAG arm needs to be honest; `do when X lands` for the Azure backend (§5.5), X = Phase 1 plus a human go-ahead |
| **Depends on** | Not blocked by any MIP. Coordinates with MIP-0054 §5.3 (its `knowledge/pt-BR/` layout is what the `lang` field and `Corpus.listFiles` must agree on — today `listFiles` walks only the top level and `safety/`, so a `pt-BR/` subdirectory would be silently skipped) and with MIP-0045 §5.1 (both add a lexical retriever; §9 says which survives). The Azure backend is gated by `AGENTS.md`'s cost-and-deployment-safety rule: the Free tier costs nothing but is still a provisioned resource. `AGENTS.md`'s Phase 1 gate does not block the local half |
| **Blocked by** | none |
| **Risk** | The corpus is seven documents. A Lucene index, a typed chunker and an ingest pass are a lot of machinery for ~60 chunks, and the golden set can only show they do no harm at this size — the gain is conditional on MIP-0034/MIP-0041/MIP-0054 actually growing the corpus. If they don't, §9's "keep the JSON file, swap the embedder" alternative gets most of the retrieval improvement for a one-line change |
| **Cost so far** | — |

## 1. Summary

`--ask` and `ask_ocean_question` retrieve over `knowledge/*.md` through `FileKnowledgeStore`: a flat
JSON file, every chunk scanned with cosine, one Ollama embedding call per question, and a relevance
threshold that is `0.0` because the default embedder's scores carry no signal. This MIP puts a real
index behind the existing `KnowledgeStore` trait, an in-process Lucene HNSW + BM25 index as the
local default, Azure AI Search as the opt-in, and, more importantly, makes the ingest side typed:
each chunk knows what kind of knowledge it is, which language, which beach, and where it came from,
and carries a model-written one-line context header so a small query-time model finds it. A golden
set with an expected document per question turns "is retrieval better" into recall@k.

Nothing here touches the swim decision. Retrieval feeds explanations, tips and lore; `Swimability`
stays deterministic.

## 2. Motivation

Three gaps, all already written down in this repo.

- **The score is thrown away.** `OceanQa.DefaultMinScore = 0.0`, with the comment: `llama3.2`'s
  embeddings put irrelevant passages at 0.49-0.50 and relevant ones at 0.36-0.40
  (`docs/benchmarks/2026-09-05.md`, run 1). Relevance is decided by a second LLM call
  (`NO_ANSWER_IN_PASSAGES`), and MIP-0045 §2 calls that "an LLM call buying nothing".
- **The corpus is about to grow, and in two languages.** MIP-0054 §5.3 adds `knowledge/pt-BR/`
  mirroring every English file; MIP-0034's reading queue and MIP-0041's book ingestion feed more.
  "corrente de retorno" and "rip current" must land in the same neighbourhood, and today's chunker
  (`Corpus.chunkDocument`: split on blank lines, merge up to 700 chars) has no notion of language,
  place, or kind, a tide table and a lifeguard rule would be cut the same way.
- **Retrieval has no metric of its own.** `just benchmark` scores the *answer* (keyword coverage,
  cited, abstained). Whether the right document was in the top-4 is never measured, so a chunking
  change can only be judged by reading the per-question table.

## 3. User-visible change

`--ask` output keeps its shape; what changes is which passage is cited, and that a Portuguese
question finds the Portuguese passage once MIP-0054's corpus exists.

Before (2026-09-05 run 1, an off-corpus question, every passage "relevant" at threshold 0):

```
$ just ask "Why is the sea sometimes green?"
... [1] Rip currents (https://www.weather.gov/safety/ripcurrent) [2] Whales off Santa Catarina ...
```

After, the citation names the document that answers, and a beach-scoped question is filtered by
place before it is ranked:

```
$ just ask "Por que o mar às vezes fica verde?"
O mar fica verde quando ... [1]
Fontes: [1] Espuma e cor da água (https://oceanservice.noaa.gov/facts/oceancolor.html)

$ just ask "Tem corrente de retorno na Joaquina?"
... [1] Correntes de retorno — Praia da Joaquina ...
🛟 Salva-vidas: 193 · SAMU: 192
```

New maintainer surface, not user surface: `just knowledge-index` prints the store backend, the
embedder and its dimensions, the chunk count per kind and per language; `just benchmark` prints a
`recall@5` line per store.

## 4. Data sources and dependencies reviewed

### 4.1 Apache Lucene (local default)

`lucene-core` 10.5.1 on Maven Central (2026-08-12), Apache-2.0, runs on **Java 21 or greater**;
marola is on JDK 25. `KnnFloatVectorField` / `KnnFloatVectorQuery` give HNSW; the same index gives
BM25, so hybrid retrieval needs no second store. **Verified live** 2026-09-13 (Appendix).

The trap: `KnnVectorsFormat.DEFAULT_MAX_DIMENSIONS` is **1024**. marola's default embedder is
`llama3.2` at **3072** dimensions (confirmed against the local Ollama's `/api/show`). The Lucene
backend therefore cannot take the default embedder; it forces `nomic-embed-text` (768) or `bge-m3`
(1024). That is the right constraint anyway, the 3072-dim vectors are the ones §2 says carry no
signal, but it must be a startup error with a message, not a stack trace from Lucene. Whether a
codec can raise the cap (`Lucene99HnswVectorsFormat.getMaxDimensions`) is **not checked**.

### 4.2 hnswlib (jelmerk) — lighter in-process alternative

Java HNSW with a Scala wrapper, Apache-2.0. Version and last-release date **not checked**. Loses
to Lucene because it has no BM25 half; would only matter if Lucene's jar weight or its native-image
behaviour (§8) turns out to be a problem.

### 4.3 Qdrant — out-of-process alternative

Official Java client, Apache-2.0, last pushed 2026-09-11. Hosted free tier: single node, 0.5 vCPU,
1 GB RAM, 4 GB disk, **suspended after 1 week unused, deleted after 4 weeks**, a prototyping
tier, never the store of record. Locally it is a Docker container, and agent sessions here have no
Docker daemon (host or Actions only). Documented as an alternative backend; not built now.

### 4.4 pgvector — rejected

marola runs no Postgres (MIP-0010's MLflow ledger is local; Postgres appears only as an Azure
option there). A database process for ~60 chunks is the wrong trade.

### 4.5 Azure AI Search — the opt-in

Free tier, **verified live** 2026-09-13 against the limits page: one free service per subscription,
shared hardware, no scale-up, **50 MB storage, 3 indexes**, "might be deleted after extended periods
of inactivity", vector fields up to 4096 dimensions. Paid tiers: Basic pricing **not checked**.
It is the sibling `ARCHITECTURE.md` §5h and the AI-103 "RAG" row already name.

### 4.6 Embedding models via Ollama (no ONNX, no DJL)

The note's open question 2 asked which multilingual embedder runs on the JVM. None needs to: marola
already embeds through Ollama's `/api/embed` (`OllamaEmbedder`), and Ollama's embedding library
lists `bge-m3` (BAAI, MIT, 1024 dims, 8192 tokens, 100+ languages, dense **and** sparse output),
`nomic-embed-text-v2-moe`, `paraphrase-multilingual`, `snowflake-arctic-embed2`,
`granite-embedding`. `bge-m3` is the candidate: multilingual, exactly at Lucene's cap, and its
sparse output is a second hybrid option. Its retrieval quality on Portuguese ocean vocabulary is
**not checked**: that is what §7's golden set is for.

### 4.7 Contextual retrieval (the ingest-time header)

Anthropic's 2024 write-up: prepend a 50-100-token chunk-specific context before embedding and before
building the BM25 index. Reported top-20 retrieval-failure reduction: 35% with contextual
embeddings, 49% adding contextual BM25, 67% adding a reranker. Those numbers are on their corpora,
not ours, the pattern is adopted, the numbers are not assumed.

### Pick

Lucene as the local default, `bge-m3` as the embedder it is paired with (`nomic-embed-text` as
the smaller fallback), Azure AI Search as the opt-in. Qdrant documented, pgvector rejected.

## 5. Design

Everything builds on what `core/knowledge/` already has, `KnowledgeStore`, `Embedder`,
`CorpusChunk`, `Passage`, `Corpus`, rather than a new `VectorStore` trait beside them.

### 5.1 The chunk record (`core/src/main/scala/marola/knowledge/Corpus.scala`)

```scala
enum ChunkKind:
  case Prose, SafetyRule, Lore, Structured

enum Lang:
  case Pt, En

final case class CorpusChunk(
    docTitle: String,
    source: String,        // the document's Source: URL — unchanged
    text: String,          // verbatim, never rewritten
    safety: Boolean = false,          // MIP-0022's footer trigger — unchanged
    kind: ChunkKind = ChunkKind.Prose,
    lang: Lang = Lang.En,
    beachId: Option[String] = None,   // marola's beach id, for a filter before ranking
    sectionPath: List[String] = Nil,  // "Tides > Spring tide", prepended at embed time
    contextHeader: Option[String] = None, // model-written at ingest (§5.3); metadata, never shown
    contentHash: String = ""          // sha-256 of text; the stable id
)
```

`Passage` gains `kind` and `lang`; `SafetyFooter` keeps reading `safety`. Existing call sites
compile unchanged (every new field has a default).

### 5.2 The chunker

`Corpus.chunkDocument` becomes kind-aware, driven by front-matter lines the corpus files already
half-have (`# Title`, `Source:`) plus three optional ones: `Kind:`, `Lang:`, `Beach:`.

| Kind | Unit | Rule |
|---|---|---|
| `Prose` | one heading's section, ≤ 700 chars, split on the file's own `##` headings before the blank-line merge | `sectionPath` prepended at embed time so "spring tide" is unambiguous out of context |
| `SafetyRule` | one paragraph = one actionable rule; never merged with its neighbour | verbatim; `safety = true` when under `knowledge/safety/` (as today) |
| `Lore` | one paragraph, `beachId` required | retrieval filters by `beachId` when the question names a beach, then ranks |
| `Structured` | a Markdown table or a `Units:` block — **not chunked as prose**; the chunker rejects it with the file and line | numbers go to a typed table or to a `scoring/` proposal (§6), never through the LLM |

Cross-cutting: units normalised at ingest (m/ft, kn/km/h, °C/°F, a pure function with a spec);
`contentHash` per chunk, re-ingest replaces by hash; live data is never chunked. `Corpus.listFiles`
learns `pt-BR/` (MIP-0054 §5.3) as it learned `safety/` (MIP-0022), one level, named, no
recursive walk.

### 5.3 The ingest pass (offline, once, `just knowledge-index`)

For each chunk, `config.llmClient` (local Ollama by default; Foundry when the operator opted in)
writes: a one-sentence `contextHeader`; extracted fields, hazard, region/beach, season, any
numeric threshold with its unit; a flag for claims that look unsourced. All stored as **metadata**
beside the verbatim text. The header is embedded with the text and indexed for BM25; it is never
shown to a user, the house rule that no model-written text reaches a user is kept by construction.
A numeric threshold found here is written to `data/knowledge-thresholds.md` as a proposal, §6.

### 5.4 `LuceneKnowledgeStore` (`local/src/main/scala/marola/knowledge/`)

```scala
final class LuceneKnowledgeStore(corpusDir: String, indexDir: String, embedder: Embedder)
    extends KnowledgeStore:
  def search(query: String, k: Int = 4): List[Passage] < Sync   // hybrid: BM25 ∪ HNSW, RRF-fused
  def rebuild: Int < Sync
```

Selected by `MAROLA_KNOWLEDGE_STORE=file|lucene` in `AppConfig` (`file` stays the default until
§7's floor is met). Startup check: `embedder` dimensions ≤ 1024, else a one-line error naming
`MAROLA_LOCAL_EMBED_MODEL`. Index fingerprint = corpus files + embed model name + Lucene codec
version; a mismatch re-indexes, as `FileKnowledgeStore` does today. Fusion is reciprocal rank
fusion with one constant, tuned only against §7.

### 5.5 `AzureSearchKnowledgeStore` (`azure/src/main/scala/marola/knowledge/`)

Same trait, `azure-search-documents` SDK, one index with a vector field and the `kind`/`lang`/
`beachId` filterable fields, hybrid query. Managed identity, no key in code (`.claude/rules/azure.md`).
Written and unit-tested against a fake; provisioned only after the go-ahead `AGENTS.md` requires.

### 5.6 Where it plugs in

`OceanQa.answer` and `ChatServer.responseFor` take a `KnowledgeStore` already; `AppConfig.knowledgeStore`
returns the selected backend. `OceanBenchmark` takes a list of stores instead of one (§7).

## 6. Scoring / safety impact

None. `Swimability.score` does not read from any store. A threshold the ingest pass extracts
("surf above 1.5 m is hazardous") lands in `data/knowledge-thresholds.md` as a proposal; making it
real is its own PR against `scoring/` with its own test. MIP-0022's footer keeps firing on
`safety = true`, which the new chunker sets exactly as the old one did.

## 7. Verification plan

- **Golden set first.** `cli/src/main/resources/benchmark_questions.json` grows an `expected_doc`
  field on every in-corpus question, and from 22 to ~30 questions (the new ones in Portuguese once
  MIP-0054's files exist). `OceanBenchmark` gains `recall@5` and MRR per store, plus retrieval
  latency, and runs `file` and `lucene` side by side with the same embedder.
- **Unit tests** (no Ollama, fake embedder like `RagOfflineSpec`): `ChunkerSpec`, one chunk per
  heading, a table rejected with file and line, units normalised, `pt-BR/` listed, `safety/`
  still flagged; `LuceneKnowledgeStoreSpec`, round-trip, hybrid returns a BM25-only hit for an
  exact term the fake embedder cannot see, a 3072-dim embedder refused at construction;
  `ProvenanceSpec`, no chunk without a `source`, no `Lore` chunk without a `beachId`.
- **Live**: `just knowledge-index` with `MAROLA_KNOWLEDGE_STORE=lucene MAROLA_LOCAL_EMBED_MODEL=bge-m3`;
  `just benchmark`; a new snapshot under `docs/benchmarks/` compared against 2026-09-05.
- **Done** = recall@5 on the local default at or above an agreed floor (proposed 0.9 on the
  in-corpus questions, to be set from the first run, not before it), `rag-general` coverage not
  below 0.84, provenance spec green, and `grep -r knowledge core/src/main/scala/marola/scoring`
  empty.

## 8. Risks, limitations, and honest caveats

- Lucene under GraalVM native-image (MIP-0008's `:native` target) is **not checked**; `MMapDirectory`
  uses the Panama FFM API on JDK 21+, which native-image supports but with reachability metadata to
  hand-maintain. The fallback is `NIOFSDirectory`, or keeping `file` as the native image's store.
- Multilingual embedding quality on Portuguese ocean vocabulary is unproven here; §7 measures it.
- Free hosted tiers (Qdrant, Azure) delete inactive resources, never the store of record.
- Sources go stale; `retrievedAt` per document is worth adding to the `Source:` contract when the
  corpus is next touched, out of this MIP's scope.
- Hybrid fusion is one more knob; it is tuned only against the golden set, and the golden set is
  small.
- The 3072 → 1024 constraint changes the default embed model for anyone who switches stores; the
  README table in `knowledge/` must say so.

## 9. Alternatives considered

- **Do nothing / keyword only** (MIP-0045 §5.1's TF-IDF store): loses PT↔EN and paraphrase
  matching; keeps everything else. If Lucene lands, its BM25 half is that store with a better
  scorer, MIP-0045 §5.1 then becomes "the lexical arm of the hybrid" rather than a separate class.
  If Lucene does not land, MIP-0045 §5.1 stands.
- **Keep the JSON file, swap the embedder.** `MAROLA_LOCAL_EMBED_MODEL=bge-m3` today, with no code
  change, may recover most of the ranking signal. §7 runs this arm too; if it clears the floor, the
  Lucene store waits for the corpus to actually grow (§Risk).
- **Qdrant as default**: a process, a port and Docker for no gain at this scale.
- **A bigger model at query time**: costs on every request, does not fix chunks, and pushes toward
  model-written user text.

## 10. Exam-coverage mapping

AI-103 "RAG" row — the Azure AI Search sibling moves from "not built" to "proposed: MIP-0055";
AI-103 "Text analysis / entity extraction" — the ingest pass (§5.3) is structured extraction from
prose; AI-500 §3 "evaluate, optimize" — recall@k on a golden set.

## 11. Open questions

1. Does a Lucene codec lift the 1024-dimension default, and is it worth using? Not needed if
   `bge-m3`/`nomic-embed-text` are the pairing, decide after §7's first run.
2. Seed-corpus licences for verbatim quoting, NOAA (public domain), Marinha do Brasil / DHN,
   IMA/SC, local lifeguard bodies: each recorded before ingest; **not checked** this session.
3. The recall@5 floor: set from the first measured run.
4. Native-image: test `lucene` in `sbt cli/nativeImage` once, before making it the default.
5. **Follow-up MIP:** corpus retrieval in the *summarizer* path (issue #354 item 2, passages as an
   input to the swim-summary prompt, checked by `Reviewer`). Deliberately not here: it changes what
   the summarizer sees and needs its own benchmark arm; it needs the next MIP number.
6. **Follow-up MIP:** `retrievedAt` and a licence field in the `Source:` contract for every corpus
   file (§8).

## Appendix

### Checked live (2026-09-13)

- https://lucene.apache.org/core/downloads.html, latest release 10.5.1.
- https://repo1.maven.org/maven2/org/apache/lucene/lucene-core/, `10.5.1/` dated 2026-08-12 (also
  10.5.0 2026-06-25, 10.4.0 2026-02-25); Maven Central's search API `latestVersion` field said
  10.4.0, i.e. it lags the directory.
- https://lucene.apache.org/core/10_5_1/SYSTEM_REQUIREMENTS.html, "Apache Lucene runs on Java 21 or greater."
- https://lucene.apache.org/core/10_5_1/core/org/apache/lucene/document/KnnFloatVectorField.html:
  class exists; `constant-values.html` for the same release: `KnnVectorsFormat.DEFAULT_MAX_DIMENSIONS = 1024`,
  `DEFAULT_MAX_CONN = 16`, `DEFAULT_BEAM_WIDTH = 100`.
- Local Ollama `/api/show`: `llama3.2` embedding_length 3072; `nomic-embed-text` 768. `bge-m3` and
  `all-minilm` not pulled on this machine.
- https://ollama.com/library/bge-m3, 1.2 GB, 567M params, `ollama pull bge-m3`, "more than 100
  working languages", inputs up to 8192 tokens. https://huggingface.co/BAAI/bge-m3, dimension 1024,
  MIT licence, dense + sparse + multi-vector output.
- https://ollama.com/search?c=embedding, lists bge-m3, paraphrase-multilingual,
  snowflake-arctic-embed2, granite-embedding, nomic-embed-text-v2-moe, nomic-embed-text,
  mxbai-embed-large, qwen3-embedding.
- https://learn.microsoft.com/en-us/azure/search/search-limits-quotas-capacity (ms.date 2026-09-04):
  Free: 1 service per subscription, storage 50 MB, 3 indexes, 3 indexers, "might be deleted after
  extended periods of inactivity", 4096 max dimensions per vector field.
- https://qdrant.tech/documentation/cloud/create-cluster/, free tier 0.5 vCPU, 1 GB RAM, 4 GB disk,
  single node; suspended after 1 week unused, deleted after 4 weeks of inactivity.
- https://api.github.com/repos/qdrant/java-client, "Official Java client for Qdrant", Apache-2.0,
  pushed 2026-09-11.
- https://github.com/jelmerk/hnswlib, Java HNSW, Apache-2.0, Scala wrapper; version not shown.
- https://www.anthropic.com/news/contextual-retrieval, 35% / 49% / 67% top-20 failure-rate
  reductions (embeddings / + BM25 / + rerank).
- This repo: `OceanQa.DefaultMinScore = 0.0`; `Corpus.chunkDocument` splits on blank lines, merges
  to 700 chars, walks only the top level and `safety/`; no Markdown table in any corpus document
  today (only in `knowledge/README.md`, which is not a corpus file); no Postgres anywhere in the
  build.

### Not checked

- Whether a Lucene codec raises the 1024-dimension cap (`Lucene99HnswVectorsFormat.getMaxDimensions`).
- Lucene under GraalVM native-image.
- hnswlib's latest version and maintenance status.
- Azure AI Search Basic-tier hourly price.
- `bge-m3`'s retrieval quality on Portuguese ocean vocabulary, §7 measures it.
- Licences of the seed sources for verbatim quotation (§11.2).
- The contextual-retrieval numbers apply to Anthropic's corpora, not marola's.
