# marola routing digest

## Wiring

| Artifact | Published by | Pinned in | Read by |
|---|---|---|---|
| `ghcr.io/marola-dev/marola-app` image | marola-app `ci.yml` (on a push to `main`) | marola-site `marola-image`, marola-ml `marola-image`, marola-oods `marola-image` | marola-site `scripts/board-schema.sh`, marola-ml `scripts/app-image.sh`, marola-app `docker-compose.yml`, marola-oods `scripts/app-image.sh` |
| `ghcr.io/marola-dev/marola-ml` image | marola-ml `docker-local.yml` (on a push to `main` touching 10 paths) | — | marola-app `docker-compose.yml` |
| `<tag>-v<tag>.pdf` release asset | marola-research `release.yml` (on a `*-v[0-9]*.[0-9]*.[0-9]*` tag) | — | — |
| `marola-corpus-<tag>.tar.gz` release asset | marola-corpus `release.yml` (on a `v*` tag) | marola-ml `corpus.version`, marola-app `corpus.version` | marola-ml `scripts/corpus-fetch.sh`, marola-app `build.sbt`, marola-app `scripts/corpus-fetch.sh` |
| `ml-resources-<tag>.tar.gz` release asset | marola-app `release.yml` (on a `v*` tag) | marola-ml `resources.version` | marola-ml `scripts/resources-fetch.sh` |
| `api-docs` branch | marola-ml `ci.yml` (`api-docs.yml@v0.20.4`, on a push to `main`), marola-app `ci.yml` (`api-docs.yml@v0.20.4`, on a push to `main`) | — | marola `scripts/fetch-api-docs.sh` |
| `site-data` branch of marola-site | marola `ci.yml` (on a push to `main`), marola-app `ci.yml` (on a push to `main`), marola-app `docker-smoke.yml` (on a schedule) | — | marola-site `site.yml` |
| PRs into each repo in `.github/consumers.txt` | marola-devkit `bump-consumers.yml`, marola-devkit `bump-merge.yml` (on a schedule) | — | marola, marola-app, marola-site, marola-ml, marola-corpus, marola-oods, marola-skills |
| PRs into marola-app: `recommendation_prompt.json`, `review_prompt.json` | marola-ml `compile-prompt.yml` (by hand) | — | marola-app |
| a marola-devkit tag | marola-devkit | marola `flake.lock` v0.20.4, marketplace v0.20.4; marola-site `flake.nix` v0.20.4, marketplace v0.20.4; marola-corpus `flake.nix` v0.20.4, marketplace v0.20.4; marola-ml `flake.nix` v0.20.4, marketplace v0.20.4; marola-app `flake.lock` v0.20.4, marketplace v0.20.4; marola-oods `flake.nix` v0.20.4, marketplace v0.20.4; marola-skills `flake.lock` v0.20.4, marketplace v0.20.4 | `agents-check.yml` v0.20.4 (marola marola-site marola-corpus marola-ml marola-app marola-oods marola-skills); `api-docs.yml` v0.20.4 (marola-ml marola-app); `ci-short-circuit.yml` v0.20.4 (marola marola-site marola-corpus marola-ml marola-app marola-oods marola-skills); `gemini-review.yml` v0.20.4 (marola marola-site marola-corpus marola-ml marola-app marola-oods); `labels-sync.yml` v0.20.4 (marola marola-site marola-corpus marola-ml marola-app marola-oods marola-skills); `notify-devkit.yml` v0.20.4 (marola marola-site marola-corpus marola-ml marola-app marola-oods marola-skills marola-devkit); `notify-umbrella.yml` v0.20.4 (marola-site marola-corpus marola-ml marola-app marola-oods marola-skills); `pr-body.yml` v0.20.4 (marola marola-site marola-corpus marola-ml marola-app marola-oods marola-skills); `python-ci.yml` v0.20.4 (marola marola-site marola-ml marola-app marola-skills); `scala-ci.yml` v0.20.4 (marola-app); `skills-update.yml` v0.20.4 (marola marola-site marola-corpus marola-ml marola-app marola-oods); `static-ci.yml` v0.20.4 (marola marola-site marola-corpus marola-ml marola-app marola-oods) |

