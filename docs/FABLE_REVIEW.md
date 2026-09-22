# marola — code and documentation review (Claude Fable 5.1, 2026-09-05)

A one-pass review of the whole repo at the initial import (`45f5628`), done before the first push to
GitHub. Every finding below was verified against the actual file, not inferred from a doc. Nothing
here was fixed as part of the review. This is the to-do list, ordered by how much each item
matters. Update or delete rows as they're addressed, same rule as every other doc under `docs/`.

**What was verified live:** `just build` and `just test` pass (12 unit tests, all green; see
D9 for why the docs say 16). The pre-commit hook's `sbt Test/compile` also passed on commit.

## 1. Code findings

### C1. Secrets are printed to stdout — **fixed** (`AppConfig.redacted`)

`cli/src/main/scala/marola/Main.scala:136` prints the entire `AppConfig` case class:

```scala
_ <- Console.printLine(s"config -> $config")
```

`AppConfig` carries `telegramBotToken`. Anyone who sets it and runs `just run` gets it echoed in
plain text, into terminal scrollback, CI logs, or wherever stdout goes. `docs/RUN-LOCALLY.md:73`
even shows this line as expected output. Either drop the line or print a redacted view (provider
choices and non-secret settings only).

### C3. The DSPy compile step writes to a directory that no longer exists — **fixed**

`dspy/compile_recommendation_prompt.py:334`:

```python
resources_dir = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources")
```

That resolves to `<repo>/src/main/resources`, a leftover from before the module hoist
(`FUTURE-WORK.md` §7.2). The artifacts the Scala side actually loads live in
`core/src/main/resources/`. Re-running the compile today does not update them. Fix: `"..", "core",
"src", "main", "resources"`. `dspy/README.md` already names the correct path.

### C6. Smaller correctness items

| Where | What | Suggested fix |
|---|---|---|
| `local/.../LocalFileSightingStore.scala:26` `recentFor` | One malformed JSON line throws `JsonParseException` and fails the whole read | **Fixed** — bad lines are skipped |
| `cli/.../SwimConditionsMcpServer.scala:40` `numberArg` | `s.toDouble` throws `NumberFormatException` on non-numeric input, surfacing as an MCP error | **Fixed** — `toDoubleOption` |
| `core/.../Json.scala:53` `render` for `JNumber` | `NaN`/`Infinity` render as bare `NaN`/`Infinity`, which is not valid JSON | **Fixed** — rendered as `null` |
| `cli/.../Main.scala` `parseOrigin` | If only one of `--lat`/`--lon` is given, it silently falls back to the default origin | **Fixed** — `resolveOrigin`/`warnHalfPair` now warn and fall through to env vars / IP geolocation |
| `core/.../Recommender.scala:89` | `LocalDate.now(...)` is a clock read inside the effect chain — harmless, but `EFFECTS-MAP.md` classes `Recommender` as pure control flow | Note it in `EFFECTS-MAP.md` |

### C7. `BeachFinder` missed every beach mapped as an OSM relation — **fixed**

Found after the review, from a live run near Praia do Campeche that returned only two beaches
within 20km. The Overpass query asked for `node` and `way` only; Campeche, Joaquina, Armação,
Matadeiro and ~40 other beaches around Florianópolis are multipolygon **relations**. Two further
problems compounded it: the element cap was `limit * 4` (24) applied in Overpass's database order,
i.e. *before* marola sorts by distance, so the nearest beaches could be truncated arbitrarily; and
`Http.postForm`'s fixed 15s timeout was below what relation-aware Overpass queries actually take
(~29s observed). All three are fixed in `BeachFinder`/`Http.postForm`; see `ARCHITECTURE.md` §9 for
the two residual limitations (centroid distance, Overpass slowness).

## 2. Documentation findings

### D3. Broken markdown in `ARCHITECTURE.md` — **fixed**

`docs/ARCHITECTURE.md:152` is a stray closing fence after the "Telemetry (§5f) wraps the
pipeline..." note. The mermaid block already closed at line 143, so line 152 *opens* a code block
that swallows the "Cross-cutting: rate limiting..." paragraph and §5's table on render. Delete it.

### D4. Stale commands and paths from before the monorepo hoist

