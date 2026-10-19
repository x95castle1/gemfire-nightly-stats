#!/usr/bin/env bash
# Installed beside collect-stats.sh. Preserve its exit status while logging to file and journal.
set -euo pipefail
umask 027
BIN_DIR=$(cd "$(dirname "$0")" && pwd)
LOG_FILE="${LOGS_DIRECTORY:-/var/log/gemfire-nightly-stats}/collector.log"

message() {
  printf '%s %s\n' "$(date -u +'%Y-%m-%dT%H:%M:%SZ')" "$1" | tee -a "$LOG_FILE"
}

message 'Collection started.'
result=0
"$BIN_DIR/collect-stats.sh" "$@" 2>&1 | tee -a "$LOG_FILE" || result=$?
message "Collection finished; exit_status=$result."
exit "$result"
