# syntax=docker/dockerfile:1.7
# marola — MIP-0008 §5.1: one Dockerfile, several targets. `docker build --target <target> .`
#
#   builder  sbt cli/assembly on Temurin 25 → /marola.jar (never shipped)
#   jvm      Temurin 25 JRE (alpine) + the jar — `docker run --rm ghcr.io/h0ffmann/marola:jvm --brief --lat … --lon …`
#   dev      the literal `nix develop`, for people without Nix: `docker run -it … marola:dev` drops you in the dev shell
#   native-build  GraalVM native-image over the same jar (never shipped)
#   native   one static-ish binary on distroless — `docker run --rm ghcr.io/h0ffmann/marola:native --brief --lat … --lon …`
#
# Secrets are never copied: `.dockerignore` is an allowlist and `.env` is not on it.
# Base images are literal tags, not ARGs: hadolint's DL3006 does not resolve an ARG default.

# --- builder ---------------------------------------------------------------------------------
# Built once, on the build platform: the jar is the same bytes for every target platform, so a
# multi-platform build of `jvm` does not run sbt per architecture.
FROM --platform=$BUILDPLATFORM sbtscala/scala-sbt:eclipse-temurin-25.0.4_7_1.13.0_3.8.4 AS builder
WORKDIR /src
# Build definition first, sources later: dependency resolution is the slow, rarely-changing layer.
COPY build.sbt ./
COPY project/build.properties project/plugins.sbt project/
RUN sbt --batch update
COPY core core
COPY local local
COPY cli cli
RUN sbt --batch cli/assembly \
 && cp cli/target/scala-3.9.0/marola-cli-assembly-*.jar /marola.jar

# --- jvm -------------------------------------------------------------------------------------
FROM eclipse-temurin:25-jre-alpine AS jvm
# Fixed numeric ids: `USER marola` by name is hadolint DL3066 (a host that bind-mounts /app/data
# cannot resolve the name); 10001 avoids every default host uid and the busybox nobody/65534.
RUN addgroup -S -g 10001 marola && adduser -S -u 10001 -G marola marola \
 && mkdir -p /app/data && chown -R marola:marola /app
WORKDIR /app
COPY --from=builder /marola.jar /app/marola.jar
# What the CLI reads from the working directory: the RAG corpus (`--ask`), the site's areas and
# page (`--site`). Runtime output (`data/`: knowledge index, sightings, benchmarks) is a volume.
COPY --chown=marola:marola knowledge /app/knowledge
COPY --chown=marola:marola site/areas.json site/board.schema.json /app/site/
COPY --chown=marola:marola site/static /app/site/static
USER 10001:10001
VOLUME ["/app/data"]
# One CLI run at a time, short-lived: the serial GC and a small heap beat the defaults here.
# --sun-misc-unsafe-memory-access=allow: same JEP 498 warning as build.sbt's forked run — Scala
# 3.9.0's LazyVals calls Unsafe.objectFieldOffset. See build.sbt for why it is a suppression with
# an expiry date rather than a fix.
ENV JAVA_TOOL_OPTIONS="-XX:+UseSerialGC -XX:MaxRAMPercentage=75 -XX:TieredStopAtLevel=1 --sun-misc-unsafe-memory-access=allow"
ENTRYPOINT ["java", "-jar", "/app/marola.jar"]
CMD ["--brief"]
# The MCP server is the other main class in the same jar (build.sbt):
#   docker run --rm -i --entrypoint java ghcr.io/h0ffmann/marola:jvm -cp /app/marola.jar marola.agent.SwimConditionsMcpServer

# --- native-build ----------------------------------------------------------------------------
# The same jar, compiled ahead of time. The arguments and reachability metadata come from the jar
# itself (cli/src/main/resources/META-INF/native-image/com.marola/marola-cli/), the same ones
# `sbt cli/nativeImage` uses, so the two builds cannot drift. amd64 only: native-image does not
# cross-compile (MIP-0008.tasks.md decision 7). ~45 s and ~4 GB RSS on 32 cores; a few minutes on
# a 4-vCPU runner.
FROM ghcr.io/graalvm/native-image-community:25 AS native-build
WORKDIR /build
COPY --from=builder /marola.jar /build/marola.jar
RUN native-image -jar /build/marola.jar -o /build/marola

# --- native ----------------------------------------------------------------------------------
# distroless base: glibc + CA certificates + tzdata, no shell, non-root — everything the binary
# links against (ldd: libc, libdl, libpthread, librt) and nothing else.
FROM gcr.io/distroless/base-debian12:nonroot AS native
WORKDIR /app
COPY --from=native-build /build/marola /app/marola
COPY --chown=nonroot:nonroot knowledge /app/knowledge
COPY --chown=nonroot:nonroot site/areas.json site/board.schema.json /app/site/
COPY --chown=nonroot:nonroot site/static /app/site/static
VOLUME ["/app/data"]
ENTRYPOINT ["/app/marola"]
CMD ["--brief"]

# --- dev -------------------------------------------------------------------------------------
# `nix develop` frozen into an image: JDK 25, sbt, just, ollama and the rest of flake.nix, for a
# machine that has Docker but not Nix. Big (a few GB) and rebuilt only when the flake changes.
FROM nixos/nix:2.35.2 AS dev
RUN echo "experimental-features = nix-command flakes" >> /etc/nix/nix.conf
WORKDIR /src
COPY flake.nix flake.lock ./
RUN nix develop --command true
COPY . .
ENTRYPOINT ["nix", "develop", "--command"]
CMD ["just", "--list"]
