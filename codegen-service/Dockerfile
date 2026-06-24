# ─── Stage 1: Build ───────────────────────────────────────────────────────────
FROM eclipse-temurin:25-jdk AS builder

WORKDIR /workspace

COPY mvnw mvnw.cmd ./
COPY .mvn .mvn
COPY pom.xml .
RUN chmod +x mvnw

COPY common/pom.xml          common/
COPY codegen-service/pom.xml codegen-service/

RUN ./mvnw dependency:go-offline -pl common,codegen-service -am --no-transfer-progress -q

COPY common/src          common/src
COPY codegen-service/src codegen-service/src

RUN ./mvnw clean package -pl common,codegen-service -am -DskipTests --no-transfer-progress -q

# ─── Stage 2: Runtime ─────────────────────────────────────────────────────────
# JDK (not JRE) is required because TestExecutionEngine uses javax.tools.JavaCompiler
# to compile AI-generated test code at runtime.
FROM eclipse-temurin:25-jdk

WORKDIR /app

# Python 3 + pip + build-essential (needed to compile headroom's hnswlib extension)
# curl is used by docker-entrypoint.sh to poll headroom's /health endpoint.
# build-essential is purged after pip install to keep the image slim.
RUN apt-get update \
 && apt-get install -y --no-install-recommends \
        python3 python3-pip python3-dev build-essential curl \
 && pip3 install --no-cache-dir "headroom-ai[proxy]" \
 && apt-get purge -y --auto-remove build-essential python3-dev \
 && rm -rf /var/lib/apt/lists/*

RUN groupadd --system --gid 1001 spring \
 && useradd  --system --uid 1001 --gid 1001 --home /home/spring --create-home spring

COPY --from=builder /workspace/codegen-service/target/codegen-service-*.jar app.jar
COPY codegen-service/docker-entrypoint.sh entrypoint.sh
RUN chmod +x entrypoint.sh

USER spring

# codegen-service listens on port 8083
EXPOSE 8083

# docker-entrypoint.sh starts headroom proxy when HEADROOM_ENABLED=true,
# waits for it to be ready, then hands off to the Java process.
# When HEADROOM_ENABLED=false (default) it is a transparent passthrough.
ENTRYPOINT ["/app/entrypoint.sh"]
CMD ["java", \
  "-XX:+UseContainerSupport", \
  "-XX:MaxRAMPercentage=75.0", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "-jar", "/app/app.jar"]

