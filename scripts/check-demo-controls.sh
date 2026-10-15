#!/usr/bin/env bash
# Test browser URLs and clean/stop behavior in disposable fixtures, without opening a browser.
set -euo pipefail
ROOT_DIR=$(cd "$(dirname "$0")/.." && pwd)
FIXTURE=$(mktemp -d)
trap 'rm -rf "$FIXTURE"' EXIT
mkdir -p "$FIXTURE/bin" "$FIXTURE/gemfire/bin"
cat > "$FIXTURE/bin/open" <<'OPEN'
#!/usr/bin/env bash
printf 'open|%s\n' "$*" >> "$TRACE"
OPEN
cat > "$FIXTURE/bin/docker" <<'DOCKER'
#!/usr/bin/env bash
printf 'docker|%s\n' "$*" >> "$TRACE"
DOCKER
cat > "$FIXTURE/bin/fzf" <<'PICKER'
#!/usr/bin/env bash
choices=$(cat)
if [[ "$*" = *'Collection interval > '* ]]; then
  if [[ "${PICK_INTERVAL:-}" = cancel ]]; then exit 130; fi
  printf '%s\n' "${PICK_INTERVAL:-${choices%%$'\n'*}}"
  exit 0
fi
if [[ "${PICK_UPLOAD:-}" = cancel ]]; then exit 130; fi
printf '%s\n' "${PICK_UPLOAD:-Local files only}"
PICKER
cat > "$FIXTURE/bin/collector" <<'COLLECTOR'
#!/usr/bin/env bash
config=${@: -1}
printf 'collector|%s|%s\n' "$(sed -n 's/^DELIVERY_MODE=//p' "$config" | tail -1)" "$config" >> "$TRACE"
printf '%s|%s\n' "${COLLECTION_INTERVAL_SECONDS:-unset}" "$(sed -n 's/^COLLECTION_INTERVAL_SECONDS=//p' "$config" | tail -1)" >> "$INTERVAL_TRACE"
count=$(grep -c '^collector|' "$TRACE")
if [[ "${RETRY_TEST:-}" = true && "$count" = 1 ]]; then exit 3; fi
exit "${COLLECTOR_RESULT:-0}"
COLLECTOR
cat > "$FIXTURE/bin/sleep" <<'SLEEP'
#!/usr/bin/env bash
if [[ "${RETRY_TEST:-}" != true ]]; then exec /bin/sleep "$@"; fi
printf 'sleep|%s\n' "$*" >> "$TRACE"
if [[ $(grep -c '^sleep|' "$TRACE") -ge 2 ]]; then exit 99; fi
SLEEP
printf '#!/usr/bin/env bash\nexit 0\n' > "$FIXTURE/gemfire/bin/gfsh"
chmod +x "$FIXTURE/bin/"* "$FIXTURE/gemfire/bin/gfsh"
export PATH="$FIXTURE/bin:$PATH" GEMFIRE_HOME="$FIXTURE/gemfire" TRACE="$FIXTURE/trace" INTERVAL_TRACE="$FIXTURE/interval-trace"
export LOCATOR_PORT=1 JMX_PORT=2
unset TEST_DIR ENV_FILE COMPOSE_FILE COMPOSE_PROJECT_NAME ADMIN_UI_PORT S3 COLLECTION_INTERVAL_SECONDS PROPERTIES
unset MAKEFLAGS MFLAGS MAKEOVERRIDES
for name in 01-embedded-locator 02-standalone-jmx; do
  example="$FIXTURE/$name"
  mkdir -p "$example/scripts"
  cp "$ROOT_DIR/examples/$name/scripts/dev.sh" "$example/scripts/"
  cp "$ROOT_DIR/examples/$name/Makefile" "$example/"
  cp "$ROOT_DIR/examples/$name/.env.example" "$example/.env"
  printf '#!/usr/bin/env bash\nexit 0\n' > "$example/gradlew"
  chmod +x "$example/gradlew"
  (
    cd "$example"
    make admin >/dev/null
    [[ $(tail -1 "$TRACE") = 'open|http://localhost:23646' ]]
    printf '\nADMIN_UI_PORT=35555\n' >> .env
    make admin >/dev/null
    [[ $(tail -1 "$TRACE") = 'open|http://localhost:35555' ]]
    mkdir -p .test-cluster
    printf 'ADMIN_UI_PORT=36666\n' > .test-cluster/runtime.settings
    make admin >/dev/null
    [[ $(tail -1 "$TRACE") = 'open|http://localhost:36666' ]]
    make stop >/dev/null
    [[ $(tail -1 "$TRACE") = 'docker|compose --env-file '*' down' ]]
    make clean >/dev/null
    [[ $(tail -1 "$TRACE") = 'docker|compose --env-file '*' down --volumes' ]]
    [[ ! -d .test-cluster ]]

    # Choosing delivery with a custom config must override it without changing the source.
    printf 'DELIVERY_MODE=s3\nCOLLECTION_INTERVAL_SECONDS=120\n' > custom.properties
    cp custom.properties original.properties
    export EMBEDDED_LAUNCHER="$FIXTURE/bin/collector" STANDALONE_LAUNCHER="$FIXTURE/bin/collector"
    : > "$TRACE"
    PROPERTIES=custom.properties make run >/dev/null
    grep -q '^collector|local|' "$TRACE"
    config=$(tail -1 "$TRACE" | cut -d '|' -f 3)
    [[ ! -f "$config" ]]
    cmp custom.properties original.properties
    PICK_UPLOAD='Local files and SeaweedFS' PROPERTIES=custom.properties make run >/dev/null
    [[ $(tail -1 "$TRACE") = 'collector|s3|'* ]]
    before=$(wc -l < "$TRACE")
    PICK_UPLOAD=cancel PROPERTIES=custom.properties make run >/dev/null
    [[ $(wc -l < "$TRACE") = "$before" ]]
    if S3=false COLLECTION_INTERVAL_SECONDS=-1 PROPERTIES=custom.properties make run >/dev/null 2>&1; then exit 1; fi

    # Both self-contained examples offer the same presets, with a mode-specific normal schedule.
    for choice in 'Every 30 seconds:30' 'Every minute:60' 'Every 5 minutes:300' 'Every 15 minutes:900'; do
      result=0
      PICK_INTERVAL="${choice%:*}" COLLECTOR_RESULT=2 S3=false PROPERTIES=custom.properties make run >/dev/null 2>&1 || result=$?
      [[ "$result" != 0 ]]
      if [[ "$name" = 01* ]]; then
        [[ $(tail -1 "$INTERVAL_TRACE") = "${choice##*:}|${choice##*:}" ]]
      else [[ $(tail -1 "$INTERVAL_TRACE") = "${choice##*:}|120" ]]; fi
    done
    before=$(wc -l < "$TRACE")
    PICK_INTERVAL=cancel S3=false PROPERTIES=custom.properties make run >/dev/null
    [[ $(wc -l < "$TRACE") = "$before" ]]
    PICK_INTERVAL=cancel S3=false COLLECTION_INTERVAL_SECONDS=0 PROPERTIES=custom.properties make run >/dev/null
    [[ $(tail -1 "$INTERVAL_TRACE") = '0|'* ]]
    cmp custom.properties original.properties

    if [[ "$name" = 02* ]]; then
      : > "$TRACE"
      # A failed collection retries, with no overlapping processes; stop after two fake sleeps.
      if RETRY_TEST=true S3=false COLLECTION_INTERVAL_SECONDS=60 PROPERTIES=custom.properties make run >/dev/null 2>&1; then exit 1; fi
      [[ $(grep -c '^collector|local|' "$TRACE") = 2 ]]
      [[ $(grep -c '^sleep|60$' "$TRACE") = 2 ]]
      : > "$TRACE"
      if COLLECTOR_RESULT=2 S3=false COLLECTION_INTERVAL_SECONDS=60 PROPERTIES=custom.properties make run >/dev/null 2>&1; then exit 1; fi
      [[ $(grep -c '^collector|' "$TRACE") = 1 ]]
      ! grep -q '^sleep|' "$TRACE"
    fi
  )
done
echo 'Demo controls passed: browser, volume cleanup, delivery choices, preserved custom settings and repeated collection.'
