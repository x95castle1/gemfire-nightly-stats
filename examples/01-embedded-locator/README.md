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
- Output is `<NIGHTLY_STATS_DIR>/<CLUSTER_NAME>-<yyyy-MM-dd>.json`;
  `NIGHTLY_STATS_DIR` defaults to `<LOG_DIRECTORY>/nightly-stats`.
- Relative paths resolve from the process working directory. A successful run replaces that day's report.
- Collection and upload errors are logged without stopping the locator. The main thread waits on the locator.
- When S3 delivery is enabled, pending reports are retried on restart and after report collection.
  See the [upload reference](report-reference.md#uploading-to-s3).

Inside a secured cluster, management operations such as `describe config` can be denied.
A denied TLS lookup is logged and produces `tls: null`; validate permissions with your security manager.

## Distribution

From this directory, run `./gradlew assembleDist` or `make embedded-dist`.
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
Add `S3=true` to verify an upload to local SeaweedFS.

Default test ports are 20334, 21099 and 40504/40505. Override `LOCATOR_PORT`, `JMX_PORT`,
`SERVER_PORT_BASE` and `TEST_DIR` as needed. `make start`, `make status`, `make report`,
`make logs` and `make stop` operate the embedded test cluster manually.

If no report appears, check `NIGHTLY_STATS_RUN_ON_STARTUP` or the next nightly time and inspect
the locator log for `Nightly stats`. Test startup output is in `.test-cluster/locator.out`.
For remote clients, make the JMX manager's advertised RMI hostname reachable and consistent with its certificate.
