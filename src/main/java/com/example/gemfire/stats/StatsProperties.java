package com.example.gemfire.stats;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Shared property-file handling for the locator and the manual upload command. */
final class StatsProperties {

    private final Properties properties;

    StatsProperties(Properties properties) {
        this.properties = properties;
    }

    static StatsProperties load(Path file) throws IOException {
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(file)) {
            properties.load(input);
        }
        return new StatsProperties(properties);
    }

    String required(String key) {
        String value = get(key, null);
        if (value == null) {
            throw new IllegalArgumentException("Missing required property " + key);
        }
        return value;
    }

    /** Startup property files often wrap values in double quotes. */
    String get(String key, String defaultValue) {
        String value = properties.getProperty(key);
        if (value == null || value.replace("\"", "").isBlank()) {
            return defaultValue;
        }
        return value.replace("\"", "").trim();
    }

    boolean uploadEnabled() {
        return Boolean.parseBoolean(get("NIGHTLY_STATS_S3_ENABLED", "false"));
    }

    Path outputDirectory() {
        return Path.of(get("NIGHTLY_STATS_DIR", Path.of(required("LOG_DIRECTORY"), "nightly-stats").toString()));
    }
}
