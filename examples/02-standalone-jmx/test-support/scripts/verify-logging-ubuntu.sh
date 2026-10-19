#!/usr/bin/env bash
# Root-only integration fixture. Run in a disposable installation-test image, never on the VM.
set -euo pipefail
[[ -f /.dockerenv && $(id -u) = 0 ]] || { echo 'Run this check as root in a disposable Docker container.' >&2; exit 2; }
PROJECT_DIR=$(cd "$(dirname "$0")/../.." && pwd)
APP=/opt/gemfire-nightly-stats/bin
LOG_DIR=/var/log/gemfire-nightly-stats
LOG_FILE="$LOG_DIR/collector.log"
getent group gemfire-stats >/dev/null || groupadd --system gemfire-stats
id gemfire-stats >/dev/null 2>&1 || useradd --system --gid gemfire-stats --no-create-home --shell /usr/sbin/nologin gemfire-stats
install -d -m 0755 "$APP"
install -d -m 0750 -o gemfire-stats -g gemfire-stats "$LOG_DIR"
install -m 0755 "$PROJECT_DIR/installation/collect-stats-logged.sh" "$APP/collect-stats-logged.sh"
cat > "$APP/collect-stats.sh" <<'COLLECTOR'
#!/usr/bin/env bash
echo 'unique-report.json: simulated successful upload'
echo 'simulated collector stderr' >&2
exit "${TEST_COLLECTOR_EXIT:-0}"
COLLECTOR
chmod 0755 "$APP/collect-stats.sh"
runuser -u gemfire-stats -- env LOGS_DIRECTORY="$LOG_DIR" "$APP/collect-stats-logged.sh" collect > /tmp/collector-console
cmp /tmp/collector-console "$LOG_FILE"
grep -q 'exit_status=0' "$LOG_FILE"
[[ $(stat -c '%a:%U:%G' "$LOG_FILE") = 640:gemfire-stats:gemfire-stats ]]

rotation=/etc/logrotate.d/gemfire-nightly-stats
install -m 0644 "$PROJECT_DIR/installation/gemfire-nightly-stats.logrotate" "$rotation"
logrotate --debug "$rotation" > /tmp/logrotate-debug 2>&1
logrotate --state /tmp/collector-rotation.state --force "$rotation"
test -f "$LOG_FILE.1"
test ! -e "$LOG_FILE.1.gz"
grep -q 'exit_status=0' "$LOG_FILE.1"
[[ $(stat -c '%a:%U:%G' "$LOG_FILE") = 640:gemfire-stats:gemfire-stats ]]
result=0
runuser -u gemfire-stats -- env LOGS_DIRECTORY="$LOG_DIR" TEST_COLLECTOR_EXIT=4 \
  "$APP/collect-stats-logged.sh" collect > /tmp/collector-failed-console || result=$?
[[ "$result" = 4 ]]
cmp /tmp/collector-failed-console "$LOG_FILE"
grep -q 'exit_status=4' "$LOG_FILE"
logrotate --state /tmp/collector-rotation.state --force "$rotation"
gzip -cd "$LOG_FILE.2.gz" | grep -q 'exit_status=0'
grep -q 'exit_status=4' "$LOG_FILE.1"

# Rotation must bound archive count and retain ownership across repeated rotations.
for iteration in {1..16}; do
  printf 'collection %s\n' "$iteration" >> "$LOG_FILE"
  logrotate --state /tmp/collector-rotation.state --force "$rotation"
done
[[ $(find "$LOG_DIR" -maxdepth 1 -name 'collector.log.*' -type f | wc -l) = 14 ]]
[[ $(stat -c '%a:%U:%G' "$LOG_FILE") = 640:gemfire-stats:gemfire-stats ]]
install -m 0644 "$PROJECT_DIR/installation/gemfire-nightly-stats.service" /etc/systemd/system/
install -m 0644 "$PROJECT_DIR/installation/gemfire-nightly-stats.timer" /etc/systemd/system/
systemd-analyze verify /etc/systemd/system/gemfire-nightly-stats.service /etc/systemd/system/gemfire-nightly-stats.timer

# Run the real installer on Ubuntu with only systemctl mocked: this container has no PID 1 systemd.
# A readable placeholder satisfies the GemFire preflight; this test never connects to JMX.
install -d /run/systemd/system /tmp/fixture-tools /tmp/fixture-gemfire/lib
touch /tmp/fixture-gemfire/lib/gemfire-dependencies.jar
cp "$APP/collect-stats.sh" /tmp/fixture-collector.sh
cat > /tmp/fixture-tools/systemctl <<'SYSTEMCTL'
#!/usr/bin/env bash
[[ "$1" != is-active ]]
SYSTEMCTL
chmod 0755 /tmp/fixture-tools/systemctl
cat > /tmp/logging.properties <<'PROPERTIES'
ENV=logging-test
LOG_DIR=/var/log/custom-collector
LOG_FILE_NAME=demo.log
LOG_ROTATE_INTERVAL=weekly
LOG_ROTATE_SIZE=25M
LOG_ROTATE_COUNT=3
LOG_LEVEL=debug
GEMFIRE_LOG_LEVEL=error
PROPERTIES
PATH="/tmp/fixture-tools:$PATH" "$PROJECT_DIR/installation/install.sh" \
  --gemfire-home /tmp/fixture-gemfire --java-home /opt/test-java \
  --config /tmp/logging.properties --no-start > /tmp/fixture-install-output
install -m 0755 /tmp/fixture-collector.sh "$APP/collect-stats.sh"
logging=/etc/systemd/system/gemfire-nightly-stats.service.d/zz-collector-logging.conf
grep -qx 'LogsDirectory=custom-collector' "$logging"
grep -qx 'Environment=COLLECTOR_LOG_LEVEL=DEBUG' "$logging"
grep -qx 'Environment=COLLECTOR_GEMFIRE_LOG_LEVEL=ERROR' "$logging"
LOG_FILE=$(sed -n 's/^Environment=COLLECTOR_LOG_FILE=//p' "$logging")
[[ "$LOG_FILE" = /var/log/custom-collector/demo.log ]]
[[ $(stat -c '%a:%U:%G' "$LOG_FILE") = 640:gemfire-stats:gemfire-stats ]]
grep -qx '    weekly' "$rotation"
grep -qx '    maxsize 25M' "$rotation"
logrotate --debug "$rotation" > /tmp/custom-logrotate-debug 2>&1
for iteration in {1..5}; do
  runuser -u gemfire-stats -- env COLLECTOR_LOG_FILE="$LOG_FILE" \
    "$APP/collect-stats-logged.sh" collect > /tmp/custom-console
  cmp /tmp/custom-console "$LOG_FILE"
  logrotate --state /tmp/custom-rotation.state --force "$rotation"
done
[[ $(find /var/log/custom-collector -maxdepth 1 -name 'demo.log.*' -type f | wc -l) = 3 ]]
gzip -cd "$LOG_FILE.3.gz" | grep -q 'exit_status=0'
[[ $(stat -c '%a:%U:%G' "$LOG_FILE") = 640:gemfire-stats:gemfire-stats ]]
systemd-analyze verify /etc/systemd/system/gemfire-nightly-stats.service /etc/systemd/system/gemfire-nightly-stats.timer
echo 'Ubuntu checks passed: default and properties-configured paths, exit status, ownership, rotation, compression, retention and units.'
