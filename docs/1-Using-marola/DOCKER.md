# Docker

The same pipeline on a machine that has Docker and nothing else: no Nix, no sbt, no Ollama install
(MIP-0008). Moved from [Run it locally](RUN-LOCALLY.md) §10. How the images are built, every tag,
the compose profiles and the native build are marola-app's
[Images](https://docs.marola.dev/5-Repos/marola-app/3-development/#images); flags and variables are
its [CLI](https://docs.marola.dev/5-Repos/marola-app/4-reference_cli/) and
[configuration](https://docs.marola.dev/5-Repos/marola-app/4-reference_config/) references.

## Log in to GHCR

The image is `ghcr.io/marola-dev/marola-app`. Until the package is made public (MIP-0065 §4.3),
pulling needs `docker login ghcr.io` with a GitHub token that has `read:packages`, for example:

```bash
gh auth token | docker login ghcr.io -u <your-github-user> --password-stdin
```

`gh auth refresh -s read:packages` adds the scope to the gh CLI's token if it lacks it.

## Run the image

`jvm` is the CLI on a Java 25 runtime, with the pinned corpus inside, and follows `main`; with no
arguments it runs `--brief`. Against an Ollama already running on the host:

```bash
docker run --rm --network host ghcr.io/marola-dev/marola-app:jvm --brief --lat -27.6733 --lon -48.47
docker run --rm --network host -e MAROLA_LOCAL_LLM_MODEL=llama3.2:1b \
  ghcr.io/marola-dev/marola-app:jvm --summarize --lat -27.6733 --lon -48.47
```

`--network host` lets the container reach the host's `localhost:11434`. The first command needs no
model; the second needs the one you pulled in [Run it locally](RUN-LOCALLY.md) §2.

`native` is the same CLI compiled ahead of time by GraalVM: one executable, no JVM, amd64 only,
same flags. The MCP server is not in it; it stays on `jvm`.

## Ollama in a container too: compose

marola-app's
[`docker-compose.yml`](https://github.com/marola-dev/marola-app/blob/main/docker-compose.yml) runs
the `jvm` image with an Ollama sidecar, and pulls the model once into a named volume:

```bash
# in a marola-app checkout
docker compose run --rm marola --brief --lat -27.6733 --lon -48.47                            # no LLM
docker compose --profile ollama run --rm marola --summarize --lat -27.6733 --lon -48.47       # + draft and reviewer
docker compose --profile local run --rm marola-local --summarize --lat -27.6733 --lon -48.47  # the marola-llama3.2 variant
```

The `ollama` profile pulls `MAROLA_LOCAL_LLM_MODEL` (`llama3.2`, 2 GB, by default);
`MAROLA_LOCAL_LLM_MODEL=llama3.2:1b` in your shell or `.env` picks the small model instead. `.env`
is read when present and never copied into the image. The profile pulls no embedding model, so the
corpus paths (`--ask`) fail there until marola-dev/marola-app#12 is fixed.

The `local` profile runs marola-ml's `ghcr.io/marola-dev/marola-ml:local`: Ollama with the
`marola-llama3.2` variant ([Ask the ocean notes](ASK-THE-OCEAN-NOTES.md#the-marola-model-variant))
already inside. It only advances when the benchmark clears marola-ml's gate; each candidate also
keeps a `local-<sha>` tag, promoted or not.

**Built with Llama.** `:local` redistributes Meta's Llama 3.2 weights under the [Llama 3.2
Community License](https://www.llama.com/llama3_2/license/); the agreement and the Acceptable Use
Policy ship inside the image (`ollama show marola-llama3.2 --license`).
