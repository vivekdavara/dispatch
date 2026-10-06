#!/usr/bin/env bash
# Stop the project-local Redis. The shared Postgres 14 server is left running; the database is kept.
set -euo pipefail

REDIS_PORT="${REDIS_PORT:-6381}"

if redis-cli -p "${REDIS_PORT}" ping >/dev/null 2>&1; then
  redis-cli -p "${REDIS_PORT}" shutdown nosave >/dev/null 2>&1 || true
  echo "redis :${REDIS_PORT}: stopped"
else
  echo "redis :${REDIS_PORT}: not running"
fi
