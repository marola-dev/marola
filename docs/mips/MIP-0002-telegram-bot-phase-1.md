# MIP-0002: The Telegram bot — marola's first real user surface

| | |
|---|---|
| **Status** | Draft |
| **Author** | Claude Fable 5.1, for M. Hoffmann |
| **Created** | 2026-09-05 |
| **Phase** | 1 (`ARCHITECTURE.md` §11) — this *is* Phase 1 |
| **Related** | `ARCHITECTURE.md` §2/§4/§11, `TELEGRAM-SETUP.md`, `FUTURE-WORK.md` §7.3 (`marola-bot` module), MIP-0001 (what a reply contains), MIP-0003 (why replies must be fast first) |

## 1. Summary

Everything marola computes is reachable only from a terminal. Nobody adopts a CLI to decide
whether to swim. This MIP builds the Telegram bot the architecture has promised since day one:
share a location, get the ranked list with water quality and the detailed top pick, ask an ocean
question, report a sighting, send a photo. Local-first (long polling from any machine with Ollama),
Portuguese by default, no location stored. Adoption target for the first month: ten real people
in Florianópolis using it more than once a week.

## 2. Motivation

The product hypothesis (`ARCHITECTURE.md` §1) is untested because there is no product. Every
feature since Phase 0 — sightings, vision, water quality, RAG — has a CLI stand-in "until the bot
exists" (`SightingStore`, `VisionClient` doc comments). The bot is the missing prerequisite for
learning anything from users, and `AGENTS.md`'s phase discipline says it comes before any Azure
spend.

## 3. User-visible change

```
User:  📍 (shares location — Campeche)
Bot:   🌊 Melhor hora pra nadar amanhã perto de você:
       1. Praia da Joaquina 55/100 — dom 06:00 · água PRÓPRIA (25 ago) · 19°C, vento 20 km/h, ondas 0,9 m
       2. Praia do Campeche 35/100 — dom 06:00 · água 4/5 PRÓPRIA, evite a foz do Riozinho · ...
       ...
       ▸ Detalhes da Joaquina  ▸ Resumo  ▸ Perguntar ao mar   (inline buttons)

User:  /agua            → the per-point water-quality block for the beaches near the last location
User:  /perguntar o que fazer se pegar uma corrente de retorno?  → grounded answer + fonte
User:  /avistei agua-viva Campeche  → recorded sighting
User:  📷 (photo)        → vision description ("parece uma caravela-portuguesa — não toque")
Bot:   🐋 Você sabia? ... [fonte]    (the lore paragraph, once per day per user, never twice)
```

English stays available (`/idioma en`). The CLI keeps working unchanged.

## 4. Data sources and dependencies reviewed

- **Telegram Bot API** (`https://core.telegram.org/bots/api`): `getUpdates` long polling needs no
  public endpoint; `sendMessage` with MarkdownV2 or HTML, inline keyboards, `Location`, `PhotoSize`
  + `getFile`. Free, rate-limited (~30 messages/s per bot, 1/s per chat). Plain JSON over HTTPS —
  `Http` + `JsonValue` already cover it; **no SDK** (consistent with §5's dependency stance, and
  the Java Telegram libraries are Spring-shaped). Verified: `TELEGRAM-SETUP.md` §1's `getMe` check
  against the real API.
- **Webhook mode**: only once deployed behind HTTPS (Phase 3). Out of scope here.
- **Nothing else new.** Pipeline, RAG, vision, sightings all exist.

## 5. Design

- **New sbt module `bot/`** (`marola-bot`, the fifth module `FUTURE-WORK.md` §7.3 left for this
  moment), depending on `cli`'s `AppConfig`/`Report` — or better, move `AppConfig` and `Report`
  to a small `app/` module both `cli` and `bot` depend on.
- **`telegram/TelegramClient`** (`< Sync`): `getUpdates(offset, timeoutSeconds = 30)`,
  `sendMessage`, `sendChatAction("typing")`, `getFile`. Errors typed (`Abort[TelegramError]`, see
  `SCALA3-JDK-REVIEW.md` §2.3).
