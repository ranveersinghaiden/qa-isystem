# ──────────────────────────────────────────────────────────────────────────────
# QA-ISystem — Custom ARC Runner Image (the "backpack")  · Design doc Part F
#
# ONE image, pre-packed so scale-to-zero pods start instantly. Built by
# QA-ISystem/.github/workflows/qa-runner-image.yml (E.4 — lives in the source repo
# because it compiles this repo's jars), pushed to ghcr.io, pulled by the
# Actions Runner Controller (ARC) scale set.
#
# Baked in (no per-pod setup):
#   • Temurin JDK 25 · git · curl · bash
#   • GitHub Copilot CLI (standalone @github/copilot) + gh CLI — the DEFAULT
#     context-selection / generation engine (aiqa.ai.provider=copilot-cli). The
#     strategy/codegen one-shots spawn `copilot --agent=… -p …`; this MUST be in
#     the image (no host to inherit it from, unlike local dev). Auth creds are NOT
#     baked — mounted at runtime (see "NOT baked" below).
#   • warm ~/.m2 dependency cache (kills Maven re-download on every pod)
#   • 5 service jars at /app/*.jar (impact, strategy, codegen, feedback, pr)
#   • headroom runtime (headroom-ai[proxy] + hnswlib) — proven pattern from
#     strategy-service/Dockerfile (compression handled by the in-cluster
#     headroom Service via AI_BASE_URL; runtime baked so a pod *could* run its
#     own proxy if ever needed).
#   • ARC runner agent layer (provided by the ${ARC_BASE_IMAGE} base).
#
# NOT baked (injected at runtime via K8s Secrets / workflow secrets / env):
#   • GitHub App private key + App id  → qa-control secrets (create-github-app-token)
#   • Copilot CLI auth creds           → K8s Secret mounted at ~/.copilot (one-time
#                                        `copilot` login on a host, like headroom);
#                                        gh auth via GH_TOKEN from a Copilot-entitled
#                                        secret. NEVER baked into the image.
#   • headroom OAuth creds             → K8s Secret on the headroom Service only (C2)
#   • QA_DB_URL (Neon)                 → K8s / workflow secret
#   • AI_BASE_URL                      → plain env (in-cluster headroom Service)
# ──────────────────────────────────────────────────────────────────────────────

# ─── Stage 1: Build all 5 service jars ────────────────────────────────────────
FROM eclipse-temurin:25-jdk AS builder

WORKDIR /workspace

COPY mvnw mvnw.cmd ./
COPY .mvn .mvn
COPY pom.xml .
RUN chmod +x mvnw

# Copy every module POM first so dependency resolution is cached as its own layer.
COPY common/pom.xml           common/
COPY pr-service/pom.xml       pr-service/
COPY impact-service/pom.xml   impact-service/
COPY strategy-service/pom.xml strategy-service/
COPY codegen-service/pom.xml  codegen-service/
COPY feedback-service/pom.xml feedback-service/

# Warm the whole reactor's dependency cache into this layer (the warm ~/.m2).
RUN ./mvnw dependency:go-offline -B --no-transfer-progress -q

# Now the sources.
COPY common/src           common/src
COPY pr-service/src       pr-service/src
COPY impact-service/src   impact-service/src
COPY strategy-service/src strategy-service/src
COPY codegen-service/src  codegen-service/src
COPY feedback-service/src feedback-service/src

RUN ./mvnw clean package -B -DskipTests --no-transfer-progress -q

# ─── Stage 2: Runner image ────────────────────────────────────────────────────
# ARC's runner scale set expects a runner agent in the image. Base off the
# GitHub Actions runner image and layer the JDK + tools on top. Override with
# --build-arg ARC_BASE_IMAGE=... if you maintain your own runner base.
ARG ARC_BASE_IMAGE=ghcr.io/actions/actions-runner:latest
FROM ${ARC_BASE_IMAGE}

USER root
WORKDIR /app

# Temurin JDK 25 (copied from the builder stage so we pin the exact JDK).
COPY --from=builder /opt/java/openjdk /opt/java/openjdk
ENV JAVA_HOME=/opt/java/openjdk
ENV PATH="${JAVA_HOME}/bin:${PATH}"

# git · curl · bash · Python 3 + headroom runtime (hnswlib needs a compiler at
# install time only; build-essential is purged afterwards to keep the image slim).
RUN apt-get update \
 && apt-get install -y --no-install-recommends \
        git curl bash ca-certificates \
        python3 python3-pip python3-dev build-essential \
 && pip3 install --no-cache-dir --break-system-packages "headroom-ai[proxy]" \
 && apt-get purge -y --auto-remove build-essential python3-dev \
 && rm -rf /var/lib/apt/lists/*

# GitHub Copilot CLI (the DEFAULT engine) + gh CLI. The ARC runner base is slim and
# ships neither, so install both explicitly:
#   • Node.js 22 LTS → `npm i -g @github/copilot` provides the standalone `copilot`
#     binary the strategy/codegen one-shots spawn (copilot --agent=… -p …).
#   • gh CLI → required by the copilot-cli provider + PR operations.
# Auth is injected at runtime (mounted ~/.copilot creds / GH_TOKEN) — never baked.
RUN curl -fsSL https://deb.nodesource.com/setup_22.x | bash - \
    && curl -fsSL https://cli.github.com/packages/githubcli-archive-keyring.gpg \
  -o /usr/share/keyrings/githubcli-archive-keyring.gpg \
    && chmod go+r /usr/share/keyrings/githubcli-archive-keyring.gpg \
    && echo "deb [arch=$(dpkg --print-architecture) signed-by=/usr/share/keyrings/githubcli-archive-keyring.gpg] https://cli.github.com/packages stable main" \
  > /etc/apt/sources.list.d/github-cli.list \
    && apt-get update \
    && apt-get install -y --no-install-recommends nodejs gh \
    && npm install -g @github/copilot \
    && rm -rf /var/lib/apt/lists/* \
    && copilot --version || true

# The 5 service jars — stable names the workflows invoke as /app/<svc>.jar.
COPY --from=builder /workspace/impact-service/target/impact-service-*.jar     /app/impact.jar
COPY --from=builder /workspace/strategy-service/target/strategy-service-*.jar /app/strategy.jar
COPY --from=builder /workspace/codegen-service/target/codegen-service-*.jar   /app/codegen.jar
COPY --from=builder /workspace/feedback-service/target/feedback-service-*.jar /app/feedback.jar
COPY --from=builder /workspace/pr-service/target/pr-service-*.jar             /app/pr.jar

# Warm ~/.m2 so any in-pod Maven invocation (target-repo test compile) is offline-fast.
COPY --from=builder /root/.m2 /home/runner/.m2

# Output dir for context-trace job summaries (Part E.1 reads /app/out/...).
RUN mkdir -p /app/out && chown -R runner:runner /app /home/runner/.m2

# ARC injects the runner agent entrypoint from the base image; do NOT override it.
USER runner
