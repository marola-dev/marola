---
name: eli5
description: "Explain an ocean, marine-safety or marola-internals topic to someone who knows nothing about it. Use when the user types /eli5 <topic>, asks to explain something simply (a rip current, swell period, upwelling, the swimability score, the Kyo effect boundary, the six pluggable integrations, the RAG corpus), or asks for a picture explainer of how part of marola works."
---

# eli5

Explain like the reader knows nothing about this topic: short sentences, one idea at a time, a
picture before the prose. Adapted for this repository from the community `eli5` skill by Thariq
Shihipar (MIT, `anthropics/claude-plugins-community`), which produces an HTML picture explainer.

Topic: $ARGUMENTS

## Ground it in this repository first

marola tells people whether it is safe to swim, so an explanation that contradicts what the code
actually does, or what the corpus actually says, is worse than no explanation. Before writing,
read whichever of these covers the topic:

- **A sea topic** (waves, tides, rips, upwelling, jellyfish, water quality, whales) →
  `knowledge/*.md`. This is marola's retrieval corpus: every file carries a `Source:` line, and
  `--ask`/`ask_ocean_question` answer from it and nothing else. Explain what *this* corpus says,
  in its terms, so the explanation and the bot agree.
- **How marola works** → `docs/2-Building-marola/ARCHITECTURE.md`: §3 is what is actually built, §5 the six
  pluggable integrations, §8 the honest limits of the jellyfish/whale heuristics, `docs/PHASES.md`
  the phases. §3 and §9 mark what is verified live versus written-not-run; carry that distinction
  into the explanation instead of flattening it.
- **Why it is shaped that way** → the MIP in `docs/MIPs/` (`docs/MIPs/README.md` indexes them with
  status), `PHILOSOPHY.md` for the repo-wide choices, `AGENTS.md` for the rules.
- **The code itself** → `core/src/main/scala/marola/...` for the real behaviour: `scoring/`,
  `conditions/`, `beaches/`, `knowledge/`, `llm/`.

If the repository and your memory disagree, the repository wins. If neither covers it, say so
plainly rather than filling the gap with a plausible-sounding invention.

Two traps specific to this repo: a MIP that is `Draft` or `Accepted` describes something **not
built**: say "proposed in MIP-NNNN", never the present tense.

## How to explain

1. **One-sentence answer first.** What it is, in words a swimmer on the beach knows. No jargon in
   this sentence, not even *swell*, *effect* or *embedding*.
2. **A picture.** A Mermaid diagram or a small ASCII sketch that shows the thing working: the
   water going back out through a channel, the hour-by-hour score for tomorrow, the
   find-beaches → fetch-conditions → score → summarise → review chain, the trait with its local
   implementation. Label the parts with the words you just used. Keep it under ten
   nodes; a diagram nobody can read is decoration.
3. **The words the field uses.** Now name it properly: *significant wave height*, *period*,
   *upwelling*, *semi-diurnal*, *corrente de retorno*; or *trait*, *effect*, *backend*,
   *retrieval corpus*, *reviewer pass*. One line each, tied back to the picture, so the reader can
   follow a forecast, a beach sign or a code comment afterwards.
4. **Why it matters here.** One or two lines connecting it to what marola does: the swimability
   score, the hour it recommends, the safety footer, the local-first default.
5. **Where to read more.** The corpus file and its `Source:` URL, the architecture section, or the
   MIP: each one you actually opened. A claim you did not check against a file in this repo or a
   page you fetched gets marked as unverified, in the answer, in those words.

## Analogies

Use physical ones: water, queues, kitchens, post, a river in the sea. Say where the analogy breaks
before the reader finds out: "a rip is not a current that pulls you under; it pulls you out, which
is why you swim sideways, not down". Never let an analogy replace the real definition; it buys
attention for the definition that follows.

## Audience and language

Default to a curious swimmer with no science background. Adjust if asked: `for a developer` earns
the real types and file paths; `for my mother` drops every acronym. Answer in the language the user
wrote in: this project is discussed in Portuguese as often as in English, and a pt-BR answer uses
the words people here actually use (água-viva, caravela, ressaca, corrente de retorno, marola),
with the English term in brackets once so the reader can search for it.

## Safety topics are not advice

Rips, jellyfish stings, water quality and sea state are the topics most likely to be asked about,
and an explanation of one is not a recommendation to act. Explain the mechanism; do not tell
someone whether to get in the water, do not give first aid instructions beyond what the corpus
states with its source, and keep the same hedges the corpus and `SafetyFooter` keep. If the
question is really "is it safe tomorrow at X", that is what the pipeline answers: point at
`just run` / the bot, not at a guess.

## Output

The default is a chat answer: short paragraphs and the diagram inline. If the user asks for a page,
a slide, something printable or something to send to someone, write a standalone HTML file with
large type and few words into `.tmp/eli5/<topic>.html` (`.tmp/` is gitignored) and give them the
path. Do not add files anywhere else in the tree: a fact that belongs in the corpus goes through
the `corpus-doc` skill (it needs a real fetched source), a design goes through `mip`, and neither
is this skill's job. This skill writes no commits and no PRs.

## Do not

- Do not invent numbers. Temperatures, wave heights, distances, score weights, model names and
  API limits come from a file in this repo or a page you fetched, or they are marked unverified.
- Do not describe proposed work as built.
- Do not explain by pasting code. A ten-line excerpt is fine when the code *is* the point; a file
  dump is not an explanation.
- Do not flatter the question or apologise for simplifying. Just explain.
</content>