| File:line | Says | Should say |
|---|---|---|
| `docs/ARCHITECTURE.md:104-108`, `:227` | `just run marola -- ...` | **Fixed** with MIP-0001 |
| `docs/ARCHITECTURE.md:291` | `sbt marola/runMain marola.agent.SwimConditionsMcpServer` | **Fixed** with MIP-0001 |
| `cli/src/test/scala/marola/E2ESpec.scala:15` | `just e2e-marola` | `just e2e` |
| `cli/src/test/scala/marola/E2ESpec.scala:35` | "the `just run marola` default path" | `just run` |
| `docs/ARCHITECTURE.md:188` | artifact at `marola/src/main/resources/recommendation_prompt.json` | `core/src/main/resources/...` |
| `docs/FUTURE-WORK.md:211`, `core/src/main/scala/marola/llm/Reviewer.scala:20` | "see `dspy/review_prompt.json`" | `core/src/main/resources/review_prompt.json` |

### D5. References to files that don't exist — **fixed**

- `build.sbt:14`: "See flake.nix and Dockerfile — both pin 25." There is no Dockerfile.

### D6. A method that doesn't exist is cited as the structured-output mechanism — **fixed**

`docs/SKILLS.md:27` names `CompiledPrompt.replay`. The real method is
`CompiledPrompt.buildMessages`; the model reply is consumed by `LlmClient.extractContent` and
`Reviewer.extractJsonObject`.

### D8. Contradictory status on the DSPy compile step — **fixed**

`dspy/compile_recommendation_prompt.py:11` (module docstring) says "NOT RUN as part of writing
this" and describes the default as a paid API; `dspy/README.md` "Status" and `main()`'s own comment
say it was run, twice, against a local Ollama model, and the default is `ollama_chat/llama3.2`.
The docstring is stale.

### D9. Numbers and ordering — **fixed**

- `docs/FUTURE-WORK.md:363`: "all 16 tests pass". There are 12 unit tests (`SwimabilitySpec`) plus
  2 E2E tests; `just test` reports 12.
- `docs/FUTURE-WORK.md` §7 runs 7.1 → 7.3 → 7.2.
- `docs/SKILLS.md:37` points at "`ARCHITECTURE.md` §5b's Status note" for the MCP JSON-RPC
  verification; that note is in §5c.

### D10. Things the docs get right that are worth keeping

Not a finding, but so this file isn't only a list of gaps: the per-integration "verified live vs.
written-not-run" status notes in `ARCHITECTURE.md` §5 matched the code in every case checked; the
`EFFECTS-MAP.md` classification is accurate apart from the `Recommender` clock read (C6);
`build.sbt`'s `META-INF/services` merge note and the `Compile / run / mainClass` pin are both real
and correct; `.gitignore` covers `.idea/`, `.bsp/`, `.tmp/`, and `data/`, and none of them leaked
into the initial commit.

### C8. The "best hour" was always midnight — **fixed**

Found from a real run after merge: every beach's best hour printed as `00:00` and the LLM
rightly called it "not a good night for swimming". `Swimability.score` ignored `isDaylight`, so on
a flat day all 24 hours tied and the first one won. Fixed: a −60 "dark" deduction, and equal
scores now break ties toward 10:00 (`Swimability.hourPreference`): staffed lifeguard posts, best
light, calmest sea. Same run showed `Tides.extrema` reporting a 2cm wobble as a high/low pair;
turns now need ≥ 0.1m of range. Golden test asserts every recommended hour is daylight.

### Regression mechanism added after the review

`cli/src/test/scala/marola/PipelineGoldenSpec.scala` replays recorded real responses (Overpass,
Open-Meteo, IMA: `cli/src/test/resources/fixtures/`) through the unchanged production code via
`Http.withTransport`, and `core/.../llm/SummarizeFlowSpec.scala` / `knowledge/RagOfflineSpec.scala`
script the LLM/embedder. That is the every-push regression check in `ci.yml`; the live E2E workflow
is manual, two-job, model-cached, and skips Ollama unless asked (`marola-e2e.yml`).

## 3. Environment notes from the review session

These aren't defects in marola, but they cost time and are easy to hit again:

- **Inside `just jail-claude`, `.env.example` and `.ai-jail` read as empty.** ai-jail bind-mounts
  both read-only with no content (the `.env.*` mask). `git status` shows them as `AM`, `cat` and
  `git diff` show them blank. The index/HEAD copies are intact. Never `git add -A` from inside the
  jail; stage files by name.
- **`gh` is unauthenticated inside the jail** because `~/.config/gh` is not mapped (the recipe maps
  `~/.claude`, `~/.claude.json`, and `~/.ssh` only). SSH to GitHub works, so `git push` is fine, but
  `gh repo create` and any other API call need either `--map ~/.config/gh` added to the
  `jail-claude` recipe or `GH_TOKEN` in the environment.
