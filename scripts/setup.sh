#!/usr/bin/env bash
# =============================================================================
# setup.sh -- One-shot developer setup for QA-ISystem
#
# Installs and verifies every prerequisite, initialises the .env file,
# and optionally builds the project so you can run start-local.sh immediately.
#
# Usage:
#   ./scripts/setup.sh              # Check + install everything, no build
#   ./scripts/setup.sh --build      # Also compile all Maven modules
#   ./scripts/setup.sh --check-only # Validate prerequisites without installing
#   ./scripts/setup.sh --help       # Print this message
#
# What this script does:
#   1. Checks for Homebrew (macOS) and installs it if missing
#   2. Installs missing CLI tools: gh, jq, curl
#   3. Installs Java 25 (Temurin) if no JDK 25+ is on PATH
#   4. Verifies Docker Desktop is running
#   5. Checks gh CLI authentication (GitHub Copilot AI provider)
#   6. Copies .env.example → .env if .env does not exist
#   7. Makes mvnw and all scripts/ executable
#   8. Optionally builds all Maven modules (./mvnw clean package -DskipTests)
#
# Prerequisites for AI generation:
#   brew install gh && gh auth login
#   gh extension install github/gh-copilot   # enables "gh copilot" sub-command
#
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
header()  { echo -e "\n${BOLD}${CYAN}══ $* ══${NC}"; }
note()    { echo -e "  ${YELLOW}→${NC}  $*"; }

# -- Paths --------------------------------------------------------------------
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
MIN_JAVA=25

# -- Flags --------------------------------------------------------------------
DO_BUILD=false
CHECK_ONLY=false

for arg in "$@"; do
  case "${arg}" in
    --build)      DO_BUILD=true ;;
    --check-only) CHECK_ONLY=true ;;
    --help|-h)
      sed -n '3,28p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
      exit 0 ;;
    *) die "Unknown option: ${arg}  (use --help)" ;;
  esac
done

# -- Tracking -----------------------------------------------------------------
WARNINGS=()   # non-fatal issues requiring manual action
ERRORS=()     # fatal problems that will block the pipeline

add_warning() { WARNINGS+=("$*"); }
add_error()   { ERRORS+=("$*"); }

# =============================================================================
# OS DETECTION
# =============================================================================
header "Operating System"
OS="$(uname -s)"
ARCH="$(uname -m)"
info "Detected: ${OS} / ${ARCH}"

if [ "${OS}" != "Darwin" ] && [ "${OS}" != "Linux" ]; then
  die "Unsupported OS '${OS}'. This script supports macOS and Linux."
fi

IS_MAC=false
[ "${OS}" = "Darwin" ] && IS_MAC=true

# =============================================================================
# 1. HOMEBREW (macOS only)
# =============================================================================
if [ "${IS_MAC}" = true ]; then
  header "Homebrew"
  if command -v brew &>/dev/null; then
    BREW_VER="$(brew --version 2>/dev/null | head -1)"
    success "${BREW_VER}"
  else
    if [ "${CHECK_ONLY}" = true ]; then
      add_error "Homebrew not found. Install from https://brew.sh"
    else
      warn "Homebrew not found — installing..."
      /bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"
      # Apple Silicon: add brew to PATH for this session
      if [ "${ARCH}" = "arm64" ] && [ -f /opt/homebrew/bin/brew ]; then
        eval "$(/opt/homebrew/bin/brew shellenv)"
      fi
      success "Homebrew installed"
      note "Add Homebrew to your shell profile if not already present:"
      if [ "${ARCH}" = "arm64" ]; then
        note '  echo '\''eval "$(/opt/homebrew/bin/brew shellenv)"'\'' >> ~/.zprofile'
      else
        note '  echo '\''eval "$(/usr/local/bin/brew shellenv)"'\'' >> ~/.zprofile'
      fi
    fi
  fi
fi

