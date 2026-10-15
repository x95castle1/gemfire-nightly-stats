package com.example.gemfire.stats.embedded;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.apache.geode.cache.CacheFactory;
import org.apache.geode.cache.server.CacheServer;
import org.apache.geode.distributed.LocatorLauncher;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Adapted from CitiGemFireLocatorStart: retains its locator and cache-server startup and SSL
 * property mapping, then adds a JMX manager and scheduled statistics collection. Writes the
 * nightly report (see README.md) to a JSON file every day from a background thread. If S3_ENDPOINT
 * is set, each report is also uploaded to that S3-compatible store (SeaweedFS, MinIO, AWS S3).
 *
 * <p>Usage: {@code NightlyStatsLocatorStart <locator.properties>}. See locator.properties.example
 * for the keys.
 */
public class NightlyStatsLocatorStart implements Runnable {

    private final LocatorLauncher locatorLauncher;

    private static final Logger logger = LogManager.getLogger();

    /** Keys with this prefix are passed to the locator as GemFire properties, e.g. gemfire.ssl-protocols */
    private static final String GEMFIRE_PREFIX = "gemfire.";
    private static final Duration STARTUP_RUN_DELAY = Duration.ofSeconds(60);

    private final Properties properties;
    private final StatsSettings settings;
    private final String env;
    private final String clusterName;
    private final Path workingDirectory;
    private final Path outputDirectory;
    private final LocalTime runAt;
    private final int intervalSeconds;
    /** null when S3_ENDPOINT isn't set */
    private final ReportDelivery delivery;
    private final ReportJob job;

    public NightlyStatsLocatorStart(Properties properties) {
        this.properties = properties;
        this.settings = new StatsSettings(properties);
        this.env = required("ENV");
        this.clusterName = required("CLUSTER_NAME");
        this.workingDirectory = Paths.get(required("LOG_DIRECTORY"));
        this.outputDirectory = Paths.get(get("NIGHTLY_STATS_DIR", workingDirectory.resolve("nightly-stats").toString()));
        this.runAt = LocalTime.parse(get("NIGHTLY_STATS_TIME", "02:00"));
        this.intervalSeconds = Integer.parseInt(get("COLLECTION_INTERVAL_SECONDS", "0"));
        if (intervalSeconds < 0) {
            throw new IllegalArgumentException("COLLECTION_INTERVAL_SECONDS must be zero or a positive integer");
        }
        this.delivery = settings.delivery(outputDirectory, env, clusterName,
                get("S3_ENDPOINT", null) == null ? "local" : "s3", true);
        this.job = new ReportJob(new ReportStore(outputDirectory, clusterName), Clock.systemDefaultZone());
        try {
            Files.createDirectories(workingDirectory);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create locator working directory", e);
        }
        this.locatorLauncher = buildLauncher();
    }

    public static void main(String... args) throws IOException {
        if (args.length != 1) {
            System.err.println("Usage: NightlyStatsLocatorStart <locator.properties>");
            System.exit(1);
        }
        Properties properties = new Properties();
        try (InputStream input = new FileInputStream(args[0])) {
            properties.load(input);
        }
        new NightlyStatsLocatorStart(properties).run();
    }

    @Override
    public void run() {
        this.locatorLauncher.start();
        this.locatorLauncher.waitOnStatusResponse(30L, 5L, TimeUnit.SECONDS);
        System.out.println(this.locatorLauncher.status().toString());
        System.out.println("Locator successfully started");

        // Preserve the cache-server startup from CitiGemFireLocatorStart.
        try {
            org.apache.geode.cache.Cache cache = new CacheFactory().create();

            CacheServer cacheServer = cache.addCacheServer();
            cacheServer.setPort(Integer.parseInt(get("CACHE_SERVER_PORT", "40404")));
            cacheServer.start();

            System.out.println("Cache server successfully started on port " + cacheServer.getPort());
        } catch (Exception e) {
            e.printStackTrace();
        }

        // Monitoring is the addition to the Citi startup lifecycle.
        scheduleNightlyStats();
        this.locatorLauncher.waitOnLocator();
    }

