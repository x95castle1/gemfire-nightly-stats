#!/usr/bin/env bash
# Checks a nightly stats report against sample-output.json and against what the Makefile's
# test cluster should contain.
# Usage: scripts/verify-report.sh <report.json> <expected server count> <ssl: true|false>
set -uo pipefail

REPORT=${1:?Usage: $0 <report.json> <expected server count> <ssl: true|false>}
SERVERS=${2:?Usage: $0 <report.json> <expected server count> <ssl: true|false>}
SSL=${3:?Usage: $0 <report.json> <expected server count> <ssl: true|false>}
SAMPLE="$(cd "$(dirname "$0")/.." && pwd)/sample-output.json"
failures=0

# check <description> <jq expression that must be true for the report>
function check() {
  if jq -e "$2" "$REPORT" >/dev/null 2>&1; then
    echo "✓ $1"
  else
    echo "✘ $1"
    failures=$((failures + 1))
  fi
}

[ -f "$REPORT" ] || { echo "✘ No report at '$REPORT'"; exit 1; }
jq empty "$REPORT" 2>/dev/null || { echo "✘ $REPORT isn't valid JSON"; exit 1; }
echo "Checking $REPORT"

# Every object key, with array positions collapsed to [], e.g. members.[].tls.ssl_protocols
FIELDS='[paths | select(.[-1] | type == "string") | map(if type == "number" then "[]" else . end) | join(".")] | unique | .[]'
field_diff=$(diff <(jq -r "$FIELDS" "$SAMPLE") <(jq -r "$FIELDS" "$REPORT"))
if [ -z "$field_diff" ]; then
  echo "✓ Same fields as sample-output.json"
else
  echo "✘ Fields differ from sample-output.json (< sample, > report):"
  echo "$field_diff" | sed 's/^/    /'
  failures=$((failures + 1))
fi

check "collected_at is a UTC timestamp" '.collected_at | test("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z$")'
check "Env and cluster name came from the properties file" '.cluster.env != null and .cluster.cluster_name != null'
check "1 locator" '.cluster.locators_count == 1 and (.cluster.locators | length) == 1'
check "$SERVERS servers, with locators not counted" ".cluster.servers_count == $SERVERS and (.cluster.servers | length) == $SERVERS"
check "All members run on this machine, so 1 node" '.cluster.nodes == 1'
check "The 3 test regions, with the right type" \
  '[.cluster.regions[] | select(.path | startswith("/Test")) | "\(.path)=\(.region_type)"] | sort
   == ["/TestLocal=NORMAL", "/TestPartition=PARTITION", "/TestReplicate=REPLICATE"]'
check "One entry per member, with the right types" \
  "(.members | length) == $((SERVERS + 1))
   and ([.members[] | select(.type == \"locator\")] | length) == 1
   and ([.members[] | select(.type == \"server\")] | length) == $SERVERS"
check "Every member has host, OS, memory, CPUs and address family" \
  'all(.members[]; .host != null and .os.kernel != null and .memory_bytes > 0 and .available_processors > 0
       and (.address_family == "IPv4" or .address_family == "IPv6"))'
check "Every member has heap, uptime, version and install path" \
  'all(.members[]; .memory_quotas.heap_max_mb > 0 and .uptime_seconds >= 0
       and .software_version != null and .installation_path != null)'

if [ "$SSL" = "true" ]; then
  check "SSL is on for every member" 'all(.members[]; .encryption.ssl_enabled == true and .encryption.ssl_enabled_components == ["ALL"])'
  check "TLS protocols were read for every member" 'all(.members[]; .tls.ssl_protocols == "TLSv1.2 TLSv1.3")'
else
  check "SSL is off for every member" 'all(.members[]; .encryption.ssl_enabled == false)'
  check "TLS protocols are the default for every member" 'all(.members[]; .tls.ssl_protocols == "any")'
fi

if [ "$failures" -eq 0 ]; then
  echo "✓ All checks passed"
else
  echo "✘ $failures check(s) failed"
  exit 1
fi