| Dispatch | Sent by | Triggers |
|---|---|---|
| `bump-ready` | marola `bump-ready.yml` (`notify-devkit.yml@v0.20.4`), marola-site `bump-ready.yml` (`notify-devkit.yml@v0.20.4`), marola-corpus `bump-ready.yml` (`notify-devkit.yml@v0.20.4`), marola-ml `bump-ready.yml` (`notify-devkit.yml@v0.20.4`), marola-app `bump-ready.yml` (`notify-devkit.yml@v0.20.4`), marola-oods `bump-ready.yml` (`notify-devkit.yml@v0.20.4`), marola-skills `bump-ready.yml` (`notify-devkit.yml@v0.20.4`), marola-devkit `notify-devkit-test.yml` (`notify-devkit.yml@v0.20.4`, by hand) | marola-devkit `bump-merge.yml` |
| `site-data-updated` | marola-app `ci.yml` (on a push to `main`), marola-app `docker-smoke.yml` (on a schedule) | marola-site `site.yml` |
| `submodule-docs-updated` | marola-site `notify-umbrella.yml` (`notify-umbrella.yml@v0.20.4`, on a push to `main`), marola-corpus `notify-umbrella.yml` (`notify-umbrella.yml@v0.20.4`, on a push to `main`), marola-ml `notify-umbrella.yml` (`notify-umbrella.yml@v0.20.4`, on a push to `main`), marola-app `notify-umbrella.yml` (`notify-umbrella.yml@v0.20.4`, on a push to `main`), marola-oods `notify-umbrella.yml` (`notify-umbrella.yml@v0.20.4`, on a push to `main`), marola-skills `notify-umbrella.yml` (`notify-umbrella.yml@v0.20.4`, on a push to `main`) | marola `docs.yml`, marola `pointer-sync.yml` |
| `submodule-updated` | marola-site `notify-umbrella.yml` (`notify-umbrella.yml@v0.20.4`, on a push to `main`), marola-corpus `notify-umbrella.yml` (`notify-umbrella.yml@v0.20.4`, on a push to `main`), marola-ml `notify-umbrella.yml` (`notify-umbrella.yml@v0.20.4`, on a push to `main`), marola-app `notify-umbrella.yml` (`notify-umbrella.yml@v0.20.4`, on a push to `main`), marola-oods `notify-umbrella.yml` (`notify-umbrella.yml@v0.20.4`, on a push to `main`), marola-skills `notify-umbrella.yml` (`notify-umbrella.yml@v0.20.4`, on a push to `main`) | marola `pointer-sync.yml` |

| Pin bump | Workflow |
|---|---|
| marola `flake.lock` | marola `ci.yml`, marola `docs.yml` |
| marola-app `corpus.version` | marola-app `ci.yml` |
| marola-ml `marola-image` | marola-ml `docker-local.yml` |
| marola-ml `resources.version` | marola-ml `docker-local.yml` |
| marola-oods `marola-image` | marola-oods `oods-check.yml` |
| marola-site `marola-image` | marola-site `board-schema.yml`, marola-site `site.yml` |

| Deploy | Site |
|---|---|
| marola `docs.yml` | docs.marola.dev |
| marola-site `site.yml` | marola.dev |
| marola-research `site.yml` | ? |

| Contract | Copied to | Kept |
|---|---|---|
| marola-app `cli/src/main/resources/board.schema.json` | marola-site `site/board.schema.json` | identical |
| marola-site `site/areas.json` | marola-app `cli/src/test/resources/site/areas.json` | identical |

## marola

- `mkdocs/hooks/mermaid_font.py`: event_priority(), pin_mermaid_font(), on_page_markdown()
- `scripts/agents_repos_check.sh`: agents_repos_check.sh script, check(), self_test(), ok()
- `scripts/arxiv_digest.py`: cache_dir(), arxiv_id_from_url(), parse_atom(), fetch_query(), relevance_score(), load_cached_ids(), write_index(), run(), self_test(), main()
- `scripts/awesome_agentic_digest.py`: cache_dir(), repo_key(), parse_search_response(), fetch_query(), relevance_score(), load_cached_ids(), write_index(), run(), self_test(), main()
- `scripts/citation.py`: scripts/citation.py, orcid_ok(), _person_errors(), validate(), _q(), _plain(), _repo(), render_cff(), _wrap(), add_person(), write_json(), _families(), render_refs(), splice(), run(), self_test(), main()
- `scripts/claude_project_check.py`: check(), self_test(), main()
- `scripts/docs_redirect_check.js`: fs, path, vm, PAGE, scripts, ok(), visit()
- `scripts/fetch-api-docs.sh`: fetch-api-docs.sh script, self_test(), ok()
- `scripts/gh-billing.sh`: gh-billing.sh script, host_block(), scope_line(), has_scope(), has_user_scope(), found_scopes_for_message(), missing_user_scope_message(), env_token_message(), classify_error(), SHAPE_FILTER, usage(), shape(), ACTIONS_INCLUDED_MINUTES, print_table(), self_test()
- `scripts/lib/doc_links.py`: Context, _fenced(), _unique(), _readme_anchors(), _excluded(), _docs_mount(), _out_path(), rewrite(), links(), mount(), adr_index(), self_test(), main()
- `scripts/lib/repos_manifest.sh`: repos_manifest.sh script, repos_manifest()
- `scripts/mip_graph.py`: Mip, .__init__(), .node_id(), .status_class(), parse_mip_file(), load_mips(), render_mermaid(), graph_block(), reachable(), source_paths(), cmd_parallel(), cmd_generate(), self_test(), main()
- `scripts/mkdocs.sh`: mkdocs.sh script, project_name(), image_tag(), pick_runtime(), serve_path(), compose_files(), stage_docs(), check_index(), run(), self_test(), ok()
- `scripts/pointer-sync-merge.sh`: pointer-sync-merge.sh script, outside_markers(), scope_problem(), read_gh(), pointer_problem(), run(), refuse(), self_test(), ok(), mkhead(), pr(), go(), calls(), usage()
- `scripts/pointer-sync.sh`: pointer-sync.sh script, trailers(), compare_url(), bump(), self_test(), ok()
- `scripts/prepare-docs.sh`: prepare-docs.sh script, self_test(), ok(), commit_all(), umbrella(), app()
- `scripts/release.sh`: release.sh script, scripts/release.sh, valid_tag(), content_lines(), guard()
- `scripts/repo_stats.py`: badge(), count_steps(), ci_badge(), loc_badge(), parse_cloc(), coverage_badge(), parse_coverage_json(), coverage_run_argv(), fetch_jobs(), cloc_code(), coverage_exe(), python_coverage(), write(), collect(), self_test(), build_parser(), main()
- `scripts/routing_bundle.py`: scores(), drops(), repo_of(), digest(), wiring_block(), assemble(), self_test(), main()
- `scripts/ruff_exclude_check.py`: check(), self_test(), main()
- `scripts/site-data-push.sh`: scripts/site-data-push.sh, site-data-push.sh script, push(), self_test()
- `scripts/site_links_check.py`: forward_prefixes(), resolve(), check_hrefs(), check_old_sitemap(), self_test(), main()
- `scripts/strip_external_scripts.py`: scripts/strip_external_scripts.py, strip(), check(), process(), self_test(), main()

