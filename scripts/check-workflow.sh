#!/usr/bin/env bash
# Exercise the picker and active-environment routing without starting services.
set -euo pipefail
ROOT_DIR=$(cd "$(dirname "$0")/.." && pwd)
FIXTURE=$(mktemp -d)
trap 'rm -rf "$FIXTURE"' EXIT
mkdir -p "$FIXTURE/scripts" "$FIXTURE/bin" "$FIXTURE/examples/01-embedded-locator/scripts" "$FIXTURE/examples/02-standalone-jmx/scripts"
cp "$ROOT_DIR/scripts/examples.sh" "$FIXTURE/scripts/"
cp "$ROOT_DIR/Makefile" "$FIXTURE/Makefile"
cat > "$FIXTURE/bin/fzf" <<'PICKER'
#!/usr/bin/env bash
choices=$(cat)
if [[ "$*" = *'Collection interval > '* ]]; then
  if [[ "${PICK_INTERVAL:-}" = cancel ]]; then exit 130; fi
  printf '%s\n' "${PICK_INTERVAL:-${choices%%$'\n'*}}"
  exit 0
fi
if [[ "$*" = *'Report delivery > '* ]]; then
  if [[ "${PICK_UPLOAD:-}" = cancel ]]; then exit 130; fi
  printf '%s\n' "${PICK_UPLOAD:-Local files only}"
  exit 0
fi
if [[ "${PICK:-}" = cancel ]]; then exit 130; fi
printf '%s\n' "${PICK:-02-standalone-jmx}"
PICKER
for example in 01-embedded-locator 02-standalone-jmx; do
  cat > "$FIXTURE/examples/$example/scripts/dev.sh" <<'CHILD'
#!/usr/bin/env bash
if [[ "$1" = probe ]]; then
  [[ "$(basename "$PWD")" = "${PROBE_EXAMPLE:-}" ]]
  exit $?
fi
printf '%s|%s|%s\n' "$(basename "$PWD")" "$1" "${TEST_DIR:-}" >> "$TRACE"
printf '%s|%s|%s\n' "$(basename "$PWD")" "$1" "${S3:-unset}" >> "$DELIVERY_TRACE"
printf '%s|%s|%s\n' "$(basename "$PWD")" "$1" "${COLLECTION_INTERVAL_SECONDS:-unset}" >> "$INTERVAL_TRACE"
CHILD
  chmod +x "$FIXTURE/examples/$example/scripts/dev.sh"
done
chmod +x "$FIXTURE/bin/fzf"
cat > "$FIXTURE/bin/docker" <<'DOCKER'
#!/usr/bin/env bash
printf 'docker|%s\n' "$*" >> "$TRACE"
DOCKER
cat > "$FIXTURE/gradlew" <<'GRADLE'
#!/usr/bin/env bash
printf 'gradle|%s\n' "$*" >> "$TRACE"
GRADLE
chmod +x "$FIXTURE/bin/docker" "$FIXTURE/gradlew"
export PATH="$FIXTURE/bin:$PATH"
export TRACE="$FIXTURE/trace" DELIVERY_TRACE="$FIXTURE/delivery-trace" INTERVAL_TRACE="$FIXTURE/interval-trace"
cd "$FIXTURE"
unset EXAMPLE TEST_DIR PROPERTIES S3 COLLECTION_INTERVAL_SECONDS

PICK=02-standalone-jmx scripts/examples.sh run
[[ $(sed -n '1p' "$TRACE") = '02-standalone-jmx|start|' ]]
[[ $(sed -n '2p' "$TRACE") = '02-standalone-jmx|run|' ]]
[[ $(head -1 .test-cluster/active-example) = 02-standalone-jmx ]]
[[ $(tail -1 "$DELIVERY_TRACE") = '02-standalone-jmx|run|false' ]]
# Explicit upload settings bypass the delivery picker; delivery cancellation changes nothing.
before=$(wc -l < "$TRACE")
PICK_UPLOAD=cancel EXAMPLE=01 scripts/examples.sh start >/dev/null
[[ $(wc -l < "$TRACE") = "$before" ]]
PICK_UPLOAD='Local files and SeaweedFS' EXAMPLE=02 scripts/examples.sh start
[[ $(tail -1 "$DELIVERY_TRACE") = '02-standalone-jmx|start|true' ]]
PICK_UPLOAD=cancel EXAMPLE=02 S3=false scripts/examples.sh start
[[ $(tail -1 "$DELIVERY_TRACE") = '02-standalone-jmx|start|false' ]]

# Interval selection reaches both startup and collection; cancelling it leaves active state alone.
for choice in 'Once and exit:0' 'Every 30 seconds:30' 'Every minute:60' 'Every 5 minutes:300' 'Every 15 minutes:900'; do
  PICK_INTERVAL="${choice%:*}" EXAMPLE=02 S3=false scripts/examples.sh run
  [[ $(tail -2 "$INTERVAL_TRACE" | head -1) = "02-standalone-jmx|start|${choice##*:}" ]]
  [[ $(tail -1 "$INTERVAL_TRACE") = "02-standalone-jmx|run|${choice##*:}" ]]
