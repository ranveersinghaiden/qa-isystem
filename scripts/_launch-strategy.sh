#!/usr/bin/env bash
set -a
# shellcheck source=../.env
source "$(dirname "$0")/../.env"
set +a
exec java -jar "$(dirname "$0")/../strategy-service/target/strategy-service-0.0.1-SNAPSHOT.jar" "$@"