## marola-app

- `marola-app/cli/src/main/scala/marola/AppConfig.scala`: WaterProvider, .fromEnv(), FacilitiesProvider, TraceBackend, AppConfig, .origin(), .redacted(), .waterQualityClient(), .backupFor(), .selectWaterClient(), .accessibilityClient(), .knowledgeStore(), .llmClient(), .tracedLlmClient(), .sightingStore(), .visionClient(), .runLedger(), .tracing()
- `marola-app/cli/src/main/scala/marola/Main.scala`: marola/Main.scala, Main, Origin, .resolveOrigin(), .flagOrigin(), .urlOrigin(), .warnHalfPair(), .argValue(), .noop(), .parseSightingReport(), .bootstrap(), .askOcean(), .serveChat(), .buildSite(), .runBenchmark(), .logBenchmarkRun(), .reindexKnowledge(), .analyzePhoto(), .reportSighting(), .resolveTracing(), .runRecommendation(), .printDetail(), .printLore(), .factInputsFor(), .summarizeTop(), .reviewAndPrint(), .loadCompiledPrompt(), .printLines()
- `marola-app/cli/src/main/scala/marola/Report.scala`: Report, .todayFor(), .waterSummary(), .facilitiesLine(), .facilityLabel(), .facilitiesSuffix(), .briefLine(), .line(), .detail(), .lore(), .answer(), .nearestTrail(), .trailsLine(), .compass()
- `marola-app/cli/src/main/scala/marola/agent/ChatServer.scala`: ChatServer, .responseFor(), .start(), .cors(), .writeJson(), .healthHandler(), .askHandler()
- `marola-app/cli/src/main/scala/marola/agent/SwimConditionsMcpServer.scala`: SwimConditionsMcpServer, .runSync(), .numberArg(), .originAndRadius(), .beachToJson(), .bestHourToJson(), .samplingPointToJson(), .waterQualityToJson(), .numberProperty(), .findBeachesHandler(), .recommendationsFor(), .recommendationHandler(), .waterQualityHandler(), .askHandler(), .main()
- `marola-app/cli/src/main/scala/marola/bench/BenchmarkLedger.scala`: BenchmarkLedger, Context, .params(), .metrics(), .runName(), .log(), .corpusSha(), .gitSha()
- `marola-app/cli/src/main/scala/marola/bench/OceanBenchmark.scala`: OceanBenchmark, Question, Result, ArmSummary, Report, .load(), .coverage(), .fold(), .isCited(), .isAbstained(), .run(), .runAll(), .timed(), .mean(), .summarise(), .verdict(), .render(), .save()
- `marola-app/cli/src/main/scala/marola/site/SiteBuilder.scala`: SiteBuilder, Area, .origin(), Areas, .load(), .parse(), .build(), .buildArea(), .writeAreasIndex(), .write()
- `marola-app/core/src/main/scala/marola/Recommender.scala`: Recommender, .bestHoursTomorrow(), .fetchWaterQuality(), .describe(), .fetchFacilities(), .bestPerBeachTomorrow(), .scoreDays(), .scoreTomorrow(), .scoreDay(), .traverse()
- `marola-app/core/src/main/scala/marola/beaches/BeachFinder.scala`: BeachFinder, .nearby(), .defaultSnapshotDir(), .fromSnapshot(), .queryOverpass(), .isJunkName(), .parseElement()
- `marola-app/core/src/main/scala/marola/beaches/BeachSnapshot.scala`: BeachSnapshot, .key(), .fileFor(), .encode(), .decode(), .read(), .write()
- `marola-app/core/src/main/scala/marola/beaches/Facilities.scala`: Facilities, AccessibilityClient
- `marola-app/core/src/main/scala/marola/beaches/NoopAccessibilityClient.scala`: NoopAccessibilityClient, .near()
- `marola-app/core/src/main/scala/marola/beaches/OverpassAccessibilityClient.scala`: OverpassAccessibilityClient, .near(), .query(), Located, .attribute(), .facilityOf()
- `marola-app/core/src/main/scala/marola/conditions/OpenMeteoClient.scala`: OpenMeteoClient, .forecastFor(), .merge()
- `marola-app/core/src/main/scala/marola/conditions/Tides.scala`: TideEvent, Tides, .extrema()
- `marola-app/core/src/main/scala/marola/http/Http.scala`: Http, HttpError, Response, Transport, send(), .withTransport(), .check(), .backoffFor(), .isRetryableFailure(), .sendRetrying(), .userAgent(), .request(), .getString(), .postForm(), .postJson(), .postBytes(), .putBytes(), BytesResponse, HttpBytesError, BinaryTransport, .withBinaryTransport(), .getBytes()
- `marola-app/core/src/main/scala/marola/json/Json.scala`: apply(), arr(), str(), num(), bool(), render(), JsonValue, .obj(), .arr(), .str(), .num(), .bool(), .renderString(), JsonParseException, .parse(), Parser, .pos(), .atEnd(), .skipWhitespace(), .fail(), .expect(), .expectLiteral(), .parseValue(), .parseObject(), .parseArray(), .parseString(), .parseNumber()
- `marola-app/core/src/main/scala/marola/knowledge/Corpus.scala`: CorpusChunk, Corpus, .listFiles(), .load(), .chunkDocument(), .mergeUpTo(), .cosine()
- `marola-app/core/src/main/scala/marola/knowledge/FileKnowledgeStore.scala`: FileKnowledgeStore, Indexed, .search(), .rank(), .rebuild(), .loadOrBuild(), .buildIfMissing(), .embedAll(), .batches(), .fingerprint(), .readIndex(), .writeIndex()
- `marola-app/core/src/main/scala/marola/knowledge/KnowledgeStore.scala`: Embedder, Passage, KnowledgeStore
- `marola-app/core/src/main/scala/marola/knowledge/OceanQa.scala`: OceanQa, Answer, .saysNoAnswer(), .answer(), .complete(), .unanswered(), .generalMessages(), .buildMessages()
- `marola-app/core/src/main/scala/marola/knowledge/SafetyFooter.scala`: SafetyFooter, .render(), .append()
- `marola-app/core/src/main/scala/marola/ledger/RunLedger.scala`: RunLedger, RunHandle, start(), metrics(), artifact(), end()
- `marola-app/core/src/main/scala/marola/llm/CompiledPrompt.scala`: CompiledPrompt, .buildMessages(), .renderFields(), .toFieldLabel(), .loadFromString()
- `marola-app/core/src/main/scala/marola/llm/LlmClient.scala`: ChatMessage, LlmClient, NoCompletionException, .extractContent()
- `marola-app/core/src/main/scala/marola/llm/Reviewer.scala`: Reviewer, ReviewResult, MalformedReviewException, .review(), .extractJsonObject()
- `marola-app/core/src/main/scala/marola/llm/TracedLlmClient.scala`: TracedLlmClient, .complete(), .renderPrompt(), .contentFromEnv()
- `marola-app/core/src/main/scala/marola/location/IpGeolocation.scala`: IpLocation, IpGeolocation, Provider, .locate(), .query(), .consensus()
- `marola-app/core/src/main/scala/marola/log/Log.scala`: Log, .error(), .warn(), .info(), .debug(), .forName()
- `marola-app/core/src/main/scala/marola/lore/SeaLore.scala`: LoreEntry, SeaLore, .loadDefault(), .parse(), .regionTagsFor(), .pick(), .format()
- `marola-app/core/src/main/scala/marola/model/Models.scala`: Coordinates, .distanceKm(), .fromMapsUrl(), Beach, HourlyConditions, BeachForecast, BestHour
- `marola-app/core/src/main/scala/marola/observability/Tracing.scala`: Tracing, .llmSpan(), Noop, .withSpan()
- `marola-app/core/src/main/scala/marola/scoring/Note.scala`: NoteCode, .fromLabel(), Note, .json(), .english()
- `marola-app/core/src/main/scala/marola/scoring/Swimability.scala`: WaterVerdict, Swimability, .isWhaleSeason(), .jellyfishRisk(), .whaleSightingLikelihood(), .waveDelta(), .windLevel(), .windDelta(), .seaTempDelta(), .precipitationDelta(), .daylightDelta(), .hourPreference(), .jellyfishDelta(), .waterVerdict(), .score()
- `marola-app/core/src/main/scala/marola/sightings/Sighting.scala`: Sighting
- `marola-app/core/src/main/scala/marola/sightings/SightingStore.scala`: SightingStore
- `marola-app/core/src/main/scala/marola/site/Board.scala`: Board, Sources, .stamp(), .build(), .beachJson(), .hourJson(), .seaJson(), .waterJson(), .facilitiesJson(), .facilityKey(), .trailJson(), .nearAnchorJson(), .loreJson(), .optNum(), .optStr()
- `marola-app/core/src/main/scala/marola/trails/TrailFinder.scala`: Trail, TrailFinder, .nearbyOrEmpty(), .soften(), .nearby(), Segment, .parse(), .parseSegment(), .parseLake(), .merge(), .segmentLengthKm(), .nearest()
- `marola-app/core/src/main/scala/marola/vision/VisionClient.scala`: VisionClient
- `marola-app/core/src/main/scala/marola/water/WaterQuality.scala`: BathingCondition, label(), .fromLabel(), WaterSample, SamplingPoint, .latest(), WaterQuality, .latestSamples(), .fresh(), .improper(), .newestSampleDate(), .isFresh(), WaterQualityClient
- `marola-app/core/src/main/scala/marola/water/WaterQualityMatcher.scala`: WaterQualityMatcher, .foldAccents(), .normalise(), .isInlandWater(), .namesMatch(), .assign()
- `marola-app/experiment/src/main/scala/marola/experiment/Cycle.scala`: Deps, Scores, Report, Cycle, .experiment(), .run(), .rescore(), .sorted(), .instrument(), .log(), .write()
- `marola-app/experiment/src/main/scala/marola/experiment/Export.scala`: Export, .window(), .metrics(), .scorecard(), .write()
- `marola-app/experiment/src/main/scala/marola/experiment/GroundTruth.scala`: GroundTruth, .scored(), Kind, .fromLabel(), Status, Thresholds, Checked, Point, .parse(), .bundled(), .validate(), .stationCodeOk(), .inBrazil(), MalformedField, .read(), .readPoint(), .str(), .num(), .int(), .date(), .label()
- `marola-app/experiment/src/main/scala/marola/experiment/LakeSamples.scala`: LakeSamples, .writePoints(), .writeForecasts(), .writeObservations(), .writeRuns(), .writeScoreCells(), .writePairCells(), .points(), .forecasts(), .observations(), .runs(), .scoreCells(), .pairCells(), .upsert(), .paged(), .page(), .columnsOf(), .exec(), .open(), .connect(), .attach(), .quote(), .utc(), .between(), .fields(), .toJdbc(), .fromJdbc(), Table
- `marola-app/experiment/src/main/scala/marola/experiment/Main.scala`: experiment/Main.scala, Flags, lake, out, mlflowUri, dryRun, Main, .main(), .program(), .catalog(), .bundled(), .blobSha(), .deps(), .observe()
- `marola-app/experiment/src/main/scala/marola/experiment/Observations.scala`: Observations, .knotsToMs(), .plausible(), .reading(), MetarClient, .url(), .parse(), .fetch(), InmetClient, .number(), .station(), InmetError, Nino34Reader, Week, .latest()
- `marola-app/experiment/src/main/scala/marola/experiment/Protocols.scala`: Protocols, .providers(), .bundledProviders(), .bundledProtocol(), .bundled(), .decode(), .duplicates()
- `marola-app/experiment/src/main/scala/marola/experiment/SampleStore.scala`: SampleStore, .apply()
- `marola-app/experiment/src/main/scala/marola/experiment/SamplingRegistry.scala`: SamplingRegistry, .parse(), .bundled(), .sampledOn(), .scorable()
- `marola-app/experiment/src/main/scala/marola/experiment/forecast/ForecastSource.scala`: ForecastSource, Collected, reason()
- `marola-app/experiment/src/main/scala/marola/experiment/forecast/OpenMeteoForecasts.scala`: OpenMeteoForecasts, .metadataUrl(), .rateLimiter(), .apply(), Metadata, .parseMetadata(), .leads(), .query(), .latestUrl(), .singleRunUrl(), .parse(), .contentSha(), .expectedRuns(), .parseJson(), Transient, Impl, .now(), .attempt(), .classify(), .get(), .metadata(), .points(), .fetch(), .latest(), .backfill(), .collect()
- `marola-app/experiment/src/main/scala/marola/experiment/schema/Labels.scala`: Labeled, .schema(), Route, Variable, MeasureUnit, .of(), Network, PointKind, RunState
- `marola-app/experiment/src/main/scala/marola/experiment/schema/Records.scala`: snake(), Provider, Instrument, SamplingPoint, Protocol, ForecastSample, ObservationSample, RunIndexRow, ScoreCell, PairCell, NavyWarning, ScorecardRow, Scorecard
- `marola-app/experiment/src/main/scala/marola/experiment/schema/ScorecardSchema.scala`: ScorecardSchema, .schema(), .json(), .snakeCase(), .snakeKeys(), .main()
- `marola-app/experiment/src/main/scala/marola/experiment/score/Matcher.scala`: Matched, .day(), .score(), Matcher, .pairs()
- `marola-app/experiment/src/main/scala/marola/experiment/score/Score.scala`: Monoid, .fold(), Score, .bias(), .rmse(), .crps(), .spread(), .debiasedEnsMeanRmse(), .spreadSkill(), empty(), combine(), .of(), .mean(), .variance(), .fairCrps(), .strided(), PairScore, .meanD(), .errorCorrelation()
- `marola-app/experiment/src/main/scala/marola/experiment/score/Scorer.scala`: HeadToHead, .verdict(), Scorer, .key(), .cells(), .window(), .pairCells(), .paired(), .common(), .twelveZ(), .issued(), .bootstrap()
- `marola-app/local/src/main/scala/marola/knowledge/OllamaEmbedder.scala`: OllamaEmbedder, .embed(), .nativeBaseUrl()
- `marola-app/local/src/main/scala/marola/ledger/MlflowApi.scala`: MlflowApi, .restBase(), .getOrCreateExperiment(), .createExperiment(), .encode(), .field()
- `marola-app/local/src/main/scala/marola/ledger/MlflowRunLedger.scala`: MlflowRunLedger, .start(), .metrics(), .artifact(), .end(), .runUrl(), .createRun(), .logParams(), .logMetrics(), .sendLogBatch(), .truncateKey()
- `marola-app/local/src/main/scala/marola/llm/LocalLlmClient.scala`: LocalLlmClient, .complete()
- `marola-app/local/src/main/scala/marola/observability/MlflowTracing.scala`: MlflowTracing, .withSpan(), .llmSpan(), .span(), .fail(), .tracesEndpoint(), .headers(), .experimentName(), .apply(), .withExporter()
- `marola-app/local/src/main/scala/marola/sightings/LocalFileSightingStore.scala`: LocalFileSightingStore, .record(), .recentFor(), .toJson(), .parseLine(), .parseJson()
- `marola-app/local/src/main/scala/marola/vision/LocalVisionClient.scala`: LocalVisionClient, .describe()
- `marola-app/local/src/main/scala/marola/water/CachedWaterQualityClient.scala`: CachedWaterQualityClient, .name(), .samplingPoints(), .fallback(), .fileFor(), .encode(), .decode(), .read(), .write()
- `marola-app/local/src/main/scala/marola/water/FallbackWaterQualityClient.scala`: FallbackWaterQualityClient, .name(), .samplingPoints(), .attempt()
- `marola-app/local/src/main/scala/marola/water/ImaScPdfParser.scala`: ImaScPdfParser, Row, .parse()
- `marola-app/local/src/main/scala/marola/water/ImaScPdfWaterQualityClient.scala`: ImaScPdfWaterQualityClient, .name(), .samplingPoints(), .latestBulletinUrl(), .fold(), .key(), .join()
- `marola-app/local/src/main/scala/marola/water/ImaScWaterQualityClient.scala`: ImaScWaterQualityClient, .name(), .samplingPoints(), .coversOrigin(), .parse(), .text(), .parsePoint(), .parseSample(), .verdict()
- `marola-app/local/src/main/scala/marola/water/IneaPdfParser.scala`: IneaPdfParser, Row, .parseTable(), RowEvent, NameEvent, .parseLines(), .columnSplit(), .nearestName(), .codePrefix(), .classify()
- `marola-app/local/src/main/scala/marola/water/IneaRjWaterQualityClient.scala`: IneaRjWaterQualityClient, .name(), .samplingPoints(), .pagePoints(), .bulletinPoints(), .orNothing(), Bulletin, .bulletins(), .coversOrigin(), .toSamplingPoints(), .toSamplingPoint()
- `marola-app/local/src/main/scala/marola/water/InemaBaWaterQualityClient.scala`: InemaBaWaterQualityClient, .name(), .samplingPoints(), .bulletinPoints(), .orNothing(), Bulletin, .bulletins(), .coversOrigin(), .toSamplingPoints(), .toSamplingPoint()
- `marola-app/local/src/main/scala/marola/water/InemaPdfParser.scala`: InemaPdfParser, Row, .parseTable(), .extractText(), .tableLines(), .mergeContinuations(), .toRow()
- `marola-app/local/src/main/scala/marola/water/PdfLines.scala`: PdfLines, Chunk, Line, LineCapturingStripper, .writeString(), .writeLineSeparator(), .captured(), .of()
- `marola-app/local/src/main/scala/marola/water/SamplingPointCoordinates.scala`: SamplingPointCoordinates, Entry, .load(), .readResource(), .parse(), .parseEntry()
- `marola-app/scripts/build-resources-tarball.sh`: build-resources-tarball.sh script, build(), self_test()
- `marola-app/scripts/corpus-fetch.sh`: corpus-fetch.sh script, marola-app/scripts/corpus-fetch.sh, fetch(), self_test(), release(), run_fetch()
- `marola-app/scripts/ocr-post.py`: GhError, .__init__(), gh_api(), _int(), normalise(), load_findings(), right_lines(), inline_position(), sanitise(), fence_for(), label(), comment_body(), one_line(), clean_field(), header(), summary_body(), post_review(), upsert_sticky(), run(), build_parser(), main(), FakeGh, .__call__(), self_test()
- `marola-app/scripts/site-data-push.sh`: marola-app/scripts/site-data-push.sh, site-data-push.sh script, push(), self_test()
- `marola-app/scripts/smoke_record.py`: parse(), record(), compact(), write(), self_test(), main()
- `marola-app/scripts/strip_external_scripts.py`: marola-app/scripts/strip_external_scripts.py, strip(), check(), process(), self_test(), main()