done
before=$(wc -l < "$TRACE")
PICK_INTERVAL=cancel EXAMPLE=01 S3=false scripts/examples.sh run >/dev/null
[[ $(wc -l < "$TRACE") = "$before" ]]
[[ $(head -1 .test-cluster/active-example) = 02-standalone-jmx ]]
if PICK_INTERVAL=invalid EXAMPLE=02 S3=false scripts/examples.sh run >/dev/null 2>&1; then exit 1; fi
[[ $(wc -l < "$TRACE") = "$before" ]]
PICK_INTERVAL=cancel EXAMPLE=02 S3=false COLLECTION_INTERVAL_SECONDS=42 scripts/examples.sh run
[[ $(tail -1 "$INTERVAL_TRACE") = '02-standalone-jmx|run|42' ]]

# Embedded normal scheduling uses the nightly label without switching the managed environment
# when custom properties are supplied.
EXAMPLE=01 S3=false PROPERTIES=custom.properties PICK_INTERVAL='Nightly (configured time)' scripts/examples.sh run
[[ $(tail -1 "$INTERVAL_TRACE") = '01-embedded-locator|run|0' ]]
[[ $(head -1 .test-cluster/active-example) = 02-standalone-jmx ]]

# Stop must use the recorded directory and must not open the picker.
PICK=cancel scripts/examples.sh stop
[[ $(tail -1 "$TRACE") = "02-standalone-jmx|stop|$FIXTURE/examples/02-standalone-jmx/.test-cluster" ]]
[[ ! -f .test-cluster/active-example ]]
[[ -f .test-cluster/last-example ]]

# The UI command uses the last environment without opening the picker.
PICK=cancel scripts/examples.sh admin
[[ $(tail -1 "$TRACE") = "02-standalone-jmx|admin|$FIXTURE/examples/02-standalone-jmx/.test-cluster" ]]

# Cancellation and invalid input must not start any components.
before=$(wc -l < "$TRACE")
PICK=cancel scripts/examples.sh start >/dev/null
[[ $(wc -l < "$TRACE") = "$before" ]]
if EXAMPLE=../../other scripts/examples.sh start >/dev/null 2>&1; then exit 1; fi
[[ $(wc -l < "$TRACE") = "$before" ]]

# Switching examples stops the previous managed environment first.
EXAMPLE=01 TEST_DIR="$FIXTURE/custom-cluster" scripts/examples.sh start
EXAMPLE=02 scripts/examples.sh start
[[ $(tail -2 "$TRACE" | head -1) = "01-embedded-locator|stop|$FIXTURE/custom-cluster" ]]
[[ $(tail -1 "$TRACE") = '02-standalone-jmx|start|' ]]

# An explicit request for the inactive example must not stop the active store.
before=$(wc -l < "$TRACE")
if EXAMPLE=01 scripts/examples.sh stop >/dev/null 2>&1; then exit 1; fi
[[ $(wc -l < "$TRACE") = "$before" ]]

# Recover a running example even when both its marker and the active record were lost.
mkdir -p examples/02-standalone-jmx/.test-cluster
printf 'LOCATOR_PORT=20334\n' > examples/02-standalone-jmx/.test-cluster/runtime.settings
rm .test-cluster/active-example
before=$(wc -l < "$TRACE")
PROBE_EXAMPLE=02-standalone-jmx PICK_INTERVAL=cancel EXAMPLE=01 S3=false scripts/examples.sh run >/dev/null
[[ $(wc -l < "$TRACE") = "$before" ]]
[[ ! -f .test-cluster/active-example ]]
PROBE_EXAMPLE=02-standalone-jmx scripts/examples.sh status
[[ $(head -1 .test-cluster/active-example) = 02-standalone-jmx ]]
[[ $(tail -1 "$TRACE") = "02-standalone-jmx|status|$FIXTURE/examples/02-standalone-jmx/.test-cluster" ]]
# A stale record for the other example must not hide the live one when switching.
printf '01-embedded-locator\n%s\n' "$FIXTURE/examples/01-embedded-locator/.test-cluster" > .test-cluster/active-example
PROBE_EXAMPLE=02-standalone-jmx EXAMPLE=01 S3=false scripts/examples.sh start
[[ $(tail -2 "$TRACE" | head -1) = "02-standalone-jmx|stop|$FIXTURE/examples/02-standalone-jmx/.test-cluster" ]]
[[ $(tail -1 "$TRACE") = '01-embedded-locator|start|' ]]
EXAMPLE=02 S3=false scripts/examples.sh start

# Clean must find the last environment after stop and explicitly remove volumes.
scripts/examples.sh stop
scripts/examples.sh clean
grep -q '^02-standalone-jmx|clean|' "$TRACE"
grep -q '^docker|compose .*down --volumes$' "$TRACE"
[[ ! -d .test-cluster ]]

for target in start-locator start-servers create-regions run-embedded collect collect-standalone test-embedded test-standalone s3-up s3-down; do
  if make -n "$target" >/dev/null 2>&1; then echo "Unexpected Make target: $target" >&2; exit 1; fi
done
echo 'Workflow checks passed: picker, active state, admin routing, volume cleanup and simplified targets.'
