# Install example 02 on an Ubuntu VM

This directory installs the standalone collector as `gemfire-nightly-stats.service`, with
`gemfire-nightly-stats.timer` scheduling a separate collection every minute. It connects to an
existing cluster over remote JMX. Each collection retains its own local file and, when enabled,
its own S3 object.

## Prerequisites

The installer checks for Ubuntu booted with systemd, root privileges, an existing Java 17+
runtime, GemFire client libraries, the built collector jar and the required Ubuntu utilities.
It installs no JDK, JRE, packages or GemFire software. Use a Java version supported by your
GemFire installation. The service account must be able to traverse the Java and GemFire
directories and read all GemFire client jars; installations under a private home directory
may need to be relocated or have their access permissions adjusted.

Build example 02 on your development machine:

```sh
cd examples/02-standalone-jmx
./gradlew assembleDist
```

Copy the resulting archive from `build/distributions/` to the VM and unpack it. Run the
following commands from its top-level directory, which contains `bin/`, `lib/` and
`installation/`. A built checkout works too. Copying only `installation/` is insufficient:
the installer also needs the jar and launcher beside it in the parent project/distribution.

The templates are [collector.example.properties](collector.example.properties) and
[collector.example.env](collector.example.env). The installer creates the active files as
`collector.properties` and `collector.env`.

## Install and configure

For a first installation, leave the timer stopped while you configure the cluster:

```sh
sudo ./installation/install.sh \
  --gemfire-home /opt/vmware-gemfire \
  --java-home /usr/lib/jvm/java-17-openjdk-amd64

sudoedit /etc/gemfire-nightly-stats/collector.properties
sudo systemctl enable --now gemfire-nightly-stats.timer
```

`--java-home` is optional if `JAVA_HOME` or the root user's `PATH` already selects a suitable
runtime. The installer records the resolved Java and GemFire paths for systemd; it does not
depend on an interactive shell's environment. Supplying `--config` starts the timer automatically.
A first installation using only the example template leaves the timer stopped and enables it
for future boots; configure it before rebooting. Use `--start` to start with an existing installed
configuration, or `--no-start` to leave the timer stopped even when supplying `--config` or updating
an active installation. `--start` and `--no-start` cannot be combined.

Set `ENV`, `CLUSTER_NAME`, `JMX_HOST` and `JMX_PORT` in the properties file. The supplied host
is an example; replace it. Use the manager's JMX port, not the locator discovery port. The VM
must reach both the configured manager and the RMI endpoint it advertises. Connection,
authentication and remote TLS failures appear in the journal when collection runs.

The example starts in `DELIVERY_MODE=local`. To upload, change it to `s3`, configure the S3
endpoint and bucket, and put `S3_ACCESS_KEY` and `S3_SECRET_KEY` in the properties file. Use the
SeaweedFS host's reachable address, rather than `localhost` unless it runs on this VM.
All JMX, S3 and TLS credentials belong in `collector.properties` only; the collector ignores
credential environment variables. `collector.env` contains only the Java/GemFire runtime paths
and JVM options written by the installer. The properties example includes JMX authentication
and TLS/mutual TLS settings. Make certificate stores readable by the `gemfire-stats` account.
Configuration files are installed
with mode `0640`, owned by root and the service group.

If you already have a configured file, install and start directly. For a 30-second demo:

```sh
sudo ./installation/install.sh \
  --gemfire-home /opt/vmware-gemfire \
  --config /tmp/my-collector.properties \
  --interval-seconds 30
```

The installer verifies timer activation, prints whether scheduling is **active** or **stopped**,
and shows the next scheduled trigger when active. This confirms scheduling only; check the
service result and journal to confirm successful collection and delivery. Add `--no-start` if
you need to adjust JVM options or certificate permissions before the first run.

The timer first triggers about 10 seconds after it starts. Each subsequent run waits the
configured interval after the previous collection/upload completes. Runs do not overlap.
Collection failures leave the service marked failed in systemd and are retried on the next
timer activation. Scheduling is controlled by the timer, not `COLLECTION_INTERVAL_SECONDS`
in a properties file. The default 60-second interval, or any positive integer number of
seconds, can be selected with `--interval-seconds`.

## Set a calendar schedule in properties

For daily collection at 10 PM US Central, add this single line to your collector properties:

```properties
COLLECTION_CALENDAR=*-*-* 22:00:00 America/Chicago
```

