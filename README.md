# GemFire nightly statistics examples

Each example is a complete project with its own collector source, tests, Gradle wrapper,
configuration, launchers and distributions. Collector code is intentionally duplicated.

| Example | How collection runs | Instructions |
|---|---|---|
| 01 - Embedded locator | Inside the Citi-based locator startup, using local MBeans | [Embedded locator](examples/01-embedded-locator/README.md) |
| 02 - Standalone JMX | A separate process connects to an existing JMX manager | [Standalone JMX](examples/02-standalone-jmx/README.md) |

## Local workflow

Install `fzf`, use Java 17+, and set `GEMFIRE_HOME` to a local GemFire installation.
Docker must be running; the local environment also uses `jq`, `keytool`, `lsof` and `gfsh`.

```sh
make start    # choose an example and report delivery with fzf; start the environment
make run      # choose an example and report delivery; reuse or start its environment
make status   # show members, containers and the Ubuntu collector timer/result
make logs     # follow the Ubuntu collector journal when running, otherwise the locator log
make admin    # open the SeaweedFS Admin UI in your browser
make stop     # stop locator, servers and SeaweedFS; keep saved data
```

By default, `make run` for example 01 installs the collector through locator startup. Its first local report
is scheduled after 60 seconds, followed by nightly runs. For example 02, `make run` offers a local
Java process or the Ubuntu systemd installation. Local Java collects once through remote JMX
with the default interval. Selecting a different example stops the previous managed environment
before starting the new one. `stop`, `status` and `logs` remember the active example and its settings.

The runner verifies GemFire PID files against the ports each process owns. If an interrupted
command loses its marker or active-example record, it rediscovers the managed environment.
A healthy cluster is reused; an incomplete cluster is stopped and restarted. Switching examples
also stops a rediscovered environment first. Reports and SeaweedFS data are preserved, while
restarting servers clears their in-memory region contents. Unrelated listeners are left alone.

Use `EXAMPLE=01` or `EXAMPLE=02` to bypass the example picker. Set `S3=true` or `S3=false`
to bypass the report-delivery picker too:

```sh
make start EXAMPLE=02 S3=false
make run EXAMPLE=02 S3=true
make stop
```

The generated locator and standalone configuration, logs and reports are under the selected
example's `.test-cluster/` directory. `TEST_DIR` overrides that location. Settings such as
`SSL`, `AUTH`, `SERVER_COUNT`, `LOCATOR_PORT`, `JMX_PORT` and `SERVER_PORT_BASE` can be supplied
on the command line; stop the environment before changing them.

For example 02, choose **Ubuntu systemd installation** in `make run` to build the application,
boot the Ubuntu container, run the VM installer, and leave its timer running in the background.
Choose the upload setting and a positive interval (30 seconds, one minute, five or fifteen minutes).
To bypass all pickers:

```sh
make run EXAMPLE=02 RUN_MODE=ubuntu S3=true COLLECTION_INTERVAL_SECONDS=60
make status
make logs
make stop
```

`RUN_MODE=local` selects the local Java process. The Ubuntu option requires Docker Desktop host
networking for the laptop demo's JMX/RMI endpoint and uses a privileged container for systemd.
See the [installation test instructions](examples/02-standalone-jmx/installation/README.md#test-the-installation-with-docker-compose).
`make status` reports its timer and last service result; `make logs` follows the collector journal
while the container is running. `make stop` stops it and keeps reports; `make clean` removes its
report volume too. `make start` continues to start the demo infrastructure.

`make start` and `make run` offer **Local files only** or **Local files and SeaweedFS**.
Reports are always saved locally. The selection applies even when reusing an environment:
changing delivery for example 01 restarts its locator and servers, preserving data; example 02
updates the collector configuration without restarting the cluster. Cancelling either picker
leaves the current environment alone. Each example directory offers the delivery picker too.

TLS is enabled by default; `SSL=false` disables it. `make start` always starts SeaweedFS. Its S3 endpoint defaults to
`http://localhost:8333`, and its admin UI to `http://localhost:23646`. The default Admin UI login is **admin / admin**. Settings are
in the ignored `.env` file, created from `.env.example` on first use. `make admin` uses the
configured Admin UI port, including overrides saved by `make start`.

`make run` also offers a collection-interval picker after the example and delivery choices:

| Choice | Use |
|---|---|
| Nightly (configured time) / Once and exit | Normal schedule for embedded / local standalone Java |
| Every 30 seconds | Fast demo after startup |
| Every minute | Recommended demo interval |
| Every 5 minutes | Longer demos |
| Every 15 minutes | Occasional refreshes |

The nightly time defaults to 02:00. Positive intervals wait after each completed run, including
its uploads. Cancelling the interval picker leaves services and their saved settings alone.
The picker is also available inside each self-contained example. `make start` keeps the saved
interval (or its default); only `make run` prompts for an interval.

Set `COLLECTION_INTERVAL_SECONDS` to skip the interval picker and supply any number of seconds.
For a report every minute during a demo:

```sh
make start COLLECTION_INTERVAL_SECONDS=60  # choose example 01 for background collection
make run COLLECTION_INTERVAL_SECONDS=60    # choose example 02 for a foreground collection loop
```

Example 01 first collects 60 seconds after locator startup, then waits the configured interval
between completed runs. Example 02's local Java mode collects immediately, waits the interval, and repeats until
Ctrl-C; the cluster keeps running afterward. Ubuntu mode uses the systemd timer, with a first
run after about ten seconds. `make start` for example 02 only prepares the cluster.
The interval is remembered with the environment; the next `make run` lets you choose again. Set `COLLECTION_INTERVAL_SECONDS=0` to restore
nightly embedded collection (02:00 local time) or one-shot standalone collection. Embedded
interval changes restart the locator and servers. Each collection creates a new JSON file and,
when enabled, a separate SeaweedFS object. The filename includes the collector's local timestamp
with milliseconds, UTC offset and a random UUID, so even simultaneous collections stay separate.
Older date-only reports remain available for uploads, status and exports.

To use your own properties instead of the managed local environment:

```sh
make run EXAMPLE=01 PROPERTIES=/path/to/locator.properties
make run EXAMPLE=02 PROPERTIES=/path/to/standalone.properties
```

The delivery choice overrides `DELIVERY_MODE` in a temporary private copy of your properties;
the supplied file is preserved. The interval selection also overrides the embedded schedule in
that temporary copy. Choosing SeaweedFS requires S3 destination and credentials in
that file. For a direct embedded launcher, set `COLLECTION_INTERVAL_SECONDS=60` in its properties.
The standalone CLI remains one-shot; the repeat loop belongs to `make run`, or you can use cron.

`JMX_PORT` is the management port, not the locator discovery port. See the example README files
for the configuration fields and report format.

## Build and validate

```sh
make build                 # build and unit test both projects
make test                  # pick an example and run its isolated integration test
make test EXAMPLE=all       # test both examples, one after the other
make dist                  # package both ZIP/TAR distributions
make clean                 # stop services and delete builds, test files and SeaweedFS volumes
```

Each example directory offers the same small set of commands, with its own delivery and interval pickers. It can be copied
out and built or run independently. Distributions are written under each example's
`build/distributions/` and contain one application jar with all its collector code.

Integration tests use separate GemFire ports and a separate SeaweedFS project, and stop their
services afterward. Saved reports and object-store volumes remain available for inspection.
`make stop` preserves SeaweedFS data. **`make clean` permanently deletes the SeaweedFS volume
and all stored objects**, as well as build and test files. It uses `docker compose down --volumes`
and remembers the last environment even if you already ran `make stop`.
