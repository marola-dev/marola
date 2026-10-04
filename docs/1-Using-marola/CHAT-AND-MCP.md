# Chat and MCP

Two ways to put your local marola behind something else: the chat server, which answers the
public site's chat widget over HTTP (MIP-0033), and the MCP server, which gives an agent marola's
tools over stdio. Moved from [Run it locally](RUN-LOCALLY.md) §5.2. Both need that page's §1–3: a
marola-app checkout, Ollama serving, and the two models.

## Run the chat server

```bash
# in a marola-app checkout
just run -- --serve-chat     # port 8787 (a number after the flag picks another); Ctrl+C stops it
```

It wraps the corpus answer of [Ask the ocean notes](ASK-THE-OCEAN-NOTES.md), with the same
emergency footer (MIP-0022). From another terminal, on 2026-10-04 with `llama3.2:1b` and
`nomic-embed-text`:

```bash
curl -s http://localhost:8787/health
# {"status":"ok"}
curl -s -X POST http://localhost:8787/ask -H 'Content-Type: application/json' \
  -d '{"question":"what should I do if I get caught in a rip current?"}'
# {"answer":"0. When caught in a rip current, do not fight it.\n1. Stay calm and float to conserve energy. ...",
#  "safety":true,"sources":[{"title":"Rip currents","source":"https://www.weather.gov/safety/ripcurrent"}, ...]}
```

Every request and response is in the app's
[CLI reference](https://docs.marola.dev/5-Repos/marola-app/4-reference_cli/#chat-server). Known
issues:

- The server listens on every interface, although its start-up line says `localhost`
  (marola-dev/marola-app#43), so anything that can reach your machine on that port can use it.
- `/ask` answers with the strict fallback and a minimum score of 0, whatever
  `MAROLA_ASK_FALLBACK` and `MAROLA_ASK_MIN_SCORE` say (marola-dev/marola-app#42).

## Expose it

The widget needs a stable public URL, so use a **named** Cloudflare Tunnel: a quick tunnel's URL
changes on every restart, which would break the widget's saved config. The tunnel is free; it needs
a Cloudflare login and, for `route dns`, a domain whose DNS Cloudflare serves. No port-forwarding on
your router.

```bash
cloudflared tunnel login                                      # once
cloudflared tunnel create marola-chat
cloudflared tunnel route dns marola-chat chat.<your-domain>
cloudflared tunnel run --url http://localhost:8787 marola-chat
```

## Turn on the widget

The widget is marola-site's code, and so is its configuration:
[Turning it on](https://docs.marola.dev/5-Repos/marola-site/1-design_chat-widget/#turning-it-on).
In short, set `window.MAROLA_CHAT_ENDPOINT` in marola-site's
[`chatbot-config.js`](https://github.com/marola-dev/marola-site/blob/main/site/static/chatbot-config.js)
to `https://chat.<your-domain>` and rebuild the site. Empty, the committed default, keeps the widget
hidden. With an endpoint, it calls `/health` on page load, shows its button only on a 200, and
says "chatbot offline" when a later request fails.

The trade-off: this narrowly overrides MIP-0005 §9's "no server" decision for the site. The
maintainer's own machine becomes a real, if intermittent, origin; uptime is whatever the
maintainer's machine and tunnel happen to be, by design (MIP-0033 §6).

## Connect an MCP client

The MCP server speaks stdio as `marola-swim-conditions` and has four tools:
`find_nearby_beaches`, `get_swim_recommendation`, `get_water_quality` and `ask_ocean_question`
(arguments and replies in the
[CLI reference](https://docs.marola.dev/5-Repos/marola-app/4-reference_cli/#mcp-server)).

- **Claude Code**, started in the marola-app checkout, finds it in the checkout's `.mcp.json` as
  `marola`.
- **Any other client** launches it with `just` against the checkout's justfile; `just` runs the
  recipe from the checkout's directory. The client's environment needs `just`, sbt and a JDK on
  `PATH` (the checkout's `nix develop` shell has them), and the `MAROLA_*` variables you set in
  [Run it locally](RUN-LOCALLY.md) §3:

```json
{
  "mcpServers": {
    "marola": {
      "command": "just",
      "args": ["--justfile", "/path/to/marola-app/justfile", "mcp-server"],
      "env": { "MAROLA_LOCAL_LLM_MODEL": "llama3.2:1b", "MAROLA_LOCAL_EMBED_MODEL": "nomic-embed-text" }
    }
  }
}
```

To check it without a client, pipe the handshake in by hand:

```bash
# in a marola-app checkout
{ printf '%s\n' \
  '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"check","version":"0"}}}' \
  '{"jsonrpc":"2.0","method":"notifications/initialized"}' \
  '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"find_nearby_beaches","arguments":{"lat":-27.6733,"lon":-48.47}}}'
  sleep 60; } | just mcp-server
# {"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-06-18",...,"serverInfo":{"name":"marola-swim-conditions","version":"0.1.0"},...}}
# {"jsonrpc":"2.0","id":2,"result":{"content":[{"type":"text","text":"[{\"name\":\"Praia do Campeche\",...,\"distance_km\":2.100910064899072},..."}]}}
```

Known issues: `get_swim_recommendation`'s `water_quality` is always `null`; ask
`get_water_quality` for it (marola-dev/marola-app#41). `ask_ocean_question` ignores
`MAROLA_ASK_FALLBACK` and `MAROLA_ASK_MIN_SCORE`, as `/ask` does (marola-dev/marola-app#42).
