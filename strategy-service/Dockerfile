# ─── Stage 1: Build ───────────────────────────────────────────────────────────
FROM eclipse-temurin:25-jdk AS builder

WORKDIR /workspace

COPY mvnw mvnw.cmd ./
COPY .mvn .mvn
COPY pom.xml .
RUN chmod +x mvnw

COPY common/pom.xml           common/
COPY strategy-service/pom.xml strategy-service/

RUN ./mvnw dependency:go-offline -pl common,strategy-service -am --no-transfer-progress -q

COPY common/src           common/src
COPY strategy-service/src strategy-service/src

RUN ./mvnw clean package -pl common,strategy-service -am -DskipTests --no-transfer-progress -q

# ─── Stage 2: Runtime ─────────────────────────────────────────────────────────
FROM eclipse-temurin:25-jre

WORKDIR /app

RUN groupadd --system --gid 1001 spring \
 && useradd  --system --uid 1001 --gid 1001 spring

COPY --from=builder /workspace/strategy-service/target/strategy-service-*.jar app.jar

USER spring

# strategy-service listens on port 8082
EXPOSE 8082

# strategy-service clones the target test repo at startup into a writable temp dir.
# Mount a volume at /tmp if you want the clone to persist between container restarts.
ENTRYPOINT ["java", \
  "-XX:+UseContainerSupport", \
  "-XX:MaxRAMPercentage=75.0", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "-jar", "app.jar"]

