#!/usr/bin/env bash
# Retry saved reports using the same packaged client that runs inside the locator.
# Usage: scripts/upload-reports.sh <locator.properties> [--verify]
set -euo pipefail

: "${GEMFIRE_HOME:?GEMFIRE_HOME must point at a GemFire install}"
PROJECT_DIR=$(cd "$(dirname "$0")/.." && pwd)

# shellcheck disable=SC2086
exec java ${JAVA_OPTS:-} \
  -classpath "$GEMFIRE_HOME/lib/gemfire-dependencies.jar:$PROJECT_DIR/build/libs/gemfire-nightly-stats.jar" \
  com.example.gemfire.stats.UploadReports "$@"
