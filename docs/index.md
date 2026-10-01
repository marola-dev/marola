# Start here

One row per guide under `docs/`, which the sidebar groups by who it is for (the rules themselves
live in `AGENTS.md`; the reasons in `PHILOSOPHY.md`). The **Kind** column says how to read a file:
*reference* is kept current as the truth about the code; *how-to* is a procedure; *review* is a
dated finding that is not updated in place; *roadmap* is ideas, most not built. The last column
names the ideas in that file that are big enough for a Marola Improvement Proposal (`MIPs/`, the
`/marola-devkit:mip` skill) and where each stands.

| File | Kind | MIP material inside, and its status |
|---|---|---|
| [`ARCHITECTURE.md`](./2-Building-marola/ARCHITECTURE.md) | reference | §5c HTTP/SSE MCP transport for a hosted agent (candidate, Phase 2); §8 calibrating the heuristics on reports (→ MIP-0007); §9 known limitations (fixes, not MIPs) |
| [`PHASES.md`](./PHASES.md) | reference | none; each phase names its own MIP inline (MIP-0057, MIP-0008) |
| [`FUTURE-WORK.md`](./4-Research-and-plans/FUTURE-WORK.md) | roadmap | see the section-by-section list below |
| [`EFFECTS-MAP.md`](./2-Building-marola/EFFECTS-MAP.md) | review | §2 `AppConfig.fromEnv` hidden effect, §3 MCP unsafe boundary, §4 resource lifecycle — refactors, direct PRs, no MIP |
| [`RUN-LOCALLY.md`](./1-Using-marola/RUN-LOCALLY.md) | how-to | none (it documents what MIPs shipped) |
| [`TELEGRAM-SETUP.md`](./1-Using-marola/TELEGRAM-SETUP.md) | how-to | the bot itself is MIP-0002 (Draft) |
| [`SKILLS.md`](./4-Research-and-plans/SKILLS.md) | roadmap | a skills ladder, not features; Stage 6 items (typed `Abort` channels, `Async.foreach`) are refactors |
| [`SCALA3-JDK-REVIEW.md`](./2-Building-marola/SCALA3-JDK-REVIEW.md) | review | the adopt list — direct PRs in the order §4 gives, no MIP |
| [`AGENT-FRAMEWORKS-SURVEY.md`](./4-Research-and-plans/AGENT-FRAMEWORKS-SURVEY.md) | review | §1.2 llm4s → **MIP-0012** (opt-in module: agent loop, MCP client/server, guardrails, structured output); §2 Python ideas → Scala shapes and §3 Pekko for the multi-agent core: **candidate MIP** ("marola as actors: escalation, digest and answer agents on Pekko") once MIP-0002/0004 exist to orchestrate |
| [`AGENT-STACK-SURVEY.md`](./4-Research-and-plans/AGENT-STACK-SURVEY.md) | review | agent4s, llm4s and ADK against every MIP (2026-09-23): llm4s stays **MIP-0012**'s in-process layer, ADK only with **MIP-0057**, agent4s read-only; §5 a "ask marola about marola" Q&A agent, v0 on the existing RAG: **candidate MIP** (`ask_project_question` MCP tool) |
| [`ISSUE-FLOW.md`](./3-Working-on-the-repo/ISSUE-FLOW.md) | reference | the GitHub tracking standard itself is **MIP-0063**; nothing further inside |
| [`CI-CD.md`](./3-Working-on-the-repo/CI-CD.md) | reference | none; the workflows as **MIP-0065** left them. The bot's own deploy is a Phase 2 candidate (MIP-0065 §5.7) |
| [`DIAGRAMS.md`](./3-Working-on-the-repo/DIAGRAMS.md) | how-to | the dialect rule is **MIP-0068**; one rendered example per dialect the rule allows |
| [`DEV-FLOW.md`](./3-Working-on-the-repo/DEV-FLOW.md) | how-to | none; MIP-0011 turns parts of it into hooks/agents |
| [`GEMINI-CODE-ASSIST.md`](./3-Working-on-the-repo/GEMINI-CODE-ASSIST.md) | how-to | §4–§6 the GCP side as Besom under `infra/gemini/`, state in GCS, `preview`-on-PR / `up`-on-dispatch on marola's runners — **candidate MIP** (the repo's first IaC; verify §4's connection-label question first); the hosted counterpart of MIP-0060's parked local route |
| [`ROADMAP.md`](./4-Research-and-plans/ROADMAP.md) | plan | the ordering: today's review bugs (P0), open MIPs, the proposed multi-agent MIPs (§5), ten triaged external candidates with a provider-query checklist |
| [`AGENT-SKILLS.md`](./3-Working-on-the-repo/AGENT-SKILLS.md) | how-to | §3 four skill candidates → MIP-0011 task 8 |
| [`FABLE_REVIEW.md`](./4-Research-and-plans/FABLE_REVIEW.md) | review | §3 jail notes → MIP-0011 task 5 |
| [`API.md`](./2-Building-marola/API.md) | reference | none; the way in to the scaladoc/pdoc trees `api-docs.yml` generates (**MIP-0064**) |
| [`SELF-DOCUMENTING.md`](./3-Working-on-the-repo/SELF-DOCUMENTING.md) | reference | the research behind **MIP-0018** (weekly post-planner + multi-platform exporter) |
| [`AWESOME-AGENTIC-ENGINEERING.md`](./4-Research-and-plans/AWESOME-AGENTIC-ENGINEERING.md) | reference | **MIP-0043** (Draft) — the doc itself and its human-gated `scripts/awesome_agentic_digest.py` update routine; no further MIP material inside |
| [`benchmarks/`](https://github.com/marola-dev/marola/blob/main/docs/benchmarks/) | reference | the runs MIP-0010 would move into a ledger (Markdown stays canonical in v1) |
| [`mips/`](./MIPs/README.md) | — | the proposals themselves, with status |

## `FUTURE-WORK.md`, section by section

| § | Idea | Where it stands |
|---|---|---|
| 1 | Beyond swimming: surfing, diving, one scoring function per activity, multi-subscription | **Candidate MIP**, large; depends on MIP-0004 (subscriptions) for §1.5. MIP-0009 notes activities as map layers |
| 2 | kyo-http + kyo-schema instead of hand-rolled `Http`/`Json` | Refactor, no behaviour change — direct PR when wanted; not a MIP |
| 3 | kyo-ai / kyo-llm | Reviewed, not a fit; revisit — nothing to propose |
| 4.1 | An actual evaluation harness | **Candidate MIP** ("eval harness over the benchmark and the reviewer"); MIP-0010 provides the ledger it writes to |
| 4.2 | Reviewer/critic pass | Built |
| 5 | workflows4s | Reviewed, revisit if orchestration grows |
| 6 | Two more Scala 3 libraries | Adopt directly if at all |
| 7 | Splitting out of the monorepo / into modules | Superseded / done |
| 8 | Smaller items | Direct fixes |
| 9.1 | RAG and fine-tuning over marine literature | Shipped as MIP-0001 (RAG) and `finetune/` tiers |
| 9.2 | Catastrophe/hazard detection agent competing with public alerts | **Candidate MIP**, gated by a human-confirmation design for proactive alerts — write that gate first |
| 10 | Scala/JVM LLMOps gap; `ds4s` (DSPy for Scala) | `ds4s` is **not a marola MIP by its own definition** — "a separate library-shaped project … large enough to be its own repo" that marola would consume. The other half of §10, Langfuse-shaped LLM tracing on the JVM, **is MIP-0010**. The "DSPy stays Python" conclusion is revisited by **MIP-0012** (marola's small bootstrap becomes a Scala step in `core/prompt/`; `dspy/` deprecated) |
| 11 | Garmin data: FIT-file import first | **Candidate MIP** (Phase 4, personalisation); the doc already decided the shape (files, never the unofficial API) |

## How a candidate becomes a MIP

Say "MIP for <idea>" in a session: the `/marola-devkit:mip` skill takes the next number from `mips/README.md`,
fetches and dates every external claim, and opens the proposal as a Draft PR. Nothing in the
tables above is a commitment; a *candidate* is an idea whose design would need the template's
rigour before it is built, as opposed to a refactor or a fix that can go straight to a branch.
