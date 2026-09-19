# MIP-0059: Jev (TypeSafe AI) as an opt-in typed-decision backend

| | |
|---|---|
| **Status** | Draft |
| **Author** | Claude (Opus 5), for M. Hoffmann |
| **Created** | 2026-09-19 |
| **Phase** | 0 (dev-loop and offline uses), 1 for anything user-facing |
| **Related** | `ARCHITECTURE.md` §5a (query synthesis) and §5h (RAG), `FUTURE-WORK.md` §4.1 (eval harness), MIP-0001 (corpus/RAG), MIP-0010 (run ledger), MIP-0025 (fine-tuning) |
| **Effort** | M — one new trait with two implementations, an `AppConfig` factory and an HTTP call through the existing `Http`/`Json` helpers; no new module, no SDK (there is no Scala SDK, §4) |
| **Gain** | `infra/dev-loop` (a judge and a reranker that cannot emit malformed output); `cost/ops` (output tokens are free and input is $0.042/MTok, §4); `exam coverage (AI-103 "implement a generative AI solution" — structured output and guardrails)` |
| **Effort vs Gain** | `cheap win` for the two offline uses (§5.3 bootstrap), `do when Phase 1 lands` for anything a user sees |
| **Depends on** | Nothing must merge first. Gated instead by *access*: Jev is early-access behind a waitlist and needs an API key (§4), so the opt-in path cannot be verified live until a key exists. No Azure resource and no paid Azure gate is involved; `AGENTS.md`'s Phase 1 gate applies only to the user-facing uses in §5.4. |
| **Blocked by** | none |
| **Risk** | Building a second decision path that nobody can run: no free tier, a waitlist, and a three-day-old API whose shape may change under us — while the local default it replaces is already good enough for everything except malformed-JSON retries. |
| **Cost so far** | — |

## 1. Summary

Jev is a "System One" model from TypeSafe AI, launched 2026-09-15..18: it takes a block of program
state plus a set of typed questions and returns *typed* answers — one of a fixed option set, an
ordinal score, or a yes/no probability — with calibrated probabilities and a confidence, in 70-500 ms.
It cannot return anything outside the declared type. This MIP proposes a `DecisionClient` trait as
marola's seventh pluggable integration, with a deterministic/Ollama local default and Jev as the
opt-in backend, and it picks two offline, non-safety-critical uses to try it on first.

## 2. Motivation

The gap is already in the code. `llm/Reviewer.scala` asks a local model for JSON and then does this:

```scala
/** Small/quantized local models sometimes wrap the requested JSON in a sentence or a code fence
  * despite being told not to (confirmed as a real, if infrequent, failure mode while testing
  * locally) — extract the first `{...}` block rather than requiring byte-perfect compliance */
private def extractJsonObject(text: String): String
```

That helper exists because a text model can always answer off-schema, and `MalformedReviewException`
is thrown when it still does. Every downstream typed value — `score: Int`, `verdict: String` — is
parsed out of prose and defaulted when absent (`verdict` falls back to `"approve"`, which fails
*open*). A model that returns a typed value by construction removes the class, not the instance.

The same shape recurs wherever marola wants a decision rather than a sentence: which sampling point
belongs to which beach, whether a corpus chunk answers a question, which of three benchmark arms
answered better. Today those are either deterministic Scala (good) or a text model asked to behave
(fragile).

## 3. User-visible change

None in the bootstrap (§5.3): both first uses are offline. What changes is a dev-loop artifact —
`just benchmark` gains a judge whose verdicts are typed:

```
before:  arm B better (judge said: "I think B is more helpful, though A ...")  [parsed by regex]
after:   arm B better   p=0.81  confidence=0.74   [Choice{A,B,tie}, no parsing]
```

Once Phase 1 exists and §5.4 is unblocked, the user-visible change is negative-space: fewer
"couldn't parse the reviewer's reply" fallbacks, and a `verdict` that can no longer silently
default to `approve`.

## 4. Data sources and dependencies reviewed

### Jev / TypeSafe AI — the candidate

- **What it is.** A "System One model": returns typed, probabilistic decisions instead of text.
  Input is "unstructured data … with an emphasis on structured program state"; output is
  "type-safe structured values. Possible outputs and structure are defined in advance" `(v)`.
