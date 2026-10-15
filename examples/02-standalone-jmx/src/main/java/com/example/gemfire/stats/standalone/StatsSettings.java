package com.example.gemfire.stats.standalone;

import java.net.URI;
import java.nio.file.Path;
import java.util.Properties;

/** Property parsing and delivery setup for this example. */
public final class StatsSettings {
    private final Properties properties;

    public StatsSettings(Properties properties) {
        this.properties = properties;
    }

    public String get(String key, String fallback) {
        String value = properties.getProperty(key);
        if (value == null) return fallback;
        value = value.trim();
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1);
        }
        return value.isBlank() ? fallback : value;
    }

    public String required(String key) {
        String value = get(key, null);
        if (value == null) throw new IllegalArgumentException("Missing required property " + key);
        return value;
    }

    public String secret(String key, String environmentVariable) {
        String value = get(key, System.getenv(environmentVariable));
        return value == null || value.isBlank() ? null : value;
    }

    private String requiredSecret(String key, String environmentVariable) {
        String value = secret(key, environmentVariable);
        if (value == null) {
            throw new IllegalArgumentException("Missing " + key + " (or environment variable " + environmentVariable + ")");
        }
        return value;
    }

    public boolean bool(String key, boolean fallback) {
        String value = get(key, Boolean.toString(fallback));
        if (!value.equalsIgnoreCase("true") && !value.equalsIgnoreCase("false")) {
            throw new IllegalArgumentException(key + " must be true or false");
        }
        return Boolean.parseBoolean(value);
    }

    public int positiveInt(String key, int fallback) {
        try {
            int value = Integer.parseInt(get(key, Integer.toString(fallback)));
            if (value > 0) return value;
        } catch (NumberFormatException ignored) {
        }
        throw new IllegalArgumentException(key + " must be a positive integer");
    }

    public static String segment(String key, String value) {
        if (!value.matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
            throw new IllegalArgumentException(key + " must start with a letter or number and contain only letters, numbers, dots, underscores or hyphens");
        }
        return value;
    }

    public String mode(String fallback) {
        String mode = get("DELIVERY_MODE", fallback);
        if (!mode.equals("local") && !mode.equals("s3")) {
            throw new IllegalArgumentException("DELIVERY_MODE must be local or s3");
        }
        return mode;
    }

    /** Inspection needs the destination, but never needs credentials or an HTTP client. */
    public ReportDelivery delivery(Path directory, String env, String cluster, String defaultMode, boolean send) {
        if (mode(defaultMode).equals("local")) return null;
        segment("ENV", env);
        segment("CLUSTER_NAME", cluster);
        URI endpoint = S3Uploader.validEndpoint(URI.create(required("S3_ENDPOINT")));
        String bucket = segment("S3_BUCKET", required("S3_BUCKET"));
        String prefix = get("S3_PREFIX", "nightly-stats").replaceAll("^/+|/+$", "");
        String keyPrefix = (prefix.isEmpty() ? "" : prefix + "/") + env + "/" + cluster;
        ReportDelivery.Sink sink = (key, content) -> {
            throw new IllegalStateException("Delivery was opened for inspection only");
        };
        if (send) {
            S3Uploader uploader = new S3Uploader(endpoint, bucket, get("S3_REGION", "us-east-1"),
                    requiredSecret("S3_ACCESS_KEY", "AWS_ACCESS_KEY_ID"),
                    requiredSecret("S3_SECRET_KEY", "AWS_SECRET_ACCESS_KEY"));
            sink = (key, content) -> {
                ReportStore.verifyIdentity(content, env, cluster);
                uploader.put(key, content, "application/json");
            };
        }
        return new ReportDelivery(directory, cluster, keyPrefix, S3Uploader.destination(endpoint, bucket), sink);
    }

    public Properties gemfireProperties() {
        Properties result = new Properties();
        for (String key : properties.stringPropertyNames()) {
            if (key.startsWith("gemfire.")) result.setProperty(key.substring(8), get(key, ""));
        }
        return result;
    }
}