# =============================================================================
# Helper — install via brew (macOS) or apt/yum fallback (Linux)
# =============================================================================
brew_install() {
  local pkg="${1}"
  local label="${2:-${pkg}}"
  if [ "${CHECK_ONLY}" = true ]; then
    add_error "${label} not found. Install with: brew install ${pkg}"
    return
  fi
  if [ "${IS_MAC}" = true ]; then
    info "Installing ${label} via Homebrew..."
    brew install "${pkg}"
  else
    warn "Auto-install on Linux not supported. Install ${label} manually."
    add_warning "Install ${label} via your package manager."
  fi
}

# =============================================================================
# 2. curl
# =============================================================================
header "curl"
if command -v curl &>/dev/null; then
  success "curl $(curl --version | head -1 | awk '{print $2}')"
else
  brew_install curl
  command -v curl &>/dev/null && success "curl installed" || add_error "curl still missing after install attempt"
fi

# =============================================================================
# 3. jq (JSON parser used by approve-bdd.sh)
# =============================================================================
header "jq"
if command -v jq &>/dev/null; then
  success "jq $(jq --version)"
else
  brew_install jq
  command -v jq &>/dev/null && success "jq installed" || add_error "jq still missing after install attempt"
fi

# =============================================================================
# 4. Java 25 (Temurin)
# =============================================================================
header "Java ${MIN_JAVA}"

java_version() {
  java -version 2>&1 | grep -oE '"[0-9]+' | head -1 | tr -d '"'
}

JAVA_OK=false
if command -v java &>/dev/null; then
  JV="$(java_version)"
  if [ "${JV:-0}" -ge "${MIN_JAVA}" ] 2>/dev/null; then
    success "Java ${JV} on PATH — $(java -version 2>&1 | head -1)"
    JAVA_OK=true
  else
    warn "Java ${JV:-?} found but Java ${MIN_JAVA}+ required"
  fi
fi

if [ "${JAVA_OK}" = false ]; then
  if [ "${CHECK_ONLY}" = true ]; then
    add_error "Java ${MIN_JAVA}+ not found. Install Temurin: brew install --cask temurin@25"
  else
    if [ "${IS_MAC}" = true ]; then
      info "Installing Temurin JDK ${MIN_JAVA} via Homebrew Cask..."
      brew install --cask "temurin@${MIN_JAVA}" || {
        warn "temurin@${MIN_JAVA} cask not found — trying temurin..."
        brew install --cask temurin
      }
      # Attempt to set JAVA_HOME for this session
      if [ "${ARCH}" = "arm64" ]; then
        JH="/Library/Java/JavaVirtualMachines/temurin-${MIN_JAVA}.jdk/Contents/Home"
      else
        JH="/Library/Java/JavaVirtualMachines/temurin-${MIN_JAVA}.jdk/Contents/Home"
      fi
      if [ -d "${JH}" ]; then
        export JAVA_HOME="${JH}"
        export PATH="${JAVA_HOME}/bin:${PATH}"
        success "JAVA_HOME set to ${JAVA_HOME} (this session only)"
        note "Add to your shell profile:"
        note "  export JAVA_HOME=${JH}"
        note '  export PATH="${JAVA_HOME}/bin:${PATH}"'
      fi
      JV="$(java_version 2>/dev/null || echo 0)"
      if [ "${JV:-0}" -ge "${MIN_JAVA}" ] 2>/dev/null; then
        success "Java ${JV} now active"
      else
        add_error "Java ${MIN_JAVA}+ still not on PATH. Open a new terminal or set JAVA_HOME manually."
        add_warning "JAVA_HOME=${JH}"
      fi
    else
      warn "Please install Temurin ${MIN_JAVA} from: https://adoptium.net"
      add_error "Java ${MIN_JAVA}+ not installed. Get Temurin from https://adoptium.net/temurin/releases/"
    fi
  fi
fi

