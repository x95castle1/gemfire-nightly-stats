package com.example.gemfire.stats;

import java.lang.reflect.Array;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.management.MBeanServerConnection;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;
import javax.management.openmbean.CompositeData;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Builds the nightly report described in README.md (same shape as sample-output.json) from the
 * GemFire MBeans held by a JMX manager. Every value comes from JMX except env and cluster name,
 * which are provided at setup.
 */
public class NightlyStatsCollector {

    private static final Logger logger = LogManager.getLogger();

    private static final ObjectName DISTRIBUTED_SYSTEM = objectName("GemFire:service=System,type=Distributed");
    private static final ObjectName ALL_MEMBERS = objectName("GemFire:type=Member,member=*");
    private static final String[] STRING_SIGNATURE = {String.class.getName()};

    private static final Pattern RUNNING_ON_IP = Pattern.compile("Running on: [^/]+/([^,]+),");
    private static final Pattern RHEL_KERNEL = Pattern.compile("\\.el(\\d+)(?:_(\\d+))?");
    private static final Pattern GEMFIRE_JAR =
            Pattern.compile("(.*)/lib/(?:gemfire-dependencies|geode-dependencies|gemfire-bootstrap)\\.jar");
    private static final String[] LEGACY_SSL_FLAGS = {"clusterSSLEnabled", "serverSSLEnabled",
            "gatewaySSLEnabled", "jmxManagerSSLEnabled", "httpServiceSSLEnabled"};

    private static final Duration FEDERATION_TIMEOUT = Duration.ofSeconds(60);

    private final MBeanServerConnection mbeans;
    private final String env;
    private final String clusterName;

    public NightlyStatsCollector(MBeanServerConnection mbeans, String env, String clusterName) {
        this.mbeans = mbeans;
        this.env = env;
        this.clusterName = clusterName;
    }

    public Map<String, Object> collect() throws Exception {
        awaitFederation();

        List<String> locators = strings(mbeans.invoke(DISTRIBUTED_SYSTEM, "listLocatorMembers",
                new Object[] {false}, new String[] {"boolean"}));
        // A locator that also runs a CacheServer shows up in listServers() too
        List<String> servers = strings(invoke(DISTRIBUTED_SYSTEM, "listServers"));
        servers.removeAll(locators);
        ObjectName manager = (ObjectName) attribute(DISTRIBUTED_SYSTEM, "MemberObjectName");

        List<String> names = strings(invoke(DISTRIBUTED_SYSTEM, "listMembers"));
        names.sort(Comparator.comparing((String name) -> !locators.contains(name)).thenComparing(name -> name));
        List<Map<String, Object>> members = new ArrayList<>();
        Set<Object> hosts = new HashSet<>();
        for (String name : names) {
            Map<String, Object> member = member(name, manager, locators, servers);
            hosts.add(member.get("host"));
            members.add(member);
        }

        List<Map<String, Object>> regions = new ArrayList<>();
        List<String> paths = strings(invoke(DISTRIBUTED_SYSTEM, "listAllRegionPaths"));
        paths.sort(null);
        for (String path : paths) {
            ObjectName region = (ObjectName) mbeans.invoke(DISTRIBUTED_SYSTEM, "fetchDistributedRegionObjectName",
                    new Object[] {path}, STRING_SIGNATURE);
            regions.add(map("path", path, "region_type", attribute(region, "RegionType")));
        }

        Map<String, Object> cluster = map(
                "env", env,
                "cluster_name", clusterName,
                "locators", locators,
                "locators_count", locators.size(),
                "servers", servers,
                "servers_count", servers.size(),
                "nodes", hosts.size(),
                "regions_count", attribute(DISTRIBUTED_SYSTEM, "TotalRegionCount"),
                "regions", regions);

        long now = (Long) attribute(manager, "CurrentTime");
        return map(
                "collected_at", Instant.ofEpochMilli(now).truncatedTo(ChronoUnit.SECONDS).toString(),
                "cluster", cluster,
                "members", members);
    }

    private Map<String, Object> member(String name, ObjectName manager, List<String> locators,
            List<String> servers) throws Exception {
        ObjectName member = (ObjectName) mbeans.invoke(DISTRIBUTED_SYSTEM, "fetchMemberObjectName",
                new Object[] {name}, STRING_SIGNATURE);
        CompositeData os = (CompositeData) invoke(member, "showOSMetrics");
        CompositeData properties = (CompositeData) invoke(member, "listGemFireProperties");
        String osVersion = (String) os.get("version");

        return map(
                "name", name,
                "type", locators.contains(name) ? "locator" : servers.contains(name) ? "server" : "other",
                "host", attribute(member, "Host"),
                "os", map("kernel", os.get("name") + " " + osVersion + " " + os.get("arch"),
                        "distribution", rhelRelease(osVersion)),
                "address_family", addressFamily((String) attribute(member, "Version")),
                "memory_bytes", os.get("totalPhysicalMemorySize"),
                "memory_quotas", map("heap_max_mb", attribute(member, "MaxMemory"),
                        "off_heap_max_bytes", attribute(member, "OffHeapMaxMemory")),
                "available_processors", os.get("availableProcessors"),
                "uptime_seconds", attribute(member, "MemberUpTime"),
                "software_version", attribute(member, "ReleaseVersion"),
                "installation_path", installationPath((String) attribute(member, "ClassPath")),
                "encryption", encryption(properties),
                "tls", tls(manager, name));
    }

