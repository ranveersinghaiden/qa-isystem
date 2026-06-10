#!/usr/bin/env bash
# approve-bdd.sh -- Manually trigger codegen for pending BDD scenario PRs.
#
# Simulates a GitHub BDD PR merged webhook by fetching pending BDD scenarios
# from strategy-service and posting each one to POST /api/strategy/approve-bdd.
#
# Usage:  ./scripts/approve-bdd.sh [OPTIONS]
#
# Options:
#   --pr-id <ID>   Only approve the scenario matching this PR ID
#   --list         List pending scenarios and exit without approving
#   --yes          Skip the confirmation prompt
#   --help         Print this help
#
# Requires: curl, jq  (brew install jq)
# strategy-service must be running on :8082
set -euo pipefail

RED='\033[0;31m'; YELLOW='\033[1;33m'; GREEN='\033[0;32m'
CYAN='\033[0;36m'; BOLD='\033[1m'; NC='\033[0m'
info()    { echo -e "${CYAN}[INFO]${NC}  $*"; }
success() { echo -e "${GREEN}[OK]${NC}    $*"; }
warn()    { echo -e "${YELLOW}[WARN]${NC}  $*"; }
error()   { echo -e "${RED}[ERROR]${NC} $*" >&2; }
die()     { error "$*"; exit 1; }
header()  { echo -e "\n${BOLD}${CYAN}== $* ==${NC}"; }

STRATEGY_URL="http://localhost:8082"
FILTER_PR_ID=""
LIST_ONLY=false
# Admin key — set AIQA_ADMIN_KEY env var when strategy-service is configured with one
ADMIN_KEY="${AIQA_ADMIN_KEY:-}"
# Build curl auth header arg (empty string = no header added)
admin_header() { [ -n "${ADMIN_KEY}" ] && echo "-H" "X-Admin-Key: ${ADMIN_KEY}" || echo ""; }
AUTO_YES=false

ARGS=("$@")
for ((i=0; i<${#ARGS[@]}; i++)); do
  case "${ARGS[$i]}" in
    --list)    LIST_ONLY=true ;;
    --yes)     AUTO_YES=true ;;
    --help|-h)
      grep '^#' "${BASH_SOURCE[0]}" | head -16 | sed 's/^# \{0,1\}//'
      exit 0 ;;
    --pr-id)   FILTER_PR_ID="${ARGS[$((i+1))]:-}" ;;
  esac
done

# -- Pre-checks -----------------------------------------------------------
command -v curl &>/dev/null || die "curl not found."
command -v jq   &>/dev/null || die "jq not found. Install with: brew install jq"

curl -sf --max-time 3 "${STRATEGY_URL}/api/strategy/status" &>/dev/null \
  || die "strategy-service not reachable at ${STRATEGY_URL}. Is it running?"

# -- Fetch pending BDD scenarios ------------------------------------------
header "Fetching Pending BDD Scenarios"
PENDING=$(curl -sf $(admin_header) "${STRATEGY_URL}/api/strategy/pending-bdd") \
  || die "Failed to call /api/strategy/pending-bdd"

COUNT=$(echo "${PENDING}" | jq 'length')

if [ "${COUNT}" -eq 0 ]; then
  warn "No pending BDD scenarios in the tracker."
  echo ""
  echo "  Possible reasons:"
  echo "  1. strategy-service restarted after the BDD PR was created"
  echo "     (InMemoryPrTracker loses state on restart)"
  echo "  2. The BDD scenario was already approved/processed"
  echo "  3. GitHub credentials were not set when the PR was submitted"
  echo ""
  echo "  To regenerate, resubmit with credentials:"
  echo "    TARGET_REPO_URL=... TARGET_REPO_TOKEN=... ./scripts/start-local.sh --skip-build"
  echo "    curl -X POST http://localhost:8080/api/pr/submit \\"
  echo "         -H 'Content-Type: application/json' -d @pr-webhook-sample.json"
  exit 0
fi

