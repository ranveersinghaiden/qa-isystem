#!/bin/bash
# Startup script for all QA-ISystem services
set -e
BASE=/Users/ranveeraiden/Desktop/Workspace/QA-ISystem
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

TARGET_REPO_URL="https://github.com/ranveersinghaiden/xeroAssignment" \
TARGET_REPO_TOKEN="${TARGET_REPO_TOKEN}" \
TARGET_REPO_USERNAME="ranveersinghaiden" \
GITHUB_WEBHOOK_REQUIRE_SECRET="false" \
nohup java -jar strategy-service/target/strategy-service-0.0.1-SNAPSHOT.jar \
  > logs/strategy-service.log 2>&1 &
echo "strategy-service started PID=$!"

TARGET_REPO_URL="https://github.com/ranveersinghaiden/xeroAssignment" \
TARGET_REPO_TOKEN="${TARGET_REPO_TOKEN}" \
TARGET_REPO_USERNAME="ranveersinghaiden" \
nohup java -jar codegen-service/target/codegen-service-0.0.1-SNAPSHOT.jar \
  > logs/codegen-service.log 2>&1 &
echo "codegen-service started PID=$!"

TARGET_REPO_URL="https://github.com/ranveersinghaiden/xeroAssignment" \
TARGET_REPO_TOKEN="${TARGET_REPO_TOKEN}" \
TARGET_REPO_USERNAME="ranveersinghaiden" \
nohup java -jar feedback-service/target/feedback-service-0.0.1-SNAPSHOT.jar \
  > logs/feedback-service.log 2>&1 &
echo "feedback-service started PID=$!"

echo ""
echo "All services started. Waiting 20s for startup..."
sleep 20

echo ""
echo "=== Health Checks ==="
for port in 8080 8081 8082 8083 8084; do
  pid=$(lsof -ti :$port 2>/dev/null)
  if [ -n "$pid" ]; then
    echo "  Port $port: UP (PID $pid)"
  else
    echo "  Port $port: NOT running - check logs"
  fi
done

