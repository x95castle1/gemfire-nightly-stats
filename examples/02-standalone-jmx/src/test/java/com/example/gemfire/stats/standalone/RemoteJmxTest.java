package com.example.gemfire.stats.standalone;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import javax.management.*;
import javax.management.openmbean.*;
import javax.management.remote.*;
import javax.security.auth.Subject;
import org.junit.jupiter.api.Test;

class RemoteJmxTest {
    private Properties settings(int port) {
        Properties p = new Properties();
        p.setProperty("ENV", "local");
        p.setProperty("CLUSTER_NAME", "test");
        p.setProperty("JMX_HOST", "127.0.0.1");
        p.setProperty("JMX_PORT", Integer.toString(port));
        return p;
    }

    @Test
    @SuppressWarnings("unchecked")
    void collectsThroughAnAuthenticatedRemoteConnection() throws Exception {
        int port;
        try (ServerSocket free = new ServerSocket(0)) { port = free.getLocalPort(); }
        Registry registry = LocateRegistry.createRegistry(port);
        MBeanServer mbeans = MBeanServerFactory.newMBeanServer();
        ObjectName member = new ObjectName("GemFire:type=Member,member=locator");
        ObjectName system = new ObjectName("GemFire:service=System,type=Distributed");
        CompositeData os = composite(Map.of("name", "Linux", "version", "4.18.0.el8_10", "arch", "amd64",
                "totalPhysicalMemorySize", 1024L, "availableProcessors", 2));
        CompositeData config = composite(Map.of("securableCommunicationChannel", new String[0],
                "clusterSSLEnabled", false, "serverSSLEnabled", false, "gatewaySSLEnabled", false,
                "jmxManagerSSLEnabled", false, "httpServiceSSLEnabled", false));
        Map<String, Object> attributes = Map.ofEntries(
                Map.entry("Host", "localhost"), Map.entry("Version", "Running on: host/127.0.0.1, test"),
                Map.entry("MaxMemory", 512L), Map.entry("OffHeapMaxMemory", 0L), Map.entry("MemberUpTime", 60L),
                Map.entry("ReleaseVersion", "10.1.3"), Map.entry("ClassPath", "/opt/gemfire/lib/gemfire-bootstrap-10.1.3.jar"),
                Map.entry("CurrentTime", 1792065600000L));
        mbeans.registerMBean(new Bean(attributes, operation -> switch (operation) {
            case "showOSMetrics" -> os;
            case "listGemFireProperties" -> config;
            case "processCommand" -> Json.write(Map.of("status", "OK", "content", Map.of("default-properties",
                    Map.of("content", Map.of("ssl-protocols", "any")))));
            default -> throw new IllegalArgumentException(operation);
        }), member);
        mbeans.registerMBean(new Bean(Map.of("MemberCount", 1, "MemberObjectName", member, "TotalRegionCount", 0),
                operation -> switch (operation) {
                    case "listLocatorMembers", "listMembers" -> new String[] {"locator"};
                    case "listServers", "listAllRegionPaths" -> new String[0];
                    case "fetchMemberObjectName" -> member;
                    default -> throw new IllegalArgumentException(operation);
                }), system);
        JMXAuthenticator authenticator = credentials -> {
            if (!(credentials instanceof String[] pair) || !Arrays.equals(pair, new String[] {"reader", "secret"})) {
                throw new SecurityException("Invalid credentials");
            }
            return new Subject();
        };
        JMXConnectorServer server = JMXConnectorServerFactory.newJMXConnectorServer(
                new JMXServiceURL("service:jmx:rmi:///jndi/rmi://127.0.0.1:" + port + "/jmxrmi"),
                Map.of(JMXConnectorServer.AUTHENTICATOR, authenticator), mbeans);
        try {
            server.start();
            Properties p = settings(port);
            p.setProperty("JMX_USERNAME", "reader");
            p.setProperty("JMX_PASSWORD", "secret");
            Map<String, Object> report = RemoteJmx.collect(new StatsSettings(p));
            Map<String, Object> cluster = (Map<String, Object>) report.get("cluster");
            assertEquals("test", cluster.get("cluster_name"));
            assertEquals(1, cluster.get("locators_count"));
            var members = (java.util.List<Map<String, Object>>) report.get("members");
            assertEquals("RHEL 8.10", ((Map<?, ?>) members.get(0).get("os")).get("distribution"));
            assertEquals("any", ((Map<?, ?>) members.get(0).get("tls")).get("ssl_protocols"));
            p.setProperty("JMX_PASSWORD", "wrong");
            assertThrows(SecurityException.class, () -> RemoteJmx.collect(new StatsSettings(p)));
        } finally {
            server.stop();
            UnicastRemoteObject.unexportObject(registry, true);
        }
    }

