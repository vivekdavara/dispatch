#!/usr/bin/env bash
# Start the local dependencies Dispatch needs: the `dispatch` database on the Homebrew Postgres 14 (port 5432)
# and a project-local Redis on port 6381. Safe to run repeatedly.
set -euo pipefail

REDIS_PORT="${REDIS_PORT:-6381}"
DB_NAME="${DB_NAME:-dispatch}"

if ! pg_isready -q -h localhost -p 5432; then
  echo "Postgres is not accepting connections on localhost:5432; start it first (brew services start postgresql@14)." >&2
  exit 1
fi
if psql -h localhost -d postgres -tAc "SELECT 1 FROM pg_database WHERE datname = '${DB_NAME}'" | grep -q 1; then
  echo "database ${DB_NAME}: exists"
else
  createdb -h localhost "${DB_NAME}"
  echo "database ${DB_NAME}: created"
fi

if redis-cli -p "${REDIS_PORT}" ping >/dev/null 2>&1; then
  echo "redis :${REDIS_PORT}: already running"
else
  # Positions are soft state (couriers resend them), so no persistence.
  redis-server --port "${REDIS_PORT}" --daemonize yes --save "" --appendonly no
  for _ in $(seq 1 20); do
    redis-cli -p "${REDIS_PORT}" ping >/dev/null 2>&1 && break
    sleep 0.1
  done
  redis-cli -p "${REDIS_PORT}" ping >/dev/null
  echo "redis :${REDIS_PORT}: started"
fi
