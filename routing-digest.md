# marola routing digest

## Wiring

| Artifact | Published by | Pinned in | Read by |
|---|---|---|---|
| `ghcr.io/marola-dev/marola-app` image | marola-app `ci.yml` (on a push to `main`) | marola-site `marola-image`, marola-ml `marola-image`, marola-oods `marola-image` | marola-site `scripts/board-schema.sh`, marola-ml `scripts/app-image.sh`, marola-app `docker-compose.yml`, marola-oods `scripts/app-image.sh` |
| `ghcr.io/marola-dev/marola-ml` image | marola-ml `docker-local.yml` (on a push to `main` touching 10 paths) | — | marola-app `docker-compose.yml` |
| `<tag>-v<tag>.pdf` release asset | marola-research `release.yml` (on a `*-v[0-9]*.[0-9]*.[0-9]*` tag) | — | — |
| `marola-corpus-<tag>.tar.gz` release asset | marola-corpus `release.yml` (on a `v*` tag) | marola-ml `corpus.version`, marola-app `corpus.version` | marola-ml `scripts/corpus-fetch.sh`, marola-app `build.sbt`, marola-app `scripts/corpus-fetch.sh` |
| `ml-resources-<tag>.tar.gz` release asset | marola-app `release.yml` (on a `v*` tag) | marola-ml `resources.version` | marola-ml `scripts/resources-fetch.sh` |
| `api-docs` branch | marola-ml `ci.yml` (`api-docs.yml@v0.20.4`, on a push to `main`), marola-app `ci.yml` (`api-docs.yml@v0.20.4`, on a push to `main`) | — | — |
| `site-data` branch of marola-site | marola-app `ci.yml` (on a push to `main`), marola-app `docker-smoke.yml` (on a schedule) | — | marola-site `site.yml` |
| PRs into each repo in `.github/consumers.txt` | marola-devkit `bump-consumers.yml`, marola-devkit `bump-merge.yml` (on a schedule) | — | marola, marola-app, marola-site, marola-ml, marola-corpus, marola-oods, marola-skills |
| PRs into marola-app: `recommendation_prompt.json`, `review_prompt.json` | marola-ml `compile-prompt.yml` (by hand) | — | marola-app |
| a marola-devkit tag | marola-devkit | marola `flake.lock` v0.20.4, marketplace v0.20.4; marola-site `flake.nix` v0.20.4, marketplace v0.20.4; marola-corpus `flake.nix` v0.20.4, marketplace v0.20.4; marola-ml `flake.nix` v0.20.4, marketplace v0.20.4; marola-app `flake.lock` v0.20.4, marketplace v0.20.4; marola-oods `flake.nix` v0.20.4, marketplace v0.20.4; marola-skills `flake.lock` v0.20.4, marketplace v0.20.4 | `agents-check.yml` v0.20.4 (marola marola-site marola-corpus marola-ml marola-app marola-oods marola-skills); `api-docs.yml` v0.20.4 (marola-ml marola-app); `ci-short-circuit.yml` v0.20.4 (marola marola-site marola-corpus marola-ml marola-app marola-oods marola-skills); `gemini-review.yml` v0.20.4 (marola marola-site marola-corpus marola-ml marola-app marola-oods); `labels-sync.yml` v0.20.4 (marola marola-site marola-corpus marola-ml marola-app marola-oods marola-skills); `notify-devkit.yml` v0.20.4 (marola marola-site marola-corpus marola-ml marola-app marola-oods marola-skills marola-devkit); `notify-umbrella.yml` v0.20.4 (marola-site marola-corpus marola-ml marola-app marola-oods marola-skills); `pr-body.yml` v0.20.4 (marola marola-site marola-corpus marola-ml marola-app marola-oods marola-skills); `python-ci.yml` v0.20.4 (marola-site marola-ml marola-app marola-skills); `scala-ci.yml` v0.20.4 (marola-app); `skills-update.yml` v0.20.4 (marola marola-site marola-corpus marola-ml marola-app marola-oods); `static-ci.yml` v0.20.4 (marola marola-site marola-corpus marola-ml marola-app marola-oods) |

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

