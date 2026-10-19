#!/usr/bin/env bash
# Pass only test mount metadata to systemd; application credentials stay in the properties file.
set -euo pipefail
umask 077
for value in "${HOST_EXAMPLE_DIR:-}" "${INSTALL_TEST_INTERVAL_SECONDS:-30}"; do
  [[ "$value" != *$'\n'* && "$value" != *'"'* && "$value" != *'\'* ]] || {
    echo 'Test paths/interval must not contain newlines, quotes or backslashes.' >&2; exit 2;
  }
done
printf 'HOST_EXAMPLE_DIR="%s"\nINSTALL_TEST_INTERVAL_SECONDS="%s"\n' \
  "${HOST_EXAMPLE_DIR:-}" "${INSTALL_TEST_INTERVAL_SECONDS:-30}" > /etc/collector-install-test.env
# On a container restart, prepare configuration before reactivating the installed timer.
# --root makes this an offline filesystem operation, before systemd becomes PID 1.
if [[ -f /etc/systemd/system/gemfire-nightly-stats.timer ]]; then
  systemctl --root=/ disable gemfire-nightly-stats.timer
fi
exec /sbin/init