# =============================================================================
# 5. Docker Desktop
# =============================================================================
header "Docker"
if command -v docker &>/dev/null; then
  if docker info &>/dev/null 2>&1; then
    DOCKER_VER=$(docker version --format '{{.Server.Version}}' 2>/dev/null || echo "unknown")
    success "Docker daemon running — server ${DOCKER_VER}"
    if docker compose version &>/dev/null 2>&1; then
      success "docker compose $(docker compose version --short 2>/dev/null || docker compose version | head -1)"
    else
      add_error "docker compose plugin not found. Upgrade to Docker Desktop 4.x+."
    fi
  else
    add_error "Docker installed but daemon is NOT running. Start Docker Desktop."
  fi
else
  if [ "${CHECK_ONLY}" = true ]; then
    add_error "Docker not found. Download Docker Desktop from https://www.docker.com/products/docker-desktop/"
  else
    if [ "${IS_MAC}" = true ]; then
      warn "Docker Desktop not found — installing via Homebrew Cask..."
      brew install --cask docker
      warn "Docker Desktop installed. MANUAL STEP: open Docker Desktop and let it finish first-time setup."
      add_warning "Start Docker Desktop before running ./scripts/start-local.sh"
    else
      warn "Install Docker Engine from https://docs.docker.com/engine/install/"
      add_error "Docker not installed."
    fi
  fi
fi

# =============================================================================
# 6. gh CLI + Copilot extension (AI provider)
# =============================================================================
header "GitHub CLI (gh) — AI Provider"
if command -v gh &>/dev/null; then
  GH_VER=$(gh --version 2>/dev/null | head -1 | awk '{print $3}')
  success "gh CLI ${GH_VER}"

  # Auth check
  if gh auth status &>/dev/null 2>&1; then
    GH_USER=$(gh api user --jq '.login' 2>/dev/null || echo "unknown")
    success "gh authenticated as ${GH_USER}"
  else
    warn "gh CLI NOT authenticated — AI generation (BDD + codegen) will FAIL"
    add_warning "Run: gh auth login    (then re-run this script or start-local.sh)"
  fi

  # Copilot extension check
  if gh copilot --version &>/dev/null 2>&1; then
    CP_VER=$(gh copilot --version 2>/dev/null | head -1 || echo "installed")
    success "gh copilot extension: ${CP_VER}"
  else
    if [ "${CHECK_ONLY}" = true ]; then
      add_warning "gh copilot extension not installed. Run: gh extension install github/gh-copilot"
    else
      info "Installing gh copilot extension..."
      gh extension install github/gh-copilot 2>/dev/null && \
        success "gh copilot extension installed" || \
        add_warning "Could not install gh copilot. Run: gh extension install github/gh-copilot"
    fi
  fi
else
  if [ "${CHECK_ONLY}" = true ]; then
    add_error "gh CLI not found. Install: brew install gh && gh auth login"
  else
    brew_install gh "GitHub CLI (gh)"
    command -v gh &>/dev/null && {
      success "gh CLI installed"
      add_warning "Authenticate gh CLI: gh auth login"
      add_warning "Then install Copilot extension: gh extension install github/gh-copilot"
    } || add_error "gh CLI still missing after install attempt"
  fi
fi

# =============================================================================
# 7. Maven wrapper — permissions
# =============================================================================
header "Maven Wrapper"
MVNW="${ROOT_DIR}/mvnw"
if [ -f "${MVNW}" ]; then
  chmod +x "${MVNW}"
  success "mvnw executable"
else
  add_error "mvnw not found in project root — repository may be incomplete."
fi

