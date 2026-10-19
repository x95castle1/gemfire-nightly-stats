package com.example.gemfire.stats.standalone;

import java.io.IOException;
import java.io.StringWriter;
import java.net.URI;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;
import org.apache.geode.internal.net.SSLConfigurationFactory;
import org.apache.geode.internal.security.SecurableCommunicationChannel;
import org.apache.geode.management.internal.ContextAwareSSLRMIClientSocketFactory;

/** One remote session per invocation. It connects as a JMX client, without starting a GemFire member. */
final class RemoteJmx {
    private static final String SSL_PROPERTIES = "org.apache.geode.internal.net.ssl.config";

    private RemoteJmx() {}

    static void validate(StatsSettings settings) {
        endpoint(settings);
        settings.positiveInt("JMX_TIMEOUT_SECONDS", 120);
        String user = settings.get("JMX_USERNAME", null);
        String password = settings.get("JMX_PASSWORD", null);
        String token = settings.get("JMX_TOKEN", null);
        if ((user == null) != (password == null)) {
            throw new IllegalArgumentException("Set both JMX_USERNAME and JMX_PASSWORD, or neither");
        }
        if (token != null && user != null) {
            throw new IllegalArgumentException("Set JMX_TOKEN or JMX_USERNAME/JMX_PASSWORD, not both");
        }
    }

    private static String endpoint(StatsSettings settings) {
        String host = settings.required("JMX_HOST");
        if (host.contains(":") && !host.startsWith("[")) host = "[" + host + "]";
        int port = settings.positiveInt("JMX_PORT", 1099);
        URI address = URI.create("rmi://" + host + ":" + port);
        if (port > 65535 || address.getHost() == null || address.getRawUserInfo() != null
                || (address.getPath() != null && !address.getPath().isEmpty())
                || address.getQuery() != null || address.getFragment() != null) {
            throw new IllegalArgumentException("JMX_HOST must be a host name or IP and JMX_PORT must be 1..65535");
        }
        return "service:jmx:rmi:///jndi/" + address + "/jmxrmi";
    }

    static Map<String, Object> collect(StatsSettings settings) throws Exception {
        validate(settings);
        Map<String, Object> environment = new HashMap<>();
        String user = settings.get("JMX_USERNAME", null);
        String token = settings.get("JMX_TOKEN", null);
        if (token != null) environment.put(JMXConnector.CREDENTIALS, token);
        else if (user != null) environment.put(JMXConnector.CREDENTIALS,
                new String[] {user, settings.get("JMX_PASSWORD", null)});

        Properties ssl = settings.gemfireProperties();
        boolean encrypted = SSLConfigurationFactory.getSSLConfigForComponent(
                ssl, SecurableCommunicationChannel.JMX).isEnabled();
        if (encrypted) {
            // Same registry and exported-stub socket factory/configuration used by gfsh.
            environment.put("com.sun.jndi.rmi.factory.socket", new ContextAwareSSLRMIClientSocketFactory());
        }
        JMXConnector connector = JMXConnectorFactory.newJMXConnector(new JMXServiceURL(endpoint(settings)), environment);
        return withinDeadline(() -> {
            String previous = System.getProperty(SSL_PROPERTIES);
            if (encrypted) {
                StringWriter text = new StringWriter();
                ssl.store(text, null);
                System.setProperty(SSL_PROPERTIES, text.toString());
            }
            try (connector) {
                connector.connect();
                return new NightlyStatsCollector(connector.getMBeanServerConnection(),
                        settings.required("ENV"), settings.required("CLUSTER_NAME")).collect();
            } finally {
                if (encrypted) {
                    if (previous == null) System.clearProperty(SSL_PROPERTIES);
                    else System.setProperty(SSL_PROPERTIES, previous);
                }
            }
        }, Duration.ofSeconds(settings.positiveInt("JMX_TIMEOUT_SECONDS", 120)), () -> {
            try {
                connector.close();
            } catch (IOException ignored) {
                // A timed-out handshake may not have established a connector yet.
            }
        });
    }

    /** RMI calls may ignore interruption. Only this daemon does remote work; it never writes files. */
    static <T> T withinDeadline(java.util.concurrent.Callable<T> work, Duration timeout, Runnable cleanup) throws Exception {
        var executor = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "remote-jmx");
            thread.setDaemon(true);
            return thread;
        });
        var future = executor.submit(work);
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new IOException("Remote JMX connect/collection exceeded " + timeout + "; no report was written", e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof Exception cause) throw cause;
            throw new IOException("Remote JMX collection failed", e.getCause());
        } finally {
            if (!future.isDone()) {
                future.cancel(true);
                Thread closer = new Thread(cleanup, "remote-jmx-close");
                closer.setDaemon(true);
                closer.start();
            }
            executor.shutdownNow();
        }
    }
}
