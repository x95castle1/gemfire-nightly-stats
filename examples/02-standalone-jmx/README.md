# 02 - Standalone JMX collector

Source: `src/main/java/com/example/gemfire/stats/standalone/`.
`NightlyStatsMain` owns the CLI; `RemoteJmx` owns remote connection setup and cleanup.
This directory contains all collector, JSON, storage and S3 delivery source code, its own Gradle build,
wrapper, tests and example configuration. It can be copied out and used as a separate project.
Run the commands below from this example directory.
The supplied properties file retains `DELIVERY_MODE=s3`; configure its S3 destination and credentials,
or explicitly change it to `DELIVERY_MODE=local` for a local-only first run.


The standalone command runs in its own Java process and connects to an **existing** cluster's JMX manager. It collects the same report as the embedded locator example. Start with local files while S3 is unavailable or awaiting approval, then upload the accumulated reports whenever the destination is ready.

Requires Java 17+ and `GEMFIRE_HOME` pointing to a GemFire installation for its client libraries, including the socket factory returned by SSL-enabled JMX managers. The initial distribution uses these libraries from the install rather than bundling them. The existing cluster must expose a running JMX manager at an address reachable from the collector, including the host advertised by RMI. `JMX_PORT` is the management port (usually 1099), not the locator discovery port (usually 10334).

```sh
./gradlew build
cp standalone.properties.example standalone.properties
# Set ENV, CLUSTER_NAME, JMX_HOST, JMX_PORT and REPORT_DIR in the copy.
scripts/collect-stats.sh collect --config standalone.properties
scripts/collect-stats.sh status --config standalone.properties
```

`collect` runs once and exits. Its report goes to `<REPORT_DIR>/<ENV>/<CLUSTER_NAME>/<CLUSTER_NAME>-<yyyy-MM-dd>.json`. `REPORT_DIR` defaults to `reports`. Both labels must start with a letter or number and contain only letters, numbers, dots, underscores or hyphens. These directories preserve report identity for later uploads. A second collection on the same day replaces that day's report, matching the embedded collector's behavior. The JSON layout and S3 key layout are unchanged.

Relative paths are resolved from the command's working directory. `REPORT_TIMEZONE` controls the daily filename; its default is the collector machine's timezone. `collected_at` still comes from the manager's clock and is always UTC. Reports are written through a temporary file and an atomic rename. Each command takes an operating-system file lock on the cluster's report directory; overlapping commands fail promptly. Reports and receipts are kept until an operator removes them. For containers or Kubernetes Jobs, mount persistent storage at `REPORT_DIR`.

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

### Authentication, TLS and scheduling

Set `JMX_USERNAME` and `JMX_PASSWORD`, or `JMX_TOKEN`. These settings fall back to environment variables of the same names. Username/password and token authentication are mutually exclusive. With a security manager, the account needs permission to read the management data and run `describe config`; verify the intended reader role against your security manager. A denied TLS lookup is logged and remains `null`, as with the embedded collector.

For SSL or mutual TLS, use the `gemfire.ssl-*` settings in `standalone.properties.example`. The connector uses GemFire's SSL configuration and socket factory, including its client certificate and truststore. It does not replace the JVM's default SSL context or truststore; S3 HTTPS continues to use the JVM defaults. Set `gemfire.ssl-endpoint-identification-enabled=true` and ensure the manager's advertised hostname matches its certificate. The default `JMX_TIMEOUT_SECONDS=120` bounds the connection, collection and connection close together. A timed-out remote worker cannot write a report; the one-shot process terminates after its local delivery/summary work finishes.

Schedule `collect` with cron, a systemd timer, or a Kubernetes CronJob. For example, with this example at `/opt/gemfire-stats-standalone`:

```cron
0 2 * * * cd /opt/gemfire-stats-standalone && GEMFIRE_HOME=/opt/gemfire scripts/collect-stats.sh collect --config /etc/gemfire-stats/cluster.properties
```

The scheduler determines when the job runs and which timezone its schedule uses. `REPORT_TIMEZONE` controls filenames only. Avoid scheduling the embedded collector and standalone collector to publish the same cluster/day to the same S3 key unless overwriting is intended.

The commands write JSON results to stdout and diagnostics to stderr. Exit statuses are:

| Status | Meaning |
| --- | --- |
| 0 | Command succeeded; optional S3 delivery may still be pending, as shown in the summary |
| 2 | Usage or configuration error |
| 3 | Collection, storage, lock, status or export failure |
| 4 | Required delivery incomplete, or explicit `upload-pending` still has pending reports |

### Distribution and tests

`./gradlew assembleDist` builds ZIP and TAR distributions in
`build/distributions/`. Each includes `lib/02-standalone-jmx.jar`,
`bin/collect-stats.sh`, example settings, report reference, sample output and this README.
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

This starts the local SeaweedFS service, collects locally, then backfills the saved report with JMX pointed at an unreachable address. It downloads each uploaded version with curl's independent SigV4 signing and compares the bytes. It also checks collection with S3 enabled, unchanged reports being skipped, optional and required delivery during an outage, and recovery of a changed report. The outage is simulated by directing this collector to a closed loopback port; the shared S3 service keeps running. Each run uses a separate `regression/<test-run>/` S3 prefix. The GemFire test cluster is stopped afterward; reports and JSON run summaries remain in the printed test directory, and SeaweedFS stays up for inspection (`make s3-down` stops it without removing its data).

