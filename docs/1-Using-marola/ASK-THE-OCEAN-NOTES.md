# Ask the ocean notes

Questions answered from [marola-corpus](https://github.com/marola-dev/marola-corpus)'s sourced
notes, with the sources printed after the answer, on your own Ollama (local RAG, MIP-0001). Moved
from [Run it locally](RUN-LOCALLY.md) §5.1. You need that page's §1–3 first: a marola-app checkout,
Ollama serving, and the two models, an LLM and an embedder.

## Ask a question

```bash
# in a marola-app checkout
just ask "what should I do if I get caught in a rip current?"
```

The recipe, in a marola-app checkout, unpacks the corpus release pinned in `corpus.version` into
`.tmp/knowledge`, embeds it into `./data/knowledge-index.json` on the first run, and re-embeds only
when a corpus file or the embed model changes. A run on 2026-10-04 with `llama3.2:1b` and
`nomic-embed-text`, sbt's lines cut:

```text
Searching .tmp/knowledge (embedder: nomic-embed-text) and asking llama3.2:1b...

[1] You should stay calm and float to conserve energy. Swim parallel to the shoreline, across the current, until you are out of it, then swim back to the beach at an angle away from the rip. If you cannot escape, float or tread water and wave and shout for help.

[3] If you can't escape, you should float or tread water.
Sources:
  [1] Rip currents — https://www.weather.gov/safety/ripcurrent (score 0.78)
  [2] Rip currents — https://www.weather.gov/safety/ripcurrent (score 0.74)
  [3] Rip currents — https://www.weather.gov/safety/ripcurrent (score 0.72)
  [4] Jellyfish, the Portuguese man o' war, and what to do about a sting — https://en.wikipedia.org/wiki/Portuguese_man_o%27_war (score 0.58)

⚠️ Em emergência na água: acione os guarda-vidas ou ligue 193 (Bombeiros) / 192 (SAMU).
Esta resposta não substitui socorro profissional.
```

The emergency footer is added whenever a passage comes from the corpus's safety notes (MIP-0022).
A passage scoring under `MAROLA_ASK_MIN_SCORE` (0.0 by default) counts as irrelevant. With no
relevant passage, the answer comes from the model's general knowledge with an "unsourced" label,
or, with `MAROLA_ASK_FALLBACK=strict`, marola abstains. Both are in the app's
[configuration reference](https://docs.marola.dev/5-Repos/marola-app/4-reference_config/#models-and-the-corpus).

**The embedder.** The app's default embedder is the chat model `llama3.2`, which current Ollama
refuses (`HTTP 501 ... This server does not support embeddings`, marola-dev/marola-app#12). Set
`MAROLA_LOCAL_EMBED_MODEL` to an embedding model; what each one costs and buys is
[Choosing the embedder](https://docs.marola.dev/5-Repos/marola-app/4-reference_config/#choosing-the-embedder).

## Measure it: the benchmark

Whether the corpus beats the model's own knowledge, and by how much, is what the benchmark
measures: 22 ocean questions, each answered by a plain prompt (`baseline`) and by marola with the
strict and the general fallback.

```bash
# in a marola-app checkout
just benchmark
```

It prints the report and saves it as `./data/benchmark-<yyyyMMdd-HHmm>.md`. The summary table of the
same 2026-10-04 run (28 s on CPU):

```text
Benchmarking 22 questions x 3 arms on llama3.2:1b (embedder nomic-embed-text) - a few minutes on CPU...

| arm | coverage (in-corpus) | coverage (general) | coverage (all) | cited | abstained | mean ms |
|---|---|---|---|---|---|---|
| baseline | 0.43 | 0.88 | 0.67 | 0% | 0% | 246 |
| rag-strict | 0.67 | 0.32 | 0.48 | 82% | 5% | 777 |
| rag-general | 0.40 | 0.31 | 0.35 | 77% | 0% | 178 |
```

The report goes on with a verdict and a row per question. Compare it with marola-ml's kept reference
run,
[`docs/benchmarks/2026-09-05.md`](https://github.com/marola-dev/marola-ml/blob/main/docs/benchmarks/2026-09-05.md),
and what it taught; that run embedded with `llama3.2`, so a run with another embedder is not a
like-for-like comparison. How marola-ml gates its published model on this benchmark is
[The benchmark gate](https://docs.marola.dev/5-Repos/marola-ml/3-development_benchmark-gate/). The
flags and arms are in the app's
[CLI reference](https://docs.marola.dev/5-Repos/marola-app/4-reference_cli/#benchmark).

## The marola model variant

`marola-llama3.2` is `llama3.2` with marola's persona and decoding settings in an Ollama
Modelfile; no weights change. marola-ml builds it:

```bash
# in a marola-ml checkout
just finetune-model
```

```bash
# in a marola-app checkout
MAROLA_LOCAL_LLM_MODEL=marola-llama3.2 just run -- --lat -27.6733 --lon -48.4700 --summarize
```

On 2026-10-04 its draft put Praia da Armação in Portimão, Portugal, and the reviewer scored it
35/100 and asked for a revision:

```text
Draft summary: It looks like autumn's arrived nicely in Portimão - mild water and a low jellyfish risk make for a pleasant swimming spot today, with the high whale sighting likelihood being the icing on the cake.
Reviewer (score 35/100, verdict: revise): Pleasant waters make for a great swim, but with relatively average conditions overall.
```

The QLoRA adapter (Tier 2), the image with the model baked in, and publishing are marola-ml's
[Fine-tuning](https://docs.marola.dev/5-Repos/marola-ml/3-development_finetune/) page.