# -- List mode ------------------------------------------------------------
if [ "${LIST_ONLY}" = true ]; then
  echo ""
  printf "  %-36s  %-14s  %-6s  %s\n" "Scenario ID" "PR ID" "PR #" "Branch"
  printf "  %-36s  %-14s  %-6s  %s\n" "------------------------------------" "--------------" "------" "------"
  echo "${PENDING}" | jq -r '.[] | [.bddScenario.scenarioId, .bddScenario.prId, (.prNumber|tostring), .branch] | join("  ")' \
    | while IFS= read -r line; do printf "  %s\n" "${line}"; done
  echo ""
  info "${COUNT} pending BDD scenario(s)"
  exit 0
fi

# -- Apply --pr-id filter -------------------------------------------------
if [ -n "${FILTER_PR_ID}" ]; then
  PENDING=$(echo "${PENDING}" | jq --arg id "${FILTER_PR_ID}" '[.[] | select(.bddScenario.prId == $id)]')
  COUNT=$(echo "${PENDING}" | jq 'length')
  [ "${COUNT}" -gt 0 ] || die "No pending BDD scenario for PR ID '${FILTER_PR_ID}'."
fi

# -- Confirm --------------------------------------------------------------
header "Pending BDD Scenarios to Approve"
echo ""
echo "${PENDING}" | jq -r '.[] | (
  "  Branch    : " + .branch,
  "  PR #      : " + (.prNumber|tostring),
  "  PR ID     : " + .bddScenario.prId,
  "  PR Title  : " + (.bddScenario.prTitle // "(not set)"),
  "  Scenarios : " + (.bddScenario.scenarios | length | tostring),
  "  Scenario ID: " + .bddScenario.scenarioId,
  ""
)'

if [ "${AUTO_YES}" != true ]; then
  echo -e "${YELLOW}Approving will publish to TestScriptsQueue -> codegen-service.${NC}"
  read -r -p "Proceed? [y/N] " confirm
  [[ "${confirm}" =~ ^[yY]$ ]] || { info "Aborted."; exit 0; }
fi

# -- Approve each scenario ------------------------------------------------
header "Approving BDD Scenarios"
APPROVED=0
FAILED=0

while IFS= read -r scenario_json; do
  pr_id=$(echo "${scenario_json}" | jq -r '.prId')
  scenario_id=$(echo "${scenario_json}" | jq -r '.scenarioId')
  n=$(echo "${scenario_json}" | jq '.scenarios | length')
  pr_title=$(echo "${scenario_json}" | jq -r '.prTitle // "(not set)"')

  info "Approving PR '${pr_id}' | ${n} scenario(s) | '${pr_title}'"

  RESPONSE=$(curl -sf -X POST "${STRATEGY_URL}/api/strategy/approve-bdd" \
    -H "Content-Type: application/json" \
    $(admin_header) \
    -d "${scenario_json}") || {
    error "Request failed for PR '${pr_id}'. Is codegen-service running?"
    FAILED=$((FAILED + 1))
    continue
  }

  STATUS=$(echo "${RESPONSE}" | jq -r '.status // "UNKNOWN"')
  if [ "${STATUS}" = "CODEGEN_TRIGGERED" ]; then
    success "Codegen triggered for PR '${pr_id}' (scenario '${scenario_id}')"
    APPROVED=$((APPROVED + 1))
  else
    warn "Unexpected response for PR '${pr_id}': ${RESPONSE}"
    FAILED=$((FAILED + 1))
  fi

done < <(echo "${PENDING}" | jq -c '.[].bddScenario')

# -- Summary --------------------------------------------------------------
echo ""
if [ "${FAILED}" -eq 0 ]; then
  success "${APPROVED} scenario(s) approved. Codegen pipeline is running."
  echo ""
  echo -e "  ${BOLD}Monitor progress:${NC}"
  echo -e "    tail -f logs/codegen-service.log"
  echo -e "    tail -f logs/strategy-service.log"
  echo -e "    grep 'Final Test PR\|CODEGEN\|TestScript' logs/codegen-service.log"
else
  warn "${APPROVED} approved, ${FAILED} failed."
  echo "  Check logs/strategy-service.log for details."
  exit 1
fi
