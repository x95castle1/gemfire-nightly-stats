#!/usr/bin/env bash
# Install this independent example on an Ubuntu VM. Run with sudo; see README.md.
set -euo pipefail
umask 027
INSTALLATION_DIR=$(cd "$(dirname "$0")" && pwd)
SOURCE_DIR=$(cd "$INSTALLATION_DIR/.." && pwd)
APP_DIR=/opt/gemfire-nightly-stats
CONFIG_DIR=/etc/gemfire-nightly-stats
STATE_DIR=/var/lib/gemfire-nightly-stats
LOG_ROOT=/var/log
LOGROTATE_DIR=/etc/logrotate.d
UNIT_DIR=/etc/systemd/system
SERVICE=gemfire-nightly-stats
ACCOUNT=gemfire-stats
gemfire_home=${GEMFIRE_HOME:-}
java_home=${JAVA_HOME:-}
interval_seconds=60
config_source=""
start_now=false
leave_stopped=false

die() { echo "Error: $*" >&2; exit 1; }
usage() {
  cat <<'HELP'
Usage: sudo installation/install.sh --gemfire-home /opt/vmware-gemfire [options]

  --gemfire-home PATH       GemFire installation providing lib/gemfire-dependencies.jar
  --java-home PATH          Use this existing Java 17+ runtime (otherwise detect java on PATH)
  --interval-seconds N      Wait N seconds between collections unless COLLECTION_CALENDAR is set (default: 60)
  --config FILE            Install configured properties and start the timer automatically
  --start                  Start the timer now using the installed configuration
  --no-start               Leave the timer stopped, including when --config is supplied
  --help                   Show this help

Run from a built checkout or an unpacked distribution. The application is installed under
/opt/gemfire-nightly-stats, configuration under /etc/gemfire-nightly-stats and reports under
/var/lib/gemfire-nightly-stats. Existing configuration and reports are preserved unless an
explicit --config is supplied. With --config, or when updating an active timer, scheduling
starts automatically unless --no-start is supplied. A template-only installation stays stopped.
The timer is enabled for future boots in all cases; configure a template before rebooting.
Set COLLECTION_CALENDAR in collector.properties for a systemd calendar schedule, for example
COLLECTION_CALENDAR=*-*-* 22:00:00 America/Chicago. Rerun this installer to apply schedule changes.
Logging properties: LOG_DIR, LOG_FILE_NAME, LOG_ROTATE_INTERVAL, LOG_ROTATE_SIZE,
LOG_ROTATE_COUNT, LOG_LEVEL and GEMFIRE_LOG_LEVEL. Rerun this installer to apply logging changes.
HELP
}

while (($#)); do
  case "$1" in
    --gemfire-home|--java-home|--interval-seconds|--config)
      (($# >= 2)) && [[ "$2" != --* ]] || die "Missing value for $1"
      case "$1" in
        --gemfire-home) gemfire_home=$2 ;;
        --java-home) java_home=$2 ;;
        --interval-seconds) interval_seconds=$2 ;;
        --config) config_source=$2 ;;
      esac
      shift 2 ;;
    --start) start_now=true; shift ;;
    --no-start) leave_stopped=true; shift ;;
    --help|-h) usage; exit 0 ;;
    *) die "Unknown option: $1" ;;
  esac
done

[[ "$start_now" != true || "$leave_stopped" != true ]] || die 'Use either --start or --no-start, not both.'
if [[ -n "$config_source" && "$leave_stopped" != true ]]; then start_now=true; fi
[[ "$interval_seconds" =~ ^[1-9][0-9]*$ && "$interval_seconds" -le 2147483647 ]] || die 'Interval must be a positive integer no greater than 2147483647.'
[[ $(uname -s) = Linux ]] || die 'Run this installer on the Ubuntu VM, not on your workstation.'
[[ $(id -u) = 0 ]] || die 'Run this installer with sudo.'
[[ -f /etc/os-release ]] && grep -Eq '^ID="?ubuntu"?$' /etc/os-release || die 'This installer requires Ubuntu.'
command -v systemctl >/dev/null && [[ -d /run/systemd/system ]] || die 'The VM must be booted with systemd.'
for prerequisite in readlink awk install getent groupadd useradd runuser chown chmod mktemp sed tee date touch logrotate tr; do
  command -v "$prerequisite" >/dev/null || die "Required command is missing: $prerequisite"
