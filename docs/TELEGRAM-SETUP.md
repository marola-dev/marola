# marola — Telegram bot setup

**Status check first:** this guide gets your Telegram credentials and config ready and lets you
verify them today. The actual message-handling loop (receiving a location share, replying with
conditions) is **Phase 1** in `ARCHITECTURE.md` §11 and is **not built yet**. Everything else in
this repo (the recommendation pipeline, `LlmClient`, `SightingStore`, `VisionClient`) is already
built and works standalone via `Main`'s CLI flags (see `ARCHITECTURE.md` §3.1), waiting for this
bot loop to call into it. Nothing below is faked to look more finished than it is.

Both paths start the same way (§1), then diverge: §2 is pure local development, zero Azure; §3 is
the path once you're deploying with Foundry/Azure backing the LLM step.

## 1. Register the bot (same for both paths)

1. Open Telegram, message [@BotFather](https://t.me/BotFather).
2. Send `/newbot`, give it a display name, then a username ending in `bot` (e.g.
   `marola_swim_bot`).
3. BotFather replies with a token shaped like `123456789:AAH...`. **Treat it like a password**:
   anyone with it can send messages as your bot. Never commit it; it goes in `.env`
   (`.env.example` already reserves `MAROLA_TELEGRAM_BOT_TOKEN` for this; see `AppConfig.scala`).
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

## 2. Local development path (no Azure)

This is the path that matches everything already built and verified this session: local Ollama for
the LLM step, a local JSON-lines file for sighting reports, a local multimodal model for photo
analysis: nothing needs an Azure account.

```bash
# .env (or your shell)
MAROLA_TELEGRAM_BOT_TOKEN=123456789:AAH...
# everything else can stay at its local-first defaults — see AppConfig.scala:
#   MAROLA_LLM_PROVIDER=local (default)          -> Ollama at localhost:11434
#   MAROLA_SIGHTING_STORE_PROVIDER=local (default) -> ./data/sightings.jsonl
#   MAROLA_VISION_PROVIDER=local (default)         -> Ollama multimodal model
```

Once Phase 1's polling loop exists, running it locally means **long polling**: the bot process
itself reaches out to Telegram's servers to ask "any new messages?" in a loop, so it needs no
public IP, no domain, no inbound firewall rule. This is exactly why §2 of `ARCHITECTURE.md`
recommended Telegram over a webhook-only interface for the POC: you can run the whole thing,
including a real bot a real phone can message, from a laptop behind NAT.

**What you can test today, before the loop exists:** everything the loop will eventually call.
```bash
just run -- --lat -22.9878 --lon -43.1913 --summarize    # the reply text it'll eventually send
just run -- --report-sighting jellyfish Arpoador "note"  # what a future /report command will do
just run -- --analyze-photo ./some-beach-photo.jpg        # what a future photo handler will do
```

## 3. Azure Foundry-backed path

Same bot, same token from §1. What changes is which backend `AppConfig` picks for the pieces that
have an Azure option (`ARCHITECTURE.md` §5's table):

```bash
MAROLA_TELEGRAM_BOT_TOKEN=123456789:AAH...

# Query synthesis via a provisioned Foundry/Azure OpenAI deployment instead of local Ollama:
MAROLA_LLM_PROVIDER=azure
FOUNDRY_PROJECT_ENDPOINT=https://<your-resource>.openai.azure.com/openai/deployments/<deployment>
# (the deployment name is part of the endpoint URL — there is no separate FOUNDRY_MODEL_DEPLOYMENT)
FOUNDRY_API_VERSION=2026-01-01-preview
# auth is DefaultAzureCredential (az login locally, managed identity once deployed) —
# no API key env var, per AGENTS.md's "no API keys" rule.

# Optionally also switch sighting storage and vision to their Azure backends:
MAROLA_SIGHTING_STORE_PROVIDER=azure
COSMOS_DB_ENDPOINT=https://<your-account>.documents.azure.com:443/
COSMOS_DB_KEY=...
MAROLA_VISION_PROVIDER=azure
AZURE_VISION_ENDPOINT=https://<your-resource>.cognitiveservices.azure.com
AZURE_VISION_KEY=...
```

Each of these is independent: set only the ones you actually want on Azure; anything left unset
falls back to its local default (`AppConfig.llmClient`/`sightingStore`/`visionClient`, each
returning `None`, and each CLI flag printing a clear "needs X set" message, if you pick a
provider without fully configuring it; see `Main.scala`).

**Long polling vs. webhook, once the loop exists:** long polling (§2) works fine even with Azure
backends configured. Azure only changes *which LLM/storage/vision calls* the bot makes, not how
it talks to Telegram. A **webhook** (Telegram pushes messages to a URL you register, instead of the
bot asking) only becomes relevant once the service is actually deployed behind a stable HTTPS
endpoint, i.e. Phase 3 in `ARCHITECTURE.md` §11 (Container App), not required for local dev even
with Azure backends in play. Once that's real, registering one looks like:

```bash
# after `azd up` (or however it's deployed) gives you a public HTTPS URL:
curl "https://api.telegram.org/bot<your-token>/setWebhook?url=https://<your-container-app-fqdn>/telegram/webhook"
```

This last command is documented for when Phase 3 lands: running it before any service exists at
that URL will just leave the bot unable to receive messages until one does.

## 4. Cost note

Telegram's Bot API itself is free with no usage-based billing (`ARCHITECTURE.md` §7). The only
spend §3's path introduces is whatever Azure resources you actually provision. The same
`AGENTS.md` cost-safety rule as everywhere else in this repo: nothing gets provisioned without an
explicit go-ahead, and each integration in §3 above is opt-in per env var, not a package deal.
