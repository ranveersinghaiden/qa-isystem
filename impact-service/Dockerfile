# ─── Stage 1: Build ───────────────────────────────────────────────────────────
FROM eclipse-temurin:25-jdk AS builder

WORKDIR /workspace

COPY mvnw mvnw.cmd ./
COPY .mvn .mvn
COPY pom.xml .
RUN chmod +x mvnw

COPY common/pom.xml         common/
COPY impact-service/pom.xml impact-service/

RUN ./mvnw dependency:go-offline -pl common,impact-service -am --no-transfer-progress -q

COPY common/src         common/src
COPY impact-service/src impact-service/src

RUN ./mvnw clean package -pl common,impact-service -am -DskipTests --no-transfer-progress -q

# ─── Stage 2: Runtime ─────────────────────────────────────────────────────────
FROM eclipse-temurin:25-jre

WORKDIR /app

RUN groupadd --system --gid 1001 spring \
 && useradd  --system --uid 1001 --gid 1001 spring

COPY --from=builder /workspace/impact-service/target/impact-service-*.jar app.jar

USER spring

# impact-service listens on port 8081
EXPOSE 8081

ENTRYPOINT ["java", \
  "-XX:+UseContainerSupport", \
  "-XX:MaxRAMPercentage=75.0", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "-jar", "app.jar"]

