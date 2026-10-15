# 01 - Embedded locator collector

`NightlyStatsLocatorStart` starts a GemFire locator and runs collection in a background thread
inside that locator's JVM. It passes the local platform MBean server to the collector in this example;
collection does not open a remote JMX connection. Servers need no collector code.

The startup is adapted from `CitiGemFireLocatorStart`: it implements `Runnable`, retains the
fourteen locator inputs, `CLUSTER_SSL_*` helper methods, locator start/status/wait sequence,
and the `CacheFactory`/`CacheServer` startup block. Monitoring is scheduled immediately before
the final `waitOnLocator()` call.

Source: `src/main/java/com/example/gemfire/stats/embedded/NightlyStatsLocatorStart.java`.
This directory contains all collector, JSON, storage and S3 delivery source code, its own Gradle build,
wrapper, tests and example configuration. It can be copied out and used as a separate project.
Filenames use `<CLUSTER_NAME>-<timestamp>-<uuid>.json`, for example
`test-cluster-2026-10-15T143000.123-0500-86b3f35e-2451-4a80-a773-eec6f465013b.json`.
The timestamp contains local date/time, milliseconds and a numeric UTC offset. The random UUID
keeps separate runs unique even if their clocks return the same instant. Earlier reports and
objects are preserved, and legacy `<CLUSTER_NAME>-<yyyy-MM-dd>.json` files remain readable by
status, uploads and date-filtered exports. The JSON `collected_at` value still comes from the
JMX manager's clock. Reports remain until an operator removes them.

See the [report reference](report-reference.md) and [sample output](sample-output.json).

## Run from a checkout

Use Java 17+ and set `GEMFIRE_HOME` to a GemFire installation. From this example directory:

```sh
./gradlew build
cp locator.properties.example locator.properties
# Edit ENV, CLUSTER_NAME, LOG_DIRECTORY and LOCATOR_PORT.
make run PROPERTIES=locator.properties
```

The direct launcher is `scripts/start-locator.sh locator.properties`.
Extra JVM options can be supplied through `JAVA_OPTS`.

## Configuration and lifecycle

- `ENV`, `CLUSTER_NAME`, `LOG_DIRECTORY` and `LOCATOR_PORT` are required.
- The default member name is `<CLUSTER_NAME>.<BIND_ADDR>.locator<LOCATOR_PORT>`; `MEMBER_NAME`
  overrides it. `BIND_ADDR` defaults to `0.0.0.0`. `LOCATOR_HOST_NAME_FOR_CLIENTS` sets the
  hostname advertised to locator clients.
- The Citi startup also starts a cache server in the locator JVM on `CACHE_SERVER_PORT`
  (default `40404`; `0` chooses an available port). Its startup exception is printed and
  monitoring continues, matching the original cache-server failure handling.
- The launcher enables the JMX manager; `JMX_MANAGER_PORT` defaults to 1099.
- `CLUSTER_SSL_ENABLED`, `CLUSTER_SSL_PROTOCOLS`, `CLUSTER_SSL_MUTUAL_AUTHENTICATION`,
  `CLUSTER_SSL_CIPHERS`, `CLUSTER_SSL_KEY_STORE_FILE_NAME`, `CLUSTER_SSL_KEY_STORE_PASSWORD`
  and `SSL_USE_DEFAULT_CONTEXT` map to the same GemFire SSL settings as the Citi class.
  The Citi configuration uses the same file/password for the keystore and truststore.
- Supply a usable keystore password directly. The referenced `CitiGemFireUtils.decrypt` is
  a placeholder, so this example does not copy it or claim to decrypt passwords;
  `PROFILE_HOME_DIR` is not used. Connect your production decryptor in
  `getSSLKeyStoreTrustStorePassword` when adapting this example to encrypted configuration.
- `gemfire.*` settings are applied afterward and override the Citi settings. They also support
  store types such as `gemfire.ssl-keystore-type=PKCS12` and separate truststores.
- `NIGHTLY_STATS_TIME` defaults to `02:00` in the locator JVM's local timezone. The next
  execution time is recalculated after every run, including across daylight-saving changes.
- `NIGHTLY_STATS_RUN_ON_STARTUP=true` also queues a report 60 seconds after startup.
- `COLLECTION_INTERVAL_SECONDS=60` replaces the daily schedule with collection every minute
  after the previous collection/upload completes. `0` (default) keeps the nightly schedule.
  With the startup flag enabled, the first run still waits 60 seconds; otherwise it waits the interval.
- Output is `<NIGHTLY_STATS_DIR>/<CLUSTER_NAME>-<timestamp>-<uuid>.json`;
  `NIGHTLY_STATS_DIR` defaults to `<LOG_DIRECTORY>/nightly-stats`.
- Relative paths resolve from the process working directory. Each successful run creates a separate report; previous files are preserved.
- Collection and upload errors are logged without stopping the locator. The main thread waits on the locator.
- When S3 delivery is enabled, pending reports are retried on restart and after report collection.
  See the [upload reference](report-reference.md#uploading-to-s3).

Inside a secured cluster, management operations such as `describe config` can be denied.
A denied TLS lookup is logged and produces `tls: null`; validate permissions with your security manager.

## Distribution

From this directory, run `./gradlew assembleDist` or `make dist`.
ZIP and TAR files appear in `build/distributions/`.
Each includes `lib/01-embedded-locator.jar`, `bin/start-locator.sh`,
example properties, report reference, sample output and this README.

Unpack on the target host, set `GEMFIRE_HOME`, copy and edit `locator.properties.example`, then run:

```sh
bin/start-locator.sh locator.properties
```

## Validation and troubleshooting

`./gradlew test` runs collection, scheduling, storage and delivery tests.
`make test` starts this locator, two servers and three regions, waits for its startup report,
verifies the report, and stops the cluster. SSL is enabled by default; `SSL=false` disables it.
S3 delivery is checked by default; `S3=false` disables uploads while still starting SeaweedFS.

Default local environment ports are 20334, 21099 and 40504/40505. Integration tests default to 21334, 22099 and 41504/41505. Override `LOCATOR_PORT`, `JMX_PORT`,
`SERVER_PORT_BASE` and `TEST_DIR` as needed. `make start`, `make status`, `make logs` and `make stop` manage the complete local environment.
`make start` includes SeaweedFS and generates a working configuration; `make run` reuses that environment.
The embedded startup report is scheduled after 60 seconds. Use `PROPERTIES=file` for a custom foreground locator.

If no report appears, check `NIGHTLY_STATS_RUN_ON_STARTUP` or the next nightly time and inspect
the locator log for `Nightly stats`. Test startup output is in `.test-cluster/locator.out`.
For remote clients, make the JMX manager's advertised RMI hostname reachable and consistent with its certificate.

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

For a minute-by-minute demo, run `make start COLLECTION_INTERVAL_SECONDS=60`.
The interval is saved; the next `make run` lets you choose again. Changing delivery or interval while running restarts
its locator and servers, preserving data. Use `COLLECTION_INTERVAL_SECONDS=0` to return to nightly
collection. Each run creates a separate JSON file and, if enabled, a separate SeaweedFS object.

The local runner uses `lsof` and GemFire PID files to recognize its own locator and servers,
even if a startup marker is missing. A healthy environment is reused; an incomplete one is
restarted, preserving reports and SeaweedFS data. Restarting clears in-memory region contents.
Ports owned by unrelated processes are reported as conflicts and are left alone.
