#!/usr/bin/env bash
# Real S3 regression for a standalone test cluster. Credentials arrive through make's environment.
set -euo pipefail
CONFIG=${1:?Usage: $0 <standalone.properties> <report.json> [ssl: true|false]}
REPORT=${2:?Usage: $0 <standalone.properties> <report.json> [ssl: true|false]}
TEST_SSL=${3:-true}
PROJECT_DIR=$(cd "$(dirname "$0")/../.." && pwd)
COLLECTOR=${STANDALONE_LAUNCHER:-$PROJECT_DIR/scripts/collect-stats.sh}
: "${S3_ENDPOINT:?Set S3_ENDPOINT}"
: "${S3_BUCKET:?Set S3_BUCKET}"
export AWS_ACCESS_KEY_ID=${S3_ACCESS_KEY:?Set S3_ACCESS_KEY}
export AWS_SECRET_ACCESS_KEY=${S3_SECRET_KEY:?Set S3_SECRET_KEY}
TEST_ROOT=$(dirname "$CONFIG")
RUN_ID=$(basename "$TEST_ROOT")
ORIGINAL_REPORT="$REPORT"
cp "$ORIGINAL_REPORT" "$TEST_ROOT/original-report.snapshot"
"$COLLECTOR" status --config "$CONFIG" > "$TEST_ROOT/before-s3.json"
INITIAL_COUNT=$(jq -r .saved_reports "$TEST_ROOT/before-s3.json")
# Each run has its own objects, so it cannot overwrite another test's history.
PREFIX="regression/$RUN_ID"
FOLDER="${S3_ENDPOINT%/}/$S3_BUCKET/$PREFIX/local/test-cluster"
S3_CONFIG="$TEST_ROOT/s3.properties"
cp "$CONFIG" "$S3_CONFIG"
printf '\nDELIVERY_MODE=s3\nUPLOAD_REQUIRED=true\nS3_ENDPOINT=%s\nS3_BUCKET=%s\nS3_PREFIX=%s\nS3_ACCESS_KEY=%s\nS3_SECRET_KEY=%s\n' \
  "$S3_ENDPOINT" "$S3_BUCKET" "$PREFIX" "$S3_ACCESS_KEY" "$S3_SECRET_KEY" >> "$S3_CONFIG"
verify_bytes() {
  "$PROJECT_DIR"/test-support/scripts/verify-upload.sh "${1:-$REPORT}" "$FOLDER" "$AWS_ACCESS_KEY_ID:$AWS_SECRET_ACCESS_KEY"
}
offline_upload() {
  # An accidental JMX connection would fail; backfill must only read the saved files.
  cp "$S3_CONFIG" "$TEST_ROOT/backfill.properties"
  printf '\nJMX_HOST=127.0.0.1\nJMX_PORT=1\n' >> "$TEST_ROOT/backfill.properties"
  "$COLLECTOR" upload-pending --config "$TEST_ROOT/backfill.properties" > "$1"
}

# Enable S3 after collecting locally, and prove both delivery and receipt persistence.
offline_upload "$TEST_ROOT/backfill.json"
jq -e --argjson count "$INITIAL_COUNT" '.uploaded == $count and .pending_uploads == 0 and .upload_error == null' "$TEST_ROOT/backfill.json" >/dev/null
verify_bytes
offline_upload "$TEST_ROOT/unchanged.json"
jq -e '.uploaded == 0 and .pending_uploads == 0' "$TEST_ROOT/unchanged.json" >/dev/null
echo "✓ Historical backfill works without JMX; unchanged reports are skipped"