done
[[ -n "$gemfire_home" ]] || die 'Set --gemfire-home (or GEMFIRE_HOME) to an existing GemFire installation.'
[[ -r "$gemfire_home/lib/gemfire-dependencies.jar" ]] || die 'GemFire client libraries are missing: lib/gemfire-dependencies.jar.'
gemfire_home=$(readlink -f "$gemfire_home")

if [[ -f "$SOURCE_DIR/lib/02-standalone-jmx.jar" ]]; then
  source_jar="$SOURCE_DIR/lib/02-standalone-jmx.jar"
  source_launcher="$SOURCE_DIR/bin/collect-stats.sh"
else
  source_jar="$SOURCE_DIR/build/libs/02-standalone-jmx.jar"
  source_launcher="$SOURCE_DIR/scripts/collect-stats.sh"
fi
[[ -r "$source_jar" && -r "$source_launcher" ]] || die 'Build example 02 with ./gradlew assembleDist, then use the checkout or unpacked distribution.'
[[ -z "$config_source" || -r "$config_source" ]] || die "Cannot read properties file: $config_source"

# Scheduling is applied by this installer; the collector JVM remains a one-shot command.
schedule_config=${config_source:-$CONFIG_DIR/collector.properties}
if [[ ! -f "$schedule_config" ]]; then schedule_config="$INSTALLATION_DIR/collector.example.properties"; fi
property() {
  awk -v key="$1" -v fallback="$2" '
  $0 ~ "^[[:space:]]*" key "[[:space:]]*[:=]" {
    sub("^[[:space:]]*" key "[[:space:]]*[:=][[:space:]]*", "")
    sub(/[[:space:]]+$/, "")
    if (length($0) >= 2 && substr($0,1,1) == "\"" && substr($0,length($0),1) == "\"") {
      $0=substr($0,2,length($0)-2)
    }
    sub(/^[[:space:]]+/, ""); sub(/[[:space:]]+$/, "")
    value=$0
  }
  END { print (value == "" ? fallback : value) }
  ' "$schedule_config"
}
calendar=$(property COLLECTION_CALENDAR '')
if [[ -n "$calendar" ]]; then
  [[ "$calendar" != *'\'* ]] || die 'Write COLLECTION_CALENDAR on one line without backslash escapes.'
  command -v systemd-analyze >/dev/null || die 'systemd-analyze is required to validate COLLECTION_CALENDAR.'
  systemd-analyze calendar -- "$calendar" >/dev/null 2>&1 || die 'Invalid COLLECTION_CALENDAR. Example: *-*-* 22:00:00 America/Chicago'
fi

LOG_DIR=$(property LOG_DIR "$LOG_ROOT/gemfire-nightly-stats")
[[ "$LOG_DIR" =~ ^/[A-Za-z0-9_./+-]+$ ]] || die 'LOG_DIR must be an absolute directory beneath /var/log without spaces or shell escapes.'
LOG_DIR=$(readlink -m "$LOG_DIR")
[[ "$LOG_DIR" = "$LOG_ROOT/"* ]] || die 'LOG_DIR must be a directory beneath /var/log.'
log_file_name=$(property LOG_FILE_NAME collector.log)
[[ "$log_file_name" =~ ^[A-Za-z0-9][A-Za-z0-9._-]*$ ]] || die 'LOG_FILE_NAME must be a filename without directory separators or spaces.'
log_rotate_interval=$(property LOG_ROTATE_INTERVAL daily)
case "$log_rotate_interval" in daily|weekly|monthly) ;; *) die 'LOG_ROTATE_INTERVAL must be daily, weekly or monthly.' ;; esac
log_rotate_size=$(property LOG_ROTATE_SIZE 10M)
[[ "$log_rotate_size" =~ ^[1-9][0-9]{0,8}[kMG]?$ ]] || die 'LOG_ROTATE_SIZE must be a positive byte count or a size such as 100k, 10M or 1G.'
log_rotate_count=$(property LOG_ROTATE_COUNT 14)
[[ "$log_rotate_count" =~ ^[1-9][0-9]{0,4}$ && "$log_rotate_count" -le 10000 ]] || die 'LOG_ROTATE_COUNT must be between 1 and 10000.'
log_level=$(property LOG_LEVEL INFO | LC_ALL=C tr '[:lower:]' '[:upper:]')
gemfire_log_level=$(property GEMFIRE_LOG_LEVEL WARN | LC_ALL=C tr '[:lower:]' '[:upper:]')
for level in "$log_level" "$gemfire_log_level"; do
  case "$level" in ALL|TRACE|DEBUG|INFO|WARN|ERROR|FATAL|OFF) ;; *) die 'LOG_LEVEL and GEMFIRE_LOG_LEVEL must be ALL, TRACE, DEBUG, INFO, WARN, ERROR, FATAL or OFF.' ;; esac
