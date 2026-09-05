# syntax=docker/dockerfile:1.7
# marola — MIP-0008 §5.1: one Dockerfile, several targets. `docker build --target <target> .`
#
#   builder  sbt cli/assembly on Temurin 25 → /marola.jar (never shipped)
#   jvm      Temurin 25 JRE (alpine) + the jar — `docker run --rm ghcr.io/h0ffmann/marola:jvm --brief --lat … --lon …`
#   dev      the literal `nix develop`, for people without Nix: `docker run -it … marola:dev` drops you in the dev shell
#   (native-build / native — the GraalVM binary — arrive with task 3 of docs/mips/MIP-0008.tasks.md)
#
# Every base image tag below was checked on its registry on 2026-09-05 (sizes in the tasks file,
# decision 2). Secrets are never copied: `.dockerignore` is an allowlist and `.env` is not on it —
# mount it (`docker compose` does) or pass `-e MAROLA_…`. Lint: `just quality` runs hadolint.

ARG SBT_IMAGE=sbtscala/scala-sbt:eclipse-temurin-25.0.4_7_1.13.0_3.8.4
ARG JRE_IMAGE=eclipse-temurin:25-jre-alpine
ARG NIX_IMAGE=nixos/nix:latest

# --- builder ---------------------------------------------------------------------------------
# Built once, on the build platform: the jar is the same bytes for every target platform, so a
# multi-platform build of `jvm` does not run sbt per architecture.
FROM --platform=$BUILDPLATFORM ${SBT_IMAGE} AS builder
WORKDIR /src
# Build definition first, sources later: dependency resolution is the slow, rarely-changing layer.
COPY build.sbt ./
COPY project/build.properties project/plugins.sbt project/
RUN sbt --batch update
COPY core core
COPY local local
COPY azure azure
COPY cli cli
RUN sbt --batch cli/assembly \
 && cp cli/target/scala-3.9.0/marola-cli-assembly-*.jar /marola.jar

# --- jvm -------------------------------------------------------------------------------------
FROM ${JRE_IMAGE} AS jvm
RUN addgroup -S marola && adduser -S marola -G marola \
 && mkdir -p /app/data && chown -R marola:marola /app
WORKDIR /app
COPY --from=builder /marola.jar /app/marola.jar
# What the CLI reads from the working directory: the RAG corpus (`--ask`), the site's areas and
# page (`--site`). Runtime output (`data/`: knowledge index, sightings, benchmarks) is a volume.
COPY --chown=marola:marola knowledge /app/knowledge
COPY --chown=marola:marola site/areas.json site/board.schema.json /app/site/
COPY --chown=marola:marola site/static /app/site/static
USER marola
VOLUME ["/app/data"]
# One CLI run at a time, short-lived: the serial GC and a small heap beat the defaults here.
ENV JAVA_TOOL_OPTIONS="-XX:+UseSerialGC -XX:MaxRAMPercentage=75 -XX:TieredStopAtLevel=1"
ENTRYPOINT ["java", "-jar", "/app/marola.jar"]
CMD ["--brief"]
# The MCP server is the other main class in the same jar (build.sbt):
#   docker run --rm -i --entrypoint java ghcr.io/h0ffmann/marola:jvm -cp /app/marola.jar marola.agent.SwimConditionsMcpServer

# --- dev -------------------------------------------------------------------------------------
# `nix develop` frozen into an image: JDK 25, sbt, just, ollama and the rest of flake.nix, for a
# machine that has Docker but not Nix. Big (a few GB) and rebuilt only when the flake changes.
FROM ${NIX_IMAGE} AS dev
RUN echo "experimental-features = nix-command flakes" >> /etc/nix/nix.conf
WORKDIR /src
COPY flake.nix flake.lock ./
RUN nix develop --command true
COPY . .
ENTRYPOINT ["nix", "develop", "--command"]
CMD ["just", "--list"]
