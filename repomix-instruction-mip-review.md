# This is a MIP review request

You are being asked to review one **Marola Improvement Proposal (MIP)**: a numbered design
document for a change to **marola**, a local-first Scala 3 assistant that answers "what's the
best hour tomorrow to swim nearby?" (real beach discovery via OpenStreetMap, live sea/weather
conditions, an LLM-generated summary reviewed by a second LLM pass; see README.md in this pack).
A MIP is written and reviewed *before* the change is built, the same way an RFC or design doc
would be elsewhere in this project's process.

This pack intentionally does not include the project's source code, its full documentation set,
or its other MIPs; only three context documents (`README.md`, `AGENTS.md`, `PHILOSOPHY.md`) plus
the one MIP under review (and its task breakdown, if one exists). You are being asked for an
outside opinion precisely because you have not seen the rest of the repository and are not the
same model family the project's own coding agent runs on: treat gaps in your knowledge of the
codebase as expected, not as something to guess past.

## What each document in this pack is for

- **`README.md`**: what marola is and does, for a newcomer.
- **`AGENTS.md`**: the workspace's repo map (marola is an umbrella repo with one submodule per
  code repo) and the project's hard rules for anyone (human or AI) working in it:
  phase discipline (don't go live on a paid cloud integration before a local path works), cost
  and deployment safety (never provision a paid resource without explicit human confirmation),
  attribution/commit conventions, code style, and testing discipline. A MIP that violates one of
  these rules without saying so, or without a stated plan to reconcile it, is a real finding.
- **`PHILOSOPHY.md`**: *why* the project is built the way it is (why Scala 3 on the JVM, why
  Nix, why a design-doc-first process at all). A MIP whose design cuts against this document's
  stated reasoning, without acknowledging the tension, is worth flagging even if the code would
  technically work.
- **The MIP itself** (and its `.tasks.md`, if attached): the actual proposal.

## What to look for

1. **Internal consistency.** Does the MIP's own Motivation, Design, and Risks sections agree with
   each other? Does the "Effort vs. Gain" row match the actual scope described in Design?
2. **Unstated assumptions.** Claims presented as fact that aren't sourced, external APIs/limits
   assumed without a citation, or "this obviously works" reasoning about something that hasn't
   been tested.
3. **Alignment with `AGENTS.md` and `PHILOSOPHY.md`.** Anything that skips the phase-discipline
   rule, the cost-confirmation gate, or the project's stated local-first/cloud-opt-in stance.
4. **Missing context you'd need but don't have.** This pack deliberately omits the MIP's own
   "Related" row targets (other MIPs, the umbrella's `docs/2-Building-marola/ARCHITECTURE.md` sections, etc.); if the MIP leans
   heavily on something you can't see, say so explicitly rather than reviewing around the gap
   silently. That is itself useful signal back to the author about whether this three-document
   pack was enough for a review, or whether the next one should include more.
5. **Risks and open questions.** Whether the MIP's own §8 (risks) and closing "Open questions"
   section are honest and complete, or whether you can think of ones they missed.

Give your review as plain prose or a short list of findings; you do not need to follow any
particular template. Be direct about anything you're unsure of rather than filling the gap with a
guess.
</content>
