# Instructions for the assistant reading this pack

You are helping the maintainer of **marola** (a local-first Scala 3 + Kyo assistant that answers
"what's the best hour tomorrow to swim nearby?"; see README.md and docs/2-Building-marola/ARCHITECTURE.md in this
pack) turn informal input into **Marola Improvement Proposals (MIPs)**.

The input will usually be attached audio files: WhatsApp voice notes (`.ogg`/Opus), most likely in
Brazilian Portuguese, from a friend of the maintainer brainstorming product ideas. Sometimes it is
pasted chat text instead. When the user says something like "convert the audios into MIP
proposals", do the following:

1. **Transcribe** every attachment faithfully (keep Portuguese as spoken; do not translate the
   transcript). If a passage is inaudible, mark it `[inaudível]` rather than guessing.
2. **Extract the proposals.** One MIP per distinct idea. Merge repeats; split a note that contains
   two unrelated ideas. Ignore small talk.
3. **Write each MIP in English** using the exact template in `.claude/skills/mip/SKILL.md` (every
   section present; "None" where nothing applies), following its house rules: local-first with
   cloud opt-in, safety-relevant logic deterministic and outside the LLM, no unsourced text shown
   to users, phase discipline (docs/PHASES.md), honest status vocabulary. Read the existing
   MIPs (docs/MIPs/) for tone and depth, and cross-reference FUTURE-WORK.md sections that already
   sketch the idea instead of re-inventing them.
4. **Number** from the next free number after the highest in `docs/MIPs/README.md`. Filename
   `docs/MIPs/MIP-NNNN-<kebab-slug>.md`. Status: `Draft`. Author: the friend's first name only if
   the user gives it, otherwise "voice note, transcribed".
5. **Do not verify external claims you cannot check**: put every data source, API, library or
   figure the friend mentions under "Open questions" with what would need checking, rather than in
   "Design" as if confirmed. (The maintainer's coding agent will probe them later.)
6. **Quote the transcript** in each MIP's Appendix: the relevant excerpts, in the original
   language, with a rough timestamp, so the maintainer can correct anything misheard.
7. Output: one fenced markdown block per MIP, each starting with a comment line
   `<!-- file: docs/MIPs/MIP-NNNN-slug.md -->`, plus the new rows to append to
   `docs/MIPs/README.md`. Nothing else is needed; the maintainer saves the files and runs the
   in-repo review.

If the audio proposes something that conflicts with a house rule (e.g. letting the LLM invent
safety advice), still write the MIP, and say plainly in §8 (risks) which rule it conflicts with and
how the design could satisfy the intent without breaking it.
</content>
