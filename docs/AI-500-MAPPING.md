# marola — AI-500 domain coverage (planning doc)

[AI-500: Designing and Implementing Multi-Agent AI Solutions](https://learn.microsoft.com/en-us/credentials/certifications/)
is a beta, Expert-tier exam with **AI-103 as a mandatory prerequisite** (see
[`AI-103-MAPPING.md`](./AI-103-MAPPING.md) for that coverage). This doc is more speculative than
that one: it's a design target for extending marola from "one agentic pipeline" (AI-103 scope) into
"a multi-agent system" (AI-500 scope), not a record of what's already built. Sections below are
marked Built / Designed-not-built accordingly — do not read a table row as "done" unless it says so.

AI-500's four published domains, and where marola's current or planned architecture lands in each:

## 1. Architect multi-agent solutions (15-20%)

**Current state:** marola has one implicit two-agent pipeline today — a summarizer (DSPy-compiled
`CompiledPrompt`) and a reviewer/critic (`Reviewer.review`, a second LLM pass that grades and can
override the first). This is a real instance of the generator-critic pattern, but it's hardcoded as
two sequential calls in `Main` (`summarizeTop` → `reviewAndPrint`; `Recommender` has no LLM call at
all by design), not an orchestrated, addressable multi-agent system.

**Designed-not-built, to actually cover this domain:**

- **Name and separate the two existing roles as real agents**, each exposed as its own MCP tool
  (extending `cli/agent/SwimConditionsMcpServer.scala`, which today exposes only
  `BeachFinder`/`Recommender` as tools, not the LLM roles themselves): a `summarize_conditions` tool
  and a `review_summary` tool, callable independently by an external orchestrator, not just chained
  internally by `Recommender`.
- **Add a third agent with a genuinely different responsibility** rather than another summarizer
  variant — the natural candidate is a **safety/escalation agent** (see the catastrophe-detection
  idea in `FUTURE-WORK.md` §9's second half): it consumes the same live conditions data but asks a
  structurally different question ("is this an emergency, not just a bad swim day?") and can trigger
  a different output channel (a proactive Telegram push, not just a reply to a query). Three agents
  with three distinct roles (summarize / critique / escalate) is a much more honest "multi-agent
  solution" than three copies of the same LLM call.
- **Document the orchestration topology explicitly** once built — sequential pipeline vs.
  supervisor-routes-to-specialist vs. fully decentralized — this is a named AI-500 architecture
  decision, not an implementation detail to leave implicit.

## 2. Develop multi-agent solutions in Azure (30-35%, the largest domain)

**Current state:** the MCP tool-calling pattern (`SwimConditionsMcpServer`, official Java MCP SDK,
verified live via raw JSON-RPC) is the real foundation here — MCP is explicitly how agents expose
capabilities to other agents/orchestrators, not just to a chat UI.

**Designed-not-built:**

- **Azure AI Foundry Agent Service** (`azure/llm/AzureFoundryLlmClient.scala` is a plain REST
  chat-completions call; the `com.azure:azure-ai-agents` SDK is *not* a dependency yet — it was
  declared unused and removed, FABLE_REVIEW C5) — the concrete next step is adding that SDK for
  what it's actually for: registering marola's summarizer/reviewer/escalation
  agents as Foundry Agents and letting Foundry's own orchestration route between them, rather than
  marola's Scala code hardcoding the call sequence.
- **Agent-to-agent protocols beyond MCP** — MCP covers tool exposure; multi-agent-to-multi-agent
  communication (A2A-style) is a distinct, currently unreviewed piece. Flagging as unreviewed here
  rather than guessing at a specific library, consistent with this repo's rule of confirming things
  against real jars/docs before claiming they're a fit (see `AGENTS.md`).
- **State/memory shared across agents** — `SightingStore` and a future `UserPreferencesStore`
  (`FUTURE-WORK.md` §1.5) are the closest existing pieces; a true multi-agent system needs a shared
  conversation/task state store, which neither currently is.

## 3. Evaluate, optimize, and monitor multi-agent solutions (20-25%)

**Current state:** `FUTURE-WORK.md` §4 (harness ideas: evaluation, reviewer agent) is the direct
ancestor of this domain — the `Reviewer` agent (§4.2, DONE) is literally "one agent evaluating
another's output," just not yet framed as a formal eval harness. `azure/observability/Telemetry.scala`
covers single-pipeline observability (Application Insights/OpenTelemetry) but has no concept of
per-agent or per-conversation tracing across multiple agents.

**Designed-not-built:**

- A proper multi-agent eval harness (its ledger is proposed as MIP-0010) — extending `FUTURE-WORK.md` §4.1's evaluation-harness sketch to
  score not just final output quality but which agent contributed what, and whether the
  escalation agent's false-positive/false-negative rate on real conditions data is acceptable (this
  matters more than usual once an agent can proactively push alerts — see the catastrophe-detection
  idea).
- Tracing that follows a request across agent boundaries, not just within one process — this is
  where the Python-only LLMOps tooling gap (`FUTURE-WORK.md` §10) is most relevant: Langfuse-style
  multi-agent tracing has no direct JVM/Scala equivalent today.

## 4. Secure, govern, and deploy multi-agent solutions (20-25%)

**Current state:** managed identity on the Foundry client (`DefaultAzureCredential`; the other Azure clients still use keys — FABLE_REVIEW D1), no hardcoded keys anywhere,
`AGENTS.md`'s cost/deploy safety rules, and the existing single-agent phase-discipline pattern
(`ARCHITECTURE.md` §11) are the direct foundations — the same discipline that's kept this repo from
ever provisioning a paid resource without explicit sign-off applies with higher stakes once an agent
can autonomously act (send a proactive alert) rather than only respond to a query.

**Designed-not-built:**

- **An explicit human-confirmation gate for any new autonomous/proactive agent behavior** (the
  developer-side twin — the cost rule as a Claude Code `PreToolUse` hook — is MIP-0011) — the
  escalation agent's alerts are the first place marola would do something without being asked, which
  is a genuinely different risk profile than answering "what's the best hour to swim." This needs
  its own rate-limiting/circuit-breaker design before it ships, not just reusing the existing
  cost-safety rule.
- **Content Safety on agent outputs**, specifically for the escalation agent's public-facing alert
  text, given it would compete for attention with real government emergency announcements (see
  `FUTURE-WORK.md` §9) — getting this wrong has real-world consequences beyond a bad API bill.
- **Governance documentation for multi-agent audit trails** — who/what triggered which agent, on
  what data, with what output — extending `Telemetry.scala` rather than a new system.