done
log_file="$LOG_DIR/$log_file_name"

java_major() {
  "$1" -XshowSettings:properties -version 2>&1 | awk '$1 == "java.specification.version" { print $3 }'
}
if [[ -n "$java_home" ]]; then
  [[ -x "$java_home/bin/java" ]] || die 'No executable bin/java under --java-home/JAVA_HOME.'
  java_binary="$java_home/bin/java"
elif command -v java >/dev/null; then
  java_binary=$(command -v java)
else
  die 'Java is missing. Provide an existing Java 17+ runtime with --java-home, JAVA_HOME or PATH.'
fi
[[ -x "$java_binary" ]] || die 'The selected Java executable is missing or not executable.'
version=$(java_major "$java_binary") || die 'The selected Java runtime could not be executed.'
[[ "$version" =~ ^[0-9]+$ && "$version" -ge 17 ]] || die 'Java 17 or newer is required.'
java_home=$(dirname "$(dirname "$(readlink -f "$java_binary")")")
# Keep generated EnvironmentFile values literal and unambiguous.
for runtime_path in "$gemfire_home" "$java_home"; do
  [[ "$runtime_path" =~ ^/[A-Za-z0-9_./+-]+$ ]] || die 'Install Java and GemFire at absolute paths without spaces or shell metacharacters.'
done

# Do not keep two credential sources when updating an earlier installation.
if [[ -f "$CONFIG_DIR/collector.env" ]] && grep -Eq '^[[:space:]]*(JMX_USERNAME|JMX_PASSWORD|JMX_TOKEN|AWS_ACCESS_KEY_ID|AWS_SECRET_ACCESS_KEY|S3_ACCESS_KEY|S3_SECRET_KEY)[[:space:]]*=' "$CONFIG_DIR/collector.env"; then
  die "Move collector credentials to $CONFIG_DIR/collector.properties and remove them from collector.env before reinstalling (use S3_ACCESS_KEY/S3_SECRET_KEY for S3)."
fi

echo "Preflight passed: Ubuntu/systemd, Java $version, GemFire libraries and collector artifacts."
getent group "$ACCOUNT" >/dev/null || groupadd --system "$ACCOUNT"
getent passwd "$ACCOUNT" >/dev/null || useradd --system --gid "$ACCOUNT" --home-dir "$STATE_DIR" --no-create-home --shell /usr/sbin/nologin "$ACCOUNT"
runuser -u "$ACCOUNT" -- test -r "$gemfire_home/lib/gemfire-dependencies.jar" || die 'The gemfire-stats account cannot read the GemFire client libraries. Check parent directory permissions.'
runuser -u "$ACCOUNT" -- test -x "$java_home/bin/java" || die 'The gemfire-stats account cannot execute the selected Java runtime.'