    /** Waits until the manager holds a Member MBean for every member of the cluster. */
    private void awaitFederation() throws Exception {
        long deadline = System.nanoTime() + FEDERATION_TIMEOUT.toNanos();
        while (true) {
            int expected = (Integer) attribute(DISTRIBUTED_SYSTEM, "MemberCount");
            int federated = mbeans.queryNames(ALL_MEMBERS, null).size();
            if (federated >= expected) {
                return;
            }
            if (System.nanoTime() > deadline) {
                logger.warn("Only {} of {} members visible after {}; collecting what's there",
                        federated, expected, FEDERATION_TIMEOUT);
                return;
            }
            Thread.sleep(1000);
        }
    }

    private Map<String, Object> encryption(CompositeData properties) {
        List<String> components = strings(properties.get("securableCommunicationChannel"));
        boolean enabled = !components.isEmpty();
        for (String flag : LEGACY_SSL_FLAGS) {
            enabled |= Boolean.TRUE.equals(properties.get(flag));
        }
        return map("ssl_enabled", enabled, "ssl_enabled_components", components);
    }

    /**
     * The SSLProtocols attribute from listGemFireProperties() is always null, so TLS settings come
     * from gfsh's "describe config", run over JMX through the manager.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> tls(ObjectName manager, String member) {
        try {
            String result = (String) mbeans.invoke(manager, "processCommand",
                    new Object[] {"describe config --member=" + member + " --hide-defaults=false"}, STRING_SIGNATURE);
            Map<String, Object> config = (Map<String, Object>) Json.parse(result);
            if (!"OK".equals(config.get("status"))) {
                logger.warn("describe config for {} returned status {}", member, config.get("status"));
                return null;
            }
            Map<String, Object> sections = (Map<String, Object>) config.get("content");
            String protocols = setting(sections, "ssl-protocols");
            return map(
                    "ssl_protocols", protocols != null ? protocols : section(sections, "default-properties").get("ssl-protocols"),
                    "ssl_client_protocols", setting(sections, "ssl-client-protocols"),
                    "ssl_server_protocols", setting(sections, "ssl-server-protocols"));
        } catch (Exception e) {
            logger.warn("Could not read TLS settings for {}", member, e);
            return null;
        }
    }

    /** A setting can come from the API, a -Dgemfire.<key> flag or a properties file; null if unset. */
    private static String setting(Map<String, Object> sections, String key) {
        Object api = section(sections, "api-properties").get(key);
        if (api instanceof String value && !value.isEmpty()) {
            return value;
        }
        String flag = "-Dgemfire." + key + "=";
        for (String arg : strings(section(sections, "jvm-args").get("JVM command line arguments"))) {
            if (arg.startsWith(flag) && arg.length() > flag.length()) {
                return arg.substring(flag.length());
            }
        }
        Object file = section(sections, "file-properties").get(key);
        return file instanceof String value && !value.isEmpty() ? value : null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> section(Map<String, Object> sections, String name) {
        Object section = sections.get(name);
        if (section instanceof Map<?, ?> wrapper && wrapper.get("content") instanceof Map<?, ?> content) {
            return (Map<String, Object>) content;
        }
        return Map.of();
    }

    static String rhelRelease(String kernelVersion) {
        Matcher matcher = RHEL_KERNEL.matcher(kernelVersion);
        if (!matcher.find()) {
            return null;
        }
        return "RHEL " + matcher.group(1) + (matcher.group(2) != null ? "." + matcher.group(2) : "");
    }

    static String addressFamily(String version) {
        Matcher matcher = RUNNING_ON_IP.matcher(version);
        if (!matcher.find()) {
            return null;
        }
        return matcher.group(1).contains(":") ? "IPv6" : "IPv4";
    }

    static String installationPath(String classPath) {
        for (String entry : classPath.split(":")) {
            Matcher matcher = GEMFIRE_JAR.matcher(entry);
            if (matcher.matches()) {
                return matcher.group(1);
            }
        }
        return null;
    }

    private Object attribute(ObjectName name, String attribute) throws Exception {
        return mbeans.getAttribute(name, attribute);
    }

    private Object invoke(ObjectName name, String operation) throws Exception {
        return mbeans.invoke(name, operation, null, null);
    }

    private static List<String> strings(Object value) {
        List<String> strings = new ArrayList<>();
        if (value instanceof Collection<?> collection) {
            collection.forEach(item -> strings.add(String.valueOf(item)));
        } else if (value != null && value.getClass().isArray()) {
            for (int i = 0; i < Array.getLength(value); i++) {
                strings.add(String.valueOf(Array.get(value, i)));
            }
        }
        return strings;
    }

    /** An insertion-ordered map that, unlike Map.of, allows null values. */
    private static Map<String, Object> map(Object... keysAndValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            map.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return map;
    }

    private static ObjectName objectName(String name) {
        try {
            return new ObjectName(name);
        } catch (MalformedObjectNameException e) {
            throw new IllegalArgumentException(name, e);
        }
    }
}