## marola-corpus

- `marola-corpus/scripts/corpus-check.sh`: corpus-check.sh script, check(), self_test()
- `marola-corpus/scripts/corpus-tarball.sh`: corpus-tarball.sh script, build(), self_test()

## marola-ml

- `marola-ml/dspy/compile_recommendation_prompt.py`: SummarizeSwimConditions, ReviewSwimSummary, review_json_is_well_formed_and_sound(), jellyfish_and_whale_mentioned_when_relevant(), _init_langfuse_tracing(), _mlflow_tracking_configured(), _log_compile_run_to_mlflow(), _log_prompt_compile_run(), self_test(), main()
- `marola-ml/finetune/build_dataset.py`: default_knowledge_dir(), render_inputs(), example(), from_compiled_prompt(), from_sea_lore(), chunk_markdown(), _split_sentences(), _chunk_examples(), _sentence_examples(), from_knowledge(), _load_knowledge_sources(), _tool_call_example(), _latlon_tool_examples(), _ask_tool_examples(), from_tool_calls(), _self_test(), main(), parse_args()
- `marola-ml/finetune/build_dpo_dataset.py`: review_records(), preference_pairs(), main(), _self_test(), parse_args()
- `marola-ml/finetune/merge_export.py`: default_adapter(), model_name(), prefixed_name(), gguf_paths(), convert_argv(), quantize_argv(), plan(), plan_json(), merge(), self_test(), main()
- `marola-ml/finetune/preflight.py`: free_disk_gb(), total_ram_gb(), vram_gb(), estimate(), eta_hours(), main(), self_test()
- `marola-ml/finetune/publish_hf.py`: finetune/publish_hf.py, sha256_of(), checksums_file(), model_card(), main()
- `marola-ml/finetune/train_dpo.py`: main()
- `marola-ml/finetune/train_lora.py`: preset_for(), ollama_base_for(), run_slug(), run_dir(), default_out(), has_artifacts(), base_conflict(), read_marker(), check_base(), adapter_base(), check_adapter_base(), latest_checkpoint(), mark_base(), self_test(), main()
- `marola-ml/scripts/analyze_training.py`: find_state(), parse_stdout(), load_run(), series(), summarize(), _trend(), diagnose(), render_markdown(), self_test(), main()
- `marola-ml/scripts/api-docs.sh`: api-docs.sh script, generate(), build_dir(), self_test()
- `marola-ml/scripts/app-image.sh`: app-image.sh script, marola-ml/scripts/app-image.sh, image(), self_test()
- `marola-ml/scripts/benchmark.sh`: benchmark.sh script, newest(), run(), self_test(), b()
- `marola-ml/scripts/benchmark_gate.py`: parse_tables(), newest(), metric(), check(), question_problems(), self_test(), main()
- `marola-ml/scripts/corpus-fetch.sh`: corpus-fetch.sh script, marola-ml/scripts/corpus-fetch.sh, fetch(), self_test(), release(), run_fetch()
- `marola-ml/scripts/marola-sea-pull.sh`: marola-sea-pull.sh script, owner_from_remote(), name_prefix(), hf_ref(), hf_repo(), quants_from_json(), quants_in_repo(), self_test(), ok()
- `marola-ml/scripts/resources-fetch.sh`: resources-fetch.sh script, fetch(), self_test(), release(), run_fetch()
- `marola-ml/scripts/sea-release.sh`: sea-release.sh script, valid(), bump(), releasable(), newer(), latest(), fail()
- `marola-ml/sea/ask.py`: latest(), download(), generate(), self_test(), main()
- `marola-ml/sea/finetune_tucano.py`: blocks(), pick_device(), dtype_for(), train(), self_test(), main()
- `marola-ml/sea/fork_check.py`: rows(), body(), check(), self_test(), main()
- `marola-ml/sea/nanogpt/bench.py`: get_batch()
- `marola-ml/sea/nanogpt/data/openwebtext/prepare.py`: openwebtext/prepare.py, process()
- `marola-ml/sea/nanogpt/data/shakespeare/prepare.py`: shakespeare/prepare.py
- `marola-ml/sea/nanogpt/data/shakespeare_char/prepare.py`: shakespeare_char/prepare.py, encode(), decode()
- `marola-ml/sea/nanogpt/model.py`: LayerNorm, .__init__(), .forward(), CausalSelfAttention, MLP, Block, GPTConfig, GPT, .get_num_params(), ._init_weights(), .crop_block_size(), .from_pretrained(), .configure_optimizers(), .estimate_mfu(), .generate()
- `marola-ml/sea/nanogpt/train.py`: get_batch(), estimate_loss(), get_lr()
- `marola-ml/sea/package.py`: repo_commit(), card(), checksums(), write(), self_test(), main()
- `marola-ml/sea/prepare_corpus.py`: read_corpus(), encode(), write(), self_test(), main()
- `marola-ml/sea/publish_hf.py`: sea/publish_hf.py, verify(), publish(), self_test(), main()

