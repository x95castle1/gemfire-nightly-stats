#!/usr/bin/env bash
# Starts a locator that writes the nightly stats report every day.
# Usage: start-locator.sh <locator.properties>
# Extra JVM options (heap size, etc.) can be passed in JAVA_OPTS.
set -euo pipefail

: "${GEMFIRE_HOME:?GEMFIRE_HOME must point at a GemFire install}"
PROPERTIES_FILE=${1:?Usage: $0 <locator.properties>}
PROJECT_DIR=$(cd "$(dirname "$0")/.." && pwd)
if [ -f "$PROJECT_DIR/lib/01-embedded-locator.jar" ]; then
  APP_CLASSPATH="$PROJECT_DIR/lib/*"
else
  [ -f "$PROJECT_DIR/build/libs/01-embedded-locator.jar" ] \
    || { echo "Example jar not found. Run ./gradlew build in the example directory." >&2; exit 2; }
  APP_CLASSPATH="$PROJECT_DIR/build/libs/01-embedded-locator.jar"
fi

# shellcheck disable=SC2086
exec java -server ${JAVA_OPTS:-} \
  --add-exports=java.management/com.sun.jmx.remote.security=ALL-UNNAMED \
  --add-exports=java.base/sun.nio.ch=ALL-UNNAMED \
  --add-opens=java.base/java.lang=ALL-UNNAMED \
  --add-opens=java.base/java.nio=ALL-UNNAMED \
  -classpath "$GEMFIRE_HOME/lib/gemfire-dependencies.jar:$APP_CLASSPATH" \
  com.example.gemfire.stats.embedded.NightlyStatsLocatorStart "$PROPERTIES_FILE"
