#!/usr/bin/env bash
# =============================================================================
# start-local.sh -- Start QA-ISystem locally for development / testing
#
# Usage: ./scripts/start-local.sh [OPTIONS]
#   --skip-build      Skip Maven build (use existing JARs in target/)
#   --fresh           Clean Docker volumes before starting
#                     (fixes stale ZooKeeper nodes from previous sessions)
#   --stop            Stop all running services and Docker containers
#   --with-kafka-ui   Also start Kafka UI at http://localhost:8090
#   --help            Print this help message
#
# Environment variables (all optional -- passed through to services):
#   TARGET_REPO_URL       HTTPS URL of target test repo
#   TARGET_REPO_TOKEN     GitHub PAT with repo scope
#   TARGET_REPO_USERNAME  GitHub username for the PAT
#   GITHUB_WEBHOOK_SECRET HMAC secret for GitHub webhooks
#   AI_PROVIDER           copilot-cli (default) | copilot | openai
#   GITHUB_COPILOT_TOKEN  Required when AI_PROVIDER=copilot
#   OPENAI_API_KEY        Required when AI_PROVIDER=openai
#   OPENAI_BASE_URL       Optional OpenAI-compatible endpoint override
#   OPENAI_MODEL          Optional model name (default gpt-4o)
# =============================================================================
set -euo pipefail

# -- Colours ------------------------------------------------------------------
RED='\033[0;31m'; YELLOW='\033[1;33m'; GREEN='\033[0;32m'
CYAN='\033[0;36m'; BOLD='\033[1m'; NC='\033[0m'
info()    { echo -e "${CYAN}[INFO]${NC}  $*"; }
success() { echo -e "${GREEN}[OK]${NC}    $*"; }
warn()    { echo -e "${YELLOW}[WARN]${NC}  $*"; }
error()   { echo -e "${RED}[ERROR]${NC} $*" >&2; }
die()     { error "$*"; exit 1; }
header()  { echo -e "\n${BOLD}${CYAN}== $* ==${NC}"; }

# -- Paths --------------------------------------------------------------------
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
LOG_DIR="${ROOT_DIR}/logs"
MIN_JAVA=25

# -- Flags --------------------------------------------------------------------
SKIP_BUILD=false
FRESH=false
STOP_MODE=false
WITH_KAFKA_UI=false

# -- Service list: name|port|jar-relative-path ---------------------------------
SERVICES=(
  "pr-service|8080|pr-service/target/pr-service-0.0.1-SNAPSHOT.jar"
  "impact-service|8081|impact-service/target/impact-service-0.0.1-SNAPSHOT.jar"
  "strategy-service|8082|strategy-service/target/strategy-service-0.0.1-SNAPSHOT.jar"
  "codegen-service|8083|codegen-service/target/codegen-service-0.0.1-SNAPSHOT.jar"
  "feedback-service|8084|feedback-service/target/feedback-service-0.0.1-SNAPSHOT.jar"
)
INFRA_PORTS=(9092 6379)
SVC_PORTS=(8080 8081 8082 8083 8084)

# -- Arg parsing --------------------------------------------------------------
for arg in "$@"; do
  case "${arg}" in
    --skip-build)    SKIP_BUILD=true ;;
    --fresh)         FRESH=true ;;
    --stop)          STOP_MODE=true ;;
    --with-kafka-ui) WITH_KAFKA_UI=true ;;
    --help|-h)
      sed -n '3,22p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
      exit 0 ;;
    *) die "Unknown option: ${arg}  (use --help)" ;;
  esac
done

