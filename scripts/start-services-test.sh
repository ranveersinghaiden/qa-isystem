#!/usr/bin/env bash
# =============================================================================
# start-services-test.sh -- Quick start for manual / CI testing
#
# Required environment variables (set before running):
#   TARGET_REPO_URL       e.g. https://github.com/your-org/your-repo
#   TARGET_REPO_TOKEN     GitHub PAT with repo scope  (never hardcode here)
#   TARGET_REPO_USERNAME  GitHub username for the PAT
#
# Example:
#   export TARGET_REPO_URL=https://github.com/your-org/your-repo
#   export TARGET_REPO_TOKEN=<your-pat>
#   export TARGET_REPO_USERNAME=your-username
#   ./scripts/start-services-test.sh
#
# NOTE: Use start-local.sh for full pre-flight checks, Docker infra,
#       and health polling.  This script is a thin wrapper for quick re-starts.
# =============================================================================
set -euo pipefail

# -- Validate required env vars -----------------------------------------------
for var in TARGET_REPO_URL TARGET_REPO_TOKEN TARGET_REPO_USERNAME; do
  if [ -z "${!var:-}" ]; then
    echo "[ERROR] Required env var '$var' is not set." >&2
    echo "        Set it before running this script — never hardcode credentials." >&2
    exit 1
  fi
done

BASE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$BASE"
mkdir -p logs

start_svc() {
  local name=$1; shift
  nohup java -jar "$@" > "logs/${name}.log" 2>&1 &
  local pid=$!
  echo "${name} started PID=${pid}"
}

start_svc "pr-service" \
  pr-service/target/pr-service-0.0.1-SNAPSHOT.jar

start_svc "impact-service" \
  impact-service/target/impact-service-0.0.1-SNAPSHOT.jar

TARGET_REPO_URL="${TARGET_REPO_URL}" \
TARGET_REPO_TOKEN="${TARGET_REPO_TOKEN}" \
TARGET_REPO_USERNAME="${TARGET_REPO_USERNAME}" \
GITHUB_WEBHOOK_REQUIRE_SECRET="false" \
nohup java -jar strategy-service/target/strategy-service-0.0.1-SNAPSHOT.jar \
  > logs/strategy-service.log 2>&1 &
echo "strategy-service started PID=$!"

TARGET_REPO_URL="${TARGET_REPO_URL}" \
TARGET_REPO_TOKEN="${TARGET_REPO_TOKEN}" \
TARGET_REPO_USERNAME="${TARGET_REPO_USERNAME}" \
nohup java -jar codegen-service/target/codegen-service-0.0.1-SNAPSHOT.jar \
  > logs/codegen-service.log 2>&1 &
echo "codegen-service started PID=$!"

TARGET_REPO_URL="${TARGET_REPO_URL}" \
TARGET_REPO_TOKEN="${TARGET_REPO_TOKEN}" \
TARGET_REPO_USERNAME="${TARGET_REPO_USERNAME}" \
nohup java -jar feedback-service/target/feedback-service-0.0.1-SNAPSHOT.jar \
  > logs/feedback-service.log 2>&1 &
echo "feedback-service started PID=$!"

echo ""
echo "All services started. Waiting 20s for startup..."
sleep 20

echo ""
echo "=== Health Checks ==="
for port in 8080 8081 8082 8083 8084; do
  pid=$(lsof -ti :$port 2>/dev/null || true)
  if [ -n "$pid" ]; then
    echo "  Port $port: UP (PID $pid)"
  else
    echo "  Port $port: NOT running - check logs"
  fi
done
