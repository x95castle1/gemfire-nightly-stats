#!/usr/bin/env bash
set -euo pipefail
timeout_seconds=${VERIFY_TIMEOUT_SECONDS:-180}
[[ "$timeout_seconds" =~ ^[1-9][0-9]*$ ]] || { echo 'VERIFY_TIMEOUT_SECONDS must be positive.' >&2; exit 2; }
deadline=$((SECONDS + timeout_seconds))
# docker compose restart returns before boot-time installation finishes.
until systemctl is-active --quiet collector-install-bootstrap.service && systemctl is-active --quiet gemfire-nightly-stats.timer; do
  if systemctl is-failed --quiet collector-install-bootstrap.service || ((SECONDS >= deadline)); then
    echo 'Boot-time installation did not complete successfully.' >&2
    journalctl --no-pager -u collector-install-bootstrap.service -n 80 >&2
    exit 1
  fi
  sleep 1
done
systemd-analyze verify /etc/systemd/system/gemfire-nightly-stats.service /etc/systemd/system/gemfire-nightly-stats.timer
[[ -r /run/collector-install-test.started ]] || { echo 'The boot-time installer has not completed.' >&2; exit 1; }
reports=/var/lib/gemfire-nightly-stats/reports
log=$(sed -n 's/^Environment=COLLECTOR_LOG_FILE=//p' /etc/systemd/system/gemfire-nightly-stats.service.d/zz-collector-logging.conf)
[[ -n "$log" ]] || { echo 'The installed service has no configured log file.' >&2; exit 1; }
delivery_mode=$(awk -F= '/^[[:space:]]*DELIVERY_MODE[[:space:]]*=/ {
  value=substr($0,index($0,"=")+1); gsub(/^[[:space:]]+|[[:space:]]+$/, "", value)
  gsub(/^"|"$/, "", value); mode=value
} END { print mode }' /etc/gemfire-nightly-stats/collector.properties)
while ((SECONDS < deadline)); do
  files=()
  while IFS= read -r file; do files+=("$file"); done < <(find "$reports" -type f -name '*.json' -newer /run/collector-install-test.started)
  if ((${#files[@]} >= 2)); then
    complete=true
    for file in "${files[@]}"; do
      jq -e '.collected_at != null and .cluster.cluster_name != null and (.members | length) > 0' "$file" >/dev/null
      if [[ "$delivery_mode" = s3 ]]; then
        [[ -f "$(dirname "$file")/.uploaded/$(basename "$file").receipt" ]] || complete=false
      fi
    done
    if [[ "$complete" = true ]]; then
      for file in "${files[@]}"; do
        grep -Fq "$(basename "$file")" "$log" || complete=false
      done
    fi
    if [[ "$complete" = true ]]; then
      printf 'Verified %s distinct reports produced by the systemd timer since container startup.\n' "${#files[@]}"
      printf 'Collection output is present in %s.\n' "$log"
      printf '%s\n' "${files[@]}"
      exit 0
    fi
  fi
  sleep 2
done
echo 'No two successful scheduled reports (and S3 receipts, when enabled) before the timeout.' >&2
echo 'Check JMX/RMI reachability, Docker Desktop host networking, credentials and TLS store paths.' >&2
journalctl --no-pager -u collector-install-bootstrap.service -u gemfire-nightly-stats.service -n 80 >&2
exit 1
