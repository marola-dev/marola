# MIP-0073: Open-weights System One models (Laya) as the local `DecisionClient`, and the two places they may not decide

| | |
|---|---|
| **Status** | Draft |
| **Author** | Claude |
| **Created** | 2026-10-02 |
| **Phase** | 0 for the offline uses (§5.4); 1 for routing a bot message (§5.5) |
| **Related** | MIP-0059 (the `DecisionClient` trait and Jev, the hosted model this is the open option for), MIP-0062 (the ressaca veto), MIP-0039 (the fact guard), MIP-0025 (a fine-tuned local classifier), MIP-0050 (Brazilian models), MIP-0054 (pt-BR first), `FUTURE-WORK.md` §1.2 (`ActivityScoring`), §4.1 (eval harness), §9.2 (hazard detection), `PHILOSOPHY.md` "Why LLMs, and where they are not allowed" |
| **Effort** | M. MIP-0059's `JevClient` generalised into a `SystemOneClient` with a base URL, a pinned sidecar in `docker-compose.yml`, three recorded fixtures; no change to `scoring/` |
| **Gain** | `infra/dev-loop` (a typed judge and reranker with no key, no waitlist and nothing leaving the machine); `cost/ops` (free) |
| **Effort vs Gain** | `do when MIP-0059's trait lands`; then a `cheap win` for the two offline uses |
| **Depends on** | MIP-0059's `DecisionClient` trait, which is Draft and not built. This MIP adds an implementation and changes MIP-0059's default; it does not need Jev access, which is what blocks MIP-0059 today |
| **Blocked by** | MIP-0059 |
| **Risk** | A two-week-old model family, trained by its authors on data this MIP could not inspect, whose calibration is a vendor claim. The mitigation is the same as MIP-0059's: it may rank, route or flag, and is benchmarked before it does any of those |
| **Cost so far** | — |

## 1. Summary

MIP-0059 designed a typed-decision interface around Jev, a hosted model nobody here can use yet.
Open-weights models of the same kind now exist, and several serve Jev's own wire format,
`POST /v1/systemone`, on a local port. Two matter here: Laya (Apache-2.0, 421M and 322M encoders
that run on a CPU) and Decider (Apache-2.0, built on Qwen3.5 2B and 4B). This MIP turns MIP-0059's `JevClient` into one
`SystemOneClient` with a base URL, so the same code reaches Jev or a local sidecar. Laya is the
first sidecar to try, and it is benchmarked against Decider and today's Ollama judge before it is
used for anything. The MIP also answers whether this kind of model can make marola's safety veto
or an activity score: it can't (§6).

## 2. Motivation

MIP-0059 designed a `DecisionClient` (typed `Choice`, `Score` and `Noul` questions over a block of
program state) around Jev, TypeSafe AI's hosted model. It stalled on access, not on design:
- Jev has no free tier and sits behind a waitlist (MIP-0059 §4, §8);
- it is a hosted API, so beach names and conditions leave the machine, against marola's local-first
  default (`ARCHITECTURE.md` §5);
- nothing in MIP-0059 §5 has been run.

Its §9 named the real competitor as constrained decoding on Ollama, and left the local default as
"Ollama with today's parse-and-default behaviour". Three days after Jev launched, an open-weights
model of the same kind appeared: Laya, released under Apache-2.0 on 2026-09-18, answering the same
three question types without generating text. A week later a community list counted 55 open
projects built around the same idea (search excerpt; the page was blocked here), and §4.3 covers
the credible ones. The question MIP-0059 could not answer, whether a System One model helps marola
at all, can now be answered on a laptop.

The maintainer also asked a second question: could this kind of model serve marola's safety veto,
or score an activity (`FUTURE-WORK.md` §1.2)? §6 answers it. The short answer is no for both
decisions, and yes for a few narrower jobs around them.

## 3. User-visible change

None for the bootstrap (§5.4); both first uses are offline. The dev loop gains typed judge arms:

```
just benchmark --judge laya
q07 "is it safe to swim after heavy rain?"   arm B better   p=0.78  conf=0.71   laya-multilingual@<rev>  local  190 ms
q08 "what is a ressaca?"                     tie            p=0.52  conf=0.31   declined → local text judge
```

The second line is the confidence gate (§5.2): a low-confidence typed answer is not used, and the
existing local judge's verdict stands.

In Phase 1, once the Telegram bot exists, a Portuguese message is routed without a text model:

```
"dá pra surfar na Joaquina amanhã cedo?"  →  Choice{swim, surf, dive, ask, other} = surf  p=0.91
```

The routed request is then scored by deterministic Scala, as today.

## 4. Data sources and dependencies reviewed

Everything below was checked on 2026-10-02. GitHub, raw GitHub files, PyPI and Maven Central were
fetched directly. Hugging Face, laya-ai.com and the community list pages were blocked from this
sandbox, so no model card was opened (§11).

### 4.1 Laya (Convai Innovations): the candidate the maintainer named

- **What it is.** A family of encoder models with a decision head. It answers `choice`, `score` and
  `noul` questions over a state in one forward pass and generates no text. The canonical repo is
  `NandhaKishorM/laya` (PyPI `laya` 0.3.23, authors "Convai Innovations"); `edu4d/laya-classifier`
  is a stale fork.
- **Licence.** Apache-2.0 for the code (`LICENSE`, PyPI) and, per the README, for the weights. The
  Hugging Face card's licence field was not opened. Backbones: ModernBERT (Apache-2.0) and mmBERT.
- **Checkpoints.**

  | Checkpoint | Backbone | Params | Context |
  |---|---|---|---|
  | `laya` | ModernBERT-large | 421M | 512 |
  | `laya-multilingual` | mmBERT-base | 322M | 1024 (up to 8,192) |
  | `laya-typed-decisions` | ModernBERT-large | 421M | 1024 |

- **Serving.** `laya-serve` (FastAPI) exposes `POST /v1/systemone` and `/v1/systemone/batch`,
  described as schema-identical to Jev, with three listed differences:
  - at most 100 options per choice;
  - score levels need descriptions;
  - `confidence` is 1 minus normalised entropy, not Jev's `(n·p_max − 1)/(n − 1)`. Each answer
    also carries `answer_confidence` (`max(p)`), which the README says is the calibrated quantity
    to threshold on.
- **CPU.** p50 for one question on a 4-core AWS m7a.xlarge: 193 ms (multilingual) and 580 ms
  (English), plus roughly the same again per extra question. Peak RSS was 9.3 GiB with up to five
  checkpoints loaded. INT8 is about twice as fast and "trades real accuracy".
- **Calibration.** "Both checkpoints are over-confident as shipped." Shipped ECE is 0.466
  (`laya`) and 0.314 (multilingual), and 0.081 and 0.106 after refitting one temperature per
  question type. `laya-multilingual` ships with no fitted temperatures. On a routing task the
  authors found the opposite, under-confidence, so the direction depends on the task.
- **Portuguese.** No Brazilian Portuguese evaluation exists. The only Portuguese number is MASSIVE
  intent (20 options, 100 cases, likely pt-PT): 0.500 on the multilingual checkpoint. Short
  Portuguese text routes to the English checkpoint unless `LAYA_DEFAULT_MODEL=multilingual`.