- `docs/assets/logo-v2/gen.js`: fs, out(), path, {chromium}, C, ro(), Xo(), Yo(), ri(), tt(), Xi(), Yi(), bands, N(), E, add(), bandExpr(), P0, P1, o0, lipPts, ck, state, draw(), serialize()
- `docs/assets/sponsors/shot.js`: path, {chromium}
- `scripts/docs_redirect_check.js`: fs, path, vm, PAGE, scripts, ok(), visit()
- `scripts/marola-tools`: marola-tools script, MAROLA_ROOT
- `tools/src/Main.scala`: src/Main.scala, Main, .main(), .usage()
- `tools/src/ci/Digest.scala`: Digest, Store, .itemsDir(), .indexPath(), .init(), .itemFiles(), .cachedIds(), .writeIndex(), .store(), .storeOf(), .arxivStore(), .awesomeStore(), .squash(), .children(), .text(), .parseAtom(), .parseSearchResponse(), .get(), .cache(), .summary(), .arxiv(), .awesome(), ArxivCommand, .run(), AwesomeCommand, .arxivCli(), .awesomeCli()
- `tools/src/ci/GhBilling.scala`: GhBilling, .isBlank(), .stripChars(), .hostBlock(), .scopes(), .hasUserScope(), .foundScopes(), .githubScopes(), .missingUserScopeMessage(), .envTokenMessage(), .classifyError(), .decodeItem(), .shape(), GhResult, .text(), Outcome, .plannedCall(), .fromText(), .live(), .run(), .execute(), .cli()
- `tools/src/ci/Model.scala`: ci/Model.scala, Step, Job, StepTally, .of(), BadgeLabel, BadgeColor, BadgeFileName, Badge, .toJson(), .noData(), .ci(), .tests(), .loc(), .thousands(), .versions(), BadgeFile, .shownIn(), ProjectFile, .directive(), RepoStatsSettings, RepoStatsConfig, JqValue, .tsv(), .compare(), UsageItem, Row, name(), includedMinutes(), Plan, .parse(), BillingNames, BillingConfig, BillingPeriod, .query(), .usagePath(), .shown(), render(), Usage, .columns(), has(), shown(), TokenScopes, Query, .relevanceScore(), Candidate, Paper, .id(), .idFromUrl(), RepoCandidate, .key(), RecordField, Match, Hit, .map(), .collect(), DigestKind, DigestSources, DigestConfig, .now(), DigestSummary, .render()
- `tools/src/ci/RepoStats.scala`: RepoStats, .decodeJob(), .parseCloc(), .fetchJobs(), .call(), .clocCode(), .write(), .badges(), .run(), Write, .cli()
- `tools/src/docs/AgentsReposCheck.scala`: AgentsReposCheck, .findings(), .check(), .run(), .cli()
- `tools/src/docs/ClaudeProject.scala`: ClaudeProject, .check(), .checkFile(), .run(), .cli()
- `tools/src/docs/ContentModel.scala`: ReadingLang, .of(), Minutes, .from(), .ceil(), value(), Prose, .words(), .isAlphanumeric(), .isFenceChar(), .scan(), ReadingEstimate, .summary(), ReadingBadge, .line(), .place(), ReadingText, .lines(), DrawingSize, BBox, .grow(), CropBox, .viewBox(), ArchifySource, DeliverReport, .errors(), FigureTools, FigureWork, .dir(), MermaidFence, .isMermaid(), .needsPin(), .pinnedCode(), .pin(), PinnedPages, PinHit, .render(), render(), AgentsFinding, ProjectRoutine, message(), ProjectConfig, .routines(), .check()
- `tools/src/docs/DocLinks.scala`: DocLinks, .regex(), text(), .allMatches(), .firstMatch(), .subRewrite(), .sub(), .onDisk(), .existsAt(), .onDiskTree(), .readUtf8(), .ioMessage(), .fenced(), .unique(), .readmeAnchors(), Rewriter, .fail(), .readmeAnchor(), .github(), .target(), .pinOwnGithub(), .relative(), .bracketed(), .inlineLinks(), .htmlLinks(), .prose(), .rewrite(), .stripAngles(), .links(), .mount(), .publish(), .adrIndex(), .run(), .printLinks(), .spreadLinks(), .cli()
- `tools/src/docs/FetchApiDocs.scala`: FetchApiDocs, .fetch(), .unpack(), .run(), .cli()
- `tools/src/docs/Figure.scala`: Figure, .sizeStyle(), .frame(), .whole(), .crop(), .viewBoxSize(), .bbox(), .deliverReport(), .deliverErrors(), .archifySource(), .realpath(), .draw(), .run(), .cli()
- `tools/src/docs/MermaidFont.scala`: MermaidFont, .readUtf8(), .pinDir(), .pin(), .run(), .cli()
- `tools/src/docs/Mkdocs.scala`: Mkdocs, .pickRuntime(), .stageDocs(), .checkIndex(), .runMode(), .build(), .mkdocsContainer(), .containerExit(), .copyOut(), .withComposeDown(), .run(), .cli()
- `tools/src/docs/PrepareDocs.scala`: PrepareDocs, .said(), .excludeDocs(), .fetchFlakeLockSource(), .lockedRev(), .checkoutSha(), .mountRepo(), .brokenLinks(), .exists(), .checkLinks(), .captureStdout(), .eachOk(), .prepare(), .run(), .cli()
- `tools/src/docs/ReadingTime.scala`: ReadingTime, .readmes(), .update(), .run(), .readingTime(), .cli()
- `tools/src/docs/ReposManifest.scala`: ReposManifest, .parse(), Reading, .repos(), .value(), .parseText(), .read()
- `tools/src/docs/SiteLinks.scala`: SiteLinks, .regex(), .allMatches(), .forwardPrefixes(), .resolve(), .htmlPages(), .readOrEmpty(), .checkHrefs(), .checkOldSitemap(), .run(), .cli()
- `tools/src/docs/SiteModel.scala`: SiteModel, .regex(), Names, EnvVar, RemoteTemplate, .from(), url(), .isolatedGitEnv(), ApiDocsConfig, PrepareConfig, MkdocsConfig, RepoSource, Repo, Checkout, message(), problem(), Landing, .check(), .builtFrom(), .buildJson(), ListedLink, .target(), .checked(), .candidate(), .broken(), .parse(), Context, .docsMount(), .outPath(), .siteHref(), .isWhitespace(), Fence, .closedBy(), .trimSlashes(), .excluded(), Rewrite, .map(), .flatMap(), .clean(), .concat(), Link, .hash(), .after(), .suffix(), reason(), line(), RepoTree, LinkTarget, .of(), .underDocs(), .inRepo(), AdrRow, .line(), .first(), .number(), .read(), .handWritten(), .adrIndexPage(), word(), composeFiles(), Stop, .fromContainer(), .cksum(), .projectName(), .imageTag(), .servePath(), exitCode(), notice(), Move, .forward(), .longestFirst(), LinkCheck, OldSitemap, .empty(), SiteLinksVerdict, .dropped(), .emptySitemap(), .ok(), .errors(), .summary(), Stripped, StripTally, ApiDocsPage, .hasExternalScript(), .replaceFirst(), .insertBefore(), .withShim(), .strip()
- `tools/src/docs/StripScripts.scala`: StripScripts, .htmlFiles(), .readOrNone(), .check(), .processDir(), .process(), .run(), .cli()
- `tools/src/publishing/Citation.scala`: Citation, Config, .in(), .validate(), .writeJson(), .readText(), .readJson(), .fail(), .run(), .wanted(), .generate(), .add(), .main(), Command, .citation(), AddCommand, .cli()
- `tools/src/publishing/MipGraph.scala`: MipGraph, Config, .in(), .lower(), .parseMip(), .statusOf(), .readUtf8(), .filesIn(), .loadMips(), .renderArchify(), .sourcePaths(), .firstFile(), .parallelCheck(), .generate(), .write(), .attempt(), .run(), .spreadParallel(), .cli()
- `tools/src/publishing/Model.scala`: publishing/Model.scala, CitationFiles, PersonName, .value(), .parse(), Orcid, .url(), .valid(), Doi, LanguageCode, License, .fromZenodo(), UploadType, .fromId(), Relation, ContributorType, Person, PersonRequest, value(), Identifier, RelatedIdentifier, ZenodoMetadata, .repositoryCode(), Zenodo, Key, Scheme, CitationText, .quoted(), .words(), .decodeEntities(), .plain(), .length(), .wrap(), .splitOnce(), .renderCff(), .initials(), .renderRefs(), .splice(), ReleaseRepo, SemVer, .tag(), .validTag(), RawDiffEntry, .touchesGitlink(), message(), exitCode(), GuardVerdict, .compared(), MipStatus, Mip, .nodeId(), .name(), .statusWord(), Edge, PlacedNode, .x(), .y(), MipDependencies, .edges(), .connected(), .blockersOf(), .blockedBy(), .reachable(), .settle(), .layout(), ParallelVerdict, .safe(), .exitCode(), .lines(), .of(), MipReadme, .renderBlock(), .graphBlock(), .withBlock()
- `tools/src/publishing/Release.scala`: Release, .contentLines(), .guard(), .refuse(), .release(), .checkAndTag(), .tagAndPush(), .cli()
- `tools/src/publishing/ZenodoJson.scala`: ZenodoJson, .str(), .strOpt(), .list(), .truthy(), .errorsOf(), .decode(), .person(), .relatedIdentifier(), .addPerson()
- `tools/src/routing/GraphSvg.scala`: GraphSvg, .str(), .array(), .load(), .keeper(), .draw(), .attempt(), .run(), .cli()
- `tools/src/routing/Model.scala`: routing/Model.scala, QuestionType, .apply(), .of(), name(), Question, Verdict, .parse(), BenchLine, Score, .add(), TypeScore, Bench, .rightOf(), .toJson(), .tally(), PublishedBench, render(), Drop, .between(), BuildInfo, .stamped(), DigestNode, .line(), Digest, .repoOf(), .basename(), Entry, .render(), .capped(), WiringBlock, .extract(), FileSize, Manifest, Given, Args, .path(), BundleFile, Repo, .ofSource(), Node, Edge, Point, RawNode, RawLink, Graph, Figure, .fnv(), .unit(), .anchor(), .layout(), .fixed1(), .plain(), .escapeText(), .caption()
- `tools/src/routing/RoutingBundle.scala`: RoutingBundle, .nonEmptyStr(), .asCount(), .pyStr(), .parseJson(), .readJson(), .questionsOf(), .benchLines(), .publishedBench(), .locationText(), .digestNodes(), .scores(), .digest(), .wiringBlock(), .assemble(), .write(), .checkSize(), .attempt(), .run(), .cli()
- `tools/src/shared/Cli.scala`: Cli, .run(), .report()
- `tools/src/shared/Conclusion.scala`: wire(), ran(), passed(), Conclusion, .parse()
- `tools/src/shared/GitHubNames.scala`: GitHubNames
- `tools/src/shared/GitNames.scala`: GitNames
- `tools/src/shared/Invocation.scala`: Invocation, .repoRoot(), .path(), .current()
- `tools/src/shared/Jq.scala`: Jq, Documents, .readable(), .values(), .skipBlanks(), .documentEnd(), .field(), .iterable(), .items(), .truthy(), .orElse(), .text(), .tsvString(), .tsv(), .money(), .number()
- `tools/src/shared/Proc.scala`: Proc, .variable(), .which(), .call(), .status(), .quiet(), .capture(), .captureQuiet(), .must(), .mustCapture(), .toplevel(), .submodules(), Result, .exact(), Abort, .exitCode(), .chomp(), .lines(), .firstWordAndRest(), .say(), .err()
- `tools/src/shared/RepoSlug.scala`: RepoSlug, .apply(), .inOrg(), .fromGitHubUrl(), .marolaDev(), value(), web()
- `tools/src/shared/ReposDoc.scala`: ReposDoc
- `tools/src/shared/Sha.scala`: Sha, .apply(), .parse(), value(), short()
- `tools/src/shared/Submodule.scala`: Submodule, .urlKey(), .fromConfig()
- `tools/src/shared/Util.scala`: Util, .readLossy(), .decodeUtf8(), .readUtf8(), .osError(), .hasExtension(), .trimUnicode(), .readJson(), .field(), .dumps(), .render(), .container(), .formatNumber(), .quote(), .pyrepr(), .pyreprStr(), .splitlinesKeepends(), .splitlines(), .unquote(), .urlencode(), .quotePlus(), .urlPath(), posix, .normpath(), .dirname(), .join(), .extension(), .relpath(), .fnmatchcase(), .fnmatchRegex()
- `tools/src/submodules/GitHub.scala`: GitHub, .openPr(), .checkRuns(), .statuses(), .ciOk(), .hasComment(), .requiredField()
- `tools/src/submodules/Model.scala`: submodules/Model.scala, PrNumber, .apply(), value(), WiringDoc, .outsideMarkers(), Moved, .compareUrl(), .tsvRow(), Bump, .isEmpty(), .names(), .commitMessage(), .prBody(), BumpFile, RollingPr, Pr, .held(), MergeRun, CheckStatus, .parse(), Check, .failed(), Checks, .pending(), .ciPassed(), .waitingOn(), TreeEntry, .isGitlink(), .isPointer(), message(), ScopeProblem, .of(), onDefaultBranch(), render(), CompareStatus, Refusal, .body(), .headMoved(), .checksFailed(), Plan, .fields(), .render(), SiteData, Tries, .exhausted(), .backoffSeconds(), .from(), PushRun, arg()
- `tools/src/submodules/PointerSync.scala`: PointerSync, .bump(), .move(), .commit(), .runIn(), .run(), .cli()
- `tools/src/submodules/PointerSyncMerge.scala`: PointerSyncMerge, .pullRef(), .gitmodulesAt(), .scopeProblem(), .wiringOnly(), .treeEntries(), .pointerProblem(), .compareStatus(), .readField(), .runIn(), Merge, .writePlan(), .refuse(), .alreadySaid(), .checkAndMerge(), .checksPassed(), .inScope(), .merge(), .run(), .cli()
- `tools/src/submodules/SiteDataPush.scala`: SiteDataPush, .push(), .rejected(), .run(), .cli()

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
- `marola-app/scripts/strip_external_scripts.py`: strip(), check(), process(), self_test(), main()

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
- `marola-research/scripts/citation.py`: orcid_ok(), errors(), dois(), quote(), cff(), self_test(), main()
- `marola-research/scripts/doi-check.sh`: doi-check.sh script, value(), need()
- `marola-research/scripts/logos.sh`: logos.sh script
- `marola-research/scripts/md-quality.sh`: md-quality.sh script, files(), prose(), self_test(), fail(), web()
- `marola-research/scripts/notes-check.sh`: notes-check.sh script, err()
- `marola-research/scripts/override-check.py`: load(), reuse_licences(), check(), self_test(), main()
- `marola-research/scripts/release.sh`: release.sh script, fail()
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

