#!/usr/bin/env bash
# kill-services.sh — kill all Java service processes on ports 8080-8084

set -euo pipefail

PORTS=(8080 8081 8082 8083 8084)
KILLED=0

for port in "${PORTS[@]}"; do
  pids=$(lsof -ti:"$port" 2>/dev/null) || true
  if [ -n "$pids" ]; then
    echo "Killing PID(s) $pids on port $port"
    echo "$pids" | xargs kill -9 2>/dev/null || true
    KILLED=$((KILLED + 1))
  else
    echo "Port $port — nothing running"
  fi
done

echo
if [ "$KILLED" -gt 0 ]; then
  echo "Done. Killed processes on $KILLED port(s)."
else
  echo "Done. No processes were running on ports 8080-8084."
fi