- **Question types** (exactly three, per the docs) `(v)`:
  - **Choice** — "selecting one option from a defined set"; cardinality **up to 255** `(v)`.
  - **Score** — "rating content against ordered, descriptive levels".
  - **Noul** — "evaluate a yes/no question and return the probability that the answer is yes".
    (Spelled *Noul*, not *Bool*; noted here because it looks like a typo and is not.)
  Each question carries `type`, `instructions` and `criteria`; answers carry the value, a
  probability per option/level, a confidence, and token usage `(v)`.
- **Confidence vs probability.** The docs distinguish them: "confidence tells you whether to act"
  and is meant as a routing signal `(v)`. This matters for §6: a low-confidence answer is a
  *decline*, not a coin flip.
- **API.** `POST https://api.typesafe.ai/v1/systemone`, bearer token, models `jev-1.13.0` /
  `jev-latest` / `jev-preview` `(v)`. Text only — "no image, audio, or video input" `(v)`.
- **Limits.** 64k tokens per request, of which 32k for `state` plus the longest question; rate
  limits 250,000 tokens/second and 1,200 requests/minute, "adjusting dynamically" under launch
  demand; over-limit returns `429` `(v)`.
- **Price.** **$0.042 per million input tokens; output free** ("too cheap to meter") `(v)`.
- **Free tier — the question this MIP was asked.** **There is none documented.** Neither the launch
  post, nor `docs.typesafe.ai`, nor the Cloudflare Workers AI model page mentions free credits, a
  trial or a free tier `(v, all three checked 2026-09-19)`. Access is **early access behind a
  waitlist**; third-party write-ups report keys arriving in a day or two ⚠ (not verified). So the
  honest statement is: *not free, but nearly free* — at $0.042/MTok in and free output, the entire
  bootstrap in §5.3 is cents, which is a different category from an Azure resource and does **not**
  trip `AGENTS.md`'s provisioning gate (no resource is created; it is a metered API key).
- **SDKs.** Official Python and JavaScript only `(v)`. **No Scala SDK and no JVM client**, so
  marola would call the HTTP API directly through its own `http/Http.scala` and `json/Json.scala`
  — the same thing `OllamaClient` already does, which is why the effort is M and not L.
- **Third-party routes.** Also listed on Cloudflare Workers AI as `typesafe/jev` (32k context
  there) `(v)`; Vercel AI Gateway and OpenRouter appear in search results, but the OpenRouter model
  page returned **404** when fetched `(v — the failure is the finding)`. If the waitlist is slow,
  Workers AI is the fallback route ⚠ (its pricing is "available through the Cloudflare dashboard",
  not stated on the page).
- **Licence/terms.** Terms of Use and Privacy Policy links exist; no open-source licence — it is a
  hosted API, and prompts/state leave the machine. That is a real change for a repo whose default
  is "runs entirely locally" and is why §5 keeps Jev strictly opt-in.

### The incumbent: a local text model (Ollama)

Free, keyless, offline, already the default everywhere. Cannot guarantee a typed answer; the
mitigation is `extractJsonObject` plus a defaulted `verdict`. Stays the default.

### Pick

Jev, **as an opt-in backend only**, for decisions with a closed answer set. Ollama stays the
default so `just run` on a laptop with no keys keeps working exactly as today.

## 5. Design

### 5.1 The trait — integration 5i

```scala
package marola.decide

enum Question:
  case Choice(id: String, instructions: String, options: List[String])   // ≤ 255 options
  case Score(id: String, instructions: String, levels: List[String])
  case Noul(id: String, instructions: String, criteria: String)

final case class Answer(id: String, value: String, probability: Double, confidence: Double)

trait DecisionClient:
  def ask(state: String, questions: List[Question]): List[Answer] < Sync
```

`state` is marola's own structured context (a `BestHour`, a corpus chunk, two benchmark answers)
rendered as JSON — the shape Jev's docs call "program state".

### 5.2 The two implementations

- `local/` — `HeuristicDecisionClient`: deterministic Scala per call site (the matcher's existing
  normalisation; a keyword overlap score for reranking), and, where a model genuinely is needed,
  the existing Ollama path with today's parse-and-default behaviour. No new dependency.
