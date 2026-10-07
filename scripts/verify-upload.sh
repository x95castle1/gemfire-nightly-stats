#!/usr/bin/env bash
# Checks that a nightly stats report was uploaded to S3 unchanged, by downloading it with curl's
# own SigV4 signing (a second implementation, independent of S3Uploader).
# Usage: scripts/verify-upload.sh <report.json> <S3 folder URL> <access key:secret key>
#   e.g. scripts/verify-upload.sh report.json http://localhost:8333/gemfire-stats/nightly-stats/local/test-cluster key:secret
set -uo pipefail

REPORT=${1:?Usage: $0 <report.json> <S3 folder URL> <access key:secret key>}
FOLDER=${2:?Usage: $0 <report.json> <S3 folder URL> <access key:secret key>}
CREDENTIALS=${3:?Usage: $0 <report.json> <S3 folder URL> <access key:secret key>}

[ -f "$REPORT" ] || { echo "✘ No report at '$REPORT'"; exit 1; }
URL="$FOLDER/$(basename "$REPORT")"
DOWNLOADED=$(mktemp)
trap 'rm -f "$DOWNLOADED"' EXIT

status=$(curl -s -o "$DOWNLOADED" -w '%{http_code}' --aws-sigv4 "aws:amz:us-east-1:s3" --user "$CREDENTIALS" "$URL")
if [ "$status" != 200 ]; then
  echo "✘ Couldn't download $URL (HTTP $status)"
  exit 1
fi
if cmp -s "$REPORT" "$DOWNLOADED"; then
  echo "✓ Uploaded to $URL, identical to the file on disk"
else
  echo "✘ $URL differs from $REPORT"
  exit 1
fi
