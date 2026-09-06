# MIP-0015: Interação — opt-in "who else is swimming here" matching between marola users

| | |
|---|---|
| **Status** | Draft — **reconstructed from a partial transcript, not the source audio (see §11.1)** |
| **Author** | Claude Sonnet 5, for M. Hoffmann (conversation with a collaborator, WhatsApp, 5 Sep 2026, 09:53–10:31) |
| **Created** | 2026-09-05 |
| **Phase** | 1 (product surface, alongside MIP-0002/MIP-0005) — this is the first MIP that puts one user's data in front of another user, which is a bigger step than a phase number captures (the source draft pointed here to "§11.3"; that didn't match any open question below — see the editorial note at the end of §11) |
| **Related** | MIP-0002 (Telegram bot — the identity/messaging surface this depends on), MIP-0004 (daily digest — the existing precedent for opt-in proactive contact and unsubscribe), MIP-0005 (the board's beach+hour vocabulary, reused as-is), MIP-0009 (same board, shown per-beach), `FUTURE-WORK.md` §1 (other activities as layers — this is a cross-activity *social* layer, not a new activity; needs its own sketch there), `PHILOSOPHY.md` ("an unrequested push is the one feature that needs a governance gate"), `AI-500-MAPPING.md` §4 (human-confirmation gate) |
| **Effort** | L — a new `intents` store (Cosmos DB in the Azure path, a local file/SQLite locally, per `ARCHITECTURE.md` §5's pattern), a pure matching module (§5), and two new bot commands (`/swim`, `/stop_intents`) riding on MIP-0002's not-yet-built bot — a new small subsystem, not a one-file change |
| **Gain** | user value (a genuinely new feature, not an amendment to an existing one — §1); exam coverage (`AI-500-MAPPING.md` §4 — the first feature moving person-to-person data, not just a proactive push, per §10) |
| **Effort vs Gain** | park — depends on MIP-0002 (§5, not yet built) and, per open question 5 below, on the collaborator confirming real demand; the MIP is also reconstructed from an unconfirmed transcript (§11 Q1), so it shouldn't move past Draft on its own merits regardless of MIP-0002's status |
| **Depends on** | MIP-0002 (Telegram identity/messaging surface — §5, hard blocker; also gated by `AGENTS.md`'s Phase 1 discipline, since MIP-0002 itself is still Draft); no paid Azure resource — the local default is a file/SQLite store, Cosmos DB is the opt-in per `ARCHITECTURE.md` §5 |
| **Risk** | small-N deanonymization (§8) — "2 others near Joaquina at 10:00" narrows to a small, guessable set once marola's user base is a handful of people, the opposite of the anonymity a bigger app has |
| **Cost so far** | — (nothing merged yet; this MIP is still Draft) |

## 0. Handoff — this MIP needs a session with real audio tooling to finish

Everything below §2 onward is reconstructed from surrounding text messages, not the source
audio (§11.1). It was drafted in a sandboxed environment with no speech-to-text tool and no
network access, so it could not transcribe the voice notes it's built from. **If you are a
Claude Code session running locally** — with real network access and the ability to install
Whisper — you can close that gap. This section is for you.

**What's blocked:** the actual content of eight WhatsApp voice notes, 5 Sep 2026, 10:02:24–
10:08:38 AM. Durations (verified, not content):

| File | Duration |
|---|---|
| `WhatsApp_Ptt_2026-09-05_at_10_02_24_AM.ogg` | 59.4s |
| `WhatsApp_Ptt_2026-09-05_at_10_02_42_AM.ogg` | 15.5s |
| `WhatsApp_Ptt_2026-09-05_at_10_03_37_AM.ogg` | 36.1s |
| `WhatsApp_Ptt_2026-09-05_at_10_04_32_AM.ogg` | 41.6s |
| `WhatsApp_Ptt_2026-09-05_at_10_05_47_AM.ogg` | 44.3s |
| `WhatsApp_Ptt_2026-09-05_at_10_06_58_AM.ogg` | 19.5s |
| `WhatsApp_Ptt_2026-09-05_at_10_07_14_AM.ogg` | 14.5s |
| `WhatsApp_Ptt_2026-09-05_at_10_08_38_AM.ogg` | 37.4s |

The 59.4s note and the two ~40s notes are the most substantial; the two ~15s notes read like
quick replies rather than new points — prioritize accordingly if time is short.

**Steps to finish this MIP:**

1. Get the eight files onto disk somewhere this session can reach (they are not, and should not
   become, part of the git history — personal audio doesn't belong in the repo permanently).
2. Use the `voice-note-ingest` skill (`.claude/skills/voice-note-ingest/SKILL.md`, drafted
   alongside this MIP) to transcribe them with local Whisper. Follow its own instructions on
   model choice and on translating separately rather than trusting Whisper's `--task translate`.
3. Anonymize per the skill's default: no name beyond M. Hoffmann's reaches this file. The
   existing text already calls the other party "a collaborator" — keep that convention.
4. Re-read §2 (Motivation), §3 (User-visible change), and §5–9 against what was actually said.
   Correct anything the reconstruction got wrong rather than patching around it — if the real
   content describes a different feature entirely, say so plainly and rewrite, don't force-fit.
5. Resolve open questions §11.1–§11.3 with what the audio actually shows, and delete them once
   resolved rather than leaving them as answered-but-still-listed.
6. Update the **Status** row: drop the "reconstructed from a partial transcript" caveat once
   confirmed, and move to `Accepted` only after M. Hoffmann signs off per the `mip` skill's
   process — confirming the content is not the same as accepting the design.

## 1. Summary

Let a marola user say "I'm swimming at Beach X around Time Y" and see whether anyone else who
said the same thing is going too — matching people to a shared swim, not broadcasting anyone's
location. It is the one idea from the 5 Sep conversation with a collaborator that is a genuinely new
feature rather than an amendment to an existing MIP (see §2 below for the one other thread that is
an amendment — Surfview, to MIP-0006; the source draft pointed here to "§11.2", which didn't match
any open question below — see the editorial note at the end of §11).

## 2. Motivation

From the WhatsApp log (`09:53`–`10:31`, 5 Sep 2026): after Matheus asked what paid app the collaborator uses
for beach cameras (Surfview — see the amendment to MIP-0006 below, not this MIP), the conversation
moved to an app Matheus had seen, **nomadtable**, which he described as "mais focado em rolê"
(more about social hangouts than travel logistics). A single-word note, "interacao", and a "sim"
answering a 36-second voice note sit between that and Matheus's "eh da pra ficar ambicioso" (it's
possible to get ambitious here) — the shape of a real back-and-forth about adding a social/
matching layer to marola, but **the content of that back-and-forth is in the eight voice notes,
which I was unable to transcribe** (see §11.1). Everything below is the most defensible feature
that fits the surrounding text and the two apps named; it needs Matheus to confirm or correct it
against what was actually said before this leaves Draft.

**Why NomadTable is the right reference and Surfview is not:** NomadTable connects people in the
same place at the same time who opted into the same activity, in real time, and its own terms are
explicit that it is "not a dating app, hookup app, or platform for sexual solicitation" (verified,
`nomadtable.app/guidelines`, 2026-09-05) — precisely the "interação" between people, at a
beach, at an hour, that marola already models as `(beach, hour)`. Surfview is a paid live-camera
product for surf spots across Brazil, Peru, El Salvador and Panama (verified,
`surfview.com.br`/Google Play, 2026-09-05) — no people-matching at all; it belongs in MIP-0006's
prior art, not here.

## 3. User-visible change

Telegram (rides on MIP-0002's bot, not a new surface):

```
You: /swim Joaquina 10:00
marola: Marked — you're swimming at Praia da Joaquina around 10:00 today.
        2 others near that beach and hour: @carol_fln (09:30), @rafa_surf (10:00).
        Say hi? [Yes, share my @handle]  [No, stay solo]
        This expires at 13:00 today. /stop_intents turns it off for good.
```

- Nothing is shown unless *both* people opted in for overlapping beach+hour.
- No map exposure, ever — the public map (MIP-0005/MIP-0009) stays exactly as it is; matching
  only happens inside the bot's private reply to the two people involved.
- An intent expires a few hours after the stated hour and is never resurfaced later.

## 4. Data sources and dependencies reviewed

- **NomadTable** (verified 2026-09-05: App Store/Google Play listings, `nomadtable.app/guidelines`,
  Trustpilot). Real-time, opt-in, activity-based matching by shared plan, not continuous location;
  premium tier gates the *full* nearby list (a design point worth copying — see §6); its own
  reviews report harassment despite the "not a dating app" policy, which is the central caution
  for §6, not a footnote.
- **Surfview** (verified 2026-09-05: Facebook page, `surfview.com.br`, Google Play). 300+ HD
  cameras, subscription, Brazil/Peru/El Salvador/Panama, no person-matching feature. Confirmed
  irrelevant to this MIP; the amendment below routes it to MIP-0006.
- **No new external data source.** This reuses marola's own beach/hour vocabulary
  (`core/scoring`, the board JSON) and MIP-0002's Telegram identity — nothing is fetched from a
  third party, and no location SDK is introduced.

## 5. Design

- A small `intents` store (Cosmos DB in the Azure path, a local file/SQLite in the local-first
  path, mirroring `ARCHITECTURE.md` §5's per-integration pattern): `(user_id, beach_id, hour,
  created_at, expires_at)`. No continuous GPS, no background tracking — an intent is a single
  explicit action (`/swim`), same shape as MIP-0004's subscription record.
- Matching is a pure function: two intents match if `beach_id` is equal and `hour` windows
  overlap (± the CLI's existing hour granularity). Deterministic, unit-testable, no LLM.
- **What goes through the LLM: nothing.** Every word in the match reply above is a fixed
  template; the model never writes a sentence about a specific person, consistent with
  `PHILOSOPHY.md`'s "no unsourced facts reach a user" — extended here to "no invented facts about
  a *person* reach a user" either.
- Depends on MIP-0002 for identity/messaging; cannot ship before it.

## 6. Scoring / safety impact

None to `Swimability.score`. This introduces a category of risk marola has not had before —
person-to-person, not person-to-sea — so the safety-relevant design decisions are these, not a
scoring change:

- **Opt-in per swim, not a standing toggle.** No "always visible" mode; each `/swim` is one
  action that expires on its own.
- **No public exposure.** The map never shows who is going anywhere; this is entirely inside the
  bot's private reply to the matched people.
- **Consent is mutual before a handle is shared** — matching tells each person *a count*, not
  *who*, until both say yes (mirrored in the mock reply above).
- **A hard kill switch** (`/stop_intents`) and normal Telegram block/report remain the actual
  backstop — this is a guard rail, not a boundary, the same honest framing MIP-0011 uses for
  `permissions.deny`.
- **Small-N deanonymization** is real and not solved by any of the above — see §8.

## 7. Verification plan

- `IntentMatchSpec` (core, pure): overlapping/non-overlapping hour windows, same-beach vs.
  different-beach, expiry, a user matching themselves excluded.
- A manual moderation dry run: report/block path via Telegram's own mechanism, documented, not
  built (no new moderation system in v1).
- **Done:** two test accounts on the local bot, one `/swim` each at the same beach/hour, see the
  count-then-consent flow; an expired intent shows nothing; `/stop_intents` removes both.

## 8. Risks, limitations, and honest caveats

- **NomadTable's own users report harassment despite an explicit anti-dating policy** (Trustpilot/
  App Store reviews, verified 2026-09-05). A policy sentence does not prevent misuse; the
  count-before-handle design in §6 is meant to raise the cost of it, not eliminate it.
- **Small user base makes "someone is going" identifying.** If only two or three people near
  Florianópolis use this, "2 others near Joaquina at 10:00" narrows to a small, guessable set —
  the opposite of the anonymity a bigger app has. This is a real limitation of shipping this at
  marola's current size, not a solved problem.
- **This MIP is reconstructed, not confirmed** (§11.1) — the single biggest risk is that it
  answers a request that was not actually made.

## 9. Alternatives considered

- **Full real-time presence, à la NomadTable's "see who's nearby now"** — richer, but continuous
  location for a toy project with a handful of users is a much bigger privacy surface than an
  opt-in, expiring intent; rejected for now.
- **A public "who's here" layer on the map** — deanonymizes immediately at marola's scale (§8) and
  breaks MIP-0005's "no cookies, no tracking" framing by putting people, not beaches, on the map.
  Rejected.
- **Do nothing until the audio is confirmed** — the honest default given §11.1. Recorded here so
  that shipping this MIP as Draft is visibly not the same as shipping the feature.

## 10. Exam-coverage mapping

`AI-500-MAPPING.md` §4 — this is the first feature where the "human-confirmation gate" language
applies to *data about a person*, not just a proactive push; worth its own row rather than folding
into the digest's.

## 11. Open questions

1. **What did the eight voice notes actually say?** I could not transcribe
   `WhatsApp_Ptt_2026-09-05_at_10_0{2..8}_*.ogg` — they were not present in my workspace when I
   looked, and this environment has no speech-to-text tool even when a file is present. Everything
   above is inferred from the text messages around them. This MIP should not move past Draft until
   Matheus confirms or corrects it against the real content — by re-sharing the audio somewhere I
   can reach, or just describing what was said.
2. **"rolê" vs. "role".** The log has "mas eh mais focado em role" — read here as *rolê* (Brazilian
   Portuguese for a social outing), which fits "mais focado em rolê [do que em viagem]" for an app
   about travelers meeting up. If the intended word was literally "role" (a persona/role-play
   framing), the feature this MIP describes is wrong and should be re-scoped.
3. **Does "interação" name this feature, or a different one entirely** — e.g. the "ask the ocean"
   Q&A becoming conversational, or a comment/rating layer on beaches? The single-word note gives no
   way to tell without the audio.
4. Age-gating and a real moderation policy before this goes past Draft — not designed here at all.
5. Given "é um toy project" and "depois tu vê com calma" earlier in the same conversation, should
   this stay a `FUTURE-WORK.md` sketch until the collaborator (the "user qualificado" who volunteered) gives
   concrete demand, rather than becoming a numbered MIP yet? Proposal: keep it Draft, not Accepted,
   until then.

**Editorial note (added when renumbering to MIP-0015):** the source draft cross-referenced this
section from three places above. Two check out: the Status row's "see §11.1" correctly points at
question 1 (the untranscribed audio), and §0 step 5's "§11.1–§11.3" correctly names the three
questions above (1, 2, 3) that hearing the actual audio would resolve. Two didn't: the Phase row's
"see §11.3" (for "this is the first MIP that puts one user's data in front of another user") and
the Summary's "see §11.2" (for "why the other two threads in that chat are amendments, not this")
each cited a question number whose actual content is unrelated. Neither claim is answered by an
open question here — the Phase-row claim is an assertion, not a question, and the "other thread"
explanation exists only for one of the two threads (Surfview → MIP-0006, in §2); the second
thread's explanation was never written into this draft (see Appendix B). Rather than invent the
missing content, both references have been repointed above (to this note, and to §2) instead of
left pointing at the wrong question.

## Appendix

**A. Verified 2026-09-05:** `nomadtable.app/guidelines` ("not a dating app…"); NomadTable App
Store/Google Play listings (real-time, opt-in, activity-based matching; premium gates the full
nearby list); NomadTable Trustpilot reviews (harassment reports despite the policy);
`facebook.com/surfviewbr`, `surfview.com.br`, Google Play `br.com.surfview.app` (300+ HD cameras,
Brazil/Peru/El Salvador/Panama, subscription, no person-matching).

**B. Not checked / not available:** the content of the eight `.ogg` voice notes (10:02:24–10:08:38
AM, 5 Sep 2026) — attempted, not accessible in this environment; the actual current text of
MIP-0002 and MIP-0006 (repo not reachable from this session — see the amendments below, given as
patch text rather than applied edits).

**Editorial note (added when renumbering to MIP-0015):** the "amendments below" promised in the
paragraph above are not actually present in the source draft handed off for this renumbering —
there is no patch text anywhere in this file. The MIP-0002/MIP-0006 amendments this draft
describes (§2, §0 step 5) were not supplied and still need to come from the original
(repo-reachable) session before they can be applied.

**C. Raw log excerpt this MIP is built from** (Matheus Hoffmann / a collaborator, WhatsApp, 5 Sep 2026):
`10:02` "e qual aquele q tu paga q tem as cameras:?" → Collaborator: "Surfview" · `10:05` "eu vi um app
disso maneiro" → "nomadtable" → "mas eh mais focado em role" · `10:08` "eh da pra ficar
ambicioso" · `10:09` "sim" / "entendi".