    LocatorLauncher buildLauncher() {
        String[] input = locatorProperties(properties);
        LocatorLauncher.Builder builder = new LocatorLauncher.Builder()
                .setMemberName(input[0])
                .setPort(Integer.valueOf(input[1]))
                .setWorkingDirectory(input[2])
                .setHostnameForClients(input[3])
                .setBindAddress(input[4])
                .set("ssl-enabled-components", input[5])
                .set("ssl-protocols", input[6])
                .set("ssl-require-authentication", input[7])
                .set("ssl-ciphers", input[8])
                .set("ssl-keystore", input[9])
                .set("ssl-keystore-password", input[10])
                .set("ssl-truststore", input[11])
                .set("ssl-truststore-password", input[12])
                .set("ssl-use-default-context", input[13])
                .setRedirectOutput(true)
                // The collector reads every member's MBeans through this locator's JMX manager.
                .set("jmx-manager", "true")
                .set("jmx-manager-start", "true")
                .set("jmx-manager-port", get("JMX_MANAGER_PORT", "1099"));
        for (String key : properties.stringPropertyNames()) {
            if (key.startsWith(GEMFIRE_PREFIX)) {
                builder.set(key.substring(GEMFIRE_PREFIX.length()), get(key, ""));
            }
        }
        return builder.build();
    }

    /** The same fourteen inputs and ordering used by CitiGemFireLocatorStart. */
    private static String[] locatorProperties(Properties properties) {
        Properties inputProperties = new Properties();
        inputProperties.setProperty("BIND_ADDR", "0.0.0.0");
        inputProperties.setProperty("LOCATOR_HOST_NAME_FOR_CLIENTS", "");
        inputProperties.setProperty("CLUSTER_SSL_ENABLED", "");
        inputProperties.setProperty("CLUSTER_SSL_PROTOCOLS", "any");
        inputProperties.setProperty("CLUSTER_SSL_MUTUAL_AUTHENTICATION", "true");
        inputProperties.setProperty("CLUSTER_SSL_CIPHERS", "any");
        inputProperties.setProperty("CLUSTER_SSL_KEY_STORE_FILE_NAME", "");
        inputProperties.setProperty("CLUSTER_SSL_KEY_STORE_PASSWORD", "");
        inputProperties.putAll(properties);
        if (getLocatorPort(inputProperties) == null) {
            throw new IllegalArgumentException("Missing required property LOCATOR_PORT");
        }

        String[] locatorProperties = new String[14];
        locatorProperties[0]  = getMemberName(inputProperties);
        locatorProperties[1]  = getLocatorPort(inputProperties);
        locatorProperties[2]  = getWorkingDirectory(inputProperties);
        locatorProperties[3]  = getHostNameForClients(inputProperties);
        locatorProperties[4]  = getBindAddress(inputProperties);
        locatorProperties[5]  = getSSLEnabledComponents(inputProperties);
        locatorProperties[6]  = getSSLProtocols(inputProperties);
        locatorProperties[7]  = getSSLRequireAuthentication(inputProperties);
        locatorProperties[8]  = getSSLCiphers(inputProperties);
        locatorProperties[9]  = getSSLKeyStoreTrustStore(inputProperties);
        locatorProperties[10] = getSSLKeyStoreTrustStorePassword(inputProperties);
        locatorProperties[11] = getSSLKeyStoreTrustStore(inputProperties);
        locatorProperties[12] = getSSLKeyStoreTrustStorePassword(inputProperties);
        locatorProperties[13] = getSSLUseDefaultContext(inputProperties);
        return locatorProperties;
    }

    private static String getMemberName(Properties inputProperties) {
        String name = getPropertyAndReplaceQuotes("MEMBER_NAME", inputProperties);
        if (name != null && !name.isBlank()) {
            return name;
        }
        return getClusterName(inputProperties) + "." + getBindAddress(inputProperties) + ".locator" + getLocatorPort(inputProperties);
    }

    private static String getSSLKeyStoreTrustStore(Properties inputProperties) {
        return getPropertyAndReplaceQuotes("CLUSTER_SSL_KEY_STORE_FILE_NAME", inputProperties);
    }

    private static String getSSLCiphers(Properties inputProperties) {
        return getPropertyAndReplaceQuotes("CLUSTER_SSL_CIPHERS", inputProperties);
    }

    private static String getSSLRequireAuthentication(Properties inputProperties) {
        return getPropertyAndReplaceQuotes("CLUSTER_SSL_MUTUAL_AUTHENTICATION", inputProperties);
    }

    private static String getClusterName(Properties inputProperties) {
        return getPropertyAndReplaceQuotes("CLUSTER_NAME", inputProperties);
    }

    private static String getSSLProtocols(Properties inputProperties) {
        return getPropertyAndReplaceQuotes("CLUSTER_SSL_PROTOCOLS", inputProperties);
    }

    private static String getSSLEnabledComponents(Properties inputProperties) {
        return getPropertyAndReplaceQuotes("CLUSTER_SSL_ENABLED", inputProperties);
    }

    private static String getBindAddress(Properties inputProperties) {
        return getPropertyAndReplaceQuotes("BIND_ADDR", inputProperties);
    }