- A new `decide/JevClient` (in `core/` — it is not Azure, so it does not belong in `azure/`;
  it is not Ollama, so it does not belong in `local/`; put it beside the trait and gate it on the
  env var): one `POST` through `Http`, bearer token from `TYPESAFE_API_KEY`, questions serialised
  with `Json`, answers parsed into `Answer`.
- `AppConfig.decisionClient: Option[DecisionClient]` follows the established pattern exactly —
  `MAROLA_DECISION_PROVIDER=local|jev`, returning `None` when `jev` is selected without a key so
  `Main` fails with a clear message rather than a stack trace (`ARCHITECTURE.md` §5).

### 5.3 Bootstrap — the two candidates to try first, in order

1. **The benchmark judge** (`just benchmark`, 22 ocean questions × 3 arms, `docs/benchmarks/`).
   Today the comparison is scored by a text model and read by a human. A `Choice{A, B, tie}` plus a
   `Score` for groundedness is the smallest possible real use: offline, no user-facing output, an
   existing corpus of runs to compare against, and a ready-made regression check — re-judge a kept
   benchmark file and see whether the typed judge agrees with the recorded verdicts. Output tokens
   are free, so re-judging the whole history costs input tokens only.
2. **RAG chunk relevance** (`knowledge/Corpus`, `OceanQa`). A `Score` per retrieved chunk
   ("does this paragraph answer the question?") used to rerank before the answer is composed. Still
   cites the corpus verbatim, so `AGENTS.md`'s no-unsourced-facts rule is untouched; failure mode is
   a worse ordering, not a wrong fact. Measurable with the existing benchmark.

Both are Phase 0, both are reversible, and neither can put a sentence in front of a user.

### 5.4 Later candidates, with the reason each waits

| Call site | Question | Why not first |
|---|---|---|
| `llm/Reviewer` verdict + score | `Choice{approve, revise, reject}` + `Score` | The best *fit* in the repo, and the motivating example (§2) — but it gates user-facing text, so it wants Phase 1 and a side-by-side run against the current reviewer before it decides anything alone |
| Telegram intent routing | `Choice` over tool names | Phase 1 does not exist yet (`ARCHITECTURE.md` §11) |
| `water/WaterQualityMatcher` unmatched points | `Choice` over nearby beaches (≤255 fits) | Water-quality assignment is safety-adjacent; Jev may **propose** matches for a human to add to a fixture, never assign at runtime |
| Jellyfish/whale heuristics | `Noul` | **Never.** `AGENTS.md`: anything that changes whether marola tells someone to swim stays deterministic Scala in `scoring/` |

## 6. Scoring / safety impact

**None.** `Swimability.score` is untouched, no threshold moves, and no `DecisionClient` output
reaches the swim recommendation. The rule this MIP adopts for every future call site: a Jev answer
may *rank*, *route* or *flag*, never *decide* whether conditions are safe. Where confidence is
below a call-site threshold, the local default's answer is used — the typed model declines rather
than guesses, which is the point of `confidence` being separate from `probability` (§4).

## 7. Verification plan

- `DecisionClientSpec` — question serialisation and answer parsing against a **recorded** Jev
  response fixture (same discipline as the existing golden fixtures; no network in `just test`).
- `HeuristicDecisionClientSpec` — the local default answers every question type without a key.
- `AppConfigSpec` — `MAROLA_DECISION_PROVIDER=jev` without `TYPESAFE_API_KEY` yields `None`.
- Live, once a key exists and excluded from `just test` like `just e2e`: one real call, recorded
  into the fixture above; re-judge one kept file in `docs/benchmarks/` and diff against its
  recorded verdicts.
- **Done** = the bootstrap judge runs offline from a fixture, the live call is recorded once, and
  `docs/benchmarks/` gains one comparison paragraph saying whether the typed judge agreed.

## 8. Risks, limitations, and honest caveats

- **Access, not money, is the blocker.** Waitlist, no free tier, no key in hand at writing time.
  Everything in §5.2 is written-not-run until one exists.
