#!/usr/bin/env bash
# Replays the simulator's scenario against a freshly started Dispatch on a fresh database, and writes the report
# to target/sim/<label>.json (the app's log goes to target/sim/app-<label>.log).
#
#   scripts/simulate.sh <label> [app arguments...]
#   scripts/simulate.sh baseline
#   scripts/simulate.sh small --dispatch.offers.timeout=10s        # app arguments are Spring properties
#
# SIM_ARGS passes options to the simulator itself, e.g. SIM_ARGS="--scenario small" for the 1,000-event version.
# Needs what dev-up.sh needs (Postgres 14 on 5432, redis-server), Java 21 and Maven. Port 8101 must be free.
set -euo pipefail
cd "$(dirname "$0")/.."

LABEL="${1:?usage: scripts/simulate.sh <label> [app arguments...]}"
shift
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@21}"
JAVA="${JAVA_HOME}/bin/java"
PORT="${DISPATCH_PORT:-8101}"
DB="${SIM_DB:-dispatch_sim}"
OUT=target/sim
mkdir -p "${OUT}"

./scripts/dev-up.sh >/dev/null
# Every run starts from an empty database, so runs compare like with like. Only this script's own database.
dropdb -h localhost --if-exists "${DB}"
createdb -h localhost "${DB}"

# The app jar, the simulator (test classes) and the test classpath, in one Maven call.
mvn -q -B -DskipTests package dependency:build-classpath \
    -Dmdep.outputFile="${OUT}/classpath.txt" -Dmdep.includeScope=test

"${JAVA}" -jar target/dispatch.jar --server.port="${PORT}" \
    --spring.datasource.url="jdbc:postgresql://localhost:5432/${DB}" "$@" > "${OUT}/app-${LABEL}.log" 2>&1 &
APP=$!
trap 'kill ${APP} 2>/dev/null || true; wait ${APP} 2>/dev/null || true' EXIT
for _ in $(seq 1 120); do
    curl -sf "localhost:${PORT}/api/v1/health" >/dev/null && break
    sleep 0.5
done
curl -sf "localhost:${PORT}/api/v1/health" >/dev/null || { echo "app didn't start; see ${OUT}/app-${LABEL}.log" >&2; exit 1; }

# shellcheck disable=SC2086 # SIM_ARGS is a list of options
"${JAVA}" -cp "target/test-classes:target/classes:$(cat "${OUT}/classpath.txt")" \
    io.github.vivekdavara.dispatch.sim.Simulator \
    --base-url "http://localhost:${PORT}" --label "${LABEL}" --out "${OUT}/${LABEL}.json" \
    --db-url "jdbc:postgresql://localhost:5432/${DB}?user=${USER}" ${SIM_ARGS:-}