# =============================================================================
# STOP MODE
# =============================================================================
if [ "${STOP_MODE}" = true ]; then
  header "Stopping QA-ISystem"
  for entry in "${SERVICES[@]}"; do
    svc="${entry%%|*}"
    jar="$(basename "${entry##*|}")"
    pids=$(pgrep -f "${jar}" 2>/dev/null || true)
    if [ -n "${pids}" ]; then
      kill ${pids} 2>/dev/null || true
      success "Stopped ${svc} (PID ${pids})"
    else
      info "${svc} was not running"
    fi
  done
  cd "${ROOT_DIR}"
  docker compose down --remove-orphans 2>/dev/null || true
  success "Docker containers stopped"
  exit 0
fi

# =============================================================================
# PRE-FLIGHT CHECKS
# =============================================================================
header "Pre-flight Checks"

# Java
command -v java &>/dev/null || die "Java not found. Install JDK ${MIN_JAVA}+ and add to PATH."
JAVA_VER=$(java -version 2>&1 | grep -oE '"[0-9]+' | head -1 | tr -d '"')
if [ "${JAVA_VER}" = "1" ]; then
  JAVA_VER=$(java -version 2>&1 | grep -oE '"1\.[0-9]+' | head -1 | cut -d. -f2 | tr -d '"')
fi
if [ "${JAVA_VER:-0}" -lt "${MIN_JAVA}" ] 2>/dev/null; then
  die "Java ${MIN_JAVA}+ required, found ${JAVA_VER}. Install Temurin ${MIN_JAVA}."
fi
success "Java ${JAVA_VER} ok"

# Maven wrapper
[ -f "${ROOT_DIR}/mvnw" ] || die "mvnw not found in project root."
success "Maven wrapper ok"

# Docker
command -v docker &>/dev/null           || die "Docker not found. Install Docker Desktop."
docker info &>/dev/null 2>&1            || die "Docker daemon not running. Start Docker Desktop."
docker compose version &>/dev/null 2>&1 || die "docker compose not available. Upgrade to Docker Desktop 4.x+."
success "Docker + Compose v2 ok"

# gh CLI check (used by default copilot-cli provider)
ACTIVE_PROVIDER="${AI_PROVIDER:-copilot-cli}"
if [ "${ACTIVE_PROVIDER}" = "copilot-cli" ]; then
  if ! command -v gh &>/dev/null; then
    warn "gh CLI not found -- AI generation will fall back to templates."
    warn "  Fix:  brew install gh && gh auth login"
  else
    GH_VER=$(gh --version 2>/dev/null | head -1 | awk '{print $3}')
    success "gh CLI ${GH_VER} ok"
    if gh auth status &>/dev/null 2>&1; then
      success "gh authenticated ok"
    else
      warn "gh CLI installed but NOT authenticated -- AI will fall back to templates."
      warn "  Fix:  gh auth login"
    fi
  fi
fi

# Port checks
# Infrastructure ports (Kafka :9092, Redis :6379) are owned by Docker — never
# auto-kill those; fail fast if something else is already holding them.
# Service ports (8080-8084) are freed on-demand just before each service starts.
info "Checking infrastructure port availability..."
port_busy() { lsof -i ":${1}" -sTCP:LISTEN -t &>/dev/null 2>&1; }

# Helper used later (per-service, not here)
kill_port() {
  local port="${1}"
  local pids
  pids=$(lsof -i ":${port}" -sTCP:LISTEN -t 2>/dev/null || true)
  if [ -n "${pids}" ]; then
    for pid in ${pids}; do
      local proc
      proc=$(ps -p "${pid}" -o comm= 2>/dev/null || echo "unknown")
      warn "Port :${port} in use by '${proc}' (PID ${pid}) -- killing..."
      kill -TERM "${pid}" 2>/dev/null || true
      local i=0
      while kill -0 "${pid}" 2>/dev/null && [ "${i}" -lt 3 ]; do
        sleep 1; i=$((i + 1))
      done
      if kill -0 "${pid}" 2>/dev/null; then
        warn "Process ${pid} did not exit after SIGTERM -- sending SIGKILL"
        kill -KILL "${pid}" 2>/dev/null || true
        sleep 1
      fi
      success "Freed port :${port} (killed PID ${pid} '${proc}')"
    done
  fi
}

INFRA_BUSY=()
for p in "${INFRA_PORTS[@]}"; do
  if port_busy "${p}"; then
    pid=$(lsof -i ":${p}" -sTCP:LISTEN -t 2>/dev/null | head -1 || true)
    proc=$(ps -p "${pid}" -o comm= 2>/dev/null || echo "unknown")
    INFRA_BUSY+=(":${p} (PID ${pid} -- ${proc})")
  fi
done
if [ ${#INFRA_BUSY[@]} -gt 0 ]; then
  error "Infrastructure ports are already in use by non-Docker processes:"
  for b in "${INFRA_BUSY[@]}"; do error "  ${b}"; done
  die "Stop the conflicting processes and retry."
fi
success "Infrastructure ports free (6379, 9092) ok"

# JARs exist when --skip-build
if [ "${SKIP_BUILD}" = true ]; then
  MISSING=()
  for entry in "${SERVICES[@]}"; do
    j="${ROOT_DIR}/${entry##*|}"
    [ -f "${j}" ] || MISSING+=("${j}")
  done
  if [ ${#MISSING[@]} -gt 0 ]; then
    error "Missing JARs (remove --skip-build to build first):"
    for m in "${MISSING[@]}"; do error "  ${m}"; done
    exit 1
  fi
  success "All JARs present ok"
fi

# =============================================================================
# DOCKER INFRASTRUCTURE
# =============================================================================
header "Starting Docker Infrastructure"
cd "${ROOT_DIR}"

if [ "${FRESH}" = true ]; then
  warn "--fresh: removing Docker volumes to clear stale ZooKeeper state..."
  docker compose down -v --remove-orphans 2>/dev/null || true
  success "Volumes purged"
else
  docker compose down --remove-orphans 2>/dev/null || true
fi

if [ "${WITH_KAFKA_UI}" = true ]; then
  info "Starting Kafka, Redis, Kafka-UI (profile: debug)..."
  docker compose --profile debug up -d
else
  info "Starting Kafka and Redis..."
  docker compose up -d
fi

# Wait for Kafka
info "Waiting for Kafka to become healthy (timeout 90s)..."
T=0
until docker inspect --format='{{.State.Health.Status}}' qa-kafka 2>/dev/null | grep -q "^healthy$"; do
  if [ "${T}" -ge 90 ]; then
    docker logs qa-kafka --tail 20 >&2
    die "Kafka did not become healthy within 90s."
  fi
  sleep 3; T=$((T + 3)); printf "."
done
printf "\n"
success "Kafka healthy after ${T}s"

# Wait for Redis
info "Waiting for Redis to become healthy (timeout 30s)..."
T=0
until docker inspect --format='{{.State.Health.Status}}' qa-redis 2>/dev/null | grep -q "^healthy$"; do
  if [ "${T}" -ge 30 ]; then
    docker logs qa-redis --tail 10 >&2
    die "Redis did not become healthy within 30s."
  fi
  sleep 2; T=$((T + 2)); printf "."
done
printf "\n"
success "Redis healthy after ${T}s"

# =============================================================================
# BUILD
# =============================================================================
if [ "${SKIP_BUILD}" = false ]; then
  header "Building JARs"
  cd "${ROOT_DIR}"
  info "Running: ./mvnw clean package -DskipTests -q"
  ./mvnw clean package -DskipTests -q --no-transfer-progress
  success "Build complete"
fi

# =============================================================================
# START SERVICES
# =============================================================================
header "Starting Spring Boot Services"
mkdir -p "${LOG_DIR}"

PASSTHROUGH_VARS=(
  TARGET_REPO_URL TARGET_REPO_TOKEN TARGET_REPO_USERNAME GITHUB_WEBHOOK_SECRET
  AI_PROVIDER GITHUB_COPILOT_TOKEN
  OPENAI_API_KEY OPENAI_BASE_URL OPENAI_MODEL
  COPILOT_MODEL COPILOT_BASE_URL GH_CLI_PATH COPILOT_CLI_TIMEOUT
)

declare -A PIDS

for entry in "${SERVICES[@]}"; do
  IFS='|' read -r svc port jar_rel <<< "${entry}"
  jar="${ROOT_DIR}/${jar_rel}"
  log="${LOG_DIR}/${svc}.log"

  # Free the service port just before launching (no-op if already free)
  kill_port "${port}"

  info "  ${svc}  :${port}  ->  ${log}"

  ENV_PAIRS=()
  for v in "${PASSTHROUGH_VARS[@]}"; do
    if [ -n "${!v:-}" ]; then
      ENV_PAIRS+=("${v}=${!v}")
    fi
  done

  env "${ENV_PAIRS[@]}" java -jar "${jar}" > "${log}" 2>&1 &
  PIDS["${svc}"]=$!
done

# =============================================================================
# HEALTH POLL
# =============================================================================
header "Waiting for Services to Become Healthy"

declare -A HEALTH_URL=(
  [pr-service]="http://localhost:8080/api/pr/health"
  [impact-service]="http://localhost:8081/api/impact/status"
  [strategy-service]="http://localhost:8082/api/strategy/status"
  [codegen-service]="http://localhost:8083/actuator/health"
  [feedback-service]="http://localhost:8084/actuator/health"
)

HEALTH_TIMEOUT=60
declare -A STATUS

for entry in "${SERVICES[@]}"; do
  IFS='|' read -r svc port _ <<< "${entry}"
  pid="${PIDS[${svc}]}"
  url="${HEALTH_URL[${svc}]}"
  elapsed=0
  info "Polling ${svc} (PID ${pid}) at ${url}..."

  while true; do
    if ! kill -0 "${pid}" 2>/dev/null; then
      error "${svc} exited unexpectedly. Last 20 lines of log:"
      tail -20 "${LOG_DIR}/${svc}.log" >&2
      STATUS["${svc}"]="FAILED"
      break
    fi
    if curl -sf --max-time 3 "${url}" &>/dev/null; then
      success "${svc} responded after ${elapsed}s"
      STATUS["${svc}"]="OK"
      break
    fi
    if [ "${elapsed}" -ge "${HEALTH_TIMEOUT}" ]; then
      warn "${svc} did not respond within ${HEALTH_TIMEOUT}s -- check logs:"
      warn "  tail -f ${LOG_DIR}/${svc}.log"
      STATUS["${svc}"]="TIMEOUT"
      break
    fi
    sleep 3; elapsed=$((elapsed + 3)); printf "."
  done
  printf "\n"
done

# =============================================================================
# SUMMARY
# =============================================================================
header "Start-up Summary"
ALL_OK=true

printf "\n  %-22s  %-14s  %-6s  %s\n" "Service" "Status" "Port" "Log"
printf "  %-22s  %-14s  %-6s  %s\n"   "---------------------" "------------" "------" "--------------------------------"

for entry in "${SERVICES[@]}"; do
  IFS='|' read -r svc port _ <<< "${entry}"
  st="${STATUS[${svc}]:-UNKNOWN}"
  case "${st}" in
    OK)      col="${GREEN}";  icon="OK     " ;;
    TIMEOUT) col="${YELLOW}"; icon="TIMEOUT"; ALL_OK=false ;;
    FAILED)  col="${RED}";    icon="FAILED "; ALL_OK=false ;;
    *)       col="${YELLOW}"; icon="UNKNOWN"; ALL_OK=false ;;
  esac
  printf "  ${col}%-22s  %-14s${NC}  :%-5s  %s\n"     "${svc}" "${icon}" "${port}" "${LOG_DIR}/${svc}.log"
done

echo ""
if [ "${WITH_KAFKA_UI}" = true ]; then
  echo "  Infrastructure:  Kafka :9092  |  Redis :6379  |  Kafka UI http://localhost:8090"
else
  echo "  Infrastructure:  Kafka :9092  |  Redis :6379"
fi

cat << 'TIPS'

  Quick tests:
    curl -X POST http://localhost:8080/api/pr/demo
    curl -X POST http://localhost:8080/api/pr/submit \
         -H 'Content-Type: application/json' \
         -d @pr-webhook-sample.json

  Monitoring:
    curl http://localhost:8082/api/qa/cost/report     # AI cost metrics
    curl http://localhost:8082/api/strategy/status    # Strategy status
    tail -f logs/strategy-service.log

  Stop everything:
    ./scripts/start-local.sh --stop

  Restart with fresh Kafka state (e.g. after NodeExistsException):
    ./scripts/start-local.sh --fresh --skip-build

TIPS

if [ "${ALL_OK}" = true ]; then
  success "All services started. Pipeline is ready."
  exit 0
else
  warn "One or more services failed or timed out -- see logs above."
  exit 1
fi
