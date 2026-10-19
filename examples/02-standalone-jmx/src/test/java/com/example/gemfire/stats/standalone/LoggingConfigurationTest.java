package com.example.gemfire.stats.standalone;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LoggerContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LoggingConfigurationTest {
    @TempDir Path directory;

    @Test
    void defaultsKeepCollectorInfoAndSuppressGemFireInfo() throws Exception {
        String output = probe(Map.of());
        assertTrue(output.contains("collector-info"), output);
        assertFalse(output.contains("collector-debug"), output);
        assertFalse(output.contains("gemfire-info"), output);
        assertTrue(output.contains("gemfire-warn"), output);
        assertTrue(output.contains("gemfire-error"), output);
    }

    @Test
    void serviceEnvironmentOverridesBothJavaLogLevels() throws Exception {
        String output = probe(Map.of("COLLECTOR_LOG_LEVEL", "DEBUG", "COLLECTOR_GEMFIRE_LOG_LEVEL", "ERROR"));
        assertTrue(output.contains("collector-debug"), output);
        assertTrue(output.contains("collector-info"), output);
        assertFalse(output.contains("gemfire-info"), output);
        assertFalse(output.contains("gemfire-warn"), output);
        assertTrue(output.contains("gemfire-error"), output);
    }

    private String probe(Map<String, String> overrides) throws Exception {
        String classpath = String.join(File.pathSeparator, location(LoggingConfigurationTest.class),
                location(LogManager.class), location(LoggerContext.class));
        Path output = directory.resolve("console.log");
        ProcessBuilder builder = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Dlog4j.configurationFile=" + getClass().getResource("/standalone-log4j2.xml"),
                "-cp", classpath, Probe.class.getName()).redirectErrorStream(true).redirectOutput(output.toFile());
        builder.environment().remove("COLLECTOR_LOG_LEVEL");
        builder.environment().remove("COLLECTOR_GEMFIRE_LOG_LEVEL");
        builder.environment().putAll(overrides);
        Process process = builder.start();
        try {
            assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Logging probe timed out");
            String text = Files.readString(output);
            assertEquals(0, process.exitValue(), text);
            return text;
        } finally {
            process.destroyForcibly();
        }
    }

    private static String location(Class<?> type) throws Exception {
        return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
    }

    public static class Probe {
        public static void main(String[] args) {
            var collector = LogManager.getLogger("collector.probe");
            var gemfire = LogManager.getLogger("org.apache.geode.probe");
            collector.debug("collector-debug");
            collector.info("collector-info");
            gemfire.info("gemfire-info");
            gemfire.warn("gemfire-warn");
            gemfire.error("gemfire-error");
            LogManager.shutdown();
        }
    }
}
