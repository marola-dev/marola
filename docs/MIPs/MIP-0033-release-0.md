# MIP-0033: Release 0 — the repo goes public, a self-hosted chatbot, the first published model

| | |
|---|---|
| **Status** | Partially implemented (§5.2 of 6) — §5.2 (chat server + widget) merged to main as PR #196 (`cli/src/main/scala/marola/agent/ChatServer.scala`, `site/static/chat.js`/`chatbot-config.js`), refined by PR #229; the `mip-0033/1-chat-server` branch it originally shipped on is stale/merged, not pending. §5.1 (repo visibility), §5.3 (model publish), §5.4 (Milestones/`RELEASES.md`) and the new §5.5/§5.6 (pre-flight checklist, GitHub Releases) not started |
| **Author** | Claude Sonnet 5, for M. Hoffmann (request of 2026-09-06: "release public will match with release 0, site should be public and safe (static), chatbot must be present working with ollama running on my local computer... with first hugging face distributed model. Idea is to create link between MIPs - milestones - releases") |
| **Created** | 2026-09-06 |
| **Phase** | Spans Phase 0-1 work (`ARCHITECTURE.md` §11) — Release 0 is a **new, orthogonal axis**, not another phase: Phase tracks infra/deployment stage (local → bot → cloud → deployed), Release tracks a *bundled, versioned, publicly-announced* milestone. Release 0 draws from Phase 0/1 work already done or in flight; it does not require Phase 2/3 |
| **Related** | MIP-0025 §5.1 (the already-verified HF publish plan this MIP's chatbot arm reuses); MIP-0005 §9 (the "no server" decision this MIP explicitly, narrowly overrides — see §6); the GitHub-Pages/hosting research this session already did (Cloudflare Tunnel confirmed as the free, no-port-forward path); the `finetune/` small-model ladder (already proven this session: a real tiny LoRA adapter trained, converted, running in Ollama, end-to-end through `just run -- --summarize`); the MIP-hardening research this session also produced (dependency-graph tooling this MIP's §7 reuses, once accepted) |
| **Effort** | L — three real deliverables (public-repo security pass, a tunnel-backed chatbot widget with graceful degradation, a real HF model publish) plus one new lightweight doc mechanism (Milestones/Releases linking MIPs) |
| **Gain** | user value (a working chatbot, a real published model); community/outreach (an open-source repo is itself outreach, ties to MIP-0014/0018/0020's stated goals); infra/dev-loop (the Milestones mechanism this MIP introduces) |
| **Effort vs Gain** | `do next` — every piece has already been de-risked this session (tunnel option researched, HF publish plan verified in MIP-0025, a real tiny model already trained and running) except the repo-public security pass, which is real, bounded work, not a research gap |
| **Depends on** | Nothing blocking technically. **Does** depend on a human decision this MIP cannot make for itself: going public is irreversible in the sense that anything ever committed becomes permanently visible (§6) — the security pass in §5.1 must complete and be reviewed before the repo visibility flips, not after |
| **Risk** | The single real risk is **secrets or sensitive material already in git history** — `AGENTS.md`'s "never hardcode a key" rule has held for tracked files, but a full-history secret scan has never been run in this repo (verified: no `SECURITY.md` exists yet, despite `README.md`'s Contributing section already pointing at one) |
| **Cost so far** | — |

## 1. Summary

**Release 0** is the first public-facing bundle of marola: the repo goes public, `marola.dev` (already public, already static) gets a real chatbot widget backed by Ollama running on the maintainer's own machine (via a Cloudflare Tunnel, with a graceful "LLM unavailable" state when the tunnel or Ollama is down), and the first fine-tuned marola model is published to Hugging Face. This MIP also introduces **Milestones**, a lightweight doc linking specific MIPs to a named, dated release, so "what shipped in Release 0" has one authoritative place, distinct from the per-MIP `docs/MIPs/README.md` index and the Phase axis in `ARCHITECTURE.md` §11.

## 2. Motivation