Use `UTC` instead of `America/Chicago` for 10 PM UTC, or omit the timezone to use the VM's
timezone. Named timezones follow daylight-saving changes. Calendar expressions use
[systemd's calendar syntax](https://www.freedesktop.org/software/systemd/man/latest/systemd.time.html#Calendar%20Events).

For an already-installed collector, edit `/etc/gemfire-nightly-stats/collector.properties`
and rerun the installer from the checkout or distribution:

```sh
sudo ./installation/install.sh \
  --gemfire-home "$GEMFIRE_HOME" \
  --config /etc/gemfire-nightly-stats/collector.properties

systemctl list-timers --all gemfire-nightly-stats.timer
```

The installer validates the calendar before stopping the running collector or changing any
installed files. `COLLECTION_CALENDAR` takes precedence over `--interval-seconds`. Calendar
mode disables both the 10-second startup collection and the repeating interval: the first
collection waits for the next calendar occurrence. There is no automatic catch-up setting
enabled by the installer. The Java process remains one-shot.

Schedule changes require rerunning the installer; editing properties alone does not change
systemd's timer. Normal JMX, S3 and report-setting edits still take effect on the next collection.
To restore interval scheduling, remove or blank `COLLECTION_CALENDAR` and rerun the installer
with `--interval-seconds 60` (or your chosen interval).

The installer writes a generated `gemfire-nightly-stats.timer.d/zz-collector-schedule.conf`
file under `/etc/systemd/system`. It resets existing timing directives and sorts after the
ordinary `override.conf` created by `systemctl edit`, so the earlier manual calendar override
does not take precedence over the properties setting. Keep scheduling in the properties file
and installer options; this generated file is replaced on each installation. Custom overrides
that sort after it can still override it.

## Installed files

| Location | Contents |
|---|---|
| `/opt/gemfire-nightly-stats/bin/collect-stats.sh` | Existing standalone launcher |
| `/opt/gemfire-nightly-stats/bin/stop.sh` | Stop the collector process and disable its timer |
| `/opt/gemfire-nightly-stats/lib/02-standalone-jmx.jar` | Complete collector application |
| `/etc/gemfire-nightly-stats/collector.properties` | JMX, report and optional S3 configuration |
| `/etc/gemfire-nightly-stats/collector.env` | Selected Java/GemFire runtime paths and JVM options only |
| `/var/lib/gemfire-nightly-stats/reports/<ENV>/<CLUSTER_NAME>/` | Individual reports and upload receipts |
| `/etc/systemd/system/gemfire-nightly-stats.service` | One-shot service running as `gemfire-stats` |
| `/etc/systemd/system/gemfire-nightly-stats.timer` | Boot-enabled collection schedule |
| `/etc/systemd/system/gemfire-nightly-stats.timer.d/zz-collector-schedule.conf` | Generated schedule from properties or the interval option |

The installer creates the `gemfire-stats` system account with no login shell. It preserves
reports and existing properties unless you explicitly pass `--config`. Reinstallation updates
the application, units and selected runtime paths while retaining `JAVA_OPTS` and the credentials
in your properties file. If upgrading an earlier installation with credentials in `collector.env`,
move them to `collector.properties` and remove the environment entries before reinstalling.
It stops the old timer/service during replacement and resumes a timer that was already running,
unless `--no-start` is supplied.
Pass `--interval-seconds` again when reinstalling to retain a nondefault interval.
An existing `COLLECTION_CALENDAR` is read from the installed properties and reapplied when
`--config` is omitted.

## Operate the collector

```sh
sudo systemctl list-timers gemfire-nightly-stats.timer
sudo systemctl status gemfire-nightly-stats.timer gemfire-nightly-stats.service
sudo systemctl show gemfire-nightly-stats.service \
  -p Result -p ExecMainStatus -p ExecMainExitTimestamp
sudo journalctl -u gemfire-nightly-stats.service -f

# Collect once immediately, independently of the next scheduled run.
sudo systemctl start gemfire-nightly-stats.service

# Stop scheduled collection and any current run; keep all saved reports.
sudo /opt/gemfire-nightly-stats/bin/stop.sh

# Resume automatic collection now and after future boots.
sudo systemctl enable --now gemfire-nightly-stats.timer
```

The same stop script is available as `sudo ./installation/stop.sh` in a checkout or unpacked
distribution. It disables and stops the timer before stopping any running collector process,
then verifies that both units are inactive. It preserves configuration and reports, and can
be run again when the collector is already stopped.

An inactive one-shot service between successful runs is expected; the timer remains active.
`list-timers` shows when scheduling last triggered and when it will trigger next. A completed
successful service run has `Result=success`, `ExecMainStatus=0` and a recent exit timestamp;
timer activation alone does not establish collection success. If the collector timer is absent
from `list-timers`, check `systemctl status gemfire-nightly-stats.timer` and start it with the
resume command above. Starting only the `.service` performs one collection and does not enable
automatic scheduling.
Changes to ordinary properties/environment settings take effect on the next collection.
For a new interval, clear `COLLECTION_CALENDAR` and rerun the installer with `--interval-seconds`.
For daily or nightly collection, set `COLLECTION_CALENDAR` and rerun the installer as shown above.

## Test the installation with Docker Compose

Use the existing Make commands from the repository root or this example directory:

```sh
make run  # Choose example 02, Ubuntu systemd installation, delivery and a positive interval.
make status
make logs
make stop
```

From the repository root, bypass the pickers with
`make run EXAMPLE=02 RUN_MODE=ubuntu S3=true COLLECTION_INTERVAL_SECONDS=60`.
Inside example 02, omit `EXAMPLE=02`. Make generates the demo configuration, builds the
application and starts the container, leaving its timer running in the background. Each run
recreates the test container to apply application, configuration and interval changes; the
report volume is retained. `make status` shows the timer and last collection service result.
`make logs` follows the collector journal while the container runs. The installer still
installs no packages. The Docker prerequisites below apply to Make as well.

The repository's root Compose file and this example's Compose file provide an opt-in
`collector-install-test` service under the `installation-test` profile. It boots Ubuntu 24.04
with systemd as PID 1, mounts this complete example and your GemFire installation read-only,
and runs the real installer at every container startup. The image provisions systemd and a
Java 17 runtime as test prerequisites; `install.sh` continues to install no packages.

This service uses a privileged container and writable cgroups so systemd can manage its units.
Use it for local installation testing. It uses host networking to reach the laptop's demo JMX
manager, including its RMI callback at `localhost`, and SeaweedFS. On Docker Desktop 4.34+,
enable **Settings > Resources > Network > Enable host networking**, then apply and restart.
See [Docker's host networking documentation](https://docs.docker.com/engine/network/drivers/host/).
Docker Desktop policies that prohibit privileged containers or host networking require testing
on an Ubuntu VM instead.

From the **repository root**, with `GEMFIRE_HOME` pointing to your existing GemFire installation:

```sh
# Start the demo cluster and SeaweedFS, and generate the collector properties file.
make start EXAMPLE=02 S3=true
./gradlew :02-standalone-jmx:assembleDist

export INSTALL_TEST_SOURCE_DIR="$PWD/examples/02-standalone-jmx"
export INSTALL_TEST_CONFIG="$INSTALL_TEST_SOURCE_DIR/.test-cluster/standalone.properties"

docker compose --profile installation-test up --build -d --wait collector-install-test
docker compose exec collector-install-test /usr/local/bin/verify-installation.sh
```

The container copies the supplied properties privately, redirects reports to its state volume,
and translates this demo's TLS-store paths from the laptop into the container. It copies the
demo certificate stores with permissions readable by the installed service account. Host
configuration and certificates remain unchanged. All credentials come from the mounted
properties file; there are no additional JMX or S3 credential environment variables.

The timer first runs after about ten seconds, then waits 30 seconds after each completed
collection. The verifier waits up to three minutes for at least two distinct reports from the
current container boot and validates their JSON. When `DELIVERY_MODE=s3`, it also requires
upload receipts for those reports. Compose's health check confirms installation and timer
activation; run the verifier to confirm actual remote collection and delivery.

For a different interval, set `INSTALL_TEST_INTERVAL_SECONDS=60` before starting the container.
For longer intervals, increase the verifier's timeout, for example
`docker compose exec -e VERIFY_TIMEOUT_SECONDS=300 collector-install-test /usr/local/bin/verify-installation.sh`.
Use `S3=false` with `make start` to test local collection instead.
For short-interval Docker tests, leave `COLLECTION_CALENDAR` unset in the supplied properties;
an explicit calendar also takes precedence over the Docker/Make interval selection.

```sh
# Inspect the installed files and units, or follow collection logs.
docker compose exec collector-install-test bash
docker compose exec collector-install-test systemctl list-timers gemfire-nightly-stats.timer
docker compose exec collector-install-test journalctl -u gemfire-nightly-stats.service -f

# Test installation on another boot, then verify two new reports.
docker compose restart collector-install-test
docker compose exec collector-install-test /usr/local/bin/verify-installation.sh

# Stop only the installation test, keeping its reports.
docker compose stop collector-install-test
```

Reports persist in the `collector-install-test-data` named volume. `make clean` removes that
volume as well as the SeaweedFS volume. `make stop` also stops the test container and keeps its
reports. A mounted template with its placeholder JMX host is
sufficient to inspect installation, but successful collection requires a configured properties
file and a reachable cluster. For another cluster, set `INSTALL_TEST_CONFIG` to your properties
file and ensure any TLS stores are also available inside the container at their configured paths.
For the self-contained example, run these commands from `examples/02-standalone-jmx` instead,
using `./gradlew assembleDist` and `INSTALL_TEST_SOURCE_DIR="$PWD"`.

If your registry policy requires an approved Ubuntu-based image, set `INSTALL_TEST_UBUNTU_IMAGE`
before building. Its base must provide Ubuntu 24.04 and `apt-get`. The default is `ubuntu:24.04`.
