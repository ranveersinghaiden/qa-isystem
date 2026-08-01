#!/usr/bin/env bash
# Repository-local knowledge is deliberately the only supported source.
set -euo pipefail

if [ "$#" -ne 0 ]; then
  printf '%s\n' "Usage: $0" >&2
  exit 2
fi

printf '%s\n' "[sync_product_experts] External knowledge synchronisation is disabled."
printf '%s\n' "[sync_product_experts] Use repository-local synthetic fixtures instead."
