#!/usr/bin/env bash
# All development operations for this self-contained example.
set -euo pipefail
umask 077
MODE=standalone
PROJECT_DIR=$(cd "$(dirname "$0")/.." && pwd)
cd "$PROJECT_DIR"
ACTION=${1:-help}

case "$ACTION" in
  help)
    cat <<'HELP'
  build   Build and test this example
  run     Start the local environment and run the example (PROPERTIES=file uses your configuration)
  start   Start the locator, two servers, sample regions and SeaweedFS
  stop    Stop the environment, keeping reports and SeaweedFS data
  status  Show the environment, cluster members and Ubuntu collector timer/result
  check   Diagnose the installed Ubuntu collector (TEST_UPLOAD=true also tests S3 writes)
  logs    Follow the Ubuntu collector journal when running, otherwise the locator log
  admin   Open the SeaweedFS Admin UI in your browser
  test    Run an isolated integration test
  dist    Build ZIP and TAR distributions
  clean   Stop the environment and delete builds, test files and SeaweedFS volumes

Settings: SSL=true AUTH=false SERVER_COUNT=2 COLLECTION_INTERVAL_SECONDS=0
start/run ask where to deliver reports. S3=true or S3=false bypasses the picker.
run also asks for the collection interval. COLLECTION_INTERVAL_SECONDS skips that picker.
COLLECTION_INTERVAL_SECONDS=60 repeats every minute; 0 keeps the normal schedule.
run asks for local Java or Ubuntu systemd. RUN_MODE=local or ubuntu skips that picker.
The Ubuntu timer requires a positive interval. status includes its timer and last service result.
SeaweedFS always starts. Configuration is saved under .test-cluster/.
check runs on the Ubuntu VM with installed properties and runtime settings; it uses sudo.
HELP
    exit 0 ;;
  build) exec ./gradlew build --quiet ;;
  dist) exec ./gradlew assembleDist --quiet ;;
  check)
    set --
    case "${TEST_UPLOAD:-false}" in
      true) set -- --test-upload ;;
      false) ;;
      *) echo 'TEST_UPLOAD must be true or false.' >&2; exit 2 ;;
    esac
    [[ $(uname -s) = Linux ]] || { echo 'Run make check on the Ubuntu collector VM.' >&2; exit 2; }
    if [[ $(id -u) = 0 ]]; then exec "$PROJECT_DIR/installation/check.sh" "$@"; fi
    exec sudo "$PROJECT_DIR/installation/check.sh" "$@" ;;
esac

# Keep this example independently usable without the repository dispatcher.
if [[ "$ACTION" = run && -z "${RUN_MODE+x}" ]]; then
  command -v fzf >/dev/null || { echo 'Install fzf, or set RUN_MODE=local or ubuntu.' >&2; exit 2; }
  if ! execution=$(printf '%s\n' 'Local Java process' 'Ubuntu systemd installation' | fzf --prompt='Collector runtime > ' --height=8 --layout=reverse); then
    echo 'Selection cancelled.'; exit 0
  fi
  case "$execution" in
    'Local Java process') export RUN_MODE=local ;;
    'Ubuntu systemd installation') export RUN_MODE=ubuntu ;;
    *) echo "Unknown collector runtime: $execution" >&2; exit 2 ;;
  esac
fi
RUN_MODE=${RUN_MODE:-local}
case "$RUN_MODE" in local|ubuntu) ;; *) echo 'RUN_MODE must be local or ubuntu.' >&2; exit 2 ;; esac

# Select delivery before starting or stopping any services. Explicit S3 skips the picker.
if [[ ( "$ACTION" = start || "$ACTION" = run ) && -z "${S3+x}" ]]; then
  command -v fzf >/dev/null || { echo 'Install fzf, or set S3=true or S3=false.' >&2; exit 2; }
  if ! delivery=$(printf '%s\n' 'Local files only' 'Local files and SeaweedFS' | fzf --prompt='Report delivery > ' --height=8 --layout=reverse); then
    echo 'Selection cancelled.'
    exit 0
  fi
  case "$delivery" in
    'Local files only') export S3=false ;;
    'Local files and SeaweedFS') export S3=true ;;
    *) echo "Unknown delivery choice: $delivery" >&2; exit 2 ;;
  esac
fi

