# MIP candidates

Ideas written up somewhere in the docs that are big enough for a Marola Improvement Proposal, one
row each, and where each stands. A *candidate* is an idea whose design would need the template's
rigour before it is built, as opposed to a refactor or a fix that can go straight to a branch.
Nothing here is a commitment. [`FUTURE-WORK.md`](../4-Research-and-plans/FUTURE-WORK.md) gives
each of its sections a verdict in its own heading; only its candidates are repeated here.

## Candidates

| Idea | Written up in | Where it stands |
|---|---|---|
| HTTP/SSE MCP transport for a hosted agent | [`ARCHITECTURE.md`](../2-Building-marola/ARCHITECTURE.md) §5c | Candidate, Phase 2 |
| Beyond swimming: surfing, diving, one scoring function per activity, multi-subscription | [`FUTURE-WORK.md`](../4-Research-and-plans/FUTURE-WORK.md) §1 | Candidate, large; depends on MIP-0004 (subscriptions) for §1.5. MIP-0009 notes activities as map layers |
| An evaluation harness over the benchmark and the reviewer, with per-agent scoring and cross-agent traces | [`FUTURE-WORK.md`](../4-Research-and-plans/FUTURE-WORK.md) §4.1; [`ROADMAP.md`](https://github.com/marola-dev/marola/blob/70526c8d22ad785c7d895f9241e1f6839f215add/docs/4-Research-and-plans/ROADMAP.md) §5 (retired) | Candidate; MIP-0010 provides the ledger it writes to |
| A catastrophe/hazard detection agent, competing with public alerts | [`FUTURE-WORK.md`](../4-Research-and-plans/FUTURE-WORK.md) §9.2; `ROADMAP.md` §5 | Candidate, gated by a human-confirmation design for proactive alerts: write that gate first |
| marola as actors: escalation, digest and answer agents on Pekko, each also an MCP tool | [`AGENT-FRAMEWORKS-SURVEY.md`](../4-Research-and-plans/AGENT-FRAMEWORKS-SURVEY.md) §2–§3; `ROADMAP.md` §5 | Candidate once MIP-0002 and MIP-0004 exist to orchestrate |
| "Ask marola about marola": a Q&A agent on the existing RAG (`ask_project_question` MCP tool) | [`AGENT-STACK-SURVEY.md`](../4-Research-and-plans/AGENT-STACK-SURVEY.md) §5 | Candidate, v0 on the existing RAG |
| The Gemini Code Assist GCP side as Besom under `infra/gemini/`, state in GCS, `preview` on PR and `up` on dispatch on marola's runners | [`GEMINI-CODE-ASSIST.md`](../4-Research-and-plans/GEMINI-CODE-ASSIST.md) §4–§6 | Candidate, the repo's first IaC; verify §4's connection-label question first. The hosted counterpart of MIP-0060's parked local route |
| Garmin data: FIT-file import first | [`FUTURE-WORK.md`](../4-Research-and-plans/FUTURE-WORK.md) §11 | Candidate (Phase 4, personalisation); the shape is decided (files, never the unofficial API) |
| The bot's continuous deployment | [MIP-0065](MIP-0065-ci-cd-on-github-hosted-runners.md) §5.7 | Candidate, Phase 2: a follow-up MIP after Phase 1 |
| Ten external candidates (Kimi, 2026-09-06), triaged, with a provider-query checklist | `ROADMAP.md` §7 | MIP-0021 and MIP-0022 were drafted from it; verify the checklist before any other becomes a MIP |

## Already a MIP

| Idea | Written up in | MIP |
|---|---|---|
| llm4s as an opt-in module: agent loop, MCP client/server, guardrails, structured output | [`AGENT-FRAMEWORKS-SURVEY.md`](../4-Research-and-plans/AGENT-FRAMEWORKS-SURVEY.md) §1.2; [`AGENT-STACK-SURVEY.md`](../4-Research-and-plans/AGENT-STACK-SURVEY.md) (2026-09-23) | MIP-0012 (Draft). llm4s stays its in-process layer, ADK only with MIP-0057, agent4s read-only |
| The Telegram bot | [`TELEGRAM-SETUP.md`](../1-Using-marola/TELEGRAM-SETUP.md) | MIP-0002 (Draft) |
| Calibrating the jellyfish/whale heuristics on reports | [`ARCHITECTURE.md`](../2-Building-marola/ARCHITECTURE.md) §8 | MIP-0007 (Draft) |
| Benchmark runs in a ledger | marola-ml's [`docs/benchmarks/`](https://github.com/marola-dev/marola-ml/tree/main/docs/benchmarks) | MIP-0010; the Markdown stays canonical in v1 |
| Four skill candidates | [`AGENT-SKILLS.md`](../3-Ways-of-working/AGENT-SKILLS.md) §3 | MIP-0011 task 8 |
| Jail notes | [`FABLE_REVIEW.md`](https://github.com/marola-dev/marola/blob/70526c8d22ad785c7d895f9241e1f6839f215add/docs/4-Research-and-plans/FABLE_REVIEW.md) §3 (retired) | MIP-0011 task 5 |
| A weekly post-planner and multi-platform exporter | [`SELF-DOCUMENTING.md`](../4-Research-and-plans/SELF-DOCUMENTING.md) | MIP-0018 (Draft) |
| The awesome-list and its human-gated `scripts/awesome_agentic_digest.py` update routine | [`AWESOME-AGENTIC-ENGINEERING.md`](../4-Research-and-plans/AWESOME-AGENTIC-ENGINEERING.md) | MIP-0043 (Implemented) |

## Not MIP material

Refactors and fixes, for a direct PR: [`EFFECTS-MAP`](https://docs.marola.dev/5-Repos/marola-app/1-design_effects/)
§2 (`AppConfig.fromEnv`'s hidden effect), §3 (the MCP unsafe boundary) and §4 (resource
lifecycle); the [Scala 3 / JDK review](https://docs.marola.dev/5-Repos/marola-app/2-libraries_scala3-jdk/)'s
adopt list, in the order its §4 gives; [`SKILLS.md`](../4-Research-and-plans/SKILLS.md)'s Stage 6
items (typed `Abort` channels, `Async.foreach`); and `ARCHITECTURE.md` §9's known limitations.

## How a candidate becomes a MIP

Say "MIP for <idea>" in a session: the `/marola-devkit:mip` skill takes the next number from the
[MIP index](README.md), fetches and dates every external claim, and opens the proposal as a Draft
PR.
