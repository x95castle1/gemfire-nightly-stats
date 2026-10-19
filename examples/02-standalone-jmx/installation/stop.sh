#!/usr/bin/env bash
# Stop the installed collector and prevent its timer from starting again at boot.
set -euo pipefail
SERVICE=gemfire-nightly-stats

case "${1:-}" in
  --help|-h)
    echo 'Usage: sudo installation/stop.sh'
    echo 'Stops and disables the collector timer, then stops any running collection.'
    echo "Resume: sudo systemctl enable --now $SERVICE.timer"
    exit 0 ;;
  '') ;;
  *) echo "Unknown option: $1" >&2; exit 2 ;;
esac
(($# <= 1)) || { echo 'This script takes no arguments.' >&2; exit 2; }
[[ $(uname -s) = Linux && $(id -u) = 0 ]] || { echo 'Run this script with sudo on the collector VM.' >&2; exit 1; }
command -v systemctl >/dev/null && [[ -d /run/systemd/system ]] || { echo 'The VM must be booted with systemd.' >&2; exit 1; }

# Stop scheduling before stopping the process, so another collection cannot be triggered.
systemctl disable --now "$SERVICE.timer"
systemctl stop "$SERVICE.service"
if systemctl is-active --quiet "$SERVICE.timer" || systemctl is-active --quiet "$SERVICE.service"; then
  echo 'The collector is still active. Check the timer and service with systemctl status.' >&2
  exit 1
fi
echo 'Collector timer disabled and stopped. Collection process stopped.'
echo "Resume collection: sudo systemctl enable --now $SERVICE.timer"
