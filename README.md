The collector runs on a locator and writes one report per cluster: when it was collected, the cluster as a whole, and one entry per member (locators and servers). The table follows that layout, and the JSON field column shows where each value lands in [`sample-output.json`](sample-output.json).

| Level | Attribute | JSON field | Value / Status | Source |
| --- | --- | --- | --- | --- |
| **Report** | Date/timestamp | `collected_at` | What is the current date timestamp of when the metrics were collected<br>Sample: `2026-09-23T17:38:55Z` | **JMX** `Member.CurrentTime` on the locator running the collector (ms since epoch), written as UTC ISO-8601 |
| **Cluster** | Env | `cluster.env` | This is going to need to be a provided property<br>Sample: `local-mac` (placeholder) | **Provided**: set when the job is set up |
|  | cluster_name | `cluster.cluster_name` | Name of the Cluster<br>Sample: `native-test` (placeholder) | **Provided**: set when the job is set up (GemFire has no cluster name, see [Notes](#notes)) |
|  | locators | `cluster.locators` | List of GemFire Locators<br>Sample: `native-locator` | **JMX** `DS.listLocatorMembers(false)`. Not `DS.listLocators()`, which returns `host[port]` addresses instead of names |
|  | locators_count | `cluster.locators_count` | Total Count of GemFire Locators<br>Sample: `1` | **JMX** length of `locators` above |
|  | servers | `cluster.servers` | List of GemFire Servers<br>Sample: `native-server-0`, `native-server-1` | **JMX** `DS.listServers()`, minus any locators. A locator that also runs a CacheServer is listed there too |
|  | servers_count | `cluster.servers_count` | Total Count of GemFire Servers<br>Sample: `2` | **JMX** length of `servers` above |
|  | "nodes" | `cluster.nodes` | Number of distinct hosts running cluster members<br>Sample: `1` (all 3 members run on the same Mac) | **JMX** distinct `Member.Host` values across `DS.listMembers()`. `Host` is the hostname or the IP depending on the setup, but every member of a cluster reports the same form |
|  | Regions | `cluster.regions_count`, `cluster.regions[].path` | Total Number of Regions<br>Sample: `5` | **JMX** `DS.TotalRegionCount`. Paths come from `DS.listAllRegionPaths()` |
|  | Region type | `cluster.regions[].region_type` | For each Region what type is it?<br>Sample: `/NativePartition` = `PARTITION`, `/NativePartitionPersistent` = `PERSISTENT_PARTITION`, `/NativeReplicate` = `REPLICATE`, `/NativeReplicatePersistent` = `PERSISTENT_REPLICATE`, `/NativeLocal` = `NORMAL` | **JMX** `Region.RegionType`, the data policy (`PARTITION`, `PERSISTENT_PARTITION`, `REPLICATE`, `PERSISTENT_REPLICATE`, `NORMAL`, …) |
|  | Replication | `cluster.regions[].replication` | Is each region REPLICATE or PARTITION?<br>Sample: partition ×2, replicate ×2, neither ×1 (`/NativeLocal`) | **JMX** worked out from `Region.RegionType` above: contains `REPLICATE` = replicate, contains `PARTITION` = partition, anything else (`NORMAL`, `EMPTY`, …) = neither |
| **Each member**<br>(`members[]`) | Member name | `name` | The member's name<br>Sample: `native-locator`, `native-server-0`, `native-server-1` | **JMX** `Member.Name` |
|  | Type | `type` | GemFire Locator or Server<br>Sample: native-locator = `locator`, native-server-0 / native-server-1 = `server` | **JMX** a member in `DS.listLocatorMembers(false)` is a locator (check this first); otherwise one in `DS.listServers()` is a server |
|  | Host | `host` | The machine the member runs on<br>Sample: `192.168.68.60` for all 3 members (the IP here; in Docker it was the hostname) | **JMX** `Member.Host` (the hostname or the IP, depending on the setup) |
|  | OS | `os.kernel`, `os.distribution` | Example: RHEL 8 (`Linux 4.18.0-553.89.1.el8_10.x86_64 amd64`)<br>Sample: `Mac OS X 15.6 aarch64`, distribution `null` (macOS isn't RHEL, and on macOS the version is the macOS release, not the kernel) | **JMX** `Member.showOSMetrics()` → `name` + `version` + `arch` for the kernel string<br>The distribution comes from the kernel version: RHEL kernels carry the release, e.g. `…el8_10…` = RHEL 8.10 (`el7` kernels only carry the major version). Other kernels give no distribution<br>Don't use the `Build-Platform` line in `Member.Version`: that's the machine GemFire was built on |
|  | Address Family | `address_family` | IPv4 or IPv6 for the host machine<br>Sample: `IPv4` on all 3 members | **JMX** `Member.Version` → the `Running on: <host>/<ip>, …` line: dotted = IPv4, contains `:` = IPv6<br>Don't use `Member.Id`: it can start with the hostname instead of the IP |
|  | Memory | `memory_bytes` | The host machines memory<br>Sample: `38654705664` bytes (36 GiB) on all 3 members | **JMX** `Member.showOSMetrics()` → `totalPhysicalMemorySize` (bytes, although the javadoc says MB) |
|  | memory quotas | `memory_quotas.heap_max_mb`, `memory_quotas.off_heap_max_bytes` | The heap allocated to gemfire<br>Sample: native-locator `512` MB heap; native-server-0 / native-server-1 `1024` MB heap; no off-heap | **JMX** `Member.MaxMemory` (MB, the `-Xmx` value). Off-heap, if used: `Member.OffHeapMaxMemory` (bytes) |
|  | CPU count | `available_processors` | CPUs the member's host reports (the `6 cpu(s)` in the log banner)<br>Sample: `12` on all 3 members | **JMX** `Member.showOSMetrics()` → `availableProcessors` |
|  | Server core | `server_core` | 6 cores (`6 cpu(s)`). ⚠️ Meaning TBD, see [Notes](#notes)<br>Sample: `null` (not collected until the meaning is settled) | If it means the CPUs the VM sees: same as `available_processors`. If it means the physical server's cores, a VM can't see those: **Provided**, e.g. from vCenter |
|  | Partition core | `partition_core` | ⚠️ TBD, see [Notes](#notes)<br>Sample: `null` (not collected until the meaning is settled) | If it's the IBM meaning: same as `available_processors` |
|  | PVU per core | `pvu_per_core` | ⚠️ TBD, see [Notes](#notes)<br>Sample: `null` (not collected until the meaning is settled) | **Provided**: IBM's PVU table value for the CPU model. JMX doesn't report the CPU model |
|  | uptime | `uptime_seconds` | Total Uptime of the Gemfire Locator or Server<br>Sample: native-locator `14` s (just restarted), native-server-0 `62` s, native-server-1 `58` s | **JMX** `Member.MemberUpTime` (seconds) |
|  | software_version | `software_version` | Example: `10.1.3` (`Tanzu GemFire 10.1.3`)<br>Sample: `10.3.2` on all 3 members | **JMX** `Member.ReleaseVersion` (`10.1.3`). `Member.Version` has the full product string |
|  | Installation path | `installation_path` | Example: `/opt/middleware/pivotal/gemfire_1013`<br>Sample: `/Users/example/dev/vmware-gemfire-10.3.2` on all 3 members | **JMX** `Member.ClassPath`: find GemFire's own jar by name (`gemfire-bootstrap.jar`, `gemfire-dependencies.jar` or `geode-dependencies.jar`) and take the folder above its `lib/`. Matching by name skips other jars that sit in their own `lib/` folders |
|  | Encryption | `encryption.ssl_enabled`, `encryption.ssl_enabled_components` | Is SSL Enabled?<br>Sample: `true`, components `["ALL"]` on all 3 members | **JMX** `Member.listGemFireProperties()` → `securableCommunicationChannel` (the `ssl-enabled-components` setting; empty list = no SSL), plus the legacy `clusterSSLEnabled` / `serverSSLEnabled` / `gatewaySSLEnabled` / `jmxManagerSSLEnabled` / `httpServiceSSLEnabled` |
|  | TLS | `tls.ssl_protocols`, `tls.ssl_client_protocols`, `tls.ssl_server_protocols` | What TLS Protocols are Enabled<br>Sample: `ssl-protocols` = `TLSv1.2 TLSv1.3` on all 3 members (set in `gfsecurity.properties`, which GemFire reports space-separated); client/server protocols not set | **JMX** `processCommand("describe config --member=<name> --hide-defaults=false")` on the manager's `Member` MBean returns JSON. Read `ssl-protocols`, `ssl-client-protocols` and `ssl-server-protocols` from the `api-properties` or `file-properties` section, or from a `-Dgemfire.ssl-…` entry in `jvm-args`. Not set = `any` (JDK default, usually TLSv1.2 + TLSv1.3)<br>Don't use `Member.listGemFireProperties()` → `SSLProtocols`: GemFire never fills it in, so it's always `null` |

## Source legend

- **JMX**: read through the JMX manager of the locator the collector runs on. The manager gathers every member's GemFire MBeans, so it sees the whole cluster. Servers need no changes: their MBeans exist by default (unless `disable-jmx=true`).
  - `DS` = `GemFire:service=System,type=Distributed` (DistributedSystemMXBean)
  - `Member` = `GemFire:type=Member,member=<member name>` (MemberMXBean)
  - `Region` = `GemFire:service=Region,name=/<region>,type=Distributed` (DistributedRegionMXBean)
  - The manager is the member whose `Member.Manager` is `true`. Its `processCommand` operation runs gfsh commands and returns the result as JSON. It's how gfsh itself talks to the cluster.
  - Use these GemFire MBeans, not `java.lang:*`. Through the locator, the `java.lang:*` MBeans describe only the locator's own JVM.
  - A locator starts its JMX manager only when a gfsh client connects through it, or when `jmx-manager-start=true` is set. Until then the JMX port refuses connections.
- **Provided**: values given to the job when it's set up.

## Sample collector

`NightlyStatsLocatorStart` starts a locator and, from a background thread inside it, writes the report above to a JSON file every day. It reads the MBeans from the locator's own JVM, so it needs no JMX connection, credentials or keystores.

| File | What it does |
| --- | --- |
| `src/main/java/com/example/gemfire/stats/NightlyStatsLocatorStart.java` | Starts the locator with its JMX manager running, schedules the daily run and writes the file |
| `src/main/java/com/example/gemfire/stats/NightlyStatsCollector.java` | Reads the MBeans and builds the report, following the Source column above |
| `src/main/java/com/example/gemfire/stats/Json.java` | Minimal JSON reader and writer. GemFire's bundled Jackson differs between 10.1 and 10.3, so it isn't used |
| `scripts/start-locator.sh` | Runs the locator with GemFire's classpath and JVM flags |
| `locator.properties.example` | Every setting, with comments |

The `Makefile` wraps the build, running it and a local test cluster. `make` lists every target.

```sh
export GEMFIRE_HOME=~/dev/vmware-gemfire-10.3.2   # any GemFire install
make build                                        # build the jar
cp locator.properties.example locator.properties   # then edit it
make run                                          # start a locator with the collector (foreground)
```

To try it out without your own settings, `make test` runs an end-to-end test on this machine and takes about 75 seconds:
1. It starts a locator with the collector, 2 servers and 3 regions. SSL is on, using a self-signed certificate the Makefile creates.
2. It waits for the collector's startup run.
3. It checks the report with `scripts/verify-report.sh`: the same fields as `sample-output.json`, the right members, regions and SSL/TLS values.
4. It stops the cluster.

Other things you can do:
- `make test SSL=false` runs the same test without SSL.
- `make start`, `status`, `report`, `logs` and `stop` drive the test cluster by hand. It uses ports 20334, 21099 and 40504+ (overridable), so it doesn't clash with gemfire-runner. Its files go in `.test-cluster/`.

- Every day at `NIGHTLY_STATS_TIME` (default `02:00`, local time) it writes `<NIGHTLY_STATS_DIR>/<CLUSTER_NAME>-<yyyy-MM-dd>.json`, in the same layout as [`sample-output.json`](sample-output.json). `NIGHTLY_STATS_DIR` defaults to `<LOG_DIRECTORY>/nightly-stats`.
- `NIGHTLY_STATS_RUN_ON_STARTUP=true` also runs it once, 60 seconds after startup, which is handy for testing.
- `ENV` and `CLUSTER_NAME` in the properties file are the provided values.
- Any key starting with `gemfire.` is passed to the locator as a GemFire property, e.g. the SSL settings.
- Its messages go to the locator's log and contain `Nightly stats`. A failed run is logged, and the next day's run tries again. It never stops the locator.
- Servers need nothing extra.
- Not tested: a cluster with a security manager. Inside the JVM, `describe config` could then be refused. If that happens, `tls` is `null` and a warning is logged.

## Verification

Three test runs on 2026-09-23. Each one read every value through a single JMX connection to the locator:

1. **gemfire-runner in Docker, SSL off.** Tanzu GemFire 10.3.0, 1 locator + 2 servers. Every source in the table was checked here.
2. **The same cluster with SSL on.** It was restarted with gemfire-runner's TLS option (`tls_version` = `TLSv1.2,TLSv1.3`), which sets `ssl-enabled-components=all` and `ssl-protocols=TLSv1.2,TLSv1.3` on every member.
   - Every member's `Member` values still came through the locator. IPs, heap, off-heap and uptime differed per member, so they're each member's own values.
   - Encryption: `securableCommunicationChannel` was `["ALL"]` on every member. The legacy `clusterSSLEnabled` / `serverSSLEnabled` / … flags stayed `false`; they only reflect the old `cluster-ssl-*` style properties.
   - TLS: `describe config` returned `ssl-protocols` = `TLSv1.2,TLSv1.3`, from the `-D` flag in `jvm-args`. `SSLProtocols` was still `null`.
   - A remote JMX client needs GemFire's jars on its classpath, because the manager hands it `org.apache.geode.management.internal.ContextAwareSSLRMIClientSocketFactory`. It also needs a client certificate, because GemFire requires mutual TLS by default. Neither applies to code running inside a member.
3. **Native processes on a Mac, no Docker.** A locator and a server were started with gfsh from a local GemFire 10.3.2 install.
   - Memory, CPU count, IP, install path, version and heap all matched the Mac's own values (`hw.memsize`, `hw.ncpu`, the `en0` address, the install folder).
   - `Member.Host` and the start of `Member.Id` were the IP (`192.168.68.60`), where Docker gave the hostname. Counting nodes by distinct `Host` still works, as long as every member in a cluster reports the same form.
   - On macOS, `showOSMetrics()` → `version` is the macOS release (`15.6`), not the kernel (`Darwin 24.6.0`). On Linux it's the kernel string, which is what the RHEL rule reads.
   - With SSL on, the JMX client checks the locator's certificate against the address the locator advertises for JMX (its IP here). gemfire-runner's certificates don't include the Mac's IP, so the locator was set to advertise `localhost` instead (`jmx-manager-hostname-for-clients=localhost`).

The sample collector was tested the same way as run 3: natively, with SSL on, with `NightlyStatsLocatorStart` running the locator and 2 servers started with gfsh.
- The startup run and a scheduled run both wrote the file, with the same fields as `sample-output.json`. The scheduled run then set the next run for the same time the following day.
- TLS was read correctly from the locator's `api-properties`, where the settings passed through its Builder land. Across the runs, TLS has now been read from all three places it can be set: API, properties file and `-D` flag.

TLS protocol settings were also checked on a throwaway locator, with them set both as `-D` flags and in `gemfire.properties` / `gfsecurity.properties`.

The `Sample:` values in the table and [`sample-output.json`](sample-output.json) come from the run 3 setup restarted with SSL on: a native locator and 2 servers, with `ssl-enabled-components=all` and `ssl-protocols=TLSv1.2,TLSv1.3` in `gfsecurity.properties` files, and five regions of different types. `local-mac` and `native-test` stand in for the Env and cluster_name values given at setup. `server_core`, `partition_core` and `pvu_per_core` stay `null` until their meaning is settled.

Not covered:
- IPv6 addresses.
- The OS distribution from a real RHEL kernel. Docker runs its own kernel (`6.10.14-linuxkit`) and macOS reports its release, so the distribution was `null` in every run. The kernel rule was tested on RHEL 7, 8, 9 and 10 version strings instead.
- Members started by the custom startup classes (`ExampleGemFireLocatorStart` / `ExampleGemFireServerStart`). That covers their classpath, and whether their locators appear in `DS.listServers()` because they also run a CacheServer. Geode's source says they will.

## Notes

- **Server core / Partition core / PVU per core: ask the template owner.** These look like IBM sub-capacity licensing terms, as used in ILMT reports:
  - *Server core*: physical cores on the physical server underneath the VM.
  - *Partition core*: cores assigned to the VM (what GemFire reports as `6 cpu(s)`).
  - *PVU per core*: IBM's Processor Value Unit rating for the CPU model (e.g. 70).

  If that reading is right, only Partition core comes from GemFire. Server core needs hypervisor (vCenter) inventory, and PVU needs IBM's PVU table.
- **cluster_name**: GemFire has no cluster-level name. The member name in gfsh `list members` is the same value as JMX `Member.Name`, and it belongs to one process (locator1, server1, …). It only identifies the cluster if your naming convention includes the cluster name.