- **Accuracy.**
  - Past about 20 options it degrades (Banking77: 0.425 against Jev's 0.870).
  - The headline "beats Jev" result (0.766 vs 0.727 on typed-decisions) comes after fine-tuning
    on that benchmark. The base checkpoints score 0.36 and 0.35 there, below the 0.461
    majority-class baseline.
  - The authors say they never measured Jev themselves ("no TypeSafe API access").
- **Training data.** Partly documented: some public sets plus a dataset scored by a teacher model
  whose identity this check could not see.
- **Maturity.** First release 2026-09-18, 31 releases since, about 1,150 commits; the latest
  release lists 64 pull requests from 21 contributors. Very active and very young.

### 4.2 Decider (Mapika): the strongest open model measured

- Apache-2.0, package and weights. Built on Qwen3.5-2B-Base, 4B-Base and 35B-A3B-Base.
- The training data is public sets plus labels from a local Qwen3.5-27B teacher; the README says
  "Nothing was distilled from Jev".
- `decider.serve` speaks `POST /v1/systemone` ("their SDKs work unchanged") and `POST /decide`.
- CPU:
  - the server runs float32 on CPU;
  - GGUF builds (2B Q4_K_M 1.3 GB, 4B Q4_K_M 2.7 GB) run through llama.cpp in the Python library,
    0.12–0.31 s per request for the 2B on 8 threads;
  - but "the HTTP server does not serve GGUF files".

### 4.3 Others, and why they are not candidates

| Project | Licence | Why not now |
|---|---|---|
| Kev (jaredpalmer) | Apache-2.0 | Qwen3.5 adapters; CUDA or MLX only, no CPU path |
| Von (wfzyx) | Apache-2.0 per README | ModernBERT 395M on OpenVINO; ranks below Laya (§4.4) |
| SemIf (TheoLeeCJ) | MIT, Qwen weights Apache-2.0 | No trained model; reads option logits from a frozen Qwen3.5-4B |
| OpenJev (GitHub30) | MIT | 8 commits in one day, no evaluation |
| Malkuth (Kev post-train) | CC-BY-NC-4.0 | Research use only |

### 4.4 Independent benchmark: JevBench v1.4.2.2

JevBench (results JSON generated 2026-09-27) lists 95 systems and ranks them on intelligence, calibration, speed
and cost with equal weights. Its latency for self-hosted endpoints is adjusted ×2 + 0.15 s, "an
assumption, not a measurement".

| Rank | System | Licence | Score | Intelligence | Calibration | Measured on |
|---|---|---|---|---|---|---|
| 3 | decider-4b v2 | Apache-2.0 | 64.1 | 49.4 | 75.0 | GPU |
| 4 | Jev 1.13.0 | proprietary API | 63.3 | 53.1 | 76.3 | TypeSafe API |
| 41 | decider-2b | Apache-2.0 | 30.7 | 38.5 | 43.5 | GPU |
| 43 | Laya (421M) | Apache-2.0 | 30.3 | 36.1 | 63.7 | CPU |

### 4.5 Running it from the JVM

- **A sidecar over HTTP.** This is the same pattern as Ollama, a separate process marola calls. Both
  Laya and Decider ship one, and both speak Jev's route, so no new client code per model.
- **In-process through ONNX.** ONNX Runtime Java 1.30.0 (MIT, a 56 MB jar with native libraries)
  plus DJL's HF tokenizers 0.38.0 (Apache-2.0) could run Laya's `encoder.onnx` and `head.onnx`.
  Laya publishes an export script but no official ONNX files, and its prompt rendering, marker
  positions and head are custom code, so this is a port, not a dependency bump.
- **Ollama.** It cannot serve encoder models with custom heads, and Decider's GGUF in Ollama "gives
  a text model, not decisions". Ollama does expose `logprobs`, so a SemIf-style readout is
  conceivable, but this is untested.

### 4.6 Pick

- **The wire format is the pick, not one model.** `POST /v1/systemone` is spoken by Jev, Laya and
  Decider, so MIP-0059's client gains a base URL and works with all three.
- **Laya is the first local sidecar.** It is the smallest, runs on a CPU, has a multilingual
  checkpoint and is Apache-2.0 end to end.
- **It is not trusted on its numbers.** Decider is the comparison arm, because it is the open
  model with the best independent result. The bootstrap benchmark (§5.4) picks the default; the
  vendor claims don't.

## 5. Design

### 5.1 One client for every System One backend

MIP-0059's `JevClient` becomes `SystemOneClient`. It stays in `core/` beside the trait, because it
is plain HTTP through `Http`/`Json`.

```scala
final case class SystemOneBackend(
    name: String,                 // "laya", "decider", "jev"
    baseUrl: String,              // http://127.0.0.1:8000 or https://api.typesafe.ai
    model: String,                // pinned id, e.g. "laya-multilingual@<hf-revision>"
    apiKey: Option[String],       // Some only for Jev
    gate: Map[String, Double]     // call site → minimum answer probability (§5.2)
)

final class SystemOneClient(backend: SystemOneBackend) extends DecisionClient:
  def ask(state: String, questions: List[Question]): List[Answer] < Sync = ???
```

`AppConfig.decisionClient` reads `MAROLA_DECISION_PROVIDER=local|laya|decider|jev`, plus
`MAROLA_DECISION_URL` and `MAROLA_DECISION_MODEL`.
- `local` stays the default: deterministic Scala, and Ollama where a model is needed, exactly as
  MIP-0059 §5.2.
- `laya` and `decider` default to `http://127.0.0.1:8000` and need no key.
- `jev` without `TYPESAFE_API_KEY` returns `None`, as MIP-0059 planned.

### 5.2 Gate on the answer's probability, per backend

Laya's `confidence` and Jev's are different formulas, and the Laya README says Jev thresholds do
not transfer; Decider has also changed its own `confidence` formula once. The gate therefore reads
the answer's own probability (Laya's `answer_confidence`, Decider's `x_p_max`, and the top
per-option probability Jev returns), with one threshold per backend and call site, set from the
bootstrap benchmark rather than from vendor defaults. Below the threshold the client returns no answer for
that question, and the call site uses the `local` answer. The typed model declines; it does not
guess.