    private static String getHostNameForClients(Properties inputProperties) {
        return getPropertyAndReplaceQuotes("LOCATOR_HOST_NAME_FOR_CLIENTS", inputProperties);
    }

    private static String getWorkingDirectory(Properties inputProperties) {
        return getPropertyAndReplaceQuotes("LOG_DIRECTORY", inputProperties);
    }

    private static String getLocatorPort(Properties inputProperties) {
        return getPropertyAndReplaceQuotes("LOCATOR_PORT", inputProperties);
    }

    private static String getSSLUseDefaultContext(Properties inputProperties) {
        String value = getPropertyAndReplaceQuotes("SSL_USE_DEFAULT_CONTEXT", inputProperties);
        if (value == null || value.trim().isEmpty()) {
            return "";
        }
        return value;
    }

    private static String getSSLKeyStoreTrustStorePassword(Properties inputProperties) {
        String password = getPropertyAndReplaceQuotes("CLUSTER_SSL_KEY_STORE_PASSWORD", inputProperties);
        // The reference CitiGemFireUtils.decrypt is a placeholder, not a working decryptor.
        // Supply the usable password directly in this self-contained example.
        return password == null ? "" : password;
    }

    public static String getPropertyAndReplaceQuotes(String key, Properties properties) {
        String value = properties.getProperty(key);
        return value != null ? value.replace("\"", "") : null;
    }

    private void scheduleNightlyStats() {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "nightly-stats");
            thread.setDaemon(true);
            return thread;
        });
        scheduleReports(scheduler);
    }

    void scheduleReports(ScheduledExecutorService scheduler) {
        if (delivery != null) {
            logger.info("Nightly stats reports will also be uploaded to {}", delivery.location());
            // Catch up on reports an earlier run couldn't upload, e.g. while S3 was down
            scheduler.execute(this::uploadPending);
        }
        boolean startup = Boolean.parseBoolean(get("NIGHTLY_STATS_RUN_ON_STARTUP", "false"));
        if (intervalSeconds > 0) {
            long initialDelay = startup ? STARTUP_RUN_DELAY.toSeconds() : intervalSeconds;
            logger.info("Stats collection repeats with {} seconds between runs", intervalSeconds);
            scheduler.scheduleWithFixedDelay(this::writeReport, initialDelay, intervalSeconds, TimeUnit.SECONDS);
            return;
        }
        if (startup) {
            // Give servers time to join before the first run
            scheduler.schedule(this::writeReport, STARTUP_RUN_DELAY.toSeconds(), TimeUnit.SECONDS);
        }
        scheduleNextRun(scheduler);
    }

    /** Reschedules after every run so each delay is recalculated, which keeps the time right across DST changes. */
    private void scheduleNextRun(ScheduledExecutorService scheduler) {
        ZonedDateTime now = ZonedDateTime.now();
        ZonedDateTime next = nextRun(now, runAt);
        logger.info("Next nightly stats run at {}", next);
        scheduler.schedule(() -> {
            try {
                writeReport();
            } finally {
                scheduleNextRun(scheduler);
            }
        }, Duration.between(now, next).toMillis(), TimeUnit.MILLISECONDS);
    }

    static ZonedDateTime nextRun(ZonedDateTime now, LocalTime runAt) {
        ZonedDateTime next = now.toLocalDate().atTime(runAt).atZone(now.getZone());
        // Resolve tomorrow's time from tomorrow's date. Adding a day to today's result would carry a
        // spring-forward shift (02:00 -> 03:00) over to the day after.
        return next.isAfter(now) ? next : now.toLocalDate().plusDays(1).atTime(runAt).atZone(now.getZone());
    }

    private void writeReport() {
        try {
            Path file = job.collect(() -> new NightlyStatsCollector(
                    ManagementFactory.getPlatformMBeanServer(), env, clusterName).collect());
            logger.info("Nightly stats written to {}", file);
        } catch (Exception e) {
            // Never let a failed collection affect the locator; the next run tries again
            logger.error("Nightly stats collection failed", e);
        }
        // Even if today's collection failed, earlier reports may still need uploading
        uploadPending();
    }

    /** Uploads reports on disk that haven't been uploaded yet. A failure is logged, and the next pass retries. */
    private void uploadPending() {
        if (delivery == null) {
            return;
        }
        try {
            delivery.uploadPending();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.error("Nightly stats upload was interrupted");
        } catch (Exception e) {
            logger.error("Nightly stats upload failed. Reports stay on disk and are retried after the next run"
                    + " or locator restart.", e);
        }
    }

    private String required(String key) {
        return settings.required(key);
    }

    /** Trims the value and strips double quotes, which startup property files often wrap values in. */
    private String get(String key, String defaultValue) {
        return settings.get(key, defaultValue);
    }
}