# Pick the interval before any services change. An explicit value bypasses this picker.
if [[ "$ACTION" = run && -z "${COLLECTION_INTERVAL_SECONDS+x}" ]]; then
  command -v fzf >/dev/null || { echo 'Install fzf, or set COLLECTION_INTERVAL_SECONDS.' >&2; exit 2; }
  if [[ "$MODE" = embedded ]]; then normal_schedule='Nightly (configured time)';
  else normal_schedule='Once and exit'; fi
  intervals=('Every 30 seconds' 'Every minute' 'Every 5 minutes' 'Every 15 minutes')
  if [[ "$RUN_MODE" != ubuntu ]]; then intervals=("$normal_schedule" "${intervals[@]}"); fi
  if ! interval=$(printf '%s\n' "${intervals[@]}" \
      | fzf --prompt='Collection interval > ' --height=10 --layout=reverse); then
    echo 'Selection cancelled.'
    exit 0
  fi
  case "$interval" in
    "$normal_schedule") export COLLECTION_INTERVAL_SECONDS=0 ;;
    'Every 30 seconds') export COLLECTION_INTERVAL_SECONDS=30 ;;
    'Every minute') export COLLECTION_INTERVAL_SECONDS=60 ;;
    'Every 5 minutes') export COLLECTION_INTERVAL_SECONDS=300 ;;
    'Every 15 minutes') export COLLECTION_INTERVAL_SECONDS=900 ;;
    *) echo "Unknown collection interval: $interval" >&2; exit 2 ;;
  esac
fi

