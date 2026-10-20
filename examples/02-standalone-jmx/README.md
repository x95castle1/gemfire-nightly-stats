# 02 - Standalone JMX collector

Source: `src/main/java/com/example/gemfire/stats/standalone/`.
`NightlyStatsMain` owns the CLI; `RemoteJmx` owns remote connection setup and cleanup.
This directory contains all collector, JSON, storage and S3 delivery source code, its own Gradle build,
wrapper, tests and example configuration. It can be copied out and used as a separate project.
Run the commands below from this example directory.
The supplied properties file retains `DELIVERY_MODE=s3`; configure its S3 destination and credentials,
or explicitly change it to `DELIVERY_MODE=local` for a local-only first run.
For a generated local configuration, use `make start` followed by `make run`; this brings up
a normal locator, two servers, sample regions and SeaweedFS. `make stop` stops all of them while preserving data.


The standalone command runs in its own Java process and connects to an **existing** cluster's JMX manager. It collects the same report as the embedded locator example. Start with local files while S3 is unavailable or awaiting approval, then upload the accumulated reports whenever the destination is ready.

Requires Java 17+ and `GEMFIRE_HOME` pointing to a GemFire installation for its client libraries, including the socket factory returned by SSL-enabled JMX managers. The initial distribution uses these libraries from the install rather than bundling them. The existing cluster must expose a running JMX manager at an address reachable from the collector, including the host advertised by RMI. `JMX_PORT` is the management port (usually 1099), not the locator discovery port (usually 10334).

```sh
./gradlew build
cp standalone.properties.example standalone.properties
# Set ENV, CLUSTER_NAME, JMX_HOST, JMX_PORT and REPORT_DIR in the copy.
scripts/collect-stats.sh collect --config standalone.properties
scripts/collect-stats.sh status --config standalone.properties
```

`collect` runs once and exits. Its report goes to `<REPORT_DIR>/<ENV>/<CLUSTER_NAME>/<CLUSTER_NAME>-<timestamp>-<uuid>.json`. `REPORT_DIR` defaults to `reports`. Both labels must start with a letter or number and contain only letters, numbers, dots, underscores or hyphens. These directories preserve report identity for later uploads. Each collection creates a unique file, including multiple collections on the same day or at the same instant. The JSON layout and S3 prefix are unchanged; each file becomes a separate S3 object.

Filenames use `<CLUSTER_NAME>-<timestamp>-<uuid>.json`, for example
`test-cluster-2026-10-15T143000.123-0500-86b3f35e-2451-4a80-a773-eec6f465013b.json`.
The timestamp contains local date/time, milliseconds and a numeric UTC offset. The random UUID
keeps separate runs unique even if their clocks return the same instant. Earlier reports and
objects are preserved, and legacy `<CLUSTER_NAME>-<yyyy-MM-dd>.json` files remain readable by
status, uploads and date-filtered exports. The JSON `collected_at` value still comes from the
JMX manager's clock. Reports remain until an operator removes them.

Relative paths are resolved from the command's working directory. `REPORT_TIMEZONE` controls the filename timestamp and its date used for export filters; its default is the collector machine's timezone. `collected_at` still comes from the manager's clock and is always UTC. Reports are written completely to a temporary file, then renamed to a unique filename without replacing an existing report. Each command takes an operating-system file lock on the cluster's report directory; overlapping commands fail promptly. Reports and receipts are kept until an operator removes them. For containers or Kubernetes Jobs, mount persistent storage at `REPORT_DIR`.

### Local storage and manual handoff

`DELIVERY_MODE=local` is the standalone default. It does not require S3 settings or credentials and makes no S3 requests, even if old S3 settings remain in the file. A saved report is a successful run. `status` shows report count, dates, bytes and free storage; upload fields are `null` while uploads are disabled. `MIN_FREE_SPACE_MB` defaults to 100 and causes a warning before collection when space is low. Any collection or local write failure returns a nonzero status.

Use `export` for a manual transfer through an approved process:

```sh
scripts/collect-stats.sh export --config standalone.properties \
  --from 2026-10-01 --to 2026-10-31 --output /tmp/october-reports.zip
```

The inclusive date filters are optional; omitting both exports all saved reports for that cluster. The ZIP includes only reports and `manifest.json`, which records the environment, cluster, filename, byte count and SHA-256 of each report. It excludes settings, credentials, locks and receipts. The output directory must already exist, the output file must not exist, and it must be outside the cluster's report directory. Empty selections and reports with a mismatching environment/cluster identity fail without leaving a partial archive.

`status`, `export` and `upload-pending` do not connect to GemFire, so they work even while the cluster is down. `status` checks receipts locally and makes no S3 requests or credential checks.

### Enabling S3 later

Set `DELIVERY_MODE=s3` and the S3 settings from the [upload reference](report-reference.md#uploading-to-s3). Missing or malformed S3 configuration fails explicitly. `collect` always saves locally before attempting uploads; it also attempts pending uploads after a failed collection. No report is deleted after uploading.

```sh
# Upload old reports without contacting GemFire, then inspect the remaining backlog.
scripts/collect-stats.sh upload-pending --config standalone.properties
scripts/collect-stats.sh status --config standalone.properties
```

Each pass attempts up to 32 pending reports, newest first, and stops at the first failure. Repeat `upload-pending` to drain a larger backlog once the store is available. The existing receipts skip unchanged reports and cause changed reports or changed destinations to upload again. Report identity is checked before upload so a misplaced file cannot be silently assigned to another cluster.

With `UPLOAD_REQUIRED=false` (the default), `collect` returns success after saving a report even if S3 is unavailable. Its JSON summary and stderr still show the upload error and remaining backlog. With `UPLOAD_REQUIRED=true`, it returns exit 4 if uploading fails or any pending reports remain after the pass. Explicit `upload-pending` also returns 4 while a backlog remains, regardless of `UPLOAD_REQUIRED`. A collection failure keeps exit 3 even if backlog uploads succeed. In local mode, `UPLOAD_REQUIRED=true` is a configuration error.

### Connection diagnostics

Run a check before your first collection or when investigating a failure:

```sh
scripts/collect-stats.sh check --config standalone.properties
# Explicitly verify write permission by leaving a unique diagnostic object in S3:
scripts/collect-stats.sh check --config standalone.properties --test-upload
```

In an unpacked distribution, use `bin/collect-stats.sh`. On an installed Ubuntu VM, use:

```sh
sudo /opt/gemfire-nightly-stats/bin/check.sh
sudo /opt/gemfire-nightly-stats/bin/check.sh --test-upload
```

From this example's checkout on the Ubuntu VM, the Make equivalents are:

```sh
make check
make check TEST_UPLOAD=true
```

These use `sudo` to run diagnostics with the installed configuration and runtime settings.
They do not start the demo cluster or SeaweedFS. The upload option leaves a diagnostic object
in your configured bucket.

The VM helper runs a separate transient unit as `gemfire-stats`, using the installed
`collector.env`, `collector.properties` and service working directory. It leaves the collector
timer, collection service result and collection log file untouched. Reinstall this version
to install the helper; it does not require additional properties. Run `make dist` before
reinstalling. The installer verifies build checksums, checkout source freshness and CLI support
before changing the installation, and rejects outdated artifacts with rebuild instructions.

`check` writes a JSON summary to stdout and progress/results to stderr. Each check reports
`PASS`, `FAIL`, `WARN` or `SKIPPED`. It validates collector settings; creates, reads and removes
a temporary file at the report directory; checks existing JMX certificate stores; and connects
with the same authentication, TLS and `JMX_TIMEOUT_SECONDS` deadline used by collection.
It exercises the collector's management reads and discards the snapshot. Unavailable optional
member TLS metadata is a warning. Newly created probe directories are removed when empty.
Existing reports, receipts, locks and any health-state files are not modified.

In S3 mode, a bounded, unsigned `HEAD` request checks HTTP reachability and, for HTTPS, the
TLS handshake using JVM trust and hostname verification. An HTTP response, including `403`,
does not establish bucket existence, valid credentials or upload permission. Redirects and
server errors are warnings, redirects are not followed, and plain HTTP produces a TLS warning.
Local delivery skips all S3 requests. S3-specific certificate properties remain planned work;
`S3_TLS_*` settings listed in the roadmap are currently ignored and produce a warning if present.

`--test-upload` requires `DELIVERY_MODE=s3` and makes one signed PUT with the configured
credentials. It leaves a small text object at
`<S3_PREFIX>/<ENV>/<CLUSTER_NAME>/_diagnostics/check-<uuid>.txt` and returns its `object_key`.
It does not delete that object or require delete permission. An interrupted upload can also
leave an object behind. Diagnostic objects are separate from report history and have no local
upload receipts. Independent checks continue after failures, so an explicit test upload can
still run if JMX or local storage fails.

Warnings alone return exit `0`; read the per-check results to see what was unverified.
Configuration failures return `2`, storage/JMX failures `3`, and S3 connection/test-upload
failures `4`. When several checks fail, configuration errors take precedence, then storage/JMX,
then S3. Checks do not write collection/delivery health history.

### Authentication, TLS and scheduling

Set `JMX_USERNAME` and `JMX_PASSWORD`, or `JMX_TOKEN`, in the properties file. All JMX, TLS and S3 credentials are read only from that file; process environment credentials are ignored. Username/password and token authentication are mutually exclusive. With a security manager, the account needs permission to read the management data and run `describe config`; verify the intended reader role against your security manager. A denied TLS lookup is logged and remains `null`, as with the embedded collector.

For SSL or mutual TLS, use the `gemfire.ssl-*` settings in `standalone.properties.example`. The connector uses GemFire's SSL configuration and socket factory, including its client certificate and truststore. It does not replace the JVM's default SSL context or truststore; S3 HTTPS continues to use the JVM defaults. Set `gemfire.ssl-endpoint-identification-enabled=true` and ensure the manager's advertised hostname matches its certificate. The default `JMX_TIMEOUT_SECONDS=120` bounds the connection, collection and connection close together. A timed-out remote worker cannot write a report; the one-shot process terminates after its local delivery/summary work finishes.

Schedule `collect` with cron, a systemd timer, or a Kubernetes CronJob. For example, with this example at `/opt/gemfire-stats-standalone`:

```cron
0 2 * * * cd /opt/gemfire-stats-standalone && GEMFIRE_HOME=/opt/gemfire scripts/collect-stats.sh collect --config /etc/gemfire-stats/cluster.properties
```

The scheduler determines when the job runs and which timezone its schedule uses. `REPORT_TIMEZONE` controls filenames only. Both collectors use unique filenames, so publishing to the same cluster prefix retains each individual collection.
With the Ubuntu installer, set `COLLECTION_CALENDAR=*-*-* 22:00:00 America/Chicago` in
`collector.properties` for daily collection at 10 PM US Central, then rerun the installer to
apply it. This replaces interval scheduling. See [calendar scheduling](installation/README.md#set-a-calendar-schedule-in-properties).

The commands write JSON results to stdout and diagnostics to stderr. Exit statuses are:

| Status | Meaning |
| --- | --- |
| 0 | Command succeeded; optional S3 delivery may still be pending, as shown in the summary |
| 2 | Usage or configuration error |
| 3 | Collection, storage, lock, status, export or diagnostic storage/JMX failure |
| 4 | Required delivery incomplete, explicit `upload-pending` still has pending reports, or diagnostic S3 failure |

### Distribution and tests

For an Ubuntu VM, use the [systemd installer](installation/README.md). It checks an existing
Java/GemFire installation, installs a dedicated service account, example configuration and a
timer (one minute by default, configurable down to 30 seconds for demos). It installs no Java
runtime or packages. The complete `installation/` directory is included in the distributions.
Supplying `--config` starts its timer automatically; `--no-start` stages configuration for later
activation. The installer prints the timer state and next trigger. Use the installed
`sudo /opt/gemfire-nightly-stats/bin/stop.sh` to stop a running collection and disable scheduling.
The installed service writes `/var/log/gemfire-nightly-stats/collector.log` and journald, with
UTC start/finish timestamps and the collector exit status. The installer configures logrotate
for daily rotation and up to 14 archives; it checks for existing `logrotate` without installing
packages. Use `sudo tail -F /var/log/gemfire-nightly-stats/collector.log` to follow the file.
Override the path, rotation and Java levels in `collector.properties`, then rerun the installer.
See [logging properties](installation/README.md#override-logging-in-collectorproperties) for the options.
The [Docker Compose installation test](installation/README.md#test-the-installation-with-docker-compose)
boots Ubuntu with systemd, runs the real installer automatically, and verifies scheduled reports
and optional S3 uploads using the `installation-test` profile.
Select **Ubuntu systemd installation** in `make run`, or use
`make run RUN_MODE=ubuntu S3=true COLLECTION_INTERVAL_SECONDS=60` from this directory.
The timer stays running in the background; `make status` shows its next activation and last
service result, `make logs` follows its journal, and `make stop` stops it.

`./gradlew assembleDist` builds ZIP and TAR distributions in
`build/distributions/`. Each includes `lib/02-standalone-jmx.jar`,
`bin/collect-stats.sh`, JAR/source checksum files under `lib/`, example settings, report reference,
sample output and this README.
On the target host, unpack the archive and use `bin/collect-stats.sh` in place of the checkout
launcher shown above. Supply Java and `GEMFIRE_HOME`; the embedded example is not required.

`./gradlew test` covers the standalone commands,
authenticated remote JMX, timeouts, file locking, archive integrity, receipt recovery and S3 outages.
`make test SSL=false` and `make test SSL=true` start a normal locator with
`gfsh` and two servers, then collect through a separate JVM and verify reports and offline commands.
The target locator runs without the embedded collector or its application jar.
`make test SSL=true AUTH=true` also checks a `CLUSTER:READ` account, token authentication
and rejected credentials. Every test stops the members it starts. Default test ports are 21334,
22099 and 41504/41505. Override `STANDALONE_LOCATOR_PORT`, `STANDALONE_JMX_PORT`,
`STANDALONE_SERVER_PORT` or `STANDALONE_TEST_DIR` as environment variables if needed.

To include a real S3 regression, run:

```sh
make test S3=true SSL=true AUTH=true
```

This starts the local SeaweedFS service, collects locally, then backfills the saved report with JMX pointed at an unreachable address. It downloads each uploaded version with curl's independent SigV4 signing and compares the bytes. It also checks collection with S3 enabled, unchanged reports being skipped, optional and required delivery during an outage, and recovery of a changed report. The outage is simulated by directing this collector to a closed loopback port; the shared S3 service keeps running. Each run uses a separate `regression/<test-run>/` S3 prefix. The GemFire test cluster is stopped afterward; reports and JSON run summaries remain in the printed test directory, and SeaweedFS is stopped afterward, with its data preserved for inspection.


## Planned features

Item 2, connection diagnostics, is implemented; see [usage above](#connection-diagnostics).
Unchecked items and their associated commands/properties remain proposed and are not supported
yet. Items 1-3 form the initial feature batch; diagnostics was implemented first.
The remaining items are tracked below for later prioritization.

- [ ] **1. MinIO HTTPS certificate configuration.** Add S3-specific trust for a private CA
  certificate bundle in PEM format and optional mutual TLS using a PKCS12 client certificate
  and private key. Keep hostname verification enabled and use Java's default trust when no
  custom CA is configured. Configure the S3 client's TLS context independently of GemFire/JMX.
  Report unreadable files, invalid passwords, expired certificates and approaching expiration.
  Have the Ubuntu installer verify access as `gemfire-stats`. Certificate loading or upload
  failures must preserve collected reports for retry and must not disrupt an embedded locator.
- [x] **2. Connection diagnostics.** Available as `check --config <file>` to validate configuration,
  certificate files, report-directory access, JMX authentication and required management reads,
  and S3 connectivity/TLS. Show separate `PASS`, `FAIL`, `WARN` and `SKIPPED` results. The normal
  check must not save a report or write a remote object; a temporary directory-access probe
  is removed afterward. An explicit `--test-upload` option uploads a small, uniquely named
  diagnostic object under the configured prefix, prints its key and leaves it there without
  requiring delete permission. TLS connectivity alone must not imply upload authorization;
  bucket inspection permissions can differ from object-write permissions. Diagnostic runs
  must not update report receipts or collection/delivery health history. The installer should
  print the check command rather than automatically contacting remote services.
- [ ] **3. Persistent collection and delivery health.** Extend `status` and add
  `health --config <file>`. Record collection and upload attempts, their separate results,
  last success times, the newest report delivered, destination identity and sanitized errors.
  Write this information atomically to `<REPORT_DIR>/<ENV>/<CLUSTER_NAME>/.collector-state.json`
  (normally under `/var/lib/gemfire-nightly-stats/reports/` for the installed service).
  Health reads that state plus existing reports and upload receipts, **not logs**, and makes
  no JMX or S3 requests. Calculate report freshness, pending count and oldest pending timestamp
  against configurable thresholds. Missing/corrupt state and a changed destination must not
  appear healthy; uploading an old report must not imply that current reports are delivered.
  Keep `status` as an inspection command. Proposed `health` exit codes are `0` for healthy,
  `1` for failed/stale/overdue or insufficient history, and `2` for invalid configuration.
  Local-only delivery skips S3 health checks. Include health in the existing `make status`
  where collector configuration is available, alongside timer/service information.

Proposed certificate and health properties, all in `collector.properties`:

```properties
# Private CA trust; omit to use Java's default trust.
S3_TLS_CA_FILE=/etc/gemfire-nightly-stats/certs/minio-ca.pem
# Optional client identity when the endpoint requires mutual TLS.
S3_TLS_KEYSTORE=/etc/gemfire-nightly-stats/certs/client.p12
S3_TLS_KEYSTORE_TYPE=PKCS12
S3_TLS_KEYSTORE_PASSWORD=replace-me
S3_TLS_EXPIRY_WARN_DAYS=30

# Proposed defaults: 26 hours, allowing two extra hours for a nightly job.
HEALTH_MAX_REPORT_AGE_SECONDS=93600
HEALTH_MAX_PENDING_AGE_SECONDS=93600
```

Acceptance checks should cover trusted/untrusted certificates, hostname mismatches, mutual TLS,
file permissions, expiration, diagnostic failures, explicit test uploads, state preservation
across restarts, destination changes, stale reports, backlog recovery and local-only operation.
Finish with an Ubuntu installation test covering HTTPS delivery, failure and recovery.

Keep credentials in properties and runtime paths/JVM options in `collector.env`. Example 02
remains a one-shot Java process scheduled externally. Implement relevant TLS and health tracking
independently in both examples so each remains self-contained; VM CLI/systemd integration
belongs to example 02.

### Additional feature backlog

These items are outside the first implementation batch; numbering identifies the work and
does not commit to a delivery order.

- [ ] **4. Configurable upload timeouts and bounded retries (high priority).** Move the fixed
  connection/request timeouts into properties. Add configurable attempt limits, backoff and an
  overall upload deadline. Retry temporary network failures and eligible server responses;
  distinguish those from certificate, authentication and permission failures that require an
  operator change. Keep the same object key when retrying a report and preserve pending files
  after exhaustion. Coordinate the maximum run duration with the systemd service timeout.
  Verify transient recovery, retry exhaustion, permanent failures and receipt consistency.
- [ ] **5. Missed calendar-run recovery (medium priority).** Add an installer-managed properties
  option for systemd timer persistence. When enabled, perform one fresh collection after the
  timer resumes if a calendar trigger was missed during downtime. Preserve the configured UTC
  schedule and external scheduling of the one-shot Java process. This does not reconstruct
  reports for past collection times. Verify behavior across reboot, timer downtime and reinstall.
- [ ] **6. Optional local report retention (medium priority).** Add configurable age and storage
  limits, with automatic deletion disabled by default. Preserve reports that have not been
  delivered to the configured destination; remove matching receipts only when eligible reports
  are removed. Define local-only retention explicitly and offer a preview of files eligible
  for cleanup. Verify pending-report protection, destination changes, retention boundaries and
  coordination with collection/upload locking.
- [ ] **7. Report version and completeness metadata (medium priority).** Add a schema version,
  collector version and structured collection warnings. Distinguish unavailable fields,
  permission-denied reads and partial collection from valid empty values. Validate field units
  across supported GemFire versions and document schema compatibility for downstream consumers.
  Keep metadata additive where possible and retain support for older saved reports.
- [ ] **8. JMX manager failover (later).** Allow an ordered list of manager endpoints and try
  another when the selected endpoint is unavailable, within a bounded total connection budget.
  Record the selected endpoint and failure reasons. Verify that a reachable manager belongs to
  the intended cluster using an explicit validation rule; do not silently combine data from
  different clusters. Test unavailable endpoints, all endpoints failing and authenticated/TLS
  connections without creating duplicate reports.
- [ ] **9. Temporary S3 credentials (later).** Support a session token alongside the access key
  and secret key, including the required signing behavior. Keep credentials in the properties
  file, redact tokens from output and define how short-lived credentials are refreshed between
  runs, including for the embedded example. Separate support for supplied temporary credentials
  from automatic credential acquisition/renewal, which needs its own agreed integration.
  Verify token signing, expiry failures, credential replacement and backlog recovery.

## SeaweedFS demo controls

`make start` and `make run` use `fzf` to choose **Local files only** or
**Local files and SeaweedFS**. Set `S3=false` or `S3=true` to skip that picker.
`make run` then asks for the interval: normal schedule, every 30 seconds, every minute,
every 5 minutes or every 15 minutes. Normal means nightly at the configured time (default 02:00)
for embedded collection, or once and exit for standalone. Every minute is a useful demo default.
Set `COLLECTION_INTERVAL_SECONDS` to skip the interval picker, including for a custom interval.
Cancelling any picker leaves the environment alone. `make start` keeps the saved interval or its default.
Reports are always saved locally, and the environment always includes SeaweedFS.
With `PROPERTIES=file`, delivery is overridden in a private temporary copy; your file is preserved.
S3 delivery then requires a destination and credentials in your supplied properties.

The default Admin UI login is **admin / admin**. Run `make admin` to open its configured URL
in your browser. `make stop` keeps saved data; `make clean` permanently deletes the SeaweedFS
volume and its objects, plus local build and test files.

For a minute-by-minute demo, run `make run COLLECTION_INTERVAL_SECONDS=60`.
This runs collection immediately in a separate JVM, then waits 60 seconds before the next run.
Press Ctrl-C to end the loop; the cluster stays running. `make start` only prepares the environment.
The interval is remembered; the next `make run` lets you choose again.
`COLLECTION_INTERVAL_SECONDS=0` restores one-shot `make run`.
Collection/upload failures retry after the interval; configuration errors stop the loop.
Changing delivery regenerates collector settings without restarting the cluster. Each run
creates a separate JSON file and, if enabled, a separate SeaweedFS object. The direct Java CLI stays
one-shot and can still be scheduled with cron.

The local runner uses `lsof` and GemFire PID files to recognize its own locator and servers,
even if a startup marker is missing. A healthy environment is reused; an incomplete one is
restarted, preserving reports and SeaweedFS data. Restarting clears in-memory region contents.
Ports owned by unrelated processes are reported as conflicts and are left alone.