- **Three days old.** `jev-1.13.0` on 2026-09-19, with rate limits "adjusting dynamically". Pin the
  versioned id, not `jev-latest`, and expect the request shape to move.
- **It leaves the machine.** marola's identity is local-first; this is an opt-in hosted API and the
  state posted to it includes beach names and conditions. Never post a user's coordinates.
- **Calibration is a claim.** "Calibrated probabilities" and the hallucination-impossibility claim
  are TypeSafe's, not measured here ⚠. The type safety is structural and believable; the
  *calibration* is exactly what the benchmark bootstrap is for.
- **A judge that cannot explain itself.** Jev returns no text. For a benchmark a human reads, losing
  the rationale is a real cost — keep the local text judge's prose alongside the typed verdict.

## 9. Alternatives considered

- **Do nothing.** Strongest option today, and the honest fallback if the waitlist never clears: the
  local path works, and `extractJsonObject` has been adequate.
- **Constrained decoding locally** (grammar/JSON-schema-constrained Ollama). Free, offline, no
  vendor, and it solves the *structural* half of the problem. It does not give calibrated
  probabilities or a confidence, and it is slower, not faster. **This is the real competitor** and
  should be benchmarked against Jev in the bootstrap ⚠ (not evaluated here).
- **Azure AI Foundry structured output.** Already an opt-in path for 5a; same schema guarantee via
  JSON-schema mode, but paid, Azure-gated, and Phase 2.
- **A fine-tuned local classifier** (MIP-0025 tiers). Cheapest at runtime, most work up front.

## 10. Exam-coverage mapping

AI-103, "Implement a generative AI solution" — structured output, guardrails and a reviewer pass;
this adds a second, type-level guardrail next to the existing prompt-level one. Not an AI-500 row:
nothing here is multi-agent or autonomous.

## 11. Open questions

- No API key: every `(v)` below is documentation, not a live call. **Nothing in §5 has been run.**
- Does `state` accept a JSON object directly (docs say "String, JSON object, or array of text
  values") and does it count against the 32k sub-budget after serialisation?
- Is there an unpublished free allowance on Cloudflare Workers AI for `typesafe/jev`?
- What happens to a `Choice` whose options exceed 255 — error or truncation? The matcher call site
  (§5.4) is the one that could approach it.
- **Follow-up MIP:** constrained decoding on the local path (grammar-constrained Ollama) is worth
  its own proposal whatever happens to Jev — it would fix `extractJsonObject` for free, offline, and
  needs the next MIP number rather than a section here.

## Appendix

### Checked live

| URL | Date | What it returned |
|---|---|---|
| `typesafe.ai/blog/introducing-system-one-models-and-jev` | 2026-09-19 | System One definition, 70-500 ms, 40-200× claim, $0.042/MTok in + free output, "Join Waitlist", choice cardinality "up to 255", a `system-one-adapter-python` wrapper |
| `docs.typesafe.ai/models` | 2026-09-19 | `jev-1.13.0`/`jev-latest`/`jev-preview`; 64k per request, 32k state + longest question; 250k tok/s, 1,200 rpm, `429`; text only; bearer auth; no free tier mentioned |
| `docs.typesafe.ai/llms.txt` | 2026-09-19 | The three question types **Choice**, **Score**, **Noul** with semantics; answers carry probability, confidence, usage; Python and JS SDKs; no pricing or trial |
| `developers.cloudflare.com/ai/models/typesafe/jev/` | 2026-09-19 | Model id `typesafe/jev`, 32,000-token context, the same three question types with `type`/`instructions`/`criteria`; pricing "available through the Cloudflare dashboard"; no free-allocation statement |
| `openrouter.ai/typesafe/jev-1.13` | 2026-09-19 | **404 Not Found** — the route appears in search results but the page did not resolve |

### Not checked

- Any Jev API call. No key; the request/response shapes above are from documentation only.
- Latency, calibration, and the "40-200× faster" and "cannot hallucinate" claims — vendor claims.
- Waitlist turnaround ("a day or two") — from third-party write-ups, not experienced.
- Vercel AI Gateway's listing, and Workers AI pricing for this model.
- Whether `dspy` could compile a Jev question set the way it compiles the summariser prompt.