Today marola has real, working pieces (the site, the CLI pipeline, a just-proven tiny fine-tune) with no single artifact answering "what can a stranger actually see or use today." `docs/4-Research-and-plans/ROADMAP.md` is a dated snapshot of what's next, not a record of what shipped; `docs/MIPs/README.md`'s Status column tracks each MIP in isolation. Nothing today groups a set of MIPs into "this is what Release 0 means" the way a changelog or a release tag would for a conventional project, and the maintainer's own request names three concrete, disparate deliverables (repo visibility, a live chatbot, a published model) that only make sense read together as one milestone.

## 3. User-visible change

- `github.com/h0ffmann/marola` is public (currently private; verified this session via `curl https://api.github.com/repos/h0ffmann/marola` → 404 anonymously).
- `marola.dev` gains a chat widget. When the maintainer's Ollama + tunnel are up: real answers from the published model. When not: a plain, honest **"chatbot offline — ask the ocean via the CLI/MCP tool, or check back later"** message, never a spinner that hangs, never a silent failure.
- `huggingface.co/<user>/marola-sea-<version>-GGUF` exists and is `ollama run hf.co/...`-pullable (MIP-0025 §5.1's already-verified mechanism).
- `docs/MIPs/RELEASES.md` (new) lists Release 0: its date, the MIPs it draws from, and a one-line description of each deliverable, the "what shipped" record `docs/4-Research-and-plans/ROADMAP.md` was never meant to be.

## 4. Data sources and dependencies reviewed

- **Cloudflare Tunnel**: free, `cloudflared`, no port-forwarding, TLS handled. Chosen over Tailscale Funnel (also free, viable alternative; not independently re-verified this session, carried over from the earlier hosting-comparison research) because Cloudflare Tunnel needs no participant-side install on the visitor and no Tailscale account for `marola.dev`'s anonymous visitors. The recommendation is confirmed by the maintainer's own choice this session, not re-derived here.
- **Hugging Face publish**: fully covered by MIP-0025 §5.1, already verified 2026-09-06 against HF's storage-limits docs and the `hf.co/` pull syntax. Not re-verified here; this MIP's §5.3 only adds "which model" (the tiny preset already trained, or a `small`/`base` re-run, see §8).
- **Public-repo secret scanning**: not yet chosen. `gitleaks` and `trufflehog` are the two well-known open-source options; neither has been fetched/compared this session. Flagged as an open question (§11), not picked here.

## 5. Design

### 5.1 Repo-public checklist (do this first, before anything else in this MIP)

- Full-history secret scan (tool TBD, §11): any hit gets the key rotated and the repo's `README.md`/`AGENTS.md` "never hardcode a key" rule cited in the fix commit.
- Write the real `docs/SECURITY.md` `README.md` already references but that doesn't exist: a vulnerability-disclosure contact, nothing more elaborate.
- Re-read `.env.example` for any placeholder that looks like a real value, not an obviously-fake one (`AGENTS.md`'s existing rule, re-verified once more before the switch, not assumed still true).
- Flip repo visibility. **This step alone needs the maintainer's explicit go-ahead in the moment.** It is exactly the kind of hard-to-reverse, shared-state action `AGENTS.md`'s risk-assessment section already asks for confirmation on, MIP or no MIP.

### 5.2 The chatbot widget

- A small `site/static/app.js` addition: a chat panel that POSTs to the tunnel's public URL (env-configured, not hardcoded; the tunnel URL changes if `cloudflared` restarts without a named tunnel; use a **named** Cloudflare Tunnel, not a quick/ephemeral one, so the URL is stable).
- Health check: the widget pings the endpoint on load; unreachable (timeout or non-2xx) → the graceful offline message from §3, never a raw fetch error surfaced to a visitor.
- The endpoint itself: Ollama's own `/api/chat` (or `/v1/chat/completions`, OpenAI-compatible; `LocalLlmClient` already speaks this), reached through the tunnel; no new server code, the tunnel just exposes the Ollama port already running locally.
- **This narrowly overrides MIP-0005 §9's "no server" decision.** Flagged explicitly, not smuggled in: the maintainer's own machine becomes a real, if intermittent, origin. §6 states what stays true despite that.

### 5.3 First published model

Either publish the tiny model already trained and verified this session (`marola-sea-tiny`, SmolLM2-360M, honestly labelled as a pipeline-proof, not a quality bar), or spend the CPU time on a `small`-preset (Llama-3.2-1B) run first, a real quality-vs-timeline call for the maintainer, not decided here (§11).

### 5.4 Milestones — linking MIPs to a release

New file `docs/MIPs/RELEASES.md`, one section per release:

```markdown
## Release 0 — YYYY-MM-DD
MIPs: MIP-0005 (map), MIP-0025 (fine-tune), MIP-0033 (this one)
- Public repo, public site (already true, now the repo matches)
- Self-hosted chatbot (Ollama + Cloudflare Tunnel), graceful offline state
- First published model: <name>, huggingface.co/<user>/<repo>
```

No new metadata field on the MIP template itself: a Release is a *view over* existing MIPs, named and dated, not a property any single MIP carries (a MIP can belong to zero or one release; most won't belong to any). This deliberately does not duplicate the MIP-hardening research's `Blocked by`/dependency-graph proposal (still pending your review, separate report); if that lands, `RELEASES.md` can later link to specific graph nodes, not before.

### 5.5 Before the first real run — pre-flight checklist

Added 2026-09-07, after a real branch audit (117 unmerged branches, 44 from that day alone) found
that roughly 75% of same-day branches were already fully absorbed into `main` under a *different*
branch name, a real, ongoing hygiene cost this MIP should close out before Release 0 "goes live"
for the first time, not leave implicit. Everything below must be true **once, right before** the
repo-public flip in §5.1, not a recurring gate on every future PR:

- **§5.1's own checklist** (secret scan, `SECURITY.md`, `.env.example` re-check): already specified above, restated here as item 1 of this consolidated list, not duplicated in substance.
- **A fresh ultrareview pass on `main`, findings fixed.** The last one (2026-09-06) found 9 issues, all fixed (`fix-agg-ultrareview1`, merged as #224); confirmed clean as of 2026-09-07's branch audit, nothing outstanding from that specific run. Re-run `/code-review ultra` once more, right before the public flip, since a lot lands on `main` between now and then; fix whatever it finds the same way.
- **Branch cleaning.** Delete every branch whose full content already exists on `main` (the 75% pattern above); `git diff --name-only main...branch` covering every touched file is the mechanical check; a branch that fails it (some files still absent from `main`) is real, unmerged work and must be triaged (merge it, or explicitly decide to drop it and say why), not silently deleted. Not a one-time pass either: recurring branch buildup is exactly what produced 117 stale refs by the time this section was written, so this should become a standing step (e.g. before each Release, not just Release 0).
- **A real GitHub Release, not just `RELEASES.md`**, see §5.6.

### 5.6 GitHub Releases (native), starting from v1

`docs/MIPs/RELEASES.md` (§5.4) stays as the authoritative, MIP-linked narrative of what shipped:
it says *why* a release exists and which MIPs it draws from, which a bare git tag can't. But
nothing today creates an actual GitHub Release object (a tag, a Releases-page entry, auto-generated
commit notes, an attachable asset); the two are complementary, not a replacement for one another.
Starting with the **v1** tag (Release 0 itself is a milestone marker, not necessarily a tagged
version, the first real semantic tag is what "v1" means here, per the maintainer's own request):

```bash
git tag -a v1.0.0 -m "v1.0.0 — <one-line summary, matches RELEASES.md's entry>"
git push origin v1.0.0
gh release create v1.0.0 --title "v1.0.0" --notes-file <(echo "See docs/MIPs/RELEASES.md#release-0 for the full MIP-linked writeup.") --generate-notes
```

`--generate-notes` gives GitHub's own commit-log summary since the previous tag, appended after the
short pointer to `RELEASES.md`. The redundant-sounding combination is deliberate: GitHub's
auto-notes are complete but MIP-blind, `RELEASES.md` is curated but has to be found; the release
notes carry a link to the richer doc rather than trying to duplicate it inline. Needs `gh auth
login` with a token that has release-creation rights; not available in this sandbox session
(`gh` unauthenticated throughout), so this step is real, un-executed future work, not verified live.

## 6. Scoring / safety impact

None to `Swimability`/scoring. Safety-adjacent note: the chatbot widget must carry the same "not a safety authority" framing MIP-0025 §6 already requires of any marola-sea output, and MIP-0022's safety footer applies to chatbot answers exactly as it does to CLI ones, no new exemption.

## 7. Verification plan

- Repo-public: the secret-scan tool's own clean-run output, kept as the PR's evidence (not committed, referenced).
- Chatbot: manually verified both states: Ollama+tunnel up (a real answer arrives) and down (kill `cloudflared`, confirm the graceful message, not a hang).
- HF publish: `ollama run hf.co/<user>/<repo>` pulls and runs from a machine that never had the model locally.
- §5.5's pre-flight checklist: a fresh ultrareview run with zero unfixed findings, and a branch audit showing no branch both (a) unmerged and (b) already fully absorbed into `main` under a different name.
- §5.6: `gh release list` shows `v1.0.0`, and its notes link to `docs/MIPs/RELEASES.md#release-0`.
- "Done" = all of the above, plus `docs/MIPs/RELEASES.md` committed with Release 0's real date.

## 8. Risks, limitations, and honest caveats

- **The chatbot's uptime is the maintainer's own machine's uptime.** This is stated in the UI (§3), not hidden, but it's worth saying plainly here too: Release 0 ships an *intermittently available* chatbot by design, not a bug to fix later.
- **Publishing the tiny model first is a real quality tradeoff.** SmolLM2-360M's actual output this session was rough (mismatched jellyfish-risk wording, reviewer flagged "revise"). Shipping it as "the first published model" is honest labelling of a real artifact, not a claim it's good; §11 leaves the small-vs-tiny call open rather than deciding it here.
- **Repo-public is one-way.** Nothing in this MIP can undo history already pushed once it's public; the checklist in §5.1 exists because of that irreversibility, not as ceremony.

## 9. Alternatives considered

- **Keep the repo private, ship only the chatbot + model.** Loses the outreach/transparency value the maintainer explicitly asked for ("release public"); considered and rejected per the request itself.
- **A hosted (Hetzner) chatbot origin instead of the maintainer's own machine.** Rejected per the maintainer's own explicit choice this session ("start with something on my personal computer") and because it reintroduces the cost questions the earlier hosting research already weighed against GitHub Pages' free baseline.

## 11. Open questions

- Which secret-scanning tool (`gitleaks` vs `trufflehog`): not compared this session.
- Tiny (`marola-sea-tiny`, already trained) vs. spending more CPU time on `small` before the first HF publish: a real timeline-vs-quality call for the maintainer.
- Whether `docs/MIPs/RELEASES.md` should also gain the dependency-graph tooling from the (separate, pending) MIP-hardening report, once that's decided; not blocking Release 0.
- Exact Cloudflare Tunnel setup steps (named tunnel creation, `cloudflared` as a systemd/launchd service so it survives a reboot); not written up here, implementation-PR detail.
- Whether branch cleaning (§5.5) should also get a `just` command (a read-only "which branches are fully absorbed into main" report, mirroring `scripts/docs-mip-stack.sh list`'s own discovery-not-auto-delete pattern); not built this session; the 2026-09-07 audit was done by hand. **Follow-up MIP:** a general-purpose `scripts/branch-audit.sh` doing this file-presence check across *all* unmerged branches (not just `docs/mip-*`) would generalize `docs-mip-stack.sh list`'s staleness detection, worth its own MIP once there's a second real need for it, not assumed here.
- §5.6's exact tag-naming scheme past `v1.0.0` (semver strictly, or date-based): not decided, first tag only.
