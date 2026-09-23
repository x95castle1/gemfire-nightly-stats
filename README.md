| Category | Attribute | Value / Status |
| --- | --- | --- |
| **Environment attributes** | Env | This is going to need to be a provided property  |
|  | OS | Example: RHEL 8 (`Linux 4.18.0-553.89.1.el8_10.x86_64 amd64`) |
|  | Type | GemFire Locator or Server |
|  | Address Family | IP Address for Host Machine |
|  | Memory | The host machines memory  |
|  | memory quotas | The heap allocated to gemfire  |
|  | Server core | 6 cores (`6 cpu(s)`) |
|  | Partition core | No clue what this means. |
|  | PVU per core | No clue what this means |
| **Cluster attributes** | cluster_name | Name of the Cluster |
|  | uptime | Total Uptime of the Gemfire Locator or Server |
|  | servers | List of GemFire Servers |
|  | servers_count | Total Count of GemFire Servers|
|  | "nodes" | This might be Kubernetes Only. Get More info. |
|  | software_version | Example: `10.1.3` (`Tanzu GemFire 10.1.3`) |
|  | Installation path | Example: `/opt/middleware/pivotal/gemfire_1013` |
|  | Regions | Total Number of Regions |
|  | Region type | For each Region what type is it? |
|  | Replication | Not sure what this means. need clarification |
|  | Encryption | Is SSL Enabled? |
|  | TLS | What TLS Protocols are Enabled |
| **Miscellaneous** | Date/timestamp | What is the current date timestamp of when the metrics were collected |
