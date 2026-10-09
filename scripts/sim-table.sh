#!/usr/bin/env bash
# Prints simulator reports (target/sim/<label>.json, written by simulate.sh) as the README's Markdown table.
#
#   scripts/sim-table.sh target/sim/standard-before.json target/sim/standard-after.json
#
# Uses the classes and classpath simulate.sh built; run that (or mvn test-compile) first.
set -euo pipefail
cd "$(dirname "$0")/.."
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@21}"
CP_FILE=target/sim/classpath.txt
[ -f "${CP_FILE}" ] || { echo "no ${CP_FILE}; run scripts/simulate.sh first" >&2; exit 1; }
"${JAVA_HOME}/bin/java" -cp "target/test-classes:target/classes:$(cat "${CP_FILE}")" \
    io.github.vivekdavara.dispatch.sim.ReportTable "$@"