## marola-oods

- `marola-oods/scripts/app-image.sh`: app-image.sh script, marola-oods/scripts/app-image.sh, image(), self_test()
- `marola-oods/scripts/oods-tree-check.sh`: oods-tree-check.sh script, allowed(), check(), self_test()

## marola-research

- `marola-research/format/title.lua`: Header()
- `marola-research/scripts/citation.py`: marola-research/scripts/citation.py, orcid_ok(), errors(), dois(), quote(), cff(), self_test(), main()
- `marola-research/scripts/doi-check.sh`: doi-check.sh script, value(), need()
- `marola-research/scripts/logos.sh`: logos.sh script
- `marola-research/scripts/md-quality.sh`: md-quality.sh script, files(), prose(), self_test(), fail(), web()
- `marola-research/scripts/notes-check.sh`: notes-check.sh script, err()
- `marola-research/scripts/override-check.py`: load(), reuse_licences(), check(), self_test(), main()
- `marola-research/scripts/release.sh`: marola-research/scripts/release.sh, release.sh script, fail()
- `marola-research/scripts/site-index.sh`: site-index.sh script, esc(), field()
- `marola-research/scripts/skills.sh`: skills.sh script, rows(), api()
- `marola-research/tools/content/src/research_content_ffi.mjs`: read(), write(), markdown_files(), revision(), arguments_()

