| Category | Attribute | Value / Status | Source |
| --- | --- | --- | --- |
| **Environment attributes** | Env | This is going to need to be a provided property | **Provided**: job config (not available from GemFire) |
|  | OS | Example: RHEL 8 (`Linux 4.18.0-553.89.1.el8_10.x86_64 amd64`) | **JMX** `Member.showOSMetrics()` → `name` + `version` + `arch` (the kernel string)<br>**Host** `/etc/os-release` `PRETTY_NAME` for the friendly name (e.g. Red Hat Enterprise Linux 8.10)<br>**Prometheus** the `instance` label on `OperatingSystemStats` metrics has both, e.g. `GNU/Linux Red Hat Enterprise Linux 10.2 (Coughlan) build 6.10.14-linuxkit` |
|  | Type | GemFire Locator or Server | **JMX** check whether the member is in `DS.listLocatorMembers(false)` or `DS.listServers()`. The `Member.Locator` / `Member.Server` booleans also work<br>**gfsh** `list members`, `Type` column<br>**Prometheus** `member_type` label |
|  | Address Family | IPv4 or IPv6 for the host machine | **JMX** `Member.Version` → the `Running on: <host>/<ip>, …` line: dotted = IPv4, contains `:` = IPv6. Don't use `Member.Id`: it starts with the hostname, not the IP<br>**Log** startup banner, same `Running on:` line<br>**Host** `ip -o addr` |
|  | Memory | The host machines memory | **JMX** `Member.showOSMetrics()` → `totalPhysicalMemorySize` (bytes, although the javadoc says MB)<br>**Prometheus** `gemfire_memoryTotal{category="OperatingSystemStats"}` (bytes)<br>**Host** `/proc/meminfo` `MemTotal` (kB) |
|  | memory quotas | The heap allocated to gemfire | **JMX** `Member.MaxMemory` (MB, the `-Xmx` value). Off-heap, if used: `Member.OffHeapMaxMemory` (bytes)<br>**Prometheus** `gemfire_maxMemory{category="VMStats"}` for heap, `gemfire_maxMemory{category="OffHeapMemoryStats"}` for off-heap (bytes)<br>**Log** `-Xmx` under the banner's `Command Line Parameters` |
|  | Server core | 6 cores (`6 cpu(s)`). ⚠️ Meaning TBD, see [Notes](#notes) | **JMX** `Member.showOSMetrics()` → `availableProcessors` is the CPU count the VM sees (the `6 cpu(s)`)<br>A VM can't see the physical server's cores. Those need **hypervisor inventory** or a **Provided** value |
|  | Partition core | ⚠️ TBD, see [Notes](#notes) | If it's the IBM meaning: same as `availableProcessors` above<br>**Prometheus** `gemfire_physicalProcessorCount` / `gemfire_logicalProcessorCount` (`OperatingSystemStats`) separate cores from threads<br>**Host** `lscpu` |
|  | PVU per core | ⚠️ TBD, see [Notes](#notes) | **Provided**: look up the CPU model (**Host** `lscpu` `Model name`) in IBM's PVU table. Not available from GemFire |
| **Cluster attributes** | cluster_name | Name of the Cluster | **Provided**: job config (GemFire has no cluster name, see [Notes](#notes))<br>**JMX** `DS.DistributedSystemId` for the numeric cluster ID. **Prometheus** has the same ID as the `cluster` label |
|  | uptime | Total Uptime of the Gemfire Locator or Server | **JMX** `Member.MemberUpTime` (seconds)<br>**Prometheus** `gemfire_process_uptime_seconds`<br>**gfsh** `status server` / `status locator` |
|  | servers | List of GemFire Servers | **JMX** `DS.listServers()`<br>**gfsh** `list members`<br>**Prometheus** distinct `member` labels where `member_type="server"` |
|  | servers_count | Total Count of GemFire Servers | **JMX** length of `DS.listServers()` |
|  | "nodes" | Number of distinct hosts running cluster members | **JMX** distinct `Member.Host` values across `DS.listMembers()`<br>**Prometheus** distinct `host` labels |
|  | software_version | Example: `10.1.3` (`Tanzu GemFire 10.1.3`) | **JMX** `Member.ReleaseVersion` (`10.1.3`). `Member.Version` has the full product string<br>**gfsh** `status server` / `status locator` → `GemFire Version`. Not `version --full`: that shows the version of the gfsh you're running, not the cluster's<br>**Log** startup banner `GemFire-Version:` |
|  | Installation path | Example: `/opt/middleware/pivotal/gemfire_1013` | **JMX** `Member.ClassPath`: on 10.x it's just `<install>/lib/gemfire-bootstrap.jar`, so drop the `/lib/…` part<br>**Log** the `GemFire Home: <path>` line, or the banner's `Class Path:` section<br>**Host** java process arguments (`ps -o args`, or `/proc/<pid>/cmdline` where `ps` isn't installed) |
|  | Regions | Total Number of Regions | **JMX** `DS.TotalRegionCount`. Paths come from `DS.listAllRegionPaths()`<br>**gfsh** `list regions`<br>**Prometheus** distinct `region` labels on `gemfire_cache_entries`, after filtering out internal queue regions (`AsyncEventQueue_…`) |
|  | Region type | For each Region what type is it? | **JMX** `Region.RegionType`, the data policy (`PARTITION`, `PERSISTENT_PARTITION`, `REPLICATE`, `PERSISTENT_REPLICATE`, `NORMAL`, …)<br>**Prometheus** `data_policy` label on `gemfire_cache_entries`<br>**gfsh** `describe region --name=/<region>` → `Data Policy` |
|  | Replication | Is each region REPLICATE or PARTITION? | Worked out from `Region.RegionType` above: contains `REPLICATE` = replicate, contains `PARTITION` = partition, anything else (`NORMAL`, `EMPTY`, …) = neither |
|  | Encryption | Is SSL Enabled? | **JMX** `Member.listGemFireProperties()` → `securableCommunicationChannel` (the `ssl-enabled-components` setting; empty list = no SSL), plus the legacy `clusterSSLEnabled` / `serverSSLEnabled`<br>**gfsh** `describe config --member=<name> --hide-defaults=false`. Without that flag, settings left at their default are hidden |
|  | TLS | What TLS Protocols are Enabled | **gfsh** `describe config --member=<name> --hide-defaults=false` → `ssl-protocols` (`any` = JDK default, usually TLSv1.2 + TLSv1.3), `ssl-client-protocols`, `ssl-server-protocols`<br>**JMX** `Member.listGemFireProperties()` → `SSLProtocols`, but it returned `null` instead of `any` when unset, and there are no client/server variants<br>⚠️ Only checked with TLS off |
| **Miscellaneous** | Date/timestamp | What is the current date timestamp of when the metrics were collected | **Job**: the collector's clock at run time (UTC, ISO-8601) |

## Source legend

- **JMX**: connect to the locator's JMX manager (e.g. port 1099). The manager gathers every member's GemFire MBeans, so one connection covers the whole cluster.
  - `DS` = `GemFire:service=System,type=Distributed` (DistributedSystemMXBean)
  - `Member` = `GemFire:type=Member,member=<member name>` (MemberMXBean)
  - `Region` = `GemFire:service=Region,name=/<region>,type=Distributed` (DistributedRegionMXBean)
  - Use these GemFire MBeans, not `java.lang:*`. Through the locator, the `java.lang:*` MBeans describe only the locator's own JVM.
- **Prometheus**: on GemFire 10.3 each member serves it from its HTTP service at `http://<member>:<http-service-port>/metrics/` (7070 by default). The `gemfire.prometheus.metrics.port` and `.host` settings are ignored. The member log's `Metrics Endpoint Configuration` block shows the real URL.
  - It only carries numbers. Text shows up only in labels.
  - Metrics that come from GemFire statistics (the ones with a `category` label) put the full member ID in `member`.
  - Micrometer metrics such as `gemfire_process_uptime_seconds` carry `member`, `host`, `member_type` and `cluster` labels.
- **gfsh**: good for spot checks. It reads the same data but prints text.
- **Log**: the startup banner at the top of each member's log file.
- **Host**: commands run on the machine over SSH.
- **Provided**: values the job reads from its own config.

## Verification

Checked on 2026-09-23 against the local gemfire-runner cluster: Tanzu GemFire 10.3.0, 1 locator + 2 servers in Docker, TLS off, Prometheus `emission=All`. Every source in the table returned the expected value unless its row says ⚠️.

[`sample-output.json`](sample-output.json) shows what the job's output could look like, built from those results. In it, `env` and `cluster_name` are placeholder values, and `server_core`, `partition_core` and `pvu_per_core` stay `null` until their meaning is settled.

Not covered by that run:
- TLS settings, since TLS was off.
- Prometheus with the default `emission` setting. The cluster used `All`, so some of the metrics above may not be exposed by default.
- IPv6 addresses.
- `lscpu` `Model name`, which prints `-` on Docker Desktop for Apple Silicon. On x86 RHEL it gives the CPU model.
- Real VMs. In Docker the "host" is a container, so the OS name comes from the image (RHEL 10.2) and the kernel from Docker's VM (linuxkit). On a real VM both describe the same machine.

## Notes

- **Server core / Partition core / PVU per core: ask the template owner.** These look like IBM sub-capacity licensing terms, as used in ILMT reports:
  - *Server core*: physical cores on the physical server underneath the VM.
  - *Partition core*: cores assigned to the VM (what GemFire reports as `6 cpu(s)`).
  - *PVU per core*: IBM's Processor Value Unit rating for the CPU model (e.g. 70).

  If that reading is right, only Partition core comes from GemFire. Server core needs hypervisor (vCenter) inventory, and PVU needs IBM's PVU table.
- **cluster_name**: GemFire has no cluster-level name. The member name in gfsh `list members` is the same value as JMX `Member.Name`, and it belongs to one process (locator1, server1, …). It only identifies the cluster if your naming convention includes the cluster name. The only cluster-wide identifier is the numeric `distributed-system-id` (`DS.DistributedSystemId`). It's also the `cluster` label on Prometheus metrics, and it's `-1` if nobody set it.
