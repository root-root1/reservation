#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${1:-http://localhost:8080}"
REQUESTS="${2:-20000}"
USERS="${3:-2000}"
IN_FLIGHT="${4:-1500}"

cd "$(dirname "$0")/.."

# .env is the local source of ADMIN_USERNAME / ADMIN_PASSWORD; real environments
# supply them directly and have no such file.
if [[ -f .env ]]; then
  set -a
  # shellcheck disable=SC1091
  source .env
  set +a
fi

# 20k sockets will exhaust the default descriptor limit long before the service struggles.
ulimit -n 65535 2>/dev/null || echo "warning: could not raise the file descriptor limit" >&2

exec java scripts/Burst.java "$BASE_URL" "$REQUESTS" "$USERS" "$IN_FLIGHT"
