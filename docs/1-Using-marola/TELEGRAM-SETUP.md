# Telegram bot setup

!!! warning "Preparation for Phase 1"
    The bot does not exist yet. This page gets your Telegram credentials and config ready and lets
    you verify them today; the message-handling loop (receiving a location share, replying with
    conditions) is Phase 1 in [Development phases](https://docs.marola.dev/PHASES/), not built.

Moved here from marola-app with the rest of the user pages (MIP-0074); the URL is unchanged.

Everything else (the recommendation pipeline, `LlmClient`, `SightingStore`, `VisionClient`) is
already built in [marola-app](https://github.com/marola-dev/marola-app) and works standalone via
`Main`'s CLI flags (see the [CLI reference](https://docs.marola.dev/5-Repos/marola-app/4-reference_cli/)),
waiting for this bot loop to call into it. Nothing below is faked to look more finished than it is.

§1 registers the bot. §2 is the local development path, and §3 covers how the bot will talk to
Telegram once the loop exists.

## 1. Register the bot

1. Open Telegram, message [@BotFather](https://t.me/BotFather).
2. Send `/newbot`, give it a display name, then a username ending in `bot` (e.g.
   `marola_swim_bot`).
3. BotFather replies with a token shaped like `123456789:AAH...`. **Treat it like a password**:
   anyone with it can send messages as your bot. Never commit it; it goes in `.env` in your
   marola-app checkout (`.env.example` already reserves `MAROLA_TELEGRAM_BOT_TOKEN` for this; see
   the [configuration reference](https://docs.marola.dev/5-Repos/marola-app/4-reference_config/#where-and-what)).
4. Optional but worth doing now: `/setdescription` and `/setcommands` in BotFather to give the bot
   a description and a command list (e.g. `swim - best hour to swim nearby`). Purely cosmetic,
   doesn't require any code to exist yet. Suggested `/setdescription` text, once the bot is live
   (Phase 1, `AGENTS.md`'s phase discipline; not set yet): "marola — the ocean intelligence
   layer: conditions, water quality, tides, sea life. First case, `/swim`, the best hour
   tomorrow." The `/swim` command's own one-line description stays `swim - best hour to swim
   nearby`: the command really is only about swimming, so it doesn't need to change.

**Verify the token works right now**, without any marola code running. Telegram's `getMe` method
just confirms the token is valid and shows your bot's own info:

```bash
curl "https://api.telegram.org/bot<your-token>/getMe"
# {"ok":true,"result":{"id":...,"is_bot":true,"first_name":"...","username":"..._bot"}}
```

An invalid or malformed token returns `{"ok":false,"error_code":401,"description":"Unauthorized"}`
(confirmed against Telegram's real API while writing this); if you see that, re-copy the token
from BotFather.

## 2. Local development path

This is the path that matches everything already built and verified: local Ollama for
the LLM step, a local JSON-lines file for sighting reports, a local multimodal model for photo
analysis: nothing needs a cloud account.

```bash
# .env (or your shell)
MAROLA_TELEGRAM_BOT_TOKEN=123456789:AAH...
# everything else stays local, by default:
#   LLM       -> Ollama at localhost:11434
#   sightings -> ./data/sightings.jsonl
#   vision    -> Ollama multimodal model
```

Once Phase 1's polling loop exists, running it locally means **long polling**: the bot process
itself reaches out to Telegram's servers to ask "any new messages?" in a loop, so it needs no
public IP, no domain, no inbound firewall rule. This is exactly why
[ARCHITECTURE §2](../2-Building-marola/ARCHITECTURE.md#2-why-telegram-not-whatsapp-or-a-streamlit-page)
recommended Telegram over a webhook-only interface for the POC: you can run the whole thing,
including a real bot a real phone can message, from a laptop behind NAT.

**What you can test today, before the loop exists:** everything the loop will eventually call
([Run it locally](RUN-LOCALLY.md) sets it up).

```bash
# in a marola-app checkout
just run -- --lat -22.9878 --lon -43.1913 --summarize    # the reply text it'll eventually send
just run -- --report-sighting jellyfish Arpoador note    # what a future /report command will do
just run -- --analyze-photo ./some-beach-photo.jpg        # what a future photo handler will do
```

## 3. Long polling vs. webhook

Once the loop exists, long polling (§2) is enough. A **webhook** (Telegram pushes messages to a URL
you register, instead of the bot asking) only becomes relevant once the service is actually deployed
behind a stable HTTPS endpoint, i.e. Phase 3 in [Development phases](https://docs.marola.dev/PHASES/),
not required for local dev.
Once that's real, registering one looks like:

```bash
# once the deployed service has a public HTTPS URL:
curl "https://api.telegram.org/bot<your-token>/setWebhook?url=https://<your-service-host>/telegram/webhook"
```

This last command is documented for when Phase 3 lands: running it before any service exists at
that URL will just leave the bot unable to receive messages until one does.

## 4. Cost note

Telegram's Bot API itself is free with no usage-based billing
([ARCHITECTURE §7](../2-Building-marola/ARCHITECTURE.md#7-third-party-apis-used-all-free-no-key-confirmed-live-against-real-data)), and §2's
path runs on your own machine. Deploying for a webhook follows the same `AGENTS.md` cost-safety
rule as everywhere else in this repo: nothing gets provisioned without an explicit go-ahead.