# Exercise collection with S3 enabled as well as the after-the-fact command.
"$COLLECTOR" collect --config "$S3_CONFIG" > "$TEST_ROOT/s3-collection.json"
jq -e '.collection_succeeded == true and .pending_uploads == 0 and .upload_error == null' "$TEST_ROOT/s3-collection.json" >/dev/null
REPORT=$(jq -r .report_file "$TEST_ROOT/s3-collection.json")
[[ "$REPORT" != "$ORIGINAL_REPORT" ]]
verify_bytes
verify_bytes "$ORIGINAL_REPORT"
cmp "$ORIGINAL_REPORT" "$TEST_ROOT/original-report.snapshot"

# Route just this collector to a closed loopback port. Do not interrupt the shared S3 service.
if lsof -nP -iTCP:1 -sTCP:LISTEN >/dev/null 2>&1; then
  echo "Cannot simulate an outage: loopback port 1 is unexpectedly in use" >&2
  exit 1
fi
OUTAGE_CONFIG="$TEST_ROOT/s3-outage.properties"
cp "$S3_CONFIG" "$OUTAGE_CONFIG"
printf '\nS3_ENDPOINT=http://127.0.0.1:1\nUPLOAD_REQUIRED=false\n' >> "$OUTAGE_CONFIG"
RECEIPT="$(dirname "$ORIGINAL_REPORT")/.uploaded/$(basename "$ORIGINAL_REPORT").receipt"
cp "$RECEIPT" "$TEST_ROOT/receipt-before-outage"
"$COLLECTOR" collect --config "$OUTAGE_CONFIG" > "$TEST_ROOT/s3-deferred.json" 2> "$TEST_ROOT/s3-deferred.err"
jq -e --argjson count "$((INITIAL_COUNT + 2))" '.collection_succeeded == true and .pending_uploads == $count and .upload_error != null' "$TEST_ROOT/s3-deferred.json" >/dev/null
cmp "$RECEIPT" "$TEST_ROOT/receipt-before-outage"
REPORT=$(jq -r .report_file "$TEST_ROOT/s3-deferred.json")
"$PROJECT_DIR"/test-support/scripts/verify-report.sh "$REPORT" 2 "$TEST_SSL"
printf '\nUPLOAD_REQUIRED=true\n' >> "$OUTAGE_CONFIG"
failure=0
"$COLLECTOR" collect --config "$OUTAGE_CONFIG" > "$TEST_ROOT/s3-required.json" 2> "$TEST_ROOT/s3-required.err" || failure=$?
[ "$failure" = 4 ] || { echo "Required upload during outage returned $failure, expected 4" >&2; exit 1; }
jq -e --argjson count "$((INITIAL_COUNT + 3))" '.collection_succeeded == true and .pending_uploads == $count and .upload_error != null' "$TEST_ROOT/s3-required.json" >/dev/null
cmp "$RECEIPT" "$TEST_ROOT/receipt-before-outage"
echo "✓ S3 outage preserves local reports and receipts; required delivery returns exit 4"

# Restore the real endpoint, upload both new reports, and inspect the recovered state.
offline_upload "$TEST_ROOT/s3-recovered.json"
jq -e '.uploaded == 2 and .pending_uploads == 0 and .upload_error == null' "$TEST_ROOT/s3-recovered.json" >/dev/null
verify_bytes
"$COLLECTOR" status --config "$TEST_ROOT/backfill.properties" > "$TEST_ROOT/s3-status.json"
jq -e --argjson count "$((INITIAL_COUNT + 3))" '.saved_reports == $count and .pending_uploads == 0' "$TEST_ROOT/s3-status.json" >/dev/null
offline_upload "$TEST_ROOT/s3-idempotent.json"
jq -e '.uploaded == 0 and .pending_uploads == 0' "$TEST_ROOT/s3-idempotent.json" >/dev/null
for file in "$(dirname "$ORIGINAL_REPORT")/test-cluster-"*.json; do verify_bytes "$file"; done
cmp "$ORIGINAL_REPORT" "$TEST_ROOT/original-report.snapshot"
echo "✓ Recovery uploads every individual report, clears the backlog and preserves earlier files and objects"
echo "S3 regression artifacts: $TEST_ROOT"
