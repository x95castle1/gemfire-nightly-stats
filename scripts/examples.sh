#!/usr/bin/env bash
# Repository convenience only. Each example owns its build and development scripts.
set -euo pipefail
ROOT_DIR=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT_DIR"
ACTION=${1:-help}
ACTIVE_FILE="$ROOT_DIR/.test-cluster/active-example"
LAST_FILE="$ROOT_DIR/.test-cluster/last-example"

if [[ -n "${PROPERTIES:-}" && "$PROPERTIES" != /* ]]; then export PROPERTIES="$ROOT_DIR/$PROPERTIES"; fi
if [[ -n "${TEST_DIR:-}" && "$TEST_DIR" != /* ]]; then export TEST_DIR="$ROOT_DIR/$TEST_DIR"; fi
if [[ -n "${ENV_FILE:-}" && "$ENV_FILE" != /* ]]; then export ENV_FILE="$ROOT_DIR/$ENV_FILE"; fi
if [[ -n "${COMPOSE_FILE:-}" && "$COMPOSE_FILE" != /* ]]; then export COMPOSE_FILE="$ROOT_DIR/$COMPOSE_FILE"; fi

case "$ACTION" in
  help)
    cat <<'HELP'
  build   Build and test both examples
  run     Pick an example with fzf and run it
  start   Pick an example and start its locator, servers, regions and SeaweedFS
  stop    Stop the active environment, keeping its data
  status  Show the active environment, members and Ubuntu collector timer/result
  logs    Follow the Ubuntu collector journal when running, otherwise the locator log
  admin   Open the SeaweedFS Admin UI in your browser
  test    Pick an example and run its integration checks
  dist    Build both ZIP and TAR distributions
  clean   Stop the environment and delete builds, test files and SeaweedFS volumes

Use EXAMPLE=01 or EXAMPLE=02 to bypass the example picker; test also accepts EXAMPLE=all.
start/run also ask where to deliver reports. S3=true or S3=false bypasses that picker.
SSL=false disables GemFire TLS. SeaweedFS starts even when uploads are disabled.
run also asks for the collection interval. COLLECTION_INTERVAL_SECONDS skips that picker.
COLLECTION_INTERVAL_SECONDS=60 repeats every minute; 0 keeps the normal schedule.
For example 02, run asks for local Java or Ubuntu systemd. RUN_MODE=local or ubuntu skips that picker.
HELP
    exit 0 ;;
  build) exec ./gradlew build --quiet ;;
  dist) exec ./gradlew assembleDist --quiet ;;
  test)
    "$ROOT_DIR/scripts/check-workflow.sh"
    "$ROOT_DIR/scripts/check-demo-controls.sh"
    "$ROOT_DIR/scripts/check-demo-recovery.sh" ;;
esac

active=""
active_directory=""
last=""
last_directory=""
if [[ -f "$ACTIVE_FILE" ]]; then
  { IFS= read -r active; IFS= read -r active_directory; } < "$ACTIVE_FILE"
fi
if [[ -f "$LAST_FILE" ]]; then
  { IFS= read -r last; IFS= read -r last_directory; } < "$LAST_FILE"
fi

normalize() {
  case "$1" in
    1|01|embedded|01-embedded-locator) printf '%s\n' 01-embedded-locator ;;
    2|02|standalone|02-standalone-jmx) printf '%s\n' 02-standalone-jmx ;;
    *) echo "Unknown example: $1. Choose EXAMPLE=01 or EXAMPLE=02." >&2; return 2 ;;
  esac
}

invoke() {
  local example=$1 action=$2
  (
    cd "$ROOT_DIR/examples/$example"
    if [[ "$action" = probe || "$action" = stop ]]; then
      # Discovery and stopping must inspect the old environment, not settings for the next start.
      unset SSL AUTH SERVER_COUNT LOCATOR_PORT JMX_PORT SERVER_PORT_BASE TEST_ENV TEST_CLUSTER_NAME
      unset S3 COLLECTION_INTERVAL_SECONDS ENV_FILE COMPOSE_FILE COMPOSE_PROJECT_NAME S3_PORT ADMIN_UI_PORT S3_ENDPOINT
    fi
    local_runtime="${TEST_DIR:-$ROOT_DIR/examples/$example/.test-cluster}/runtime.settings"
    if [[ "$action" = test || ! -f "$local_runtime" ]]; then
      export COMPOSE_FILE="${COMPOSE_FILE:-$ROOT_DIR/docker-compose.yml}"
      export ENV_FILE="${ENV_FILE:-$ROOT_DIR/.env}"
      if [[ "$action" != test ]]; then
        export COMPOSE_PROJECT_NAME="${COMPOSE_PROJECT_NAME:-$(basename "$ROOT_DIR")}"
      fi
    fi
    exec scripts/dev.sh "$action"
  )
}

# The last command may have lost its marker or stopped before recording active state.
# Probe only known project directories; each example verifies its member PIDs own their ports.
# Discovery is read-only until all pickers have completed successfully.
recovered=false
if [[ "$ACTION" != run || -z "${PROPERTIES:-}" ]]; then
  case "$ACTION" in
    start|run|stop|status|logs|admin|clean)
      for candidate in "$active" "$last" 01-embedded-locator 02-standalone-jmx; do
        [[ -n "$candidate" ]] || continue
        candidate=$(normalize "$candidate")
        directory="$ROOT_DIR/examples/$candidate/.test-cluster"
        if [[ "$candidate" = "$last" && -n "$last_directory" ]]; then directory=$last_directory; fi
        if [[ "$candidate" = "$active" && -n "$active_directory" ]]; then directory=$active_directory; fi
        if [[ -n "${EXAMPLE:-}" && "$candidate" = "$(normalize "$EXAMPLE")" && -n "${TEST_DIR:-}" ]]; then directory=$TEST_DIR; fi
        if [[ -f "$directory/runtime.settings" ]] && TEST_DIR="$directory" invoke "$candidate" probe; then
          if [[ "$active" != "$candidate" || "$active_directory" != "$directory" ]]; then recovered=true; fi
          active=$candidate
          active_directory=$directory
          break
        fi
      done ;;
  esac
fi

record_active() {
  mkdir -p "$(dirname "$ACTIVE_FILE")"
  printf '%s\n%s\n' "$1" "$2" > "$ACTIVE_FILE"
  cp "$ACTIVE_FILE" "$LAST_FILE"
}

if [[ "$ACTION" = test && "${EXAMPLE:-}" = all ]]; then
  invoke 01-embedded-locator test
  invoke 02-standalone-jmx test
  exit 0
fi

if [[ "$ACTION" = clean ]]; then
  cleanup_example=${active:-$last}
  cleanup_directory=${active_directory:-$last_directory}
  if [[ -n "$cleanup_example" ]]; then
    TEST_DIR="$cleanup_directory" invoke "$(normalize "$cleanup_example")" clean
  fi
  # Also cover the default local store, including when stop has already cleared active state.
  compose_file=${COMPOSE_FILE:-$ROOT_DIR/docker-compose.yml}
  env_file=${ENV_FILE:-$ROOT_DIR/.env}
  project=${COMPOSE_PROJECT_NAME:-$(basename "$ROOT_DIR")}
  if [[ -f "$env_file" ]]; then
    docker compose --project-name "$project" --env-file "$env_file" --file "$compose_file" --profile '*' down --volumes
  else
    AWS_ACCESS_KEY_ID=unused AWS_SECRET_ACCESS_KEY=unused \
      docker compose --project-name "$project" --file "$compose_file" --profile '*' down --volumes
  fi
  ./gradlew clean --quiet
  rm -rf "$ROOT_DIR/.test-cluster" "$ROOT_DIR/examples/01-embedded-locator/.test-cluster" "$ROOT_DIR/examples/02-standalone-jmx/.test-cluster"
  exit 0
fi

if [[ -n "${EXAMPLE:-}" ]]; then
  selected=$(normalize "$EXAMPLE")
elif [[ "$ACTION" = admin ]]; then
  selected=$(normalize "${active:-${last:-01}}")
  if [[ -n "${active_directory:-$last_directory}" ]]; then
    export TEST_DIR="${active_directory:-$last_directory}"
  fi
elif [[ "$ACTION" = stop || "$ACTION" = status || "$ACTION" = logs ]]; then
  if [[ -z "$active" ]]; then
    echo "No active example. Run make start first."
    exit 0
  fi
  selected=$(normalize "$active")
  export TEST_DIR="$active_directory"
else
  command -v fzf >/dev/null || { echo "Install fzf, or use EXAMPLE=01 or EXAMPLE=02." >&2; exit 2; }
  if ! selected=$(printf '%s\n' 01-embedded-locator 02-standalone-jmx | fzf --prompt='Example > ' --height=8 --layout=reverse); then
    echo "Selection cancelled."
    exit 0
  fi
  selected=$(normalize "$selected")
fi

if [[ "$selected" = "$active" && "$ACTION" != test && -z "${TEST_DIR+x}" ]]; then
  export TEST_DIR="$active_directory"
fi

if [[ "$ACTION" = stop && -n "$active" && "$selected" != "$active" ]]; then
  echo "The active example is $active. Use make stop to stop that environment." >&2
  exit 2
fi

# Select how example 02 runs before changing the active environment.
if [[ "$ACTION" = run && "$selected" = 02-standalone-jmx && -z "${RUN_MODE+x}" ]]; then
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
if [[ "$ACTION" = run ]]; then
  case "${RUN_MODE:-local}" in
    local) ;;
    ubuntu) [[ "$selected" = 02-standalone-jmx ]] || { echo 'RUN_MODE=ubuntu requires EXAMPLE=02.' >&2; exit 2; } ;;
    *) echo 'RUN_MODE must be local or ubuntu.' >&2; exit 2 ;;
  esac
fi

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
  if [[ "$selected" = 01-embedded-locator ]]; then normal_schedule='Nightly (configured time)';
  else normal_schedule='Once and exit'; fi
  intervals=('Every 30 seconds' 'Every minute' 'Every 5 minutes' 'Every 15 minutes')
  if [[ "${RUN_MODE:-local}" != ubuntu ]]; then intervals=("$normal_schedule" "${intervals[@]}"); fi
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

if [[ "$ACTION" = run && "${RUN_MODE:-local}" = ubuntu ]]; then
  [[ "${COLLECTION_INTERVAL_SECONDS:-0}" =~ ^[1-9][0-9]{0,9}$ && "$COLLECTION_INTERVAL_SECONDS" -le 2147483647 ]] || {
    echo 'Ubuntu systemd requires COLLECTION_INTERVAL_SECONDS from 1 to 2147483647 (for example, 30 or 60).' >&2
    exit 2
  }
fi

if [[ "$recovered" = true && "$ACTION" != test ]]; then
  echo "Recovered the running $active environment from its process IDs."
  record_active "$active" "$active_directory"
fi

if [[ "$ACTION" = start || ( "$ACTION" = run && -z "${PROPERTIES:-}" ) ]]; then
  if [[ -n "$active" && "$active" != "$selected" ]]; then
    TEST_DIR="$active_directory" invoke "$(normalize "$active")" stop
  fi
  invoke "$selected" start
  record_active "$selected" "${TEST_DIR:-$ROOT_DIR/examples/$selected/.test-cluster}"
  if [[ "$ACTION" = start ]]; then exit 0; fi
fi

invoke "$selected" "$ACTION"
if [[ "$ACTION" = stop && "$active" = "$selected" ]]; then
  cp "$ACTIVE_FILE" "$LAST_FILE"
  rm -f "$ACTIVE_FILE"
fi