absolute() {
  case "$1" in /*) printf '%s\n' "$1" ;; *) printf '%s/%s\n' "$PROJECT_DIR" "$1" ;; esac
}

if [[ "$ACTION" = test ]]; then
  TEST_DIR=${TEST_DIR:-"$PROJECT_DIR/.test-cluster/test-$(date +%s)-$$"}
  LOCATOR_PORT=${LOCATOR_PORT:-21334}
  JMX_PORT=${JMX_PORT:-22099}
  SERVER_PORT_BASE=${SERVER_PORT_BASE:-41504}
  S3_PORT=${S3_PORT:-18333}
  ADMIN_UI_PORT=${ADMIN_UI_PORT:-33646}
  COMPOSE_PROJECT_NAME=${COMPOSE_PROJECT_NAME:-"gemfire-stats-test-$$"}
fi
TEST_DIR=$(absolute "${TEST_DIR:-.test-cluster}")
RUNTIME_FILE="$TEST_DIR/runtime.settings"
COLLECTION_INTERVAL_EXPLICIT=${COLLECTION_INTERVAL_SECONDS+x}
saved_s3=true
saved_interval=0
# Restore the settings used by start, without executing the state file as shell code.
if [[ "$ACTION" != test && -f "$RUNTIME_FILE" ]]; then
  while IFS='=' read -r key value; do
    case "$key" in S3) saved_s3=$value ;; COLLECTION_INTERVAL_SECONDS) saved_interval=$value ;; esac
    case "$key" in
      COLLECTION_INTERVAL_SECONDS|SSL|AUTH|SERVER_COUNT|LOCATOR_PORT|JMX_PORT|SERVER_PORT_BASE|TEST_ENV|TEST_CLUSTER_NAME|S3|ENV_FILE|COMPOSE_FILE|COMPOSE_PROJECT_NAME|S3_PORT|ADMIN_UI_PORT|S3_ENDPOINT)
        if [[ -z "${!key+x}" ]]; then export "$key=$value"; fi ;;
    esac
  done < "$RUNTIME_FILE"
fi

SSL=${SSL:-true}
AUTH=${AUTH:-false}
SERVER_COUNT=${SERVER_COUNT:-2}
LOCATOR_PORT=${LOCATOR_PORT:-20334}
JMX_PORT=${JMX_PORT:-21099}
SERVER_PORT_BASE=${SERVER_PORT_BASE:-40504}
TEST_ENV=${TEST_ENV:-local}
TEST_CLUSTER_NAME=${TEST_CLUSTER_NAME:-test-cluster}
S3=${S3:-true}
COLLECTION_INTERVAL_SECONDS=${COLLECTION_INTERVAL_SECONDS:-0}
ENV_FILE=$(absolute "${ENV_FILE:-.env}")
COMPOSE_FILE=$(absolute "${COMPOSE_FILE:-docker-compose.yml}")
COMPOSE_PROJECT_NAME=${COMPOSE_PROJECT_NAME:-$(basename "$PROJECT_DIR")}
EMBEDDED_LAUNCHER=${EMBEDDED_LAUNCHER:-$PROJECT_DIR/scripts/start-locator.sh}
STANDALONE_LAUNCHER=${STANDALONE_LAUNCHER:-$PROJECT_DIR/scripts/collect-stats.sh}
LOCATOR_DIR="$TEST_DIR/locator"
REPORT_DIR="$LOCATOR_DIR/nightly-stats"
CERTS_DIR="$TEST_DIR/certs"
SECURITY_FILE="$TEST_DIR/gfsecurity.properties"
CERT_PASSWORD=changeit
export COLLECTION_INTERVAL_SECONDS
export TEST_DIR SSL AUTH SERVER_COUNT LOCATOR_PORT JMX_PORT SERVER_PORT_BASE TEST_ENV TEST_CLUSTER_NAME S3
export ENV_FILE COMPOSE_FILE COMPOSE_PROJECT_NAME EMBEDDED_LAUNCHER STANDALONE_LAUNCHER

for flag in SSL AUTH S3; do
  case "${!flag}" in true|false) ;; *) echo "$flag must be true or false" >&2; exit 2 ;; esac
done
for number in SERVER_COUNT LOCATOR_PORT JMX_PORT SERVER_PORT_BASE; do
  [[ "${!number}" =~ ^[0-9]+$ && "${!number}" -gt 0 ]] || { echo "$number must be a positive integer" >&2; exit 2; }
done

[[ "$COLLECTION_INTERVAL_SECONDS" =~ ^(0|[1-9][0-9]*)$ && "$COLLECTION_INTERVAL_SECONDS" -le 2147483647 ]] || {
  echo 'COLLECTION_INTERVAL_SECONDS must be an integer from 0 to 2147483647.' >&2; exit 2;
}
if [[ "$ACTION" = run && "$RUN_MODE" = ubuntu && "$COLLECTION_INTERVAL_SECONDS" = 0 ]]; then
  echo 'Ubuntu systemd requires a positive COLLECTION_INTERVAL_SECONDS (for example, 30 or 60).' >&2
  exit 2
fi

collection_settings_changed() {
  [[ "$S3" != "$saved_s3" || "$COLLECTION_INTERVAL_SECONDS" != "$saved_interval" ]]
}

check_gemfire() {
  : "${GEMFIRE_HOME:?Set GEMFIRE_HOME to a local GemFire installation}"
  command -v lsof >/dev/null || { echo 'lsof is required to identify the demo processes.' >&2; return 1; }
  GFSH="$GEMFIRE_HOME/bin/gfsh"
  [[ -x "$GFSH" ]] || { echo "No gfsh at $GFSH" >&2; return 1; }
}

env_value() {
  local value
  value=$(sed -n "s/^$1=//p" "$ENV_FILE" 2>/dev/null | tail -1) || true
  value=${value%$'\r'}
  if [[ "$value" = \"*\" || "$value" = \'*\' ]]; then value=${value:1:${#value}-2}; fi
  printf '%s' "$value"
}

load_s3() {
  if [[ ! -f "$ENV_FILE" ]]; then
    mkdir -p "$(dirname "$ENV_FILE")"
    cp "$PROJECT_DIR/.env.example" "$ENV_FILE"
  fi
  S3_PORT=${S3_PORT:-$(env_value S3_PORT)}
  ADMIN_UI_PORT=${ADMIN_UI_PORT:-$(env_value ADMIN_UI_PORT)}
  S3_PORT=${S3_PORT:-8333}
  ADMIN_UI_PORT=${ADMIN_UI_PORT:-23646}
  S3_ENDPOINT=${S3_ENDPOINT:-http://localhost:$S3_PORT}
  S3_BUCKET=${S3_BUCKET:-$(env_value S3_BUCKET)}
  S3_BUCKET=${S3_BUCKET:-gemfire-stats}
  S3_ACCESS_KEY=${S3_ACCESS_KEY:-$(env_value AWS_ACCESS_KEY_ID)}
  S3_SECRET_KEY=${S3_SECRET_KEY:-$(env_value AWS_SECRET_ACCESS_KEY)}
  export S3_PORT ADMIN_UI_PORT S3_ENDPOINT S3_BUCKET S3_ACCESS_KEY S3_SECRET_KEY
}

compose() { docker compose --env-file "$ENV_FILE" --file "$COMPOSE_FILE" "$@"; }
installation_running() {
  local container
  container=$(compose --profile installation-test ps --status running --quiet collector-install-test) || return
  [[ -n "$container" ]]
}

installation_status() {
  echo 'Ubuntu collector:'
  if installation_running; then
    compose exec -T collector-install-test systemctl list-timers gemfire-nightly-stats.timer --no-pager
    echo 'Last collection service result (inactive between runs is normal):'
    compose exec -T collector-install-test systemctl show gemfire-nightly-stats.service \
      --property=ActiveState,Result,ExecMainStatus,ExecMainExitTimestamp
  else
    echo 'Stopped. Use make run and select Ubuntu systemd installation.'
  fi
}

run_installation() {
  local source_config
  source_config=$(absolute "${PROPERTIES:-$TEST_DIR/standalone.properties}")
  [[ -r "$source_config" ]] || { echo "Cannot read collector properties: $source_config" >&2; return 2; }
  mkdir -p "$TEST_DIR/installation-test"
  export INSTALL_TEST_CONFIG="$TEST_DIR/installation-test/collector.properties"
  cat "$source_config" > "$INSTALL_TEST_CONFIG.next"
  printf '\nDELIVERY_MODE=%s\n' "$(if [[ "$S3" = true ]]; then echo s3; else echo local; fi)" >> "$INSTALL_TEST_CONFIG.next"
  mv "$INSTALL_TEST_CONFIG.next" "$INSTALL_TEST_CONFIG"
  ./gradlew assembleDist --quiet
  export INSTALL_TEST_SOURCE_DIR="$PROJECT_DIR"
  export INSTALL_TEST_INTERVAL_SECONDS="$COLLECTION_INTERVAL_SECONDS"
  # The installer and application are mounted. Compose builds the harness image if missing.
  compose --profile installation-test up -d --wait --force-recreate collector-install-test
  installation_status
  echo 'Ubuntu collector is running in the background. Use make status, make logs or make stop.'
}
wipe_s3() {
  if [[ -f "$ENV_FILE" ]]; then
    compose --profile '*' down --volumes
  else
    # Compose still requires these variables to parse the configuration for down.
    AWS_ACCESS_KEY_ID=unused AWS_SECRET_ACCESS_KEY=unused \
      docker compose --file "$COMPOSE_FILE" --profile '*' down --volumes
  fi
}
port_open() { lsof -nP -iTCP:"$1" -sTCP:LISTEN -t >/dev/null 2>&1; }

# A marker can disappear after an interrupted command. Verify the process that owns each
# port against GemFire's PID file; a stale marker or reused PID must not claim another service.
member_owns_port() {
  local directory=$1 kind=$2 port=$3 pid
  [[ -f "$directory/vf.gf.$kind.pid" ]] || return 1
  pid=$(cat "$directory/vf.gf.$kind.pid")
  [[ "$pid" =~ ^[1-9][0-9]*$ ]] || return 1
  kill -0 "$pid" 2>/dev/null || return 1
  pid_owns_port "$pid" "$port"
}

pid_owns_port() { lsof -nP -a -p "$1" -iTCP:"$2" -sTCP:LISTEN -t >/dev/null 2>&1; }

owns_locator() { member_owns_port "$LOCATOR_DIR" locator "$LOCATOR_PORT"; }

owns_environment() {
  if owns_locator; then return 0; fi
  local index
  for ((index=0; index<SERVER_COUNT; index++)); do
    if member_owns_port "$TEST_DIR/server-$index" server "$((SERVER_PORT_BASE + index))"; then return 0; fi
  done
  return 1
}

running() {
  owns_locator && member_owns_port "$LOCATOR_DIR" locator "$JMX_PORT" || return 1
  local index
  for ((index=0; index<SERVER_COUNT; index++)); do
    member_owns_port "$TEST_DIR/server-$index" server "$((SERVER_PORT_BASE + index))" || return 1
  done
}

connect_args() {
  CONNECT="connect --locator=localhost[$LOCATOR_PORT]"
  if [[ "$SSL" = true ]]; then CONNECT+=" --use-ssl"; fi
  if [[ "$SSL" = true || "$AUTH" = true ]]; then CONNECT+=" --security-properties-file=$SECURITY_FILE"; fi
  if [[ "$AUTH" = true ]]; then CONNECT+=" --user=cluster,data --password=cluster,data"; fi
}

configure() {
  mkdir -p "$LOCATOR_DIR" "$CERTS_DIR"
  if [[ "$SSL" = true && ! -f "$CERTS_DIR/truststore.p12" ]]; then
    keytool -genkeypair -alias test-cluster -keyalg RSA -keysize 2048 -validity 365 \
      -dname CN=test-cluster -ext SAN=dns:localhost,ip:127.0.0.1 \
      -storetype PKCS12 -keystore "$CERTS_DIR/keystore.p12" -storepass "$CERT_PASSWORD" -keypass "$CERT_PASSWORD"
    keytool -exportcert -alias test-cluster -keystore "$CERTS_DIR/keystore.p12" -storepass "$CERT_PASSWORD" \
      -file "$CERTS_DIR/test-cluster.crt" 2>/dev/null
    keytool -importcert -noprompt -alias test-cluster -file "$CERTS_DIR/test-cluster.crt" \
      -storetype PKCS12 -keystore "$CERTS_DIR/truststore.p12" -storepass "$CERT_PASSWORD" 2>/dev/null
  fi
  {
    if [[ "$SSL" = true ]]; then
      printf '%s\n' 'ssl-enabled-components=all' 'ssl-protocols=TLSv1.2,TLSv1.3' \
        "ssl-keystore=$CERTS_DIR/keystore.p12" "ssl-keystore-password=$CERT_PASSWORD" 'ssl-keystore-type=PKCS12' \
        "ssl-truststore=$CERTS_DIR/truststore.p12" "ssl-truststore-password=$CERT_PASSWORD" 'ssl-truststore-type=PKCS12'
    fi
    if [[ "$AUTH" = true ]]; then
      printf '%s\n' 'security-manager=org.apache.geode.examples.SimpleSecurityManager' \
        'security-username=cluster,data' 'security-password=cluster,data'
    fi
  } > "$SECURITY_FILE"
  {
    printf '%s\n' "ENV=$TEST_ENV" "CLUSTER_NAME=$TEST_CLUSTER_NAME" 'MEMBER_NAME=test-locator' \
      "LOCATOR_PORT=$LOCATOR_PORT" "JMX_MANAGER_PORT=$JMX_PORT" "LOG_DIRECTORY=$LOCATOR_DIR" \
      'BIND_ADDR=127.0.0.1' 'LOCATOR_HOST_NAME_FOR_CLIENTS=localhost' 'CACHE_SERVER_PORT=0' \
      "COLLECTION_INTERVAL_SECONDS=$COLLECTION_INTERVAL_SECONDS" 'NIGHTLY_STATS_RUN_ON_STARTUP=true' 'gemfire.http-service-port=0' 'gemfire.jmx-manager-hostname-for-clients=localhost'
    if [[ "$SSL" = true ]]; then
      printf '%s\n' 'CLUSTER_SSL_ENABLED=all' 'CLUSTER_SSL_PROTOCOLS=TLSv1.2,TLSv1.3' \
        'CLUSTER_SSL_MUTUAL_AUTHENTICATION=true' 'CLUSTER_SSL_CIPHERS=any' \
        "CLUSTER_SSL_KEY_STORE_FILE_NAME=$CERTS_DIR/keystore.p12" "CLUSTER_SSL_KEY_STORE_PASSWORD=$CERT_PASSWORD" \
        'SSL_USE_DEFAULT_CONTEXT=false' 'gemfire.ssl-keystore-type=PKCS12' 'gemfire.ssl-truststore-type=PKCS12'
    fi
    if [[ "$AUTH" = true ]]; then sed -n '/^security-/s/^/gemfire./p' "$SECURITY_FILE"; fi
    if [[ "$S3" = true ]]; then
      printf '%s\n' "S3_ENDPOINT=$S3_ENDPOINT" "S3_BUCKET=$S3_BUCKET" "S3_ACCESS_KEY=$S3_ACCESS_KEY" "S3_SECRET_KEY=$S3_SECRET_KEY"
    fi
    if [[ "$S3" = true ]]; then printf '%s\n' 'DELIVERY_MODE=s3';
    else printf '%s\n' 'DELIVERY_MODE=local'; fi
  } > "$TEST_DIR/locator.properties"
  {
    printf '%s\n' "ENV=$TEST_ENV" "CLUSTER_NAME=$TEST_CLUSTER_NAME" 'JMX_HOST=localhost' "JMX_PORT=$JMX_PORT" "REPORT_DIR=$TEST_DIR/reports"
    if [[ "$S3" = true ]]; then
      printf '%s\n' 'DELIVERY_MODE=s3' "S3_ENDPOINT=$S3_ENDPOINT" "S3_BUCKET=$S3_BUCKET" "S3_ACCESS_KEY=$S3_ACCESS_KEY" "S3_SECRET_KEY=$S3_SECRET_KEY"
    else printf '%s\n' 'DELIVERY_MODE=local'; fi
    sed -n '/^ssl-/s/^/gemfire./p' "$SECURITY_FILE"
    if [[ "$AUTH" = true ]]; then printf '%s\n' 'JMX_USERNAME=clusterread' 'JMX_PASSWORD=clusterread'; fi
  } > "$TEST_DIR/standalone.properties"
}

save_runtime() {
  : > "$RUNTIME_FILE"
  for key in COLLECTION_INTERVAL_SECONDS SSL AUTH SERVER_COUNT LOCATOR_PORT JMX_PORT SERVER_PORT_BASE TEST_ENV TEST_CLUSTER_NAME S3 ENV_FILE COMPOSE_FILE COMPOSE_PROJECT_NAME S3_PORT ADMIN_UI_PORT S3_ENDPOINT; do
    printf '%s=%s\n' "$key" "${!key}" >> "$RUNTIME_FILE"
  done
}

stop_members() {
  check_gemfire
  local directory index pid pair listening attempt
  local stopping=()
  for directory in "$TEST_DIR"/server-*; do
    [[ -d "$directory" ]] || continue
    index=${directory##*/server-}
    [[ "$index" =~ ^[0-9]+$ ]] || continue
    if member_owns_port "$directory" server "$((SERVER_PORT_BASE + index))"; then
      pid=$(cat "$directory/vf.gf.server.pid")
      stopping+=("$pid:$((SERVER_PORT_BASE + index))")
      "$GFSH" -e "stop server --dir=$directory" >/dev/null 2>&1 || true
    fi
  done
  if owns_locator; then
    pid=$(cat "$LOCATOR_DIR/vf.gf.locator.pid")
    stopping+=("$pid:$LOCATOR_PORT")
    if pid_owns_port "$pid" "$JMX_PORT"; then stopping+=("$pid:$JMX_PORT"); fi
    "$GFSH" -e "stop locator --dir=$LOCATOR_DIR" >/dev/null 2>&1 || true
  fi
  # GemFire can remove its PID files before its listening sockets close. Keep the verified
  # PIDs captured above so the next example does not race the previous JVM's shutdown.
  listening=false
  for ((attempt=0; attempt<30; attempt++)); do
    listening=false
    for pair in "${stopping[@]}"; do
      if pid_owns_port "${pair%:*}" "${pair#*:}"; then listening=true; break; fi
    done
    [[ "$listening" = true ]] || break
    sleep 1
  done
  if [[ "$listening" = true ]]; then echo "Some managed members did not release their ports. See the logs under $TEST_DIR." >&2; return 1; fi
  rm -f "$TEST_DIR/running"
}

