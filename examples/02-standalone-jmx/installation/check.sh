#!/usr/bin/env bash
# Diagnose the installed collector using its service account, working directory and runtime env.
set -euo pipefail
case "${1:-}" in
  --help|-h)
    echo 'Usage: sudo installation/check.sh [--test-upload]'
    echo 'Checks configuration, storage, JMX and S3. --test-upload leaves a unique diagnostic object in S3.'
    exit 0 ;;
  ''|--test-upload) ;;
  *) echo "Unknown option: $1" >&2; exit 2 ;;
esac
(($# <= 1)) || { echo 'Use only the optional --test-upload flag.' >&2; exit 2; }
[[ $(uname -s) = Linux && $(id -u) = 0 ]] || { echo 'Run this script with sudo on the collector VM.' >&2; exit 1; }
command -v systemd-run >/dev/null && [[ -d /run/systemd/system ]] || { echo 'The VM must be booted with systemd.' >&2; exit 1; }
[[ -x /opt/gemfire-nightly-stats/bin/collect-stats.sh && -r /etc/gemfire-nightly-stats/collector.env ]] \
  || { echo 'Install the collector before running this script.' >&2; exit 1; }

# A separate transient unit leaves the collection timer/service and its last result untouched.
# systemd reads EnvironmentFile directly; never source it as shell code or duplicate credentials.
exec systemd-run --quiet --wait --pipe --collect --service-type=exec \
  --property=User=gemfire-stats --property=Group=gemfire-stats \
  --property=EnvironmentFile=/etc/gemfire-nightly-stats/collector.env \
  --property=WorkingDirectory=/var/lib/gemfire-nightly-stats \
  --property=UMask=0027 --property=NoNewPrivileges=yes --property=PrivateTmp=yes \
  --property=RuntimeMaxSec=20min \
  /opt/gemfire-nightly-stats/bin/collect-stats.sh check \
  --config /etc/gemfire-nightly-stats/collector.properties "$@"