# =============================================================================
# 8. Script permissions
# =============================================================================
header "Script Permissions"
SCRIPTS_DIR="${ROOT_DIR}/scripts"
if [ -d "${SCRIPTS_DIR}" ]; then
  chmod +x "${SCRIPTS_DIR}"/*.sh 2>/dev/null || true
  success "All scripts/*.sh executable"
else
  warn "scripts/ directory not found — skipping chmod"
fi

# =============================================================================
# 9. Environment file (.env)
# =============================================================================
header "Environment Configuration"
ENV_FILE="${ROOT_DIR}/.env"
ENV_EXAMPLE="${ROOT_DIR}/.env.example"

if [ -f "${ENV_FILE}" ]; then
  success ".env already exists — not overwritten"
  note "Review ${ENV_FILE} and fill in any missing values."
else
  if [ -f "${ENV_EXAMPLE}" ]; then
    cp "${ENV_EXAMPLE}" "${ENV_FILE}"
    success ".env created from .env.example"
    warn "IMPORTANT: Open .env and fill in required values:"
    note "  TARGET_REPO_URL        — HTTPS URL of the target test repo"
    note "  TARGET_REPO_TOKEN      — GitHub PAT with 'repo' scope (CI/prod only)"
    note "  TARGET_REPO_USERNAME   — GitHub username for the PAT"
    note "  GITHUB_WEBHOOK_SECRET  — HMAC secret from GitHub repo webhook settings"
    note "  AIQA_ADMIN_KEY         — Admin API key (generate: openssl rand -hex 32)"
    note ""
    note "  For LOCAL dev with IntelliJ GitHub auth, leave TARGET_REPO_TOKEN blank."
    note "  gh CLI manages AI credentials — no AIQA_AI_API_KEY needed."
  else
    add_warning ".env.example not found — create .env manually from QA-ISystem-Architecture.md"
  fi
fi

# =============================================================================
# 10. Build (optional)
# =============================================================================
if [ "${DO_BUILD}" = true ]; then
  header "Maven Build"
  if ! command -v java &>/dev/null || [ "$(java_version 2>/dev/null || echo 0)" -lt "${MIN_JAVA}" ] 2>/dev/null; then
    add_error "Skipping build — Java ${MIN_JAVA}+ not active on PATH."
  else
    cd "${ROOT_DIR}"
    info "Running: ./mvnw clean package -DskipTests --no-transfer-progress"
    ./mvnw clean package -DskipTests --no-transfer-progress && \
      success "Build complete — all JARs in target/" || \
      add_error "Maven build FAILED. Run './mvnw clean package' to see the full error."
  fi
fi

# =============================================================================
# SUMMARY
# =============================================================================
header "Setup Summary"

echo ""
if [ ${#ERRORS[@]} -gt 0 ]; then
  echo -e "  ${RED}${BOLD}ERRORS (must fix before starting the pipeline):${NC}"
  for e in "${ERRORS[@]}"; do
    echo -e "    ${RED}✗${NC}  ${e}"
  done
  echo ""
fi

if [ ${#WARNINGS[@]} -gt 0 ]; then
  echo -e "  ${YELLOW}${BOLD}REQUIRED MANUAL STEPS:${NC}"
  for w in "${WARNINGS[@]}"; do
    echo -e "    ${YELLOW}→${NC}  ${w}"
  done
  echo ""
fi

if [ ${#ERRORS[@]} -eq 0 ] && [ ${#WARNINGS[@]} -eq 0 ]; then
  success "All prerequisites satisfied — ready to start!"
elif [ ${#ERRORS[@]} -eq 0 ]; then
  warn "Prerequisites installed. Complete the manual steps above, then:"
else
  error "Fix the errors above before continuing."
fi

cat << 'NEXT_STEPS'

  ── Next Steps ────────────────────────────────────────────────────────────────

  1. Fill in .env (if you haven't already):
       nano .env

  2. Authenticate gh CLI (required for AI generation):
       gh auth login
       gh extension install github/gh-copilot

  3. Build (if you didn't use --build):
       ./mvnw clean package -DskipTests --no-transfer-progress

  4. Start the full pipeline:
       ./scripts/start-local.sh

  5. Submit a test PR webhook:
       curl -X POST http://localhost:8080/api/pr/demo

  6. (Optional) Monitor AI cost metrics:
       curl http://localhost:8082/api/qa/cost/report

  ─────────────────────────────────────────────────────────────────────────────

NEXT_STEPS

if [ ${#ERRORS[@]} -gt 0 ]; then
  exit 1
fi
exit 0

