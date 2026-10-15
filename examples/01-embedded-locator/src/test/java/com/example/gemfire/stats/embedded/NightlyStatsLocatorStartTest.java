package com.example.gemfire.stats.embedded;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.apache.geode.distributed.LocatorLauncher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NightlyStatsLocatorStartTest {
    @TempDir Path directory;

    private Properties settings() {
        Properties properties = new Properties();
        properties.setProperty("ENV", "local");
        properties.setProperty("CLUSTER_NAME", "test-cluster");
        properties.setProperty("LOG_DIRECTORY", directory.toString());
        properties.setProperty("LOCATOR_PORT", "10334");
        properties.setProperty("BIND_ADDR", "127.0.0.1");
        properties.setProperty("LOCATOR_HOST_NAME_FOR_CLIENTS", "localhost");
        return properties;
    }

    @Test
    void preservesCitiNamingAddressesAndSslPropertyMapping() throws Exception {
        Properties properties = settings();
        properties.setProperty("CLUSTER_NAME", "\"test-cluster\"");
        properties.setProperty("CLUSTER_SSL_ENABLED", "\"all\"");
        properties.setProperty("CLUSTER_SSL_PROTOCOLS", "TLSv1.2,TLSv1.3");
        properties.setProperty("CLUSTER_SSL_MUTUAL_AUTHENTICATION", "false");
        properties.setProperty("CLUSTER_SSL_CIPHERS", "any");
        properties.setProperty("CLUSTER_SSL_KEY_STORE_FILE_NAME", "\"/test/cluster.jks\"");
        properties.setProperty("CLUSTER_SSL_KEY_STORE_PASSWORD", "\"test-password\"");
        properties.setProperty("SSL_USE_DEFAULT_CONTEXT", "false");
        LocatorLauncher launcher = new NightlyStatsLocatorStart(properties).buildLauncher();

        assertEquals("test-cluster.127.0.0.1.locator10334", launcher.getMemberName());
        assertEquals("localhost", launcher.getHostnameForClients());
        assertEquals("127.0.0.1", launcher.getBindAddress().getHostAddress());
        assertEquals(directory.toRealPath().toString(), launcher.getWorkingDirectory());
        Properties actual = launcher.getProperties();
        assertEquals("all", actual.getProperty("ssl-enabled-components"));
        assertEquals("TLSv1.2,TLSv1.3", actual.getProperty("ssl-protocols"));
        assertEquals("false", actual.getProperty("ssl-require-authentication"));
        assertEquals("any", actual.getProperty("ssl-ciphers"));
        assertEquals("/test/cluster.jks", actual.getProperty("ssl-keystore"));
        assertEquals("/test/cluster.jks", actual.getProperty("ssl-truststore"));
        assertEquals("test-password", actual.getProperty("ssl-keystore-password"));
        assertEquals("test-password", actual.getProperty("ssl-truststore-password"));
        assertEquals("false", actual.getProperty("ssl-use-default-context"));
        assertEquals("true", actual.getProperty("jmx-manager"));
        assertEquals("true", actual.getProperty("jmx-manager-start"));
        assertTrue(launcher.isRedirectingOutput());
    }

    @Test
    void keepsExistingMemberNameAndGemfireOverrides() {
        Properties properties = settings();
        properties.setProperty("MEMBER_NAME", "test-locator");
        properties.setProperty("JMX_MANAGER_PORT", "21099");
        properties.setProperty("CLUSTER_SSL_PROTOCOLS", "TLSv1.2");
        properties.setProperty("gemfire.ssl-protocols", "TLSv1.3");
        properties.setProperty("gemfire.ssl-truststore", "/test/separate-truststore.p12");
        properties.setProperty("gemfire.ssl-truststore-type", "PKCS12");
        LocatorLauncher launcher = new NightlyStatsLocatorStart(properties).buildLauncher();

        assertEquals("test-locator", launcher.getMemberName());
        assertEquals("21099", launcher.getProperties().getProperty("jmx-manager-port"));
        assertEquals("TLSv1.3", launcher.getProperties().getProperty("ssl-protocols"));
        assertEquals("/test/separate-truststore.p12", launcher.getProperties().getProperty("ssl-truststore"));
        assertEquals("PKCS12", launcher.getProperties().getProperty("ssl-truststore-type"));
    }

    @Test
    void handlesMissingOrEmptyOptionalSslPropertiesWithoutDecryptor() {
        Properties properties = settings();
        LocatorLauncher launcher = new NightlyStatsLocatorStart(properties).buildLauncher();
        assertEquals("", launcher.getProperties().getProperty("ssl-keystore-password"));
        assertEquals("", launcher.getProperties().getProperty("ssl-truststore-password"));
        assertEquals("", launcher.getProperties().getProperty("ssl-use-default-context"));
        properties.setProperty("CLUSTER_SSL_KEY_STORE_PASSWORD", "");
        assertDoesNotThrow(() -> new NightlyStatsLocatorStart(properties));
    }

    @Test
    void createsTheWorkingDirectoryBeforeBuildingTheLauncher() {
        Properties properties = settings();
        Path working = directory.resolve("new-locator");
        properties.setProperty("LOG_DIRECTORY", working.toString());
        new NightlyStatsLocatorStart(properties);
        assertTrue(Files.isDirectory(working));
    }

    @Test
    void removesAllDoubleQuotesLikeTheCitiHelper() {
        Properties properties = new Properties();
        properties.setProperty("quoted", "\"one\"two\"");
        assertEquals("onetwo", NightlyStatsLocatorStart.getPropertyAndReplaceQuotes("quoted", properties));
        assertNull(NightlyStatsLocatorStart.getPropertyAndReplaceQuotes("missing", properties));
    }
}
