# MIP-0004: The reason to come back — daily digest, subscriptions, and reach beyond Santa Catarina

| | |
|---|---|
| **Status** | Draft |
| **Author** | Claude Fable 5.1, for M. Hoffmann |
| **Created** | 2026-09-05 |
| **Phase** | 1 (digest, subscriptions, pt-BR content) → 2 (other states' water data), after MIP-0002/0003 |
| **Related** | `FUTURE-WORK.md` §1.5 (multi-activity subscriptions), §9.2 (proactive alerts — deliberately *not* this), MIP-0001 §4.3 (other agencies), `AI-500-MAPPING.md` §4 (human gate on proactive behaviour) |

## 1. Summary

A bot people query once is a novelty; a message that arrives at 18:00 saying "amanhã 07:00 na
Joaquina: 82/100, água própria, baleias prováveis" is a habit. This MIP adds an opt-in daily
digest per subscribed spot and activity, a persistent per-user preference store (the first one),
Portuguese-first content, a one-tap "share this" text, and — the reach part — bathing-water
providers for Rio de Janeiro and São Paulo so the water-quality feature works where most Brazilian
swimmers are. Success metric: weekly retention of digest subscribers above 50% after four weeks.

## 2. Motivation

Everything so far is pull. Adoption for a conditions product is driven by the push: surf and
weather apps live on the morning notification. `FUTURE-WORK.md` §1.5 already sketched
subscriptions; MIP-0003's precomputed boards make a digest nearly free to produce. And the single
biggest feature of MIP-0001 — official water quality — currently covers one state; the bot will be
shared by people in Rio and São Paulo within a week of existing.

## 3. User-visible change

```
/assinar Praia do Campeche 18:00          → "Combinado: todo dia às 18:00, o resumo de amanhã."
/assinar Joaquina surf                    → activity-aware once FUTURE-WORK §1 lands; "nadar" today
/assinaturas · /cancelar Campeche
18:00 (daily, each subscription):
   🌊 Amanhã na Praia do Campeche — melhor às 07:00 · 55/100
   água 4/5 PRÓPRIA (evite a foz do Riozinho, 25 ago) · 19°C · vento 20 km/h · ondas 1,0 m · maré baixa 05:00
   ▸ detalhes  ▸ compartilhar
"compartilhar" → a plain-text card (no link required) the user forwards to a group.
```

Water quality shows for Rio and São Paulo beaches with the agency named (INEA / CETESB).

## 4. Data sources and dependencies reviewed

- **Telegram scheduled sends**: just `sendMessage` from a timer in the bot process; no Telegram
  feature needed. Bots can message a chat that has messaged them first — subscription is
  consent.
- **INEA (Rio de Janeiro) balneabilidade** and **CETESB (São Paulo) balneabilidade**: both publish
  weekly PRÓPRIA/IMPRÓPRIA per point under the same CONAMA 274 rule. **Not verified in this
  session** — no endpoint, format or coordinates confirmed (INEA is known to publish PDF bulletins;
  CETESB has a web portal). Per the `mip` skill rule these are *open questions*, not design inputs:
  each becomes a `WaterQualityClient` only after the same probing MIP-0001 §4.1 did for IMA.
- **Preference storage**: `LocalFileUserPreferencesStore` (JSON-lines, like sightings) as the
  default; `CosmosDbUserPreferencesStore` opt-in — `FUTURE-WORK.md` §1.5's exact shape. Stores
  chat id, beach name, hour, language, activity. That is the first user data marola keeps; it needs
  a `/apagar` (delete everything) command from day one.

## 5. Design

- **`core/prefs/UserPreferencesStore`** trait + two implementations (as above).
- **`bot/Digest`**: at each subscribed hour (bot-local time, per subscription), for each unique
  beach: read the MIP-0003 board if present, else run the pipeline once, then render with
  `Report` in the user's language and send. Batched per beach so a hundred subscribers to Joaquina
  cost one computation.
- **Sharing**: `Report.shareCard(best)` — six lines, emoji, no markdown, under 400 characters, with
  "via marola" at the end. Forwardable; the cheapest growth loop there is.
- **Portuguese-first content**: pt-BR entries in `sea_lore.json` (the `lang` field exists) and
  pt-BR corpus documents alongside the English ones (`knowledge/pt/`), selected by the user's
  language; retrieval stays per-language so citations match the text shown.
- **Regional water providers**: `WaterProvider` gains `IneaRj`, `CetesbSp`; `Auto` picks by
  bounding box as it does for SC. Same matcher, same scoring, agency named in the column.
- **Explicitly not in this MIP**: proactive *hazard* alerts (`FUTURE-WORK.md` §9.2). A digest the
  user scheduled is consent; an unrequested "dangerous seas" push is the AI-500 §4 governance case
  and gets its own MIP with a human gate.

## 6. Scoring / safety impact

None to scoring. Governance: a stored subscription is personal data — minimal fields, `/apagar`,
and the store path documented in `TELEGRAM-SETUP.md`.

## 7. Verification plan

- Unit: preference store round-trip; digest batching (N subscribers → 1 computation per beach);
  share-card length and content on the golden `BestHour`s; language selection of lore/corpus.
- Live: subscribe from a phone, receive the next digest at the chosen hour; `/apagar` removes the
  file line; forward a share card to a group and read it on the other side.
- Retention: subscribers active in week 4 / subscribers in week 1, from the no-PII counters.

## 8. Risks, limitations, and honest caveats

- **Unverified agencies.** RJ/SP are reach multipliers only if their data is machine-readable;
  budget a day of probing each before promising them.
- **Digest timing on a laptop host.** If the host sleeps at 18:00 the digest is late or missing;
  fine for friends, fixed by Phase 3.
- **Notification fatigue.** One message per subscription per day, never more; no digest when the
  forecast is unavailable (silence beats a "no data" message).
- **Portuguese content debt.** Translating the corpus is human work; machine-translating sourced
  safety text is exactly what the sourcing rule forbids without a check.

## 9. Alternatives considered

- **Push for everyone who ever shared a location**: no consent, no. Subscription only.
- **Instagram/WhatsApp status cards**: later; the forwardable text card gets 80% of the value.
- **Only SC forever**: simpler, and caps the audience at one state.

## 10. Exam-coverage mapping

AI-103 §1 "responsible AI": consent-based push, data minimisation, user deletion. AI-500 §4:
the explicit boundary between a scheduled digest (fine) and an autonomous alert (gated) is the
governance decision the mapping asks for.

## 11. Open questions

1. Digest hour: user-chosen (proposed) or fixed 18:00 for everyone (simpler batching)?
2. Do RJ/SP feeds exist in machine-readable form? Probe INEA and CETESB the way MIP-0001 §4.1
   probed IMA before scoping the providers.
3. Share card: include the marola bot handle for growth, or keep it neutral?
4. Should subscriptions be per beach, per origin tile ("near home"), or both? Proposal: per beach
   first; "near home" once MIP-0003 boards exist.
