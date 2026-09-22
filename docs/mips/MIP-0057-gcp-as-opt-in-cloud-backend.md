# MIP-0057: GCP as an opt-in alternative cloud backend, alongside Azure

| | |
|---|---|
| **Status** | Draft |
| **Author** | Claude Sonnet 5, from a repo-maintainer brief of 2026-09-15 |
| **Created** | 2026-09-15 |
| **Phase** | 0 — this MIP is a design/comparison exercise only; if accepted, the first build task (§9's recommended path) is Phase 0 work (no Telegram bot dependency), but *any* real GCP provisioning is gated the same way Phase 2's Azure provisioning already is (`AGENTS.md` cost-safety rule) |
| **Related** | `docs/ARCHITECTURE.md` §5/§6 (the six pluggable integrations and today's Azure resource list), `.claude/rules/azure.md`/`.claude/hooks/guard-azure.sh` (the cost-safety enforcement this MIP proposes mirroring), MIP-0012 (llm4s — a Scala-native LLM/agent layer; whatever `LlmClient` shape it lands on is what a `gcp/` Vertex AI backend would implement against), MIP-0055 (Azure AI Search as `KnowledgeStore`'s opt-in backend — same shape question for a GCP alternative, not addressed here) |
| **Effort** | S for this MIP itself (a survey/comparison, no code); the thing it recommends building (§9, LLM-only `gcp/` module) is **M** — a new sbt module mirroring `azure/`'s existing shape (one trait implementation, one dependency, no new CI workflow, no new trait) |
| **Gain** | `infra/dev-loop` (avoid single-vendor lock-in on the *opt-in* integrations — today "Azure opt-in" is really "Azure or nothing" past the local default); `cost/ops` (a second free-tier shape to pick from per integration, e.g. Cloud Vision's flat 1,000-units/month free tier vs. Azure AI Vision's transaction-based free tier); `exam coverage` (none directly — GCP isn't part of AI-103/AI-500, see §10) |
| **Effort vs Gain** | `do when X lands` — this MIP does not itself justify building anything; the recommended first slice (§9) is a `cheap win` sized on its own once someone wants a second LLM vendor, but nothing here is blocking or blocked-on by product need today |
| **Depends on** | Not blocked by any MIP. Depends on MIP-0012's `LlmClient`/agent-layer shape settling first if the LLM slice is built after that lands, since a `gcp/` module should implement whatever trait shape exists at build time, not a shape this MIP freezes. No Phase 1 gate (Telegram bot) — this is Phase 0 comparison work, and even the recommended build slice needs no Telegram bot. A paid GCP resource is gated exactly like a paid Azure resource (`AGENTS.md`): explicit human go-ahead before any `gcloud`/`terraform apply`/console provisioning step |
| **Blocked by** | none |
| **Risk** | A third opt-in path (local / Azure / GCP) per integration multiplies the number of code paths that are "compiles, never run against a live account" (`ARCHITECTURE.md` §5's honest status notes already flag this for every Azure backend) — adding GCP without ever provisioning it just adds more unverified surface area for the same reason, not less. The mitigating design choice (§9) is to add only one GCP-backed trait implementation at a time, same discipline as Azure's rollout |
| **Cost so far** | — |

## 1. Summary

marola's `docs/ARCHITECTURE.md` §5 already treats six capabilities as pluggable behind traits,
each with a free local default and one paid opt-in backend, today, always Azure. This MIP asks
whether Google Cloud Platform (GCP) should be a *second* opt-in backend for some or all of those
traits, alongside Azure, never replacing the local-first default. It compares the Azure service
already named in `ARCHITECTURE.md` §6 against its closest GCP equivalent for each of the six
integrations, states what could and couldn't be price/terms-verified this session, and recommends
starting, if at all, with a single, low-risk slice (Vertex AI/Gemini behind `LlmClient`) rather
than a six-integration parallel build. It commits to nothing: no code changes, no module, no
provisioning.

## 2. Motivation

Every one of marola's six pluggable integrations (`ARCHITECTURE.md` §5) is local-by-default with
exactly one paid opt-in today: Azure. That's a deliberate, working pattern, but it is also a
single-vendor dependency for anyone who wants the *opt-in* tier at all. Three concrete reasons to
look at GCP specifically, not "some other cloud" in the abstract:

- **Model marketplace breadth.** Vertex AI's Model Garden serves Google's own Gemini family
  alongside third-party and open models from one API surface, which is a different shape from
  Azure AI Foundry's own catalog, worth a side-by-side before assuming Foundry is the only sane
  opt-in for §5a.
- **A different free-tier shape, not just a different price.** Cloud Vision's free tier is a flat
  1,000 units/month across the whole account (confirmed this session, see §4.4); Azure AI Vision's
  free tier is an F0-tier transaction cap per resource. Neither is "cheaper" in the abstract, the
  shapes suit different usage patterns, which only matters if both are on the table to compare.
- **Firestore/Cloud SQL/BigQuery vs. Cosmos DB** for §5d's sighting store, **Google Maps Platform**
  vs. Azure Maps for §5b's route distance, and **Cloud Logging/Trace** vs. Application Insights for
  §5f's telemetry are the natural GCP-side equivalents once the LLM question is on the table, a
  maintainer choosing an opt-in tier today has no comparison to make that decision against.

This MIP does not argue GCP is better or cheaper, it lays out what's actually comparable and what
isn't, so a future "turn on the paid tier" decision (still gated by `AGENTS.md`'s cost rule) has
real numbers instead of assumed ones.

## 3. User-visible change

None from this MIP alone, no code changes. If §9's recommended slice is later built and a human
opts in (`MAROLA_LLM_PROVIDER=gcp`, by analogy with today's `MAROLA_LLM_PROVIDER=azure`), the
user-visible change is exactly what the Azure opt-in already produces: the same CLI/MCP output
shape, generated by a different backend. No new output field, no new flag surface beyond the one
new env var value.

## 4. Data sources and dependencies reviewed

One subsection per integration, matching `ARCHITECTURE.md` §5's own lettering (5a/5b/5d/5e/5f) plus
§5c (agentic tool access, which has no cloud-hosted equivalent to compare) and §5g/5h (regional/
local-only, no Azure opt-in exists to compare against, so GCP doesn't apply either).

### 4.1 §5a — Query synthesis: Azure AI Foundry vs. Vertex AI (Gemini)

- **GCP side:** Vertex AI serves Gemini models (and Model Garden third-party models) via a REST/
  gRPC API, analogous in shape to Foundry/Azure OpenAI's chat-completions call, a plain HTTP
  request, no SDK required, matching `AzureFoundryLlmClient`'s own "plain REST call, not the full
  agent SDK" design choice (`ARCHITECTURE.md` §5a).
- **Pricing (checked live, `ai.google.dev/gemini-api/docs/pricing`, 2026-09-15):** Gemini 2.5 Flash
  is $0.30/1M input tokens, $2.50/1M output tokens (text/image/video input; $1.00/1M for audio
  input). Gemini 2.5 Flash-Lite is cheaper still: $0.10/1M input, $0.40/1M output. Both list a free
  tier, but it is scoped to Google Search grounding requests specifically (a shared 500
  requests/day quota), not general chat completions, so, unlike Ollama's genuinely free local
  path, there is no meaningfully free way to run Gemini chat completions at marola's actual usage
  (query synthesis, not grounded search).
- **Azure side pricing:** not re-verified this session, `ARCHITECTURE.md` §6 already names
  `gpt-4o-mini` as "cheapest capable model" for Foundry without a cited number; this MIP does not
  add one. A real comparison needs both sides priced the same day, which is future work (§11).
- **Auth:** GCP's equivalent of `DefaultAzureCredential`+managed identity is a service account plus
  **Workload Identity Federation** (WIF), lets a workload authenticate without a downloaded JSON
  key, the GCP-side answer to "never hardcode a key" (`AGENTS.md`). Not independently verified
  against a live GCP project this session (none provisioned, per the cost-safety rule), the shape
  is well-documented and analogous to Azure's managed identity, but "analogous" is not "confirmed
  identical."
- **Migration/parallel-support effort:** low. `LlmClient` is already a two-implementation trait; a
  third implementation (`VertexAiLlmClient`) is additive, same shape as `AzureFoundryLlmClient`.
  The real dependency risk is MIP-0012 (llm4s) potentially reshaping `LlmClient` before this is
  built, build against whatever trait shape exists at the time, not this MIP's description of it.

### 4.2 §5b — Real travel distance: Azure Maps vs. Google Maps Platform (Routes API)

- **GCP side:** Google Maps Platform's Routes API (`computeRoutes`) is the direct equivalent of
  Azure Maps' Route Directions API used today.
- **Pricing/free-tier shape (checked live, `mapsplatform.google.com/pricing`, 2026-09-15):** Google
  Maps Platform moved off its old $200/month credit model in March 2025 to a per-SKU free-call
  allotment instead, reported by the fetched page as 10K free calls/SKU/month on the "Essentials"
  plan, 5K on "Pro," 1K on "Enterprise," with fixed-price subscription tiers ($100/$275/$1,200 per
  month) as an alternative to pure pay-as-you-go, and volume discounts (20%–80%) at high monthly
  call counts. This is a materially different shape from Azure Maps' "free monthly transaction
  allotment" per `ARCHITECTURE.md` §6, which itself is not cited with a number there, so this MIP
  can say the *shapes* differ (subscription tiers vs. a flat transaction cap) but not which is
  cheaper at marola's actual call volume without both sides priced against a real usage estimate.
- **Auth:** an API key (as Azure Maps already uses today, per `ARCHITECTURE.md` §5b/§6, this repo
  already takes an Azure Maps key from the environment, not a managed identity, per
  `.claude/rules/azure.md`'s own FABLE_REVIEW D1 note), no meaningful auth-model difference here.
- **Migration/parallel-support effort:** low, `RouteFinder`'s Azure Maps call is a single REST GET
  with a query-string key; a GCP variant is the same shape against a different endpoint and request
  format (`computeRoutes` is a POST with a JSON body and a field mask, not a query string, a real
  implementation difference, not just an endpoint swap).

### 4.3 §5d — Sighting reports: Cosmos DB vs. Firestore

- **GCP side:** Firestore is the closest shape match, a managed NoSQL document store, the natural
  sibling to Cosmos DB's partitioned-container model used today (`CosmosDbSightingStore`,
  partitioned by `beach_name`). Cloud SQL (relational) and BigQuery (analytical/columnar) are GCP
  options too, but neither matches `Sighting`'s actual access pattern (append + query-by-beach) as
  directly as Firestore does, including them only for completeness per the task brief, not because
  they're serious contenders.
- **Pricing/free-tier shape (checked live, `firebase.google.com/docs/firestore/quotas`,
  2026-09-15):** Firestore's free tier is a **daily** quota, not monthly: 50,000 reads/day, 20,000
  writes/day, 20,000 deletes/day, 1 GiB stored. `ARCHITECTURE.md` §6 describes Cosmos DB's
  serverless tier as keeping "idle cost near zero" without a cited number, so again, shape is
  comparable (both are effectively free at marola's current, near-zero sighting volume) but a
  precise side-by-side needs a priced Cosmos DB serverless number this session didn't fetch.
- **Auth:** Firestore via a GCP service account (WIF for a deployed workload, same mechanism as
  §4.1) vs. Cosmos DB's current key-based auth (`CosmosDbSightingStore` takes a key from the
  environment today, same FABLE_REVIEW D1 gap noted above), so a Firestore backend built with WIF
  from day one would actually be a *better* credential story than marola's existing Cosmos DB path,
  not just an equivalent one.
- **Migration/parallel-support effort:** low-to-medium. `SightingStore`'s `record`/`recentFor`
  shape maps directly onto Firestore's collection/document model; the existing Cosmos backend's
  choice to pass `java.util.Map` rather than a typed POJO (avoiding a Jackson dependency,
  `ARCHITECTURE.md` §5d) is a pattern a Firestore client could reuse with its own SDK's document-map
  API.

### 4.4 §5e — Photo analysis: Azure AI Vision vs. Cloud Vision API

- **GCP side:** Cloud Vision API's label/caption-style detection is the direct equivalent of Azure
  AI Vision's Image Analysis 4.0 API used today.
- **Pricing/free-tier shape (checked live, `cloud.google.com/vision/pricing`, 2026-09-15):** first
  1,000 units/month free across all features; $1.50 per 1,000 units from 1,001 to 5,000,000
  units/month, $1.00/1,000 beyond that (label detection tier; other features like Web Detection are
  priced differently, e.g. $3.50/1,000). This free tier is a flat per-account monthly cap,
  structurally different from Azure's transaction-based F0 tier (not itself re-priced this
  session), genuinely different shapes worth knowing about even without a head-to-head number.
- **Auth:** GCP service account/WIF vs. Azure AI Vision's current key-based auth (same
  FABLE_REVIEW D1 gap as Cosmos DB/Azure Maps above).
- **Migration/parallel-support effort:** low. `VisionClient.describe(imageBytes)` is a
  single-method trait; a `CloudVisionClient` is additive, same shape as `AzureVisionClient`. One
  real design fork: Cloud Vision returns structured labels/annotations (not a free-text caption by
  default the way Azure's Image Analysis "caption" feature does), `describe`'s return type
  (presumably a caption string today) may need to compose Cloud Vision's label list into a sentence
  itself, a small but real implementation difference, not a drop-in swap.

### 4.5 §5f — Observability: Application Insights vs. Cloud Logging/Cloud Trace

- **GCP side:** two separate services stand in for the one Application Insights resource:
  Cloud Logging (log ingestion) and Cloud Trace (distributed tracing/spans), both under the wider
  "Google Cloud Observability" (formerly Stackdriver) umbrella. `Tracing.withSpan`/`llmSpan`
  (`ARCHITECTURE.md` §5f) map onto Cloud Trace specifically, not Cloud Logging.
- **Pricing/free-tier shape (search-result summary, not independently fetched from the source
  page this session, see Appendix "Not checked"):** commonly reported as 50 GiB/month free log
  ingestion (then $0.50/GiB) for Cloud Logging, and 2.5 million free spans/month (then $0.20/million
  spans) for Cloud Trace. **Flag explicitly: these two numbers came back from a WebSearch summary,
  not a direct fetch of `cloud.google.com/logging/pricing`/`cloud.google.com/trace/pricing`, both of
  which returned unusable/truncated content this session, treat them as plausible, not
  confirmed**, and re-verify against the live pages before using either number in a real decision.
  Application Insights' own pricing is not re-cited here either (`ARCHITECTURE.md` §5f/§6 doesn't
  cite one).
- **Auth:** GCP service account/WIF, consistent with every other GCP integration above; Application
  Insights already uses a connection string, not a key or managed identity, per
  `AzureMonitorTracing`'s existing design.
- **Migration/parallel-support effort:** medium, the core repo already has a vendor-neutral
  `Tracing` trait (`core/observability/Tracing`) built specifically so a third backend is additive,
  per `ARCHITECTURE.md` §5f's own design intent ("`AzureMonitorTracing`... behind the trait").
  OpenTelemetry has an official Google Cloud exporter, so a `GoogleCloudTracing` implementation
  would plug into the same OTLP-shaped pattern `MlflowTracing` already uses, rather than needing
  a wholly new export mechanism.

### 4.6 §5c — Agentic tool access: no GCP equivalent to compare

§5c's MCP server runs over stdio and needs no cloud account at all locally; its only "cloud"
variant noted in `ARCHITECTURE.md` is a hypothetical Foundry agent using the HTTP/SSE MCP
transport, which is explicitly **not built**. Vertex AI Agent Builder / Agent Engine would be the
GCP-side analog if that transport variant were ever built for either cloud, but since neither side
has anything running today, there is nothing concrete to compare; noted for completeness, not
pursued further here.

### 4.7 §5g/§5h — no Azure opt-in exists, so no GCP comparison applies

Bathing-water quality (§5g) is sourced from Brazilian regional agencies, not a cloud service on
either side (`ARCHITECTURE.md` §5g explicitly: "none — regional agencies, not a cloud service").
Ocean knowledge/RAG (§5h) has Azure AI Search as a *named-but-not-built* Phase 2 sibling
(`ARCHITECTURE.md` §5h, and MIP-0055's own Azure AI Search backend, written-not-provisioned), a
Vertex AI Search comparison would be a reasonable follow-up once MIP-0055's Azure side is actually
built, but is out of scope here since there's no working Azure backend yet to compare against.

### Pick

No pick is made here, see §9 for the recommended path. This section's job was comparing what
exists, not choosing a winner.

## 5. Design

No code is proposed to change in this MIP. If a future MIP or task builds §9's recommended slice,
the shape would be:

```scala
// core/ — no change to the LlmClient trait signature itself (whatever MIP-0012 settles it to)
trait LlmClient:
  def complete(messages: List[ChatMessage]): String < (Abort[LlmError] & Sync)

// gcp/ — new sbt module, mirroring azure/'s existing structure
final class VertexAiLlmClient(project: String, location: String, model: String) extends LlmClient:
  // REST call to Vertex AI's generateContent endpoint, auth via a service account /
  // Workload Identity Federation token — same "plain REST, not the full agent SDK"
  // choice AzureFoundryLlmClient already makes (ARCHITECTURE.md §5a)
  def complete(messages: List[ChatMessage]): String < (Abort[LlmError] & Sync) = ???
```

`AppConfig.llmClient` would grow a third branch (`MAROLA_LLM_PROVIDER=gcp`) alongside `local`/
`azure`, returning `None` with a clear message if GCP is selected but not fully configured, the
same graceful-failure contract `ARCHITECTURE.md` §5 already requires of every backend factory.
`build.sbt` would gain a `gcp/` project depending on `core/`, structured exactly like `azure/`
today (zero dependency from `local/` or `core/` back onto it). Nothing here is built by this MIP.

## 6. Scoring / safety impact

None. Every integration this MIP discusses (§5a/§5b/§5d/§5e/§5f) is either non-safety-scoring
(query synthesis is deliberately kept out of `Swimability.score`, `ARCHITECTURE.md` §5a) or
infrastructure (storage, distance refinement, telemetry). No GCP backend changes what
`Swimability.score` computes or how, the six pluggable integrations were designed precisely so a
backend swap can never touch scoring logic, and this MIP doesn't change that design.

## 7. Verification plan

This MIP proposes no code, so there is nothing to unit-test yet. If §9's recommended slice is
later built as its own task/PR, that PR's own verification plan should require, at minimum:

- A unit test for `AppConfig.llmClient`'s new `gcp` branch (config-present/config-absent cases),
  matching the existing test shape for the `azure` branch.
- A compile-only check of `VertexAiLlmClient` against the real Vertex AI REST API shape (matching
  `ARCHITECTURE.md` §5a's own honest status note that `AzureFoundryLlmClient` "compiles... but is
  unverified against a live Foundry deployment"), explicitly labeled unverified-against-a-live-
  account until a human opts in and provisions a real GCP project.
- `just build && just test && just quality` green, same gate as every other change.

"Done" for *this* MIP is: merged as `Draft`, indexed in `docs/mips/README.md`, with every §4 claim
either cited live or explicitly flagged unverified, not a working GCP integration.

## 8. Risks, limitations, and honest caveats

- **Every price/free-tier figure in §4 was fetched on 2026-09-15 and can drift**, GCP's own Maps
  Platform pricing already changed shape once (the March 2025 move off the $200/month credit,
  confirmed live this session), so a figure here going stale is not hypothetical.
- **This MIP does not price the Azure side of any comparison to the same rigor**, every §4
  subsection is honest that the Azure figure, where cited at all, comes from `ARCHITECTURE.md` §6's
  own prose (itself uncited in most cases), not a fresh fetch this session. A real "which is
  cheaper" decision needs both sides priced the same day against the same estimated usage, which
  this MIP does not attempt.
- **§4.5's Cloud Logging/Cloud Trace figures are the weakest sourced numbers in this MIP**, they
  came back from a WebSearch summary after two direct page fetches failed (truncated/404), and are
  flagged as such rather than presented as confirmed.
- **Three GCP price points cited here (Vertex AI Gemini, Cloud Vision) came from an AI-summarized
  fetch of the vendor's own page, not a raw HTML/text capture**, the fetch tool itself does model-
  assisted extraction, which is a real, if small, risk of paraphrase-introduced error even when the
  page was genuinely retrieved (as opposed to the cases where the tool visibly failed and fell back
  to "general knowledge," which are separately flagged in the Appendix).
- **No GCP resource has been, or will be, provisioned to verify any of this against a live
  account**, every auth-model claim (service accounts, Workload Identity Federation) is a
  documented-shape comparison, not something exercised against a real GCP project, mirroring
  exactly how `ARCHITECTURE.md` already flags most of marola's *existing* Azure backends as
  "compiles... unverified against a live account."
- **Adding a third backend option per integration is a real, if small, maintenance cost** even
  before anything is built: this MIP itself is now a doc another maintainer has to read before
  assuming "opt-in" means "Azure." Worth stating plainly rather than pretending a comparison MIP is
  free.

## 9. Alternatives considered

- **Do nothing (stay Azure-only for every opt-in tier).** Loses nothing marola uses today, every
  local default still works with zero cloud account. The cost is optionality: anyone who *wants*
  the paid tier only ever gets one vendor to price against. Not unreasonable, given `AGENTS.md`'s
  own "opt-in per integration, never a package deal" principle already limits blast radius either
  way, this MIP's real argument is comparison-shopping value, not urgency.
- **Add GCP as a backend for all six integrations at once.** Rejected: violates the same
  incremental, per-integration discipline `ARCHITECTURE.md` §5 already uses for Azure (Foundry
  shipped and was verified live long before Cosmos DB/Vision/Application Insights caught up, per
  each subsection's own "Status" notes), no reason to abandon that discipline for a second vendor.
- **Recommended path: add `gcp/` as a fully opt-in third backend module behind the existing core
  traits, starting with LLM only (Vertex AI/Gemini), if and when a maintainer wants a second LLM
  vendor to price against Foundry.** This is the lowest-risk single slice: `LlmClient` is already a
  two-implementation trait (so adding a third changes no interface), query synthesis is explicitly
  non-safety-scoring (§6), and §4.1's pricing shows a real, cited reason to want the comparison
  (Gemini 2.5 Flash-Lite's $0.10/$0.40 per-1M-token pricing is a genuinely different price point
  from anything currently cited for Foundry). The task brief's own suggested order (LLM first) held
  up against a read of the actual architecture, nothing in §4/§5's other integrations argued for
  starting anywhere else. **This MIP does not commit to building even that slice**, it only says
  that if one integration is picked first, the evidence points here.
- **A GCP-specific cost/deployment guard (`guard-gcp.sh`), built now, ahead of any GCP code.**
  Rejected as premature: `.claude/hooks/guard-azure.sh` was built because `azure/` code and real
  `azd`/`az` commands already exist in this repo to guard against; building a `guard-gcp.sh` today
  would be guarding an empty directory. Stated instead as an explicit prerequisite (§11), a real
  task for whoever picks up §9's recommended slice, before any `gcloud`/Terraform command that
  provisions anything, not something this MIP builds.

## 10. Exam-coverage mapping

None. `docs/AI-103-MAPPING.md` and `docs/AI-500-MAPPING.md` both map Microsoft's own AI-103/AI-500
certification domains, which are Azure-specific by definition — a GCP backend closes no row in
either mapping. Noted here per the `mip` skill's own instruction to check both before writing, per
its "Before implementing a feature" step in `AGENTS.md` — the honest answer is "not applicable,"
not a gap to close.

## 11. Open questions

- **Which integration, if any, does a maintainer actually want a second vendor for?** This MIP
  argues LLM (§9) is the lowest-risk *first* slice if one is picked, not that one must be picked at
  all, that's a product decision, not something this MIP can settle.
- **A same-day, same-usage-estimate price comparison for every §4 pair**, this MIP's Azure-side
  figures are inherited from `ARCHITECTURE.md` §6's own uncited prose in most cases; a real
  head-to-head needs both sides fetched and priced against marola's actual (tiny) usage volume on
  the same day.
- **§4.5's Cloud Logging/Cloud Trace numbers need a direct re-fetch** of
  `cloud.google.com/logging/pricing` and `cloud.google.com/trace/pricing`, both returned unusable
  content (truncated / 404) this session; the figures used came from a search-result summary
  instead and are flagged as such throughout.
- **A `guard-gcp.sh`-style PreToolUse hook, and the `.claude/settings.json` deny-list entries it
  would need** (mirroring `azd up`/`az deployment `/`az group create`'s literal-prefix denials for
  GCP's own `gcloud deployment-manager`/`terraform apply`/`gcloud projects create` equivalents) is a
  named prerequisite for §9's slice, not something to design in more detail until someone actually
  starts that build, flagged here so it isn't forgotten when that day comes.
- **Follow-up MIP:** a Vertex AI Search vs. Azure AI Search comparison for §5h/MIP-0055, once
  MIP-0055's own Azure AI Search backend is actually built and verified, comparing against an
  unbuilt Azure side (as this MIP's §4.7 notes) isn't a real comparison yet.

## Appendix

### Checked live

- `https://ai.google.dev/gemini-api/docs/pricing`, fetched 2026-09-15: returned Gemini 2.5 Flash
  ($0.30/1M input text/image/video, $1.00/1M input audio, $2.50/1M output) and Gemini 2.5
  Flash-Lite ($0.10/1M input text/image/video, $0.30/1M input audio, $0.40/1M output) pricing, plus
  a free tier scoped to Google Search grounding (shared 500 requests/day), not general chat
  completions.
- `https://mapsplatform.google.com/pricing/`, fetched 2026-09-15: returned the post-March-2025
  per-SKU free-call model (10K/5K/1K free calls per SKU per month on Essentials/Pro/Enterprise),
  fixed subscription tiers ($100/$275/$1,200/month), and volume discounts (20%–80%) at high usage.
- `https://firebase.google.com/docs/firestore/quotas`, fetched 2026-09-15: returned Firestore's
  free-tier daily quota, 50,000 reads/day, 20,000 writes/day, 20,000 deletes/day, 1 GiB stored.
- `https://cloud.google.com/vision/pricing`, fetched 2026-09-15: returned Cloud Vision's free tier
  (first 1,000 units/month across all features) and tiered per-1,000-unit pricing ($1.50 from
  1,001–5,000,000 units/month, $1.00 beyond, with other features like Web Detection priced
  separately at e.g. $3.50/1,000).
- `https://cloud.google.com/vertex-ai/generative-ai/pricing`, fetched 2026-09-15: **failed**, the
  fetch tool reported truncated content and fell back to "based on general knowledge" figures,
  which are explicitly **not** used anywhere in §4 of this MIP (the ai.google.dev fetch above
  supplied the real, cited Gemini numbers instead).
- `https://cloud.google.com/logging/pricing`, fetched 2026-09-15: **failed**, truncated content,
  no usable pricing extracted.
- `https://cloud.google.com/trace/pricing`, fetched 2026-09-15: **failed**, the tool reported a
  404 at that URL.
- `https://cloud.google.com/firestore/pricing`, fetched 2026-09-15: **failed**, truncated content;
  superseded by the successful `firebase.google.com/docs/firestore/quotas` fetch above.
- `https://cloud.google.com/stackdriver/pricing`, fetched 2026-09-15: **failed**, truncated
  content, no usable pricing extracted.

### Not checked

- **Cloud Logging's "50 GiB/month free, then $0.50/GiB" and Cloud Trace's "2.5 million spans/month
  free, then $0.20/million spans"** (§4.5) came from a WebSearch results summary on 2026-09-15,
  not a direct fetch of either service's own pricing page (both direct fetches failed, see
  above). Treat both numbers as plausible, not confirmed.
- **Azure AI Foundry (`gpt-4o-mini`), Azure Maps, Cosmos DB serverless, Azure AI Vision F0, and
  Application Insights pricing** were not re-fetched this session on either side of any §4
  comparison, every Azure-side reference here is inherited from `ARCHITECTURE.md` §6's own prose,
  which itself does not cite a source or a number for most of these.
- **Workload Identity Federation's exact setup mechanics** (how a Cloud Run job or a GitHub Actions
  workflow attaches to it) were not verified against GCP's own docs this session, described here
  only at the level of "the WIF concept exists and is analogous to Azure managed identity," which
  is a lower bar than this repo normally holds for a claim that would drive a real auth
  implementation.
- **Vertex AI Agent Builder / Agent Engine** (§4.6) is named from background knowledge, not a
  fetched page, flagged as unverified since §4.6 doesn't recommend building against it anyway.
- **Google Maps Platform's Routes API request/response shape** (`computeRoutes`, POST with a field
  mask) is stated from background knowledge, not verified against Google's own API reference this
  session, a real implementation would need that reference checked first, same bar
  `ARCHITECTURE.md` §5b already held Azure Maps' Route Directions API to.
