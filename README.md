| Category | Attribute | Value / Status | Source |
| --- | --- | --- | --- |
| **Environment attributes** | Env | This is going to need to be a provided property | **Provided**: set when the job is set up |
|  | OS | Example: RHEL 8 (`Linux 4.18.0-553.89.1.el8_10.x86_64 amd64`) | **JMX** `Member.showOSMetrics()` → `name` + `version` + `arch` for the kernel string<br>The distribution comes from the kernel version: RHEL kernels carry the release, e.g. `…el8_10…` = RHEL 8.10 (`el7` kernels only carry the major version). Other kernels give no distribution<br>Don't use the `Build-Platform` line in `Member.Version`: that's the machine GemFire was built on |
|  | Type | GemFire Locator or Server | **JMX** a member in `DS.listLocatorMembers(false)` is a locator; one in `DS.listServers()` is a server |
|  | Address Family | IPv4 or IPv6 for the host machine | **JMX** `Member.Version` → the `Running on: <host>/<ip>, …` line: dotted = IPv4, contains `:` = IPv6<br>Don't use `Member.Id`: it starts with the hostname, not the IP |
|  | Memory | The host machines memory | **JMX** `Member.showOSMetrics()` → `totalPhysicalMemorySize` (bytes, although the javadoc says MB) |
|  | memory quotas | The heap allocated to gemfire | **JMX** `Member.MaxMemory` (MB, the `-Xmx` value). Off-heap, if used: `Member.OffHeapMaxMemory` (bytes) |
|  | Server core | 6 cores (`6 cpu(s)`). ⚠️ Meaning TBD, see [Notes](#notes) | **JMX** `Member.showOSMetrics()` → `availableProcessors` is the CPU count the VM sees (the `6 cpu(s)`)<br>If it means the physical server's cores, a VM can't see those: **Provided**, e.g. from vCenter |
|  | Partition core | ⚠️ TBD, see [Notes](#notes) | If it's the IBM meaning: **JMX** `availableProcessors` above |
|  | PVU per core | ⚠️ TBD, see [Notes](#notes) | **Provided**: IBM's PVU table value for the CPU model. JMX doesn't report the CPU model |
| **Cluster attributes** | cluster_name | Name of the Cluster | **Provided**: set when the job is set up (GemFire has no cluster name, see [Notes](#notes))<br>**JMX** `DS.DistributedSystemId` for the numeric cluster ID |
|  | uptime | Total Uptime of the Gemfire Locator or Server | **JMX** `Member.MemberUpTime` (seconds) |
|  | servers | List of GemFire Servers | **JMX** `DS.listServers()` |
|  | servers_count | Total Count of GemFire Servers | **JMX** length of `DS.listServers()` |
|  | "nodes" | Number of distinct hosts running cluster members | **JMX** distinct `Member.Host` values across `DS.listMembers()` |
|  | software_version | Example: `10.1.3` (`Tanzu GemFire 10.1.3`) | **JMX** `Member.ReleaseVersion` (`10.1.3`). `Member.Version` has the full product string |
|  | Installation path | Example: `/opt/middleware/pivotal/gemfire_1013` | **JMX** `Member.ClassPath`: on 10.x it's just `<install>/lib/gemfire-bootstrap.jar`, so drop the `/lib/…` part |
|  | Regions | Total Number of Regions | **JMX** `DS.TotalRegionCount`. Paths come from `DS.listAllRegionPaths()` |
|  | Region type | For each Region what type is it? | **JMX** `Region.RegionType`, the data policy (`PARTITION`, `PERSISTENT_PARTITION`, `REPLICATE`, `PERSISTENT_REPLICATE`, `NORMAL`, …) |
|  | Replication | Is each region REPLICATE or PARTITION? | **JMX** worked out from `Region.RegionType` above: contains `REPLICATE` = replicate, contains `PARTITION` = partition, anything else (`NORMAL`, `EMPTY`, …) = neither |
|  | Encryption | Is SSL Enabled? | **JMX** `Member.listGemFireProperties()` → `securableCommunicationChannel` (the `ssl-enabled-components` setting; empty list = no SSL), plus the legacy `clusterSSLEnabled` / `serverSSLEnabled` / `gatewaySSLEnabled` / `jmxManagerSSLEnabled` / `httpServiceSSLEnabled` |
|  | TLS | What TLS Protocols are Enabled | **JMX** `processCommand("describe config --member=<name> --hide-defaults=false")` on the manager's `Member` MBean returns JSON. Read `ssl-protocols`, `ssl-client-protocols` and `ssl-server-protocols` from the `api-properties` or `file-properties` section, or from a `-Dgemfire.ssl-…` entry in `jvm-args`. Not set = `any` (JDK default, usually TLSv1.2 + TLSv1.3)<br>Don't use `Member.listGemFireProperties()` → `SSLProtocols`: GemFire never fills it in, so it's always `null` |
| **Miscellaneous** | Date/timestamp | What is the current date timestamp of when the metrics were collected | **JMX** `Member.CurrentTime` on the manager (ms since epoch), written as UTC ISO-8601 |

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

[`sample-output.json`](sample-output.json) was built from one JMX connection to that cluster. `env` and `cluster_name` stand in for values given at setup. `server_core`, `partition_core` and `pvu_per_core` stay `null` until their meaning is settled.

Not covered:
- SSL switched on. `ssl-enabled-components` has only been seen empty.
- IPv6 addresses.
- The OS distribution from a real RHEL kernel. Docker runs its own kernel (`6.10.14-linuxkit`), so `os.distribution` is `null` in the sample. The kernel rule was tested on RHEL 7, 8, 9 and 10 version strings instead.

## Notes

- **Server core / Partition core / PVU per core: ask the template owner.** These look like IBM sub-capacity licensing terms, as used in ILMT reports:
  - *Server core*: physical cores on the physical server underneath the VM.
  - *Partition core*: cores assigned to the VM (what GemFire reports as `6 cpu(s)`).
  - *PVU per core*: IBM's Processor Value Unit rating for the CPU model (e.g. 70).

  If that reading is right, only Partition core comes from GemFire. Server core needs hypervisor (vCenter) inventory, and PVU needs IBM's PVU table.
- **cluster_name**: GemFire has no cluster-level name. The member name in gfsh `list members` is the same value as JMX `Member.Name`, and it belongs to one process (locator1, server1, …). It only identifies the cluster if your naming convention includes the cluster name. The only cluster-wide identifier is the numeric `distributed-system-id` (`DS.DistributedSystemId`), and it's `-1` if nobody set it.
