#!/usr/bin/env bash
# Starts a locator that writes the nightly stats report every day.
# Usage: scripts/start-locator.sh <locator.properties>
# Extra JVM options (heap size, etc.) can be passed in JAVA_OPTS.
set -euo pipefail

: "${GEMFIRE_HOME:?GEMFIRE_HOME must point at a GemFire install}"
PROPERTIES_FILE=${1:?Usage: $0 <locator.properties>}
PROJECT_DIR=$(cd "$(dirname "$0")/.." && pwd)

# shellcheck disable=SC2086
exec java -server ${JAVA_OPTS:-} \
  --add-exports=java.management/com.sun.jmx.remote.security=ALL-UNNAMED \
  --add-exports=java.base/sun.nio.ch=ALL-UNNAMED \
  --add-opens=java.base/java.lang=ALL-UNNAMED \
  --add-opens=java.base/java.nio=ALL-UNNAMED \
  -classpath "$GEMFIRE_HOME/lib/gemfire-dependencies.jar:$PROJECT_DIR/build/libs/gemfire-nightly-stats.jar" \
  com.example.gemfire.stats.NightlyStatsLocatorStart "$PROPERTIES_FILE"
