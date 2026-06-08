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

RUN groupadd --system --gid 1001 spring \
 && useradd  --system --uid 1001 --gid 1001 spring

COPY --from=builder /workspace/codegen-service/target/codegen-service-*.jar app.jar

USER spring

# codegen-service listens on port 8083
EXPOSE 8083

ENTRYPOINT ["java", \
  "-XX:+UseContainerSupport", \
  "-XX:MaxRAMPercentage=75.0", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "-jar", "app.jar"]