stop_stack() {
  stop_members
  if [[ -f "$ENV_FILE" ]]; then compose --profile '*' down; fi
  echo 'Stopped locator, servers and SeaweedFS. Saved data is preserved.'
}

start_stack() {
  check_gemfire
  if owns_environment && ! running; then
    echo 'Recovering an incomplete managed environment; saved reports and SeaweedFS data are preserved.'
    stop_members
  elif [[ "$MODE" = embedded ]] && running && collection_settings_changed; then
    echo 'Restarting locator and servers to apply the collection settings; saved reports and SeaweedFS data are preserved.'
    stop_members
  fi
  command -v docker >/dev/null || { echo 'Docker is required for SeaweedFS.' >&2; return 1; }
  if port_open "$LOCATOR_PORT" && ! running; then
    echo "Port $LOCATOR_PORT is already in use by another environment. Override LOCATOR_PORT or stop that environment." >&2
    return 1
  fi
  if ! running; then
    for port in "$JMX_PORT" $(seq "$SERVER_PORT_BASE" "$((SERVER_PORT_BASE + SERVER_COUNT - 1))"); do
      if port_open "$port"; then echo "Port $port is already in use." >&2; return 1; fi
    done
    ./gradlew build --quiet
  fi
  load_s3
  compose up -d --wait seaweedfs
  if running; then
    if [[ "$MODE" = standalone ]] && collection_settings_changed; then
      configure
      save_runtime
    fi
    touch "$TEST_DIR/running"
    echo "Report delivery: $(if [[ "$S3" = true ]]; then echo 'local files and SeaweedFS'; else echo 'local files only'; fi)"
    echo "Environment is already running. SeaweedFS: $S3_ENDPOINT"
    return 0
  fi
  # Tear down only this newly started environment if any startup stage fails.
  trap 'status=$?; if ((status != 0)); then stop_stack || true; fi' EXIT
  configure
  save_runtime
  local security=""
  if [[ "$SSL" = true || "$AUTH" = true ]]; then security="--security-properties-file=$SECURITY_FILE"; fi
  if [[ "$MODE" = embedded ]]; then
    JAVA_OPTS=${JAVA_OPTS:--Xmx512m} nohup "$EMBEDDED_LAUNCHER" "$TEST_DIR/locator.properties" > "$TEST_DIR/locator.out" 2>&1 &
  else
    "$GFSH" -e "start locator --name=test-locator --dir=$LOCATOR_DIR --port=$LOCATOR_PORT --bind-address=127.0.0.1 \
      $security --J=-Xmx512m --J=-Dgemfire.jmx-manager=true --J=-Dgemfire.jmx-manager-start=true \
      --J=-Dgemfire.jmx-manager-port=$JMX_PORT --J=-Dgemfire.jmx-manager-hostname-for-clients=localhost \
      --J=-Dgemfire.http-service-port=0" > "$TEST_DIR/locator.out" 2>&1
  fi
  local ready=false
  for ((attempt=0; attempt<60; attempt++)); do
    if port_open "$JMX_PORT"; then ready=true; break; fi
    sleep 1
  done
  [[ "$ready" = true ]] || { echo "Locator failed to start. See $TEST_DIR/locator.out" >&2; return 1; }
  for ((index=0; index<SERVER_COUNT; index++)); do
    mkdir -p "$TEST_DIR/server-$index"
    if ! "$GFSH" -e "start server --name=test-server-$index --dir=$TEST_DIR/server-$index --bind-address=127.0.0.1 \
      --locators=localhost[$LOCATOR_PORT] --server-port=$((SERVER_PORT_BASE + index)) \
      $security --J=-Xmx512m --J=-Dgemfire.http-service-port=0" > "$TEST_DIR/server-$index.out" 2>&1; then
      echo "Server $index failed. See $TEST_DIR/server-$index.out" >&2; return 1
    fi
    grep -q 'is currently online' "$TEST_DIR/server-$index.out" || { echo "Server $index failed. See $TEST_DIR/server-$index.out" >&2; return 1; }
  done
  connect_args
  "$GFSH" -e "$CONNECT" -e 'create region --name=TestPartition --type=PARTITION --if-not-exists' \
    -e 'create region --name=TestReplicate --type=REPLICATE --if-not-exists' \
    -e 'create region --name=TestLocal --type=LOCAL --if-not-exists' > "$TEST_DIR/regions.out" 2>&1
  grep -q TestLocal "$TEST_DIR/regions.out" || { echo "Region creation failed. See $TEST_DIR/regions.out" >&2; return 1; }
  touch "$TEST_DIR/running"
  trap - EXIT
  echo "Started $MODE locator, $SERVER_COUNT servers, sample regions and SeaweedFS."
  echo "JMX: localhost:$JMX_PORT | S3: $S3_ENDPOINT | Admin UI: http://localhost:$ADMIN_UI_PORT"
  echo "Report delivery: $(if [[ "$S3" = true ]]; then echo 'local files and SeaweedFS'; else echo 'local files only'; fi)"
  echo "Configuration and logs: $TEST_DIR"
}