    @Test
    void stalledRmiHandshakeIsBounded() throws Exception {
        AtomicReference<Socket> accepted = new AtomicReference<>();
        CountDownLatch connected = new CountDownLatch(1);
        try (ServerSocket server = new ServerSocket(0)) {
            Thread listener = new Thread(() -> {
                try { accepted.set(server.accept()); connected.countDown(); }
                catch (IOException ignored) { }
            });
            listener.setDaemon(true);
            listener.start();
            Properties p = settings(server.getLocalPort());
            p.setProperty("JMX_TIMEOUT_SECONDS", "1");
            try {
                IOException error = assertTimeoutPreemptively(Duration.ofSeconds(5),
                        () -> assertThrows(IOException.class, () -> RemoteJmx.collect(new StatsSettings(p))));
                assertTrue(error.getMessage().contains("exceeded"), error.getMessage());
                assertTrue(connected.await(1, TimeUnit.SECONDS));
            } finally {
                if (accepted.get() != null) accepted.get().close();
            }
        }
    }

    @Test
    void validatesEndpointAndCredentials() {
        Properties p = settings(70000);
        assertThrows(IllegalArgumentException.class, () -> RemoteJmx.validate(new StatsSettings(p)));
        p.setProperty("JMX_PORT", "1099");
        p.setProperty("JMX_USERNAME", "reader");
        assertThrows(IllegalArgumentException.class, () -> RemoteJmx.validate(new StatsSettings(p)));
    }

    private static CompositeData composite(Map<String, Object> values) throws OpenDataException {
        String[] keys = values.keySet().toArray(String[]::new);
        OpenType<?>[] types = new OpenType<?>[keys.length];
        for (int i = 0; i < keys.length; i++) {
            Object value = values.get(keys[i]);
            types[i] = value instanceof String ? SimpleType.STRING : value instanceof Long ? SimpleType.LONG
                    : value instanceof Integer ? SimpleType.INTEGER : value instanceof Boolean ? SimpleType.BOOLEAN
                    : ArrayType.getArrayType(SimpleType.STRING);
        }
        return new CompositeDataSupport(new CompositeType("test", "test", keys, keys, types), values);
    }

    private record Bean(Map<String, Object> attributes, Function<String, Object> operations) implements DynamicMBean {
        @Override public Object getAttribute(String attribute) { return attributes.get(attribute); }
        @Override public void setAttribute(Attribute attribute) { throw new UnsupportedOperationException(); }
        @Override public AttributeList getAttributes(String[] names) {
            AttributeList list = new AttributeList();
            for (String name : names) list.add(new Attribute(name, getAttribute(name)));
            return list;
        }
        @Override public AttributeList setAttributes(AttributeList list) { throw new UnsupportedOperationException(); }
        @Override public Object invoke(String operation, Object[] parameters, String[] signature) { return operations.apply(operation); }
        @Override public MBeanInfo getMBeanInfo() {
            MBeanAttributeInfo[] info = attributes.entrySet().stream().map(entry -> new MBeanAttributeInfo(
                    entry.getKey(), entry.getValue().getClass().getName(), entry.getKey(), true, false, false))
                    .toArray(MBeanAttributeInfo[]::new);
            return new MBeanInfo(getClass().getName(), "Test GemFire management data", info, null, null, null);
        }
    }
}
