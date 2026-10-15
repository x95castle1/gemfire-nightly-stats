# GemFire nightly statistics examples

Two Java examples collect the same cluster, member and region statistics from a GemFire JMX manager.
Each folder is a complete project with all collector source code, tests, Gradle wrapper, build settings,
configuration, launcher and ZIP/TAR distribution. Copy either folder out and use it independently.
Collector code is intentionally duplicated between the examples.

| Example | How it runs | Scheduling | Start here |
|---|---|---|---|
| 01 - Embedded locator | Starts a locator and collects inside its JVM using local MBeans | Background scheduler in the locator | [Embedded locator](examples/01-embedded-locator/README.md) |
| 02 - Standalone JMX | A separate process connects to an existing locator's JMX manager port | One run per invocation; use cron or another scheduler | [Standalone JMX](examples/02-standalone-jmx/README.md) |

Each example includes its own [report format](sample-output.json), local persistence and optional S3 delivery.
The root Gradle build and Makefile are optional conveniences for working with both examples;
each example also includes its own Makefile and integration checks.

## Build

Set `GEMFIRE_HOME` to a local GemFire installation and use Java 17 or newer:

```sh
./gradlew build
./gradlew test
./gradlew assembleDist
```

Each example can also be built independently:

```sh
(cd examples/01-embedded-locator && ./gradlew build)
(cd examples/02-standalone-jmx && ./gradlew build)
```

Distributions are written under each example's `build/distributions/`. They include that example's
single jar containing all its code, a launcher, example configuration and instructions. Both use GemFire
libraries supplied by `GEMFIRE_HOME` on the machine running the example.

## Run

Run these commands from the repository root after editing the copied configuration:

```sh
cp examples/01-embedded-locator/locator.properties.example locator.properties
make run-embedded PROPERTIES=locator.properties
```

In a separate process, connect to an existing locator's JMX manager:

```sh
cp examples/02-standalone-jmx/standalone.properties.example standalone.properties
# Set JMX_HOST/JMX_PORT and configure S3, or set DELIVERY_MODE=local.
make collect-standalone PROPERTIES=standalone.properties
```

`JMX_PORT` is the manager port, usually 1099, not the locator discovery port, usually 10334.
Local paths in configuration resolve from the process working directory.

## Validate

```sh
make test-embedded SSL=false
make test-standalone SSL=false
make test-standalone SSL=true AUTH=true
make test-parity SSL=true
```

The standalone test starts a normal `gfsh` locator without the embedded collector.
The parity test compares embedded and remote reports against the same cluster, ignoring timestamps and uptime.
Use `S3=true` to include uploads to local SeaweedFS. Tests need a local GemFire installation,
`jq`, and available ports; SeaweedFS additionally requires Docker. `make help` lists the targets.
Existing `make run`, `make collect` and `make test` remain aliases for the corresponding examples.

## Local S3 storage

`make s3-up` starts the shared SeaweedFS service and creates `.env` on first use.
`make s3-ls` lists objects; `make s3-down` stops the service while keeping its named volume.
`docker compose down --volumes` permanently removes that local object-store data.
See the [S3 reference](examples/02-standalone-jmx/report-reference.md#uploading-to-s3) for configuration and retry behavior.
