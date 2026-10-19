#!/usr/bin/env bash
# Invoked by systemd on each container boot, including restarts.
set -euo pipefail
umask 077
config=/run/collector-test.properties
[[ -r /input/collector.properties ]] || { echo 'Mount a configured INSTALL_TEST_CONFIG properties file.' >&2; exit 2; }
[[ -r /example/installation/install.sh ]] || { echo 'Mount the complete example 02 directory at /example.' >&2; exit 2; }

# Adapt a private copy of the laptop demo config; never modify the host's properties or stores.
cat /input/collector.properties > "$config"
if [[ -n "${HOST_EXAMPLE_DIR:-}" ]]; then
  escaped=$(printf '%s' "$HOST_EXAMPLE_DIR" | sed 's/[][\\.^$*|]/\\&/g')
  sed "s|$escaped/|/example/|g" "$config" > "$config.next"
  mv "$config.next" "$config"
fi
if [[ -d /example/.test-cluster/certs ]]; then
  mkdir -p /etc/gemfire-nightly-stats/test-certs
  cp -R /example/.test-cluster/certs/. /etc/gemfire-nightly-stats/test-certs/
  sed 's|/example/.test-cluster/certs/|/etc/gemfire-nightly-stats/test-certs/|g' "$config" > "$config.next"
  mv "$config.next" "$config"
fi
# Reports belong to the container's persistent volume, regardless of the source config's path.
printf '\nREPORT_DIR=/var/lib/gemfire-nightly-stats/reports\n' >> "$config"
bash /example/installation/install.sh \
  --gemfire-home /opt/gemfire \
  --java-home /opt/test-java \
  --config "$config" \
  --interval-seconds "${INSTALL_TEST_INTERVAL_SECONDS:-30}"
if [[ -d /etc/gemfire-nightly-stats/test-certs ]]; then
  chown -R root:gemfire-stats /etc/gemfire-nightly-stats/test-certs
  find /etc/gemfire-nightly-stats/test-certs -type d -exec chmod 0750 {} +
  find /etc/gemfire-nightly-stats/test-certs -type f -exec chmod 0640 {} +
fi
touch /run/collector-install-test.started
systemctl restart gemfire-nightly-stats.timer
echo 'Installation complete. Run /usr/local/bin/verify-installation.sh to check two real collections.'