### 5.3 The sidecar

- A `decision` service in `docker-compose.yml`, next to `ollama`:
  - pinned PyPI version (`laya==0.3.23`);
  - pinned Hugging Face revision of the checkpoint;
  - `LAYA_DEFAULT_MODEL=multilingual`;
  - bound to 127.0.0.1.
- `just decision-up` and `just decision-down`, as for Ollama.
- One checkpoint loaded, not five, to keep RSS well under the 9.3 GiB measured with all of them.
- Laya ships over-confident, so the bootstrap refits its temperatures on marola's own labelled
  rows (the README's per-question-type refit) and commits the fitted values next to the pin.
- Nothing provisions a cloud resource: the sidecar is a local process, like Ollama.

### 5.4 Bootstrap: MIP-0059's two offline uses, four arms

1. **The benchmark judge** (`just benchmark`, 22 questions × 3 arms). `Choice{A, B, tie}` plus a
   groundedness `Score`.
2. **RAG chunk relevance.** A `Score` per retrieved chunk, used only to reorder chunks that are
   cited verbatim anyway.

Each use runs four arms: today's Ollama text judge, Laya, Decider-2b (and 4b where a GPU exists),
and Jev once a key exists. Each arm records:
- agreement with the recorded verdicts in `docs/benchmarks/`;
- coverage at the chosen gate;
- p50 latency and RSS on the maintainer's machine.

The winner becomes the documented default for `laya|decider`. If no open arm beats the Ollama
judge, the MIP says so and `local` stays the only default.

### 5.5 Phase 1: routing a bot message

When the Telegram bot exists (`ARCHITECTURE.md` §11), a `Choice` over activities and tools routes
a message such as "dá pra surfar na Joaquina amanhã?". Laya has no Brazilian Portuguese
evaluation, so this waits for a labelled set of about 200 real or written pt-BR messages, and it
uses the multilingual checkpoint with fitted temperatures. A routed request is scored by the same
deterministic `ActivityScoring` as a typed command.

### 5.6 What changes in MIP-0059

- Its Related row and §9 point here.
- Its "access" blocker no longer blocks the trait: the trait can be built and tested against a
  local sidecar.
- Its rule that a typed answer may rank, route or flag, never decide, is unchanged, and §6 below
  applies it to the veto and to activity scores.

## 6. Scoring / safety impact

**None.** `Swimability.score`, every threshold, the water veto and MIP-0062's ressaca veto are
untouched, and no `DecisionClient` output reaches them. The rest of this section answers the
maintainer's question of whether this model class could take over either decision.

### 6.1 Could it be the safety veto? No.

The veto is what forces a score to 0: every fresh matched point IMPRÓPRIA (MIP-0001 §6), and
MIP-0062's ressaca threshold. A System One model should not decide it, for five reasons:
1. **The house rule.** Anything that changes whether marola tells someone to swim is plain Scala in
   `scoring/`, unit-tested, and never model output (`PHILOSOPHY.md`, `AGENTS.md`). MIP-0059 §6
   already narrowed every typed model to "rank, route or flag, never decide".
2. **The inputs are already typed.** The veto reads an agency's verdict and a wave height against
   sourced thresholds (CONAMA 274/2000, the Navy's ressaca criterion). A model over those numbers
   can only reproduce the rule or disagree with it, and a disagreement is either a missed hazard
   or a false alarm.
3. **"Cannot hallucinate" is about the output type, not the answer.** A typed model always returns
   one of the declared options, so it never produces an off-schema answer. It can still return the
   wrong option, confidently.
4. **Calibration is unmeasured here.** The vendor reports calibrated probabilities on its own data
   (§4.1). marola has no labelled set of "conditions → was it actually unsafe", so there is nothing
   to check a veto threshold against.
5. **A veto must explain itself.** Every veto today carries a note naming its source (the agency
   and bulletin date, the Navy criterion). A typed answer carries a probability, not a reason.

A one-way version ("the model may add a veto but never lift one") was also considered and
rejected: adding a veto still changes the advice, and a model that cries wolf teaches people to
ignore the vetoes that matter (MIP-0062's risk line, "a veto that cries wolf", is the same worry
about its own threshold).

What it may do near the veto, none of it at runtime on the swim path:
- **Monitor a parser.** Offline, re-read each agency bulletin's text and ask `Choice{própria,
  imprópria, indisponível}` per point. When it disagrees with the deterministic parser, the job
  alerts a maintainer. The parser's result stays on the map either way; the model never edits it.
- **Triage hazard text for a human.** Official alerts (the CAP documents of MIP-0071, open as
  marola-dev/marola#542) and agency notes
  ("interditado", "mancha escura") can be sorted into hazard types for a person to read first, in
  the human-confirmation gate `FUTURE-WORK.md` §9.2 already requires before any alerting.

### 6.2 Could it produce an activity score? No, not the score.

`FUTURE-WORK.md` §1.2 proposes one `ActivityScoring` per activity: `score(hour) → (Int, notes)`,
deterministic, the same shape as `Swimability`. A typed `Score` question could return a level from
"poor" to "excellent" for surf, but it is the wrong tool for the score itself:
1. **The score carries safety.** Swim's score includes the rough-sea and darkness deductions, and a
   surf or dive score will include its own hazards (big swell for beginners, current for divers).
   The same rule as §6.1 applies.
2. **No reasons.** Each deduction today becomes a note the user reads ("wind 27 km/h"). A model's
   level comes with no deduction list.
3. **Numbers are a poor fit for a text encoder.** The inputs are a handful of floats per hour (wave
   height and period, wind speed and direction relative to the beach). Thresholds, or later a small
   numeric model trained on outcomes, fit that better than a language model reading JSON.
4. **Nothing to train or test against.** A learned surf score needs labelled sessions ("this hour
   was good"), and marola has none. MIP-0051's buoy ledger will measure the forecast, not the
   session. Until such labels exist, a learned score cannot be tested.

What it may do around an activity score:
- **Route the activity.** In the bot, pick which `ActivityScoring` a message asks for, in
  Portuguese (§5.5). This is routing; the score is still deterministic.
- **Propose curated data for a human.** Surf scoring needs per-beach attributes no API has, such as
  which way a beach faces (`FUTURE-WORK.md` §1.3). A model can read OSM tags and descriptions and
  propose values into a reviewed file with a source per row, the way MIP-0059 §5.4 lets Jev propose
  water-point matches. It never fills the value at runtime.
- **Judge the sentence, not the conditions.** Check that the model-written sentence agrees with
  the deterministic score (`Noul`: "does this sentence describe conditions as worse than or equal
  to the score?"). MIP-0039's fact guard is the deterministic version and stays the gate; the typed
  judge is a benchmark signal only.

| Job | Allowed | Where |
|---|---|---|
| Swim veto, ressaca veto, water veto | never | — |
| Any activity's score or deductions | never | — |
| Benchmark judge, RAG chunk rerank | yes, offline | §5.4 |
| Route a bot message to an activity or tool | yes, Phase 1 | §5.5 |
| Cross-check an agency parser and alert a maintainer | yes, offline | §6.1 |
| Triage official hazard text for a human | yes, behind §9.2's human gate | §6.1 |
| Propose per-beach attributes into a reviewed file | yes, offline | §6.2 |

## 7. Verification plan

- `SystemOneClientSpec`: request serialisation and answer parsing against three recorded
  fixtures, one each from `laya-serve`, `decider.serve` and Jev's documented example. Passing all
  three is the wire-compatibility check. No network in `just test`.
- `SystemOneGateSpec`: an answer below the backend's gate is dropped and the `local` answer is used.
- `AppConfigSpec`:
  - `laya` without a URL resolves to `127.0.0.1:8000`;
  - `jev` without a key yields `None`;
  - an unknown provider fails with a clear message.
- `ScoringIsolationSpec`: no file under `scoring/` imports `marola.decide`. This makes §6 a test,
  not only a promise.
- Live, excluded from `just test` like `just e2e`:
  - `just decision-up`;
  - `just benchmark --judge laya`, then `--judge decider`;
  - one comparison paragraph in `docs/benchmarks/` with agreement, coverage, latency and RSS per arm.
- **Done:**
  - the three fixtures pass;
  - the isolation spec is green;
  - the benchmark paragraph exists and names the default it chose, or says none won.

## 8. Risks, limitations, and honest caveats

- **Young and fast-moving.** Laya had 31 releases in two weeks. Pin the package and the
  checkpoint revision, and expect the wire details to move.
- **Weak independent numbers.** Laya ranks 43rd of 95 on JevBench. Its base checkpoints score
  below the majority class on typed-decisions, and its "beats Jev" result needs a fine-tune on
  that benchmark. This is why §5.4 benchmarks before anything uses it.
- **Over-confident as shipped,** and the multilingual checkpoint has no fitted temperatures. A gate
  set on raw probabilities would let wrong answers through.
- **No Brazilian Portuguese evaluation.** marola's users write in Portuguese (MIP-0054). Routing
  waits for a labelled pt-BR set (§5.5).
- **Many options degrade.** Past about 20 options accuracy drops, and the server caps choices at
  100. Water-point matching across many beaches (MIP-0059 §5.4) is not a Laya job.
- **Training data is partly opaque.** The teacher behind its fine-tune dataset was not identified.
  If labels came from Jev, TypeSafe's terms may matter. §11 asks.
- **A second runtime.** A Python sidecar in a Scala repo is a real cost, accepted because Ollama
  already set the pattern. The ONNX port (§4.5) removes it if a model earns it.
- **Never on the safety path.** §6 and `ScoringIsolationSpec` keep it that way.

## 9. Alternatives considered

- **Do nothing; keep MIP-0059 as written.** Jev stays blocked on access, and the trait stays
  unbuilt. This loses nothing safety-wise, but leaves the typed-decision question unanswered.
- **Constrained decoding in Ollama** (MIP-0059 §11's follow-up). It is still the cheapest fix for
  malformed JSON, with no new process, but it gives no calibrated probability. It remains its own
  MIP and can be a fifth benchmark arm.
- **Read option log-probabilities from Ollama** (the SemIf/OpenJev approach). It would avoid a
  sidecar, but it is untested, and SemIf ranks 13th with a 4B model. It is an open question, not a
  pick.
- **Port Laya to the JVM through ONNX Runtime Java.** No sidecar, but it means porting custom
  prompt and head code for a model that has not yet won anything. Revisit if Laya wins §5.4.
- **Decider as the only pick.** It has the stronger numbers (3rd, GPU-measured), but its CPU path
  is the GGUF library, which its server does not serve, so the server runs float32 on CPU.
  It stays the comparison arm.
- **A fine-tuned local classifier** (MIP-0025 tiers). Cheapest at runtime and most work up front.
  It is worth it only after a benchmark shows the task needs it.

## 10. Exam-coverage mapping

None. The AI-103 and AI-500 mapping files are no longer in the tree.

## 11. Open questions

- **The Hugging Face cards were not opened** (blocked here): the weights' licence field, file
  sizes, and the dataset card of `LocalLLaMA/typed-decisions`. Check them before pinning.
- **The teacher.** Which model labelled Laya's fine-tune data, and whether any labels came from
  Jev?
- **Portuguese.** Does the multilingual checkpoint handle beach vocabulary ("ressaca", "imprópria",
  "maré") well enough? The pt-BR set in §5.5 answers this; nothing here does.
- **Latency and memory on the maintainer's machine,** with one checkpoint loaded.
- **Decider on CPU.** Will its server load GGUF in a later release? Until then its CPU numbers come
  from the library, not the server.
- **The Ollama `logprobs` route** (§9), worth one experiment.
- **Phase 3.** If the bot is deployed, where does the sidecar run, and at what cost? That is a cost
  gate for a person, not this MIP.

## Appendix

### Checked (2026-10-02)

| Source | What it said |
|---|---|
| `github.com/NandhaKishorM/laya` (README, `BENCHMARKS.md`, `laya/serve.py`, `LICENSE`) | Apache-2.0; three checkpoints; `/v1/systemone` with a 100-option cap; the entropy `confidence` vs `answer_confidence`; CPU p50 and RSS; ECE as shipped and refit; "over-confident as shipped"; no TypeSafe access |
| `pypi.org/project/laya` | 0.3.23 (2026-10-01), first release 0.1.0 on 2026-09-18, Apache-2.0 |
| `github.com/edu4d/laya-classifier` | A fork of the above, 0 stars, README at 0.3.20 |
| `github.com/Mapika/decider` (README, `LICENSE`) | Apache-2.0; Qwen3.5 bases; "Nothing was distilled from Jev"; `/v1/systemone`; GGUF via llama.cpp, not served over HTTP |
| `github.com/fstandhartinger/jevbench` results JSON | v1.4.2.2, generated 2026-09-27; ranks and axes in §4.4 |
| `github.com/jaredpalmer/kev`, `wfzyx/von`, `TheoLeeCJ/SemIf`, `GitHub30/OpenJev` | Licences and runtimes in §4.3 |
| Maven Central | `com.microsoft.onnxruntime:onnxruntime` 1.30.0 (MIT); `ai.djl.huggingface:tokenizers` 0.38.0 (Apache-2.0) |
| `github.com/ollama/ollama` `api/types.go` | `logprobs` and `top_logprobs` request fields |

### Not checked

- Any Hugging Face page, laya-ai.com, madewithjev.com, or the dev.to list (all blocked here).
- Any model run. Every latency and accuracy number above is the authors' or JevBench's.
- Jev itself (still no key; MIP-0059 §11).
