#!/usr/bin/env bash
# Real-process integration test. Uses an isolated cluster and always stops the members it starts.
set -euo pipefail
PROJECT_DIR=$(cd "$(dirname "$0")/../.." && pwd)
cd "$PROJECT_DIR"
COLLECTOR=${STANDALONE_LAUNCHER:-$PROJECT_DIR/examples/02-standalone-jmx/scripts/collect-stats.sh}
TEST_SSL=${1:-true}
TEST_AUTH=${2:-false}
TEST_S3=${3:-false}
TEST_MODE=${4:-standalone}
case "$TEST_MODE" in
  standalone|embedded) ;;
  *) echo "Unknown test mode: $TEST_MODE" >&2; exit 2 ;;
esac
TEST_ROOT=${STANDALONE_TEST_DIR:-$PROJECT_DIR/.test-cluster/$TEST_MODE-$TEST_SSL-$TEST_AUTH-$(date +%s)-$$}
TEST_LOCATOR_PORT=${STANDALONE_LOCATOR_PORT:-21334}
TEST_JMX_PORT=${STANDALONE_JMX_PORT:-22099}
TEST_SERVER_PORT=${STANDALONE_SERVER_PORT:-41504}
cluster_make=(make --no-print-directory "TEST_DIR=$TEST_ROOT" "SSL=$TEST_SSL" "AUTH=$TEST_AUTH" "S3=false" "START_MODE=$TEST_MODE"
  "LOCATOR_PORT=$TEST_LOCATOR_PORT" "JMX_PORT=$TEST_JMX_PORT" "SERVER_PORT_BASE=$TEST_SERVER_PORT")
if lsof -nP -iTCP:"$TEST_LOCATOR_PORT" -sTCP:LISTEN >/dev/null 2>&1 \
  || lsof -nP -iTCP:"$TEST_JMX_PORT" -sTCP:LISTEN >/dev/null 2>&1; then
  echo "Test ports are in use. Override STANDALONE_LOCATOR_PORT/STANDALONE_JMX_PORT/STANDALONE_SERVER_PORT." >&2
  exit 1
fi
trap '"${cluster_make[@]}" stop' EXIT
"${cluster_make[@]}" start
CONFIG="$TEST_ROOT/standalone.properties"
cat > "$CONFIG" <<EOF
ENV=local
CLUSTER_NAME=test-cluster
JMX_HOST=localhost
JMX_PORT=$TEST_JMX_PORT
REPORT_DIR=$TEST_ROOT/reports
DELIVERY_MODE=local
EOF
if [ "$TEST_SSL" = true ]; then
  sed -n '/^ssl-/s/^/gemfire./p' "$TEST_ROOT/gfsecurity.properties" >> "$CONFIG"
  echo 'gemfire.ssl-endpoint-identification-enabled=true' >> "$CONFIG"
fi
if [ "$TEST_AUTH" = true ]; then
  echo 'JMX_USERNAME=clusterread' >> "$CONFIG"
  echo 'JMX_PASSWORD=clusterread' >> "$CONFIG"
fi
"$COLLECTOR" collect --config "$CONFIG" > "$TEST_ROOT/collection.json"
jq -e '.collection_succeeded == true and .delivery_mode == "local" and .pending_uploads == null' "$TEST_ROOT/collection.json" >/dev/null
REPORT=$(jq -r .report_file "$TEST_ROOT/collection.json")
test-support/scripts/verify-report.sh "$REPORT" 2 "$TEST_SSL"
"$COLLECTOR" status --config "$CONFIG" > "$TEST_ROOT/status.json"
jq -e '.saved_reports == 1 and .report_bytes > 0' "$TEST_ROOT/status.json" >/dev/null
ARCHIVE="$TEST_ROOT/handoff-$(date +%s).zip"
"$COLLECTOR" export --config "$CONFIG" --output "$ARCHIVE" > "$TEST_ROOT/export.json"
unzip -p "$ARCHIVE" manifest.json | jq -e '.reports | length == 1' >/dev/null
if [ "$TEST_MODE" = standalone ] && find "$TEST_ROOT/locator" -name 'test-cluster-*.json' -print | read -r _; then
  echo "Unexpected embedded report in the standalone test cluster" >&2
  exit 1
fi
if [ "$TEST_AUTH" = true ]; then
  sed 's/JMX_PASSWORD=clusterread/JMX_PASSWORD=wrong/' "$CONFIG" > "$TEST_ROOT/bad-password.properties"
  failure=0
  "$COLLECTOR" collect --config "$TEST_ROOT/bad-password.properties" > "$TEST_ROOT/rejected.json" 2> "$TEST_ROOT/rejected.err" || failure=$?
  [ "$failure" = 3 ] || { echo "Wrong password returned $failure, expected 3" >&2; exit 1; }
  sed -e '/^JMX_USERNAME=/d' -e '/^JMX_PASSWORD=/d' "$CONFIG" > "$TEST_ROOT/token.properties"
  echo 'JMX_TOKEN=FOO_BAR' >> "$TEST_ROOT/token.properties"
  "$COLLECTOR" collect --config "$TEST_ROOT/token.properties" > "$TEST_ROOT/token.json"
  jq -e '.collection_succeeded == true' "$TEST_ROOT/token.json" >/dev/null
  echo "✓ Remote collection with a reader role, token authentication, rejected credentials, status and export passed (SSL=$TEST_SSL)"
  if [ "$TEST_S3" = true ]; then test-support/scripts/verify-standalone-s3.sh "$CONFIG" "$REPORT" "$TEST_SSL"; fi
  exit 0
fi
if [ "$TEST_MODE" = embedded ]; then
  # Compare both modes against the same cluster, allowing clocks and uptime to differ.
  EMBEDDED=""
  for ((attempt=0; attempt<90; attempt++)); do
    EMBEDDED=$(find "$TEST_ROOT/locator/nightly-stats" -maxdepth 1 -name 'test-cluster-*.json' -print 2>/dev/null | head -1) || true
    [ -n "$EMBEDDED" ] && break
    sleep 1
  done
  [ -n "$EMBEDDED" ] || { echo "Embedded startup report did not arrive" >&2; exit 1; }
  test-support/scripts/verify-report.sh "$EMBEDDED" 2 "$TEST_SSL"
  diff <(jq -S 'del(.collected_at, .members[].uptime_seconds)' "$EMBEDDED") \
       <(jq -S 'del(.collected_at, .members[].uptime_seconds)' "$REPORT")
  echo "✓ Embedded and standalone reports match (SSL=$TEST_SSL)"
else
  echo "✓ Standalone collection, status and export passed against a gfsh locator (SSL=$TEST_SSL)"
fi
if [ "$TEST_S3" = true ]; then test-support/scripts/verify-standalone-s3.sh "$CONFIG" "$REPORT" "$TEST_SSL"; fi
