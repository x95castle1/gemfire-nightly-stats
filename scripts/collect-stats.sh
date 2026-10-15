#!/usr/bin/env bash
# One-shot remote collector and offline report tools. Works in a checkout or an unpacked distribution.
set -euo pipefail
: "${GEMFIRE_HOME:?GEMFIRE_HOME must point at a GemFire install for the JMX client libraries}"
PROJECT_DIR=$(cd "$(dirname "$0")/.." && pwd)
COLLECTOR_JAR="$PROJECT_DIR/lib/gemfire-nightly-stats.jar"
if [ ! -f "$COLLECTOR_JAR" ]; then
  COLLECTOR_JAR="$PROJECT_DIR/build/libs/gemfire-nightly-stats.jar"
fi
[ -f "$COLLECTOR_JAR" ] || { echo "Collector jar not found. Run ./gradlew build first." >&2; exit 2; }
# shellcheck disable=SC2086
exec java ${JAVA_OPTS:-} \
  --add-exports=java.management/com.sun.jmx.remote.security=ALL-UNNAMED \
  --add-exports=java.base/sun.nio.ch=ALL-UNNAMED \
  --add-opens=java.base/java.lang=ALL-UNNAMED \
  --add-opens=java.base/java.nio=ALL-UNNAMED \
  -Dlog4j.configurationFile=standalone-log4j2.xml \
  -classpath "$GEMFIRE_HOME/lib/gemfire-dependencies.jar:$COLLECTOR_JAR" \
  com.example.gemfire.stats.NightlyStatsMain "$@"
