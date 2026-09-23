| Category | Attribute | Value / Status | Source |
| --- | --- | --- | --- |
| **Environment attributes** | Env | This is going to need to be a provided property<br>Sample: `local-docker` (placeholder) | **Provided**: set when the job is set up |
|  | OS | Example: RHEL 8 (`Linux 4.18.0-553.89.1.el8_10.x86_64 amd64`)<br>Sample: `Linux 6.10.14-linuxkit aarch64`, distribution `null` (Docker's kernel isn't a RHEL kernel) | **JMX** `Member.showOSMetrics()` → `name` + `version` + `arch` for the kernel string<br>The distribution comes from the kernel version: RHEL kernels carry the release, e.g. `…el8_10…` = RHEL 8.10 (`el7` kernels only carry the major version). Other kernels give no distribution<br>Don't use the `Build-Platform` line in `Member.Version`: that's the machine GemFire was built on |
|  | Type | GemFire Locator or Server<br>Sample: locator-0 = `locator`, server-0 / server-1 = `server` | **JMX** a member in `DS.listLocatorMembers(false)` is a locator; one in `DS.listServers()` is a server |
|  | Address Family | IPv4 or IPv6 for the host machine<br>Sample: `IPv4` on all 3 members | **JMX** `Member.Version` → the `Running on: <host>/<ip>, …` line: dotted = IPv4, contains `:` = IPv6<br>Don't use `Member.Id`: it starts with the hostname, not the IP |
|  | Memory | The host machines memory<br>Sample: `23316455424` bytes (~21.7 GiB) on all 3 members | **JMX** `Member.showOSMetrics()` → `totalPhysicalMemorySize` (bytes, although the javadoc says MB) |
|  | memory quotas | The heap allocated to gemfire<br>Sample: locator-0 `1024` MB heap, no off-heap; server-0 / server-1 `4096` MB heap + `1073741824` bytes (1 GiB) off-heap | **JMX** `Member.MaxMemory` (MB, the `-Xmx` value). Off-heap, if used: `Member.OffHeapMaxMemory` (bytes) |
|  | Server core | 6 cores (`6 cpu(s)`). ⚠️ Meaning TBD, see [Notes](#notes)<br>Sample: `availableProcessors` = `12` on all 3 members | **JMX** `Member.showOSMetrics()` → `availableProcessors` is the CPU count the VM sees (the `6 cpu(s)`)<br>If it means the physical server's cores, a VM can't see those: **Provided**, e.g. from vCenter |
|  | Partition core | ⚠️ TBD, see [Notes](#notes)<br>Sample: `null` (not collected until the meaning is settled) | If it's the IBM meaning: **JMX** `availableProcessors` above |
|  | PVU per core | ⚠️ TBD, see [Notes](#notes)<br>Sample: `null` (not collected until the meaning is settled) | **Provided**: IBM's PVU table value for the CPU model. JMX doesn't report the CPU model |
| **Cluster attributes** | cluster_name | Name of the Cluster<br>Sample: `gemfire-runner` (placeholder), distributed-system-id `42` | **Provided**: set when the job is set up (GemFire has no cluster name, see [Notes](#notes))<br>**JMX** `DS.DistributedSystemId` for the numeric cluster ID |
|  | uptime | Total Uptime of the Gemfire Locator or Server<br>Sample: locator-0 `3032` s, server-0 `3023` s, server-1 `3023` s | **JMX** `Member.MemberUpTime` (seconds) |
|  | servers | List of GemFire Servers<br>Sample: `server-0`, `server-1` | **JMX** `DS.listServers()` |
|  | servers_count | Total Count of GemFire Servers<br>Sample: `2` | **JMX** length of `DS.listServers()` |
|  | "nodes" | Number of distinct hosts running cluster members<br>Sample: `3` | **JMX** distinct `Member.Host` values across `DS.listMembers()` |
|  | software_version | Example: `10.1.3` (`Tanzu GemFire 10.1.3`)<br>Sample: `10.3.0` | **JMX** `Member.ReleaseVersion` (`10.1.3`). `Member.Version` has the full product string |
|  | Installation path | Example: `/opt/middleware/pivotal/gemfire_1013`<br>Sample: `/gemfire` | **JMX** `Member.ClassPath`: on 10.x it's just `<install>/lib/gemfire-bootstrap.jar`, so drop the `/lib/…` part |
|  | Regions | Total Number of Regions<br>Sample: `16` | **JMX** `DS.TotalRegionCount`. Paths come from `DS.listAllRegionPaths()` |
|  | Region type | For each Region what type is it?<br>Sample: `PARTITION` ×3, `PERSISTENT_PARTITION` ×4, `REPLICATE` ×2, `PERSISTENT_REPLICATE` ×3, `NORMAL` ×4 (e.g. `/Account` = `PARTITION`) | **JMX** `Region.RegionType`, the data policy (`PARTITION`, `PERSISTENT_PARTITION`, `REPLICATE`, `PERSISTENT_REPLICATE`, `NORMAL`, …) |
|  | Replication | Is each region REPLICATE or PARTITION?<br>Sample: partition ×7, replicate ×5, neither ×4 | **JMX** worked out from `Region.RegionType` above: contains `REPLICATE` = replicate, contains `PARTITION` = partition, anything else (`NORMAL`, `EMPTY`, …) = neither |
|  | Encryption | Is SSL Enabled?<br>Sample: `false` (`ssl-enabled-components` empty) | **JMX** `Member.listGemFireProperties()` → `securableCommunicationChannel` (the `ssl-enabled-components` setting; empty list = no SSL), plus the legacy `clusterSSLEnabled` / `serverSSLEnabled` / `gatewaySSLEnabled` / `jmxManagerSSLEnabled` / `httpServiceSSLEnabled` |
|  | TLS | What TLS Protocols are Enabled<br>Sample: `ssl-protocols` = `any`; `ssl-client-protocols` / `ssl-server-protocols` not set | **JMX** `processCommand("describe config --member=<name> --hide-defaults=false")` on the manager's `Member` MBean returns JSON. Read `ssl-protocols`, `ssl-client-protocols` and `ssl-server-protocols` from the `api-properties` or `file-properties` section, or from a `-Dgemfire.ssl-…` entry in `jvm-args`. Not set = `any` (JDK default, usually TLSv1.2 + TLSv1.3)<br>Don't use `Member.listGemFireProperties()` → `SSLProtocols`: GemFire never fills it in, so it's always `null` |
| **Miscellaneous** | Date/timestamp | What is the current date timestamp of when the metrics were collected<br>Sample: `2026-09-23T15:21:39Z` | **JMX** `Member.CurrentTime` on the manager (ms since epoch), written as UTC ISO-8601 |

## Source legend

- **JMX**: connect to the locator's JMX manager (port 1099 by default). The manager gathers every member's GemFire MBeans, so one connection covers the whole cluster.
  - `DS` = `GemFire:service=System,type=Distributed` (DistributedSystemMXBean)
  - `Member` = `GemFire:type=Member,member=<member name>` (MemberMXBean)
  - `Region` = `GemFire:service=Region,name=/<region>,type=Distributed` (DistributedRegionMXBean)
  - The manager is the member whose `Member.Manager` is `true`. Its `processCommand` operation runs gfsh commands and returns the result as JSON. It's how gfsh itself talks to the cluster.
  - Use these GemFire MBeans, not `java.lang:*`. Through the locator, the `java.lang:*` MBeans describe only the locator's own JVM.
  - A locator starts its JMX manager only when a gfsh client connects through it, or when `jmx-manager-start=true` is set. Until then the JMX port refuses connections.
- **Provided**: values given to the job when it's set up.

## Verification

Checked on 2026-09-23 against the local gemfire-runner cluster: Tanzu GemFire 10.3.0, 1 locator + 2 servers in Docker, SSL off. TLS protocol settings were also checked on a throwaway locator, with them set both as `-D` flags and in `gemfire.properties` / `gfsecurity.properties`.

The `Sample:` values in the table and [`sample-output.json`](sample-output.json) come from the same JMX connection to that cluster. `local-docker` and `gemfire-runner` stand in for the Env and cluster_name values given at setup. `server_core`, `partition_core` and `pvu_per_core` stay `null` until their meaning is settled.

Not covered:
- SSL switched on. `ssl-enabled-components` has only been seen empty.
- IPv6 addresses.
- The OS distribution from a real RHEL kernel. Docker runs its own kernel (`6.10.14-linuxkit`), so the sample distribution is `null`. The kernel rule was tested on RHEL 7, 8, 9 and 10 version strings instead.

## Notes

- **Server core / Partition core / PVU per core: ask the template owner.** These look like IBM sub-capacity licensing terms, as used in ILMT reports:
  - *Server core*: physical cores on the physical server underneath the VM.
  - *Partition core*: cores assigned to the VM (what GemFire reports as `6 cpu(s)`).
  - *PVU per core*: IBM's Processor Value Unit rating for the CPU model (e.g. 70).

  If that reading is right, only Partition core comes from GemFire. Server core needs hypervisor (vCenter) inventory, and PVU needs IBM's PVU table.
- **cluster_name**: GemFire has no cluster-level name. The member name in gfsh `list members` is the same value as JMX `Member.Name`, and it belongs to one process (locator1, server1, …). It only identifies the cluster if your naming convention includes the cluster name. The only cluster-wide identifier is the numeric `distributed-system-id` (`DS.DistributedSystemId`), and it's `-1` if nobody set it.
