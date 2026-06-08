# ─── Stage 1: Build ───────────────────────────────────────────────────────────
FROM eclipse-temurin:25-jdk AS builder

WORKDIR /workspace

COPY mvnw mvnw.cmd ./
COPY .mvn .mvn
COPY pom.xml .
RUN chmod +x mvnw

COPY common/pom.xml           common/
COPY feedback-service/pom.xml feedback-service/

RUN ./mvnw dependency:go-offline -pl common,feedback-service -am --no-transfer-progress -q

COPY common/src           common/src
COPY feedback-service/src feedback-service/src

RUN ./mvnw clean package -pl common,feedback-service -am -DskipTests --no-transfer-progress -q

# ─── Stage 2: Runtime ─────────────────────────────────────────────────────────
FROM eclipse-temurin:25-jre

WORKDIR /app

RUN groupadd --system --gid 1001 spring \
 && useradd  --system --uid 1001 --gid 1001 spring

COPY --from=builder /workspace/feedback-service/target/feedback-service-*.jar app.jar

USER spring

# feedback-service listens on port 8084
EXPOSE 8084

ENTRYPOINT ["java", \
  "-XX:+UseContainerSupport", \
  "-XX:MaxRAMPercentage=75.0", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "-jar", "app.jar"]

