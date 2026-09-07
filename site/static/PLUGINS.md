# marola map plugins (MIP-0035)

A plugin is a small JS bundle, loaded by URL, that gets access to marola's live Leaflet map and
the current board. Modeled on [windy-plugin-template](https://github.com/windycom/windy-plugin-template)'s
dev-mode loading pattern — adapted to marola's plain Leaflet map and a static site with no backend.

## Writing one

```js
window.marola.registerPlugin({
  name: "my-hazard-layer",
  init(ctx) {
    // ctx.map: the live Leaflet map instance (L.Map)
    // ctx.L: the Leaflet global, already loaded
    // ctx.board: the current board (schema: site/board.schema.json) — beaches, scores, trails
    // ctx.onBoardUpdate(fn): fn(board) is called whenever the visitor changes area or day
    const marker = ctx.L.marker([-27.60, -48.51]).addTo(ctx.map);
    marker.bindTooltip("Reported: strong current");
  }
});
```

`registerPlugin` can be called before or after the board has loaded — a plugin registered early is
queued and `init()`'d once the map/board are ready; one registered late is `init()`'d immediately.

## Trying it locally

```
https://marola.dev/?plugin=https://localhost:9999/plugin.js
```

Repeatable (`?plugin=A&plugin=B`) for testing more than one at once.

## Shipping it for every visitor

A PR adding one entry to `plugins.json` (this directory):

```json
{ "name": "my-hazard-layer", "url": "https://cdn.jsdelivr.net/gh/user/repo@v1/plugin.js",
  "author": "you", "description": "one line" }
```

That PR review is the real trust gate — **a registered plugin runs with full page access**, the
same trust shape as any browser extension or injected script, not a sandboxed capability model.
There is no technical isolation here; don't register anything you wouldn't trust with everything
the page can see (see MIP-0035 §8 for the honest tradeoff and why).
