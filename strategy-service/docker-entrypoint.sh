#!/bin/sh
# Starts the headroom proxy (when enabled) before handing off to the Java process.
#
# Prerequisites when HEADROOM_ENABLED=true:
#   1. Run once on host: headroom device add copilot  (OAuth flow, saves creds in ~/.headroom)
#   2. Mount host ~/.headroom into container at /home/spring/.headroom (see docker-compose.prod.yml)
#   3. Set HEADROOM_GITHUB_USERNAME to your GitHub username
#
# HEADROOM_ENABLED=false (default) → transparent passthrough, no Python/headroom required.
set -e

HEADROOM_PORT="${HEADROOM_PORT:-8787}"
HEADROOM_WAIT="${HEADROOM_STARTUP_WAIT_SECONDS:-15}"

if [ "${HEADROOM_ENABLED:-false}" = "true" ]; then

    # ── Guard: github-username must be set ────────────────────────────────────
    if [ -z "${HEADROOM_GITHUB_USERNAME:-}" ]; then
        echo "[entrypoint] WARNING: HEADROOM_ENABLED=true but HEADROOM_GITHUB_USERNAME is not set." \
             "Copilot will call the LLM API directly — compression disabled."
        exec "$@"
    fi

    # ── Guard: device must be registered (credentials in ~/.headroom) ─────────
    if ! headroom device list 2>/dev/null | grep -qi copilot; then
        echo "[entrypoint] WARNING: Headroom copilot device not registered."
        echo "[entrypoint]   Fix: run 'headroom device add copilot' on the host, then"
        echo "[entrypoint]   mount ~/.headroom into this container."
        echo "[entrypoint]   Proceeding WITHOUT compression."
        exec "$@"
    fi

    # ── Start proxy ───────────────────────────────────────────────────────────
    echo "[entrypoint] Starting headroom proxy on 127.0.0.1:${HEADROOM_PORT} ..."
    HEADROOM_TELEMETRY=off \
    HEADROOM_SAVINGS_PATH=/tmp/headroom-savings.json \
        headroom proxy \
            --host 127.0.0.1 \
            --port "${HEADROOM_PORT}" \
            --no-telemetry &

    echo "[entrypoint] Waiting up to ${HEADROOM_WAIT}s for headroom proxy to be ready ..."
    i=0
    while [ "${i}" -lt "${HEADROOM_WAIT}" ]; do
        if curl -sf "http://127.0.0.1:${HEADROOM_PORT}/health" > /dev/null 2>&1; then
            echo "[entrypoint] Headroom proxy ready at 127.0.0.1:${HEADROOM_PORT}" \
                 "— COPILOT_PROVIDER_BASE_URL=http://127.0.0.1:${HEADROOM_PORT}/p/${HEADROOM_GITHUB_USERNAME}/v1"
            break
        fi
        i=$((i + 1))
        sleep 1
    done

    if [ "${i}" -ge "${HEADROOM_WAIT}" ]; then
        echo "[entrypoint] WARNING: headroom proxy did not respond within ${HEADROOM_WAIT}s." \
             "Copilot will call the LLM API directly — compression disabled for this session."
    fi
fi

exec "$@"