run_collector() {
  local config=$1 result=0
  if [[ "$COLLECTION_INTERVAL_SECONDS" = 0 ]]; then
    "$STANDALONE_LAUNCHER" collect --config "$config"
    return
  fi
  echo "Collecting through remote JMX, then waiting $COLLECTION_INTERVAL_SECONDS seconds between runs. Press Ctrl-C to stop."
  while true; do
    result=0
    "$STANDALONE_LAUNCHER" collect --config "$config" || result=$?
    if [[ "$result" = 2 ]]; then return "$result"; fi
    if [[ "$result" != 0 ]]; then echo "Collection returned $result; retrying after the interval." >&2; fi
    sleep "$COLLECTION_INTERVAL_SECONDS"
  done
}

case "$ACTION" in
  probe) owns_environment ;; # Internal read-only discovery for the repository dispatcher.
  start) start_stack ;;
  stop) stop_stack ;;
  status)
    check_gemfire
    echo "Example: $MODE | Settings: $TEST_DIR"
    if running; then connect_args; "$GFSH" -e "$CONNECT" -e 'list members'; else echo 'GemFire is stopped.'; fi
    if [[ -f "$ENV_FILE" ]]; then compose --profile '*' ps --all; installation_status; fi ;;
  logs)
    if [[ -f "$ENV_FILE" ]] && installation_running; then
      exec docker compose --env-file "$ENV_FILE" --file "$COMPOSE_FILE" exec -T collector-install-test \
        journalctl -u gemfire-nightly-stats.service -f
    fi
    [[ -f "$LOCATOR_DIR/test-locator.log" ]] || { echo 'No locator log. Run make start first.' >&2; exit 1; }
    exec tail -f "$LOCATOR_DIR/test-locator.log" ;;
  admin)
    port=${ADMIN_UI_PORT:-$(env_value ADMIN_UI_PORT)}
    url="http://localhost:${port:-23646}"
    echo "Opening SeaweedFS Admin UI: $url (default login: admin/admin)"
    if command -v open >/dev/null; then exec open "$url";
    elif command -v xdg-open >/dev/null; then exec xdg-open "$url";
    else echo "Open $url in your browser."; fi ;;
  run)
    if [[ "$RUN_MODE" = ubuntu ]]; then
      if [[ -z "${PROPERTIES:-}" ]]; then start_stack; else load_s3; fi
      run_installation
      exit 0
    fi
    if [[ -n "${PROPERTIES:-}" ]]; then
      [[ -f "$PROPERTIES" ]] || { echo "Properties file not found: $PROPERTIES" >&2; exit 2; }
      ./gradlew build --quiet
      # Append overrides to a private copy so the chosen delivery always wins, without editing the supplied file.
      custom_config=$(mktemp "${TMPDIR:-/tmp}/gemfire-demo-properties.XXXXXX")
      trap 'rm -f "$custom_config"' EXIT
      cat "$PROPERTIES" > "$custom_config"
      printf '\nDELIVERY_MODE=%s\n' "$(if [[ "$S3" = true ]]; then echo s3; else echo local; fi)" >> "$custom_config"
      if [[ "$MODE" = embedded ]]; then
        if [[ -n "${COLLECTION_INTERVAL_EXPLICIT:-}" ]]; then
          printf 'COLLECTION_INTERVAL_SECONDS=%s\n' "$COLLECTION_INTERVAL_SECONDS" >> "$custom_config"
        fi
        "$EMBEDDED_LAUNCHER" "$custom_config"
      else run_collector "$custom_config"; fi
      exit $?
    fi
    if ! running || collection_settings_changed; then start_stack; fi
    if [[ "$MODE" = embedded ]]; then
      echo "The embedded collector is running inside the locator. Its first report is scheduled 60 seconds after startup."
      if [[ "$COLLECTION_INTERVAL_SECONDS" != 0 ]]; then
        echo "Then it repeats with a $COLLECTION_INTERVAL_SECONDS second delay between runs."
      else echo 'Then it runs nightly at 02:00 in the locator timezone.'; fi
      echo "Reports: $REPORT_DIR | Follow progress with make logs."
    else
      ./gradlew build --quiet
      run_collector "$TEST_DIR/standalone.properties"
    fi ;;
  test)
    check_gemfire
    ./gradlew build --quiet
    load_s3
    if [[ "$MODE" = standalone ]]; then
      export STANDALONE_TEST_DIR="$TEST_DIR" STANDALONE_LOCATOR_PORT="$LOCATOR_PORT" STANDALONE_JMX_PORT="$JMX_PORT" STANDALONE_SERVER_PORT="$SERVER_PORT_BASE"
      exec test-support/scripts/test-standalone.sh "$SSL" "$AUTH" "$S3"
    fi
    trap 'stop_stack' EXIT
    start_stack
    trap 'stop_stack' EXIT
    report=""
    for ((attempt=0; attempt<150; attempt++)); do
      for file in "$REPORT_DIR/$TEST_CLUSTER_NAME-"*.json; do if [[ -f "$file" ]]; then report=$file; break; fi; done
      [[ -n "$report" ]] && break
      sleep 1
    done
    [[ -n "$report" ]] || { echo "No startup report. See $LOCATOR_DIR/test-locator.log" >&2; exit 1; }
    test-support/scripts/verify-report.sh "$report" "$SERVER_COUNT" "$SSL"
    if [[ "$S3" = true ]]; then
      receipt="$REPORT_DIR/.uploaded/$(basename "$report").receipt"
      for ((attempt=0; attempt<30; attempt++)); do [[ -f "$receipt" ]] && break; sleep 1; done
      test-support/scripts/verify-upload.sh "$report" "$S3_ENDPOINT/$S3_BUCKET/nightly-stats/$TEST_ENV/$TEST_CLUSTER_NAME" "$S3_ACCESS_KEY:$S3_SECRET_KEY"
    fi
    if ((COLLECTION_INTERVAL_SECONDS > 0 && COLLECTION_INTERVAL_SECONDS <= 60)); then
      cp "$report" "$TEST_DIR/first-collection.snapshot"
      second_report=""
      for ((attempt=0; attempt<COLLECTION_INTERVAL_SECONDS+30; attempt++)); do
        sleep 1
        for file in "$REPORT_DIR/$TEST_CLUSTER_NAME-"*.json; do
          if [[ -f "$file" && "$file" != "$report" ]]; then second_report=$file; break; fi
        done
        [[ -n "$second_report" ]] && break
      done
      [[ -n "$second_report" ]] || { echo 'The interval collector did not create a second report.' >&2; exit 1; }
      cmp "$report" "$TEST_DIR/first-collection.snapshot"
      test-support/scripts/verify-report.sh "$second_report" "$SERVER_COUNT" "$SSL"
      if [[ "$S3" = true ]]; then
        receipt="$REPORT_DIR/.uploaded/$(basename "$second_report").receipt"
        for ((attempt=0; attempt<30; attempt++)); do [[ -f "$receipt" ]] && break; sleep 1; done
        test-support/scripts/verify-upload.sh "$second_report" "$S3_ENDPOINT/$S3_BUCKET/nightly-stats/$TEST_ENV/$TEST_CLUSTER_NAME" "$S3_ACCESS_KEY:$S3_SECRET_KEY"
        test-support/scripts/verify-upload.sh "$report" "$S3_ENDPOINT/$S3_BUCKET/nightly-stats/$TEST_ENV/$TEST_CLUSTER_NAME" "$S3_ACCESS_KEY:$S3_SECRET_KEY"
      fi
      echo 'Interval collection created separate reports and preserved the first report.'
    fi ;;
  clean)
    stop_members
    wipe_s3
    ./gradlew clean --quiet
    [[ "$TEST_DIR" != / && "$TEST_DIR" != "$PROJECT_DIR" && "$TEST_DIR" != "$HOME" ]] || { echo 'Refusing to remove this TEST_DIR.' >&2; exit 2; }
    rm -rf "$TEST_DIR"
    echo 'Removed build and test files and deleted the SeaweedFS volume.' ;;
  *) echo "Unknown command: $ACTION" >&2; exit 2 ;;
esac