- **`bot/Bot`**: a Kyo `Async` loop — poll, dispatch each update as its own fiber (a slow Overpass
  query for one user must not block another), reply. Commands: `/start`, `/nadar` (or a location
  message), `/agua`, `/perguntar`, `/avistei`, `/idioma`, `/ajuda`. Per-chat state is only the
  last shared location, kept **in memory** with a 24h TTL — nothing written to disk about a user.
- **Language**: `Report` gains a `Locale`; strings move to a small `i18n` map (pt-BR, en). Lore and
  corpus get their `lang` field used: Portuguese entries first, since the users are here.
- **Rate limiting**: token bucket per chat (e.g. 6 requests/minute) *before* any external call —
  Overpass's fair-use policy is the constraint (`ARCHITECTURE.md` §7), and MIP-0003's cache is
  what makes the bucket generous enough to feel unlimited.
- **Vision**: `VisionClient.describe` on `getFile` bytes; local `llava`/`moondream` needs a pull
  (`RUN-LOCALLY.md` §5).
- **Safety of output**: the MIP-0001 rules stand — water veto deterministic, lore verbatim, RAG
  cited or labelled unsourced. The bot adds one: any `--ask` answer about first aid ends with
  "procure um guarda-vidas / SAMU 192".

## 6. Scoring / safety impact

None to the score. New: the per-chat rate limit and the first-aid footer.

## 7. Verification plan

- Unit: command parsing, i18n rendering (pt/en) of the golden-suite `BestHour`s via `Report`,
  token bucket, in-memory location TTL. `TelegramClient` against a `ReplayTransport` with recorded
  `getUpdates` payloads (record them from a real bot chat once).
- Live: `just bot` from a laptop with `MAROLA_TELEGRAM_BOT_TOKEN`; a real phone shares a location
  and gets the list under the MIP-0003 budget; photo → description; `/perguntar` → cited answer.
- Adoption instrumentation (no PII): counts per command per day in a local JSON-lines file —
  enough to know whether anyone comes back.

## 8. Risks, limitations, and honest caveats

- **Reply time.** Today a cold request is ~35-45s (Overpass). Telegram users give up in ten.
  MIP-0003 is a hard prerequisite for anyone but the author to use this; ship the bot behind
  `sendChatAction("typing")` and an immediate "buscando praias perto de você…" message regardless.
- **Laptop-hosted.** Long polling from a machine that sleeps means a bot that sleeps. Acceptable
  for ten friends; Phase 3 fixes it.
- **Location privacy.** Never persist; say so in `/start`.
- **Portuguese quality of a 3B model.** `llama3.2` writes acceptable Portuguese; the reviewer pass
  helps. Benchmark it (`just benchmark` gains a `lang` column) before defaulting to pt.

## 9. Alternatives considered

- WhatsApp (needs Business API approval), a web page (no native location share, `ARCHITECTURE.md`
  §2), an iOS/Android app (weeks, and nobody installs an app for a beach). Rejected as before.
- A Telegram library (`java-telegram-bot-api`, `TelegramBots`): brings a framework and its threading
  model for five endpoints marola can call with `Http` in 100 lines. Rejected.

## 10. Exam-coverage mapping

AI-103 §3 "agentic solutions": a real user-facing agent loop with tools (pipeline, RAG, vision).
AI-500 §4: the per-chat rate limit and "never persist location" are the first governance controls
on a public surface.

## 11. Open questions

1. `bot/` as a module vs. a second `main` in `cli/` — module is cleaner and matches the plan; it
   costs an `app/` extraction. Decide before code.
2. pt-BR default with `/idioma en`, or detect from Telegram's `language_code`? (Detect, with
   override.)
3. Should `/perguntar` default to `general` fallback (labelled) as the CLI does, or `strict` for a
   public surface? Proposal: `general`, because the benchmark shows strict abstains on most
   questions people actually ask — but the label must be visible in Telegram formatting.