if [[ -f "$UNIT_DIR/$SERVICE.timer" ]]; then
  if [[ "$leave_stopped" != true ]] && systemctl is-active --quiet "$SERVICE.timer"; then start_now=true; fi
  systemctl stop "$SERVICE.timer" "$SERVICE.service"
fi
install -d -m 0755 "$APP_DIR/bin" "$APP_DIR/lib"
install -d -m 0750 -o root -g "$ACCOUNT" "$CONFIG_DIR"
install -d -m 0750 -o "$ACCOUNT" -g "$ACCOUNT" "$STATE_DIR" "$STATE_DIR/reports"
install -d -m 0750 -o "$ACCOUNT" -g "$ACCOUNT" "$LOG_DIR"
if [[ ! -e "$log_file" ]]; then
  runuser -u "$ACCOUNT" -- touch "$log_file"
fi
install -m 0644 "$source_jar" "$APP_DIR/lib/02-standalone-jmx.jar"
install -m 0755 "$source_launcher" "$APP_DIR/bin/collect-stats.sh"
install -m 0755 "$INSTALLATION_DIR/collect-stats-logged.sh" "$APP_DIR/bin/collect-stats-logged.sh"
install -m 0755 "$INSTALLATION_DIR/stop.sh" "$APP_DIR/bin/stop.sh"
install -d -m 0755 "$LOGROTATE_DIR"
sed -e "1s|.*|$log_file {|" \
  -e "s/^[[:space:]]*daily$/    $log_rotate_interval/" \
  -e "s/^[[:space:]]*maxsize .*/    maxsize $log_rotate_size/" \
  -e "s/^[[:space:]]*rotate .*/    rotate $log_rotate_count/" \
  "$INSTALLATION_DIR/$SERVICE.logrotate" > "$LOGROTATE_DIR/$SERVICE"
chmod 0644 "$LOGROTATE_DIR/$SERVICE"
install -m 0640 -o root -g "$ACCOUNT" "$INSTALLATION_DIR/collector.example.properties" "$CONFIG_DIR/collector.example.properties"
if [[ -n "$config_source" ]]; then
  if [[ $(readlink -f "$config_source") != "$CONFIG_DIR/collector.properties" ]]; then
    install -m 0640 -o root -g "$ACCOUNT" "$config_source" "$CONFIG_DIR/collector.properties"
  fi
elif [[ ! -f "$CONFIG_DIR/collector.properties" ]]; then
  install -m 0640 -o root -g "$ACCOUNT" "$INSTALLATION_DIR/collector.example.properties" "$CONFIG_DIR/collector.properties"
fi
chown root:"$ACCOUNT" "$CONFIG_DIR/collector.properties"
chmod 0640 "$CONFIG_DIR/collector.properties"

temporary_env=$(mktemp)
trap 'rm -f "$temporary_env"' EXIT
env_source="$CONFIG_DIR/collector.env"
if [[ ! -f "$env_source" ]]; then env_source="$INSTALLATION_DIR/collector.example.env"; fi
printf '# Runtime settings only. Collector credentials belong in collector.properties.\n' > "$temporary_env"
awk '/^(JAVA_OPTS|JAVA_TOOL_OPTIONS|JDK_JAVA_OPTIONS)=/' "$env_source" >> "$temporary_env"
printf '\nGEMFIRE_HOME=%s\nJAVA_HOME=%s\nPATH=%s/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin\n' \
  "$gemfire_home" "$java_home" "$java_home" >> "$temporary_env"
install -m 0640 -o root -g "$ACCOUNT" "$temporary_env" "$CONFIG_DIR/collector.env"