## marola-site

- `marola-site/scripts/board-schema.sh`: board-schema.sh script, image(), extract(), check(), self_test(), run()
- `marola-site/scripts/br-proxy-node.sh`: br-proxy-node.sh script, say(), die(), compose(), need_docker(), near(), in_tailscale(), joined(), up(), down(), ts_json(), state(), status(), via(), check(), ok(), bad()
- `marola-site/scripts/br-proxy-preflight.sh`: br-proxy-preflight.sh script, say(), fail(), target(), pool(), preflight(), self_test(), check(), run(), rc()
- `marola-site/scripts/br-proxy.sh`: br-proxy.sh script, say(), warn(), upstream(), hosts(), config(), jvm_options(), answered(), probe(), stop(), start(), self_test(), check()
- `marola-site/scripts/i18n_bundle.py`: ParseError, parse(), arg_names(), serialize(), pseudo(), load(), validate(), bundle(), run(), self_test(), main()
- `marola-site/scripts/mapbox_config.sh`: mapbox_config.sh script, write(), self_test(), run()
- `marola-site/scripts/members_build.py`: MembersError, parse(), initials(), sniff(), avatar_files(), sync_avatars(), get_avatar(), render(), fetch(), run(), self_test(), main()
- `marola-site/scripts/mip-trailer-check.sh`: mip-trailer-check.sh script, pushed_ranges(), check(), self_test(), g(), c()
- `marola-site/scripts/news_build.py`: PostError, inline(), long_date(), release_card(), words(), reading_minutes(), render(), build(), run(), self_test(), main()
- `marola-site/scripts/redirect_check.js`: fs, path, vm, PAGE, scripts, ok(), visit()
- `marola-site/scripts/site-data-push.sh`: marola-site/scripts/site-data-push.sh, site-data-push.sh script, push(), self_test()
- `marola-site/scripts/site_check.js`: fs, path, vm, ROOT, APP, FLOW, SCHEMA, BOARD, BOARD_V1, INDEX, ABOUT, NEWS, SUPPORT, MEMBERS, CONTACT, I18N_JS, UI, CATALOGS, plain(), ok(), KECCAK_RC, KECCAK_ROT, keccak256Hex(), eip55(), typeName(), validate(), El, .constructor(), .addEventListener(), .fire(), .setAttribute(), .getAttribute(), .removeAttribute(), .querySelector(), .querySelectorAll(), .closest(), IDS, LAYER_KEYS, flowPanel(), makeMapbox(), makeAudioContext(), stubStorage(), runPage(), pageDom(), runUi(), ZONES, validAreaEntry(), areasRaw, validAreas, areaIds, getCurrentPosition(), leaks()
- `marola-site/scripts/site_live_check.py`: fetch(), check_board(), check_site(), self_test(), main()
- `marola-site/scripts/stamp_site_version.sh`: stamp_site_version.sh script
- `marola-site/site/static/app.js`: esc(), tx(), num(), fmt(), shortDate(), word(), colour(), getCss(), band(), fetchJson(), haversineKm(), param(), shown(), isPast(), status(), loadAreas(), areaCode(), renderInsta(), selectArea(), renderDays(), selectDay(), ensureMap(), mapNote(), reducedMotion(), addCoastline(), firstSymbolLayer(), hoverTip(), domMarker(), flowPoints(), renderFlow(), rasterDay(), renderRaster(), icon(), compass(), waveIcon(), hourEntry(), noWaterData(), waterSummary(), waterDotClass(), wavesText(), aspectsHtml(), trailColour(), trailTooltipHtml(), addTrailLayer(), renderTrails(), facilitiesHtml(), render(), renderMarkers(), renderHourLabel(), ranked(), renderList(), select(), closeCard(), beachByName(), condLabel(), renderWaterPoints(), waterDrop(), addWaterPoint(), noteText(), notesOf(), renderCard(), renderFooter(), startWaveSound(), fail(), enabled()
- `marola-site/site/static/chat.js`: addMessage(), ask()
- `marola-site/site/static/flow.js`: mercX(), mercY(), lonOf(), latOf(), buildField(), sample(), at(), hexRgb(), rampPixels(), program(), shader(), texture(), layer(), self, setUp(), .setKind(), .setData(), .kind(), .field(), .onAdd(), .onRemove(), uploadField(), view(), spawn(), particles(), step(), buildLines(), .render()
- `marola-site/site/static/ui.js`: match(), resolveLang(), parse(), word(), expect(), message(), argument(), intlLang(), number(), format(), lookup(), t(), has(), applyLang(), setParam(), readStored(), writeStored(), setLang()

## marola-skills

- `marola-skills/scripts/refresh.py`: kind_of(), front_matter(), GitHub, .__init__(), .get(), list_repos(), one_repo(), blob_text(), read_lock(), apply_locks(), scan_repo(), refresh(), lock_status(), annotate(), render(), stars(), gain(), render_curated(), oods_adoption(), render_oods(), splice(), load(), main(), self_test()

