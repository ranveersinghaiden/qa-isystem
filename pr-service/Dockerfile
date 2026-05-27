# ─── Stage 1: Build ───────────────────────────────────────────────────────────
# Uses the full JDK to compile the project inside the container.
# Common module is built first because all services depend on it.
FROM eclipse-temurin:25-jdk AS builder

WORKDIR /workspace

# Copy Maven wrapper and root POM first — these rarely change so Docker can
# cache this layer and skip re-downloading the maven wrapper on every build.
COPY mvnw mvnw.cmd ./
COPY .mvn .mvn
COPY pom.xml .
RUN chmod +x mvnw

# Copy module POMs (needed for dependency resolution before copying source)
COPY common/pom.xml       common/
COPY pr-service/pom.xml   pr-service/

# Download all dependencies into the local .m2 cache layer.
# This layer is only re-built when pom.xml files change — not on source changes.
RUN ./mvnw dependency:go-offline -pl common,pr-service -am --no-transfer-progress -q

# Copy source trees
COPY common/src     common/src
COPY pr-service/src pr-service/src

# Build and package (tests run in CI, skipped here for image build speed)
RUN ./mvnw clean package -pl common,pr-service -am -DskipTests --no-transfer-progress -q

# ─── Stage 2: Runtime ─────────────────────────────────────────────────────────
# Only the JRE is needed at runtime — much smaller image than the full JDK.
FROM eclipse-temurin:25-jre

WORKDIR /app

# Create a non-root system user and group for security best practice.
# Services should never run as root inside a container.
RUN groupadd --system --gid 1001 spring \
 && useradd  --system --uid 1001 --gid 1001 spring

# Copy the fat JAR from the builder stage
COPY --from=builder /workspace/pr-service/target/pr-service-*.jar app.jar

# Switch to non-root user
USER spring

# pr-service listens on port 8080
EXPOSE 8080

# JVM flags:
#   UseContainerSupport  — respect cgroup memory limits instead of reading host RAM
#   MaxRAMPercentage     — use up to 75% of the container's allocated RAM for heap
#   urandom              — faster entropy source (avoids slow /dev/random blocking)
ENTRYPOINT ["java", \
  "-XX:+UseContainerSupport", \
  "-XX:MaxRAMPercentage=75.0", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "-jar", "app.jar"]

