#!/usr/bin/env bash
# Verify ownership and marker recovery without touching real services or binding ports.
set -euo pipefail
ROOT_DIR=$(cd "$(dirname "$0")/.." && pwd)
FIXTURE=$(mktemp -d)
trap 'rm -rf "$FIXTURE"' EXIT
mkdir -p "$FIXTURE/bin" "$FIXTURE/gemfire/bin"
export TRACE="$FIXTURE/trace" BINDINGS="$FIXTURE/bindings" OWNER_PID=$$
cat > "$FIXTURE/bin/lsof" <<'LSOF'
#!/usr/bin/env bash
pid_filter="" port=""
while (($#)); do
  case "$1" in -p) pid_filter=$2; shift ;; -iTCP:*) port=${1#-iTCP:} ;; esac
  shift
done
while read -r pid bound_port; do
  if [[ "$port" = "$bound_port" && ( -z "$pid_filter" || "$pid_filter" = "$pid" ) ]]; then
    echo "$pid"
    exit 0
  fi
done < "$BINDINGS"
exit 1
LSOF
cat > "$FIXTURE/bin/docker" <<'DOCKER'
#!/usr/bin/env bash
printf 'docker|%s\n' "$*" >> "$TRACE"
DOCKER
cat > "$FIXTURE/gemfire/bin/gfsh" <<'GFSH'
#!/usr/bin/env bash
printf 'gfsh|%s\n' "$*" >> "$TRACE"
case "$*" in
  *'stop locator'*) first=20334; second=21099 ;;
  *'stop server'*'server-0'*) first=40504; second=40504 ;;
  *'stop server'*'server-1'*) first=40505; second=40505 ;;
  *) exit 1 ;;
esac
if [[ "${DELAY_STOP:-}" = true ]]; then
  directory=${*: -1}
  directory=${directory##*--dir=}
  if [[ "$*" = *'stop locator'* ]]; then
    rm -f "$directory/vf.gf.locator.pid"
    (sleep 1; : > "$BINDINGS") &
  else rm -f "$directory/vf.gf.server.pid"; fi
  exit 0
fi
awk -v first="$first" -v second="$second" '$2 != first && $2 != second' "$BINDINGS" > "$BINDINGS.next"
mv "$BINDINGS.next" "$BINDINGS"
GFSH
chmod +x "$FIXTURE/bin/"* "$FIXTURE/gemfire/bin/gfsh"
export PATH="$FIXTURE/bin:$PATH" GEMFIRE_HOME="$FIXTURE/gemfire"
unset TEST_DIR ENV_FILE COMPOSE_FILE COMPOSE_PROJECT_NAME S3 COLLECTION_INTERVAL_SECONDS
unset SSL AUTH SERVER_COUNT LOCATOR_PORT JMX_PORT SERVER_PORT_BASE ADMIN_UI_PORT S3_PORT S3_ENDPOINT
unset MAKEFLAGS MFLAGS MAKEOVERRIDES
for name in 01-embedded-locator 02-standalone-jmx; do
  example="$FIXTURE/$name"
  mkdir -p "$example/scripts" "$example/.test-cluster/locator" "$example/.test-cluster/server-0" "$example/.test-cluster/server-1"
  cp "$ROOT_DIR/examples/$name/scripts/dev.sh" "$example/scripts/"
  cp "$ROOT_DIR/examples/$name/.env.example" "$example/.env"
  printf '#!/usr/bin/env bash\nprintf "unexpected-build\\n" >> "$TRACE"\nexit 1\n' > "$example/gradlew"
  chmod +x "$example/gradlew"
  cat > "$example/.test-cluster/runtime.settings" <<'SETTINGS'
SSL=false
AUTH=false
SERVER_COUNT=2
LOCATOR_PORT=20334
JMX_PORT=21099
SERVER_PORT_BASE=40504
S3=true
COLLECTION_INTERVAL_SECONDS=0
SETTINGS
  for file in locator/vf.gf.locator.pid server-0/vf.gf.server.pid server-1/vf.gf.server.pid; do
    printf '%s' "$OWNER_PID" > "$example/.test-cluster/$file"
  done
  for port in 20334 21099 40504 40505; do printf '%s %s\n' "$OWNER_PID" "$port"; done > "$BINDINGS"
  : > "$TRACE"
  (
    cd "$example"
    # Ownership discovery is read-only. Reuse repairs a missing marker without starting duplicates.
    scripts/dev.sh probe
    [[ ! -f .test-cluster/running ]]
    S3=true scripts/dev.sh start > "$FIXTURE/output"
    grep -q 'Environment is already running' "$FIXTURE/output"
    [[ -f .test-cluster/running ]]
    ! grep -q 'unexpected-build\|gfsh|' "$TRACE"
    # A stale marker and PID file must not claim (or stop) an unrelated listener.
    # Surviving servers are discoverable even when the locator PID file is missing.
    rm .test-cluster/locator/vf.gf.locator.pid
    scripts/dev.sh probe
    for file in locator/vf.gf.locator.pid server-0/vf.gf.server.pid server-1/vf.gf.server.pid; do
      printf '999999999' > ".test-cluster/$file"
    done
    if scripts/dev.sh probe; then exit 1; fi
    if S3=true scripts/dev.sh start > "$FIXTURE/output" 2>&1; then exit 1; fi
    grep -q 'already in use by another environment' "$FIXTURE/output"
    ! grep -q 'gfsh|' "$TRACE"
    # The managed members are stopped using only their verified PID and port ownership.
    for file in locator/vf.gf.locator.pid server-0/vf.gf.server.pid server-1/vf.gf.server.pid; do
      printf '%s' "$OWNER_PID" > ".test-cluster/$file"
    done
    DELAY_STOP=true scripts/dev.sh stop >/dev/null
    [[ ! -s "$BINDINGS" && ! -f .test-cluster/running ]]
    [[ $(grep -c '^gfsh|' "$TRACE") = 3 ]]
  )
done
echo 'Recovery checks passed: missing markers, PID ownership, unrelated listeners and verified shutdown.'