install -m 0644 "$INSTALLATION_DIR/$SERVICE.service" "$UNIT_DIR/$SERVICE.service"
logging_dir="$UNIT_DIR/$SERVICE.service.d"
install -d -m 0755 "$logging_dir"
{
  printf '# Generated from collector.properties by installation/install.sh.\n[Service]\nLogsDirectory=\n'
  printf 'LogsDirectory=%s\n' "${LOG_DIR#"$LOG_ROOT/"}"
  printf 'Environment=COLLECTOR_LOG_FILE=%s\n' "$log_file"
  printf 'Environment=COLLECTOR_LOG_LEVEL=%s\nEnvironment=COLLECTOR_GEMFIRE_LOG_LEVEL=%s\n' "$log_level" "$gemfire_log_level"
} > "$logging_dir/zz-collector-logging.conf"
chmod 0644 "$logging_dir/zz-collector-logging.conf"
sed "s/^OnUnitInactiveSec=.*/OnUnitInactiveSec=${interval_seconds}s/" "$INSTALLATION_DIR/$SERVICE.timer" > "$UNIT_DIR/$SERVICE.timer"
chmod 0644 "$UNIT_DIR/$SERVICE.timer"
# Apply after ordinary override.conf, including the manual calendar override used by older installs.
# This generated file is replaced on each installation so properties remain the schedule source.
schedule_dir="$UNIT_DIR/$SERVICE.timer.d"
install -d -m 0755 "$schedule_dir"
{
  printf '# Generated by installation/install.sh. Set COLLECTION_CALENDAR in collector.properties.\n[Timer]\n'
  printf 'OnActiveSec=\nOnBootSec=\nOnStartupSec=\nOnUnitActiveSec=\nOnUnitInactiveSec=\nOnCalendar=\n'
  if [[ -n "$calendar" ]]; then
    printf 'OnCalendar=%s\n' "$calendar"
  else
    printf 'OnActiveSec=10s\nOnUnitInactiveSec=%ss\n' "$interval_seconds"
  fi
} > "$schedule_dir/zz-collector-schedule.conf"
chmod 0644 "$schedule_dir/zz-collector-schedule.conf"
systemctl daemon-reload
systemctl enable "$SERVICE.timer"
if [[ "$start_now" = true ]]; then
  systemctl restart "$SERVICE.timer" || die "Could not start $SERVICE.timer. Check: sudo journalctl -u $SERVICE.timer -n 50 --no-pager"
  systemctl is-active --quiet "$SERVICE.timer" || die "The timer is not active. Check: sudo systemctl status $SERVICE.timer"
fi

if [[ -n "$calendar" ]]; then
  echo "Installed $SERVICE with calendar schedule: $calendar"
  echo 'COLLECTION_CALENDAR takes precedence over --interval-seconds; startup and interval triggers are disabled.'
else
  echo "Installed $SERVICE with a ${interval_seconds}-second delay between completed collections."
fi
echo "Configuration: $CONFIG_DIR/collector.properties and $CONFIG_DIR/collector.env"
echo "Reports: $STATE_DIR/reports/<ENV>/<CLUSTER_NAME>/"
if [[ "$start_now" = true ]]; then
  echo 'Timer: active and enabled for boot. Next trigger is shown below.'
  systemctl list-timers --all "$SERVICE.timer" --no-pager
else
  echo 'Timer: STOPPED; enabled for future boots only. Automatic collection has not started.'
  echo "Configure the collector, then run: sudo systemctl enable --now $SERVICE.timer"
fi
echo 'Timer activation does not confirm JMX collection or S3 delivery; check the service results.'
echo "Status: sudo systemctl list-timers --all $SERVICE.timer"
echo "Last run: sudo systemctl show $SERVICE.service -p Result -p ExecMainStatus -p ExecMainExitTimestamp"
echo "Logs: sudo journalctl -u $SERVICE.service -f"
echo "Log file: sudo tail -F $log_file"
echo "Java logging: $log_level; GemFire: $gemfire_log_level"
echo "Rotation: $log_rotate_interval or over $log_rotate_size at the next logrotate check; $log_rotate_count archives. Check: systemctl status logrotate.timer"
echo "Stop collection and disable scheduling: sudo $APP_DIR/bin/stop.sh"
