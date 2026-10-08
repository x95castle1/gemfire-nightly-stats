package com.example.gemfire.stats;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.apache.geode.distributed.LocatorLauncher;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Sample locator start class. Starts a locator with its JMX manager running, then writes the
 * nightly report (see README.md) to a JSON file every day from a background thread. If S3_ENDPOINT
 * is set, each report is also uploaded to that S3-compatible store (SeaweedFS, MinIO, AWS S3).
 *
 * <p>Usage: {@code NightlyStatsLocatorStart <locator.properties>}. See locator.properties.example
 * for the keys.
 */
public class NightlyStatsLocatorStart {

    private static final Logger logger = LogManager.getLogger();

    /** Keys with this prefix are passed to the locator as GemFire properties, e.g. gemfire.ssl-protocols */
    private static final String GEMFIRE_PREFIX = "gemfire.";
    private static final Duration STARTUP_RUN_DELAY = Duration.ofSeconds(60);

    private final Properties properties;
    private final String env;
    private final String clusterName;
    private final Path workingDirectory;
    private final Path outputDirectory;
    private final LocalTime runAt;
    /** null when S3_ENDPOINT isn't set */
    private final ReportDelivery delivery;

    public NightlyStatsLocatorStart(Properties properties) {
        this.properties = properties;
        this.env = required("ENV");
        this.clusterName = required("CLUSTER_NAME");
        this.workingDirectory = Paths.get(required("LOG_DIRECTORY"));
        this.outputDirectory = Paths.get(get("NIGHTLY_STATS_DIR", workingDirectory.resolve("nightly-stats").toString()));
        this.runAt = LocalTime.parse(get("NIGHTLY_STATS_TIME", "02:00"));
        this.delivery = get("S3_ENDPOINT", null) == null ? null : buildDelivery();
    }

    /** Uploads to S3_ENDPOINT, with object keys <S3_PREFIX>/<ENV>/<CLUSTER_NAME>/<file name> */
    private ReportDelivery buildDelivery() {
        S3Uploader uploader = new S3Uploader(URI.create(get("S3_ENDPOINT", null)),
                required("S3_BUCKET"),
                get("S3_REGION", "us-east-1"),
                requiredOrEnv("S3_ACCESS_KEY", "AWS_ACCESS_KEY_ID"),
                requiredOrEnv("S3_SECRET_KEY", "AWS_SECRET_ACCESS_KEY"));
        String prefix = get("S3_PREFIX", "nightly-stats").replaceAll("^/+|/+$", "");
        String keyPrefix = (prefix.isEmpty() ? "" : prefix + "/")
                + keySegment("ENV", env) + "/" + keySegment("CLUSTER_NAME", clusterName);
        return new ReportDelivery(outputDirectory, clusterName, keyPrefix, uploader.destination(),
                (key, content) -> uploader.put(key, content, "application/json"));
    }

    /** ENV and CLUSTER_NAME become folders in the object key, so keep them to safe characters */
    private static String keySegment(String key, String value) {
        if (!value.matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
            throw new IllegalArgumentException(key + " must be letters, numbers, dots, underscores or hyphens"
                    + " when S3 upload is on: " + value);
        }
        return value;
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

    public void run() throws IOException {
        Files.createDirectories(workingDirectory);
        LocatorLauncher locatorLauncher = buildLauncher();
        locatorLauncher.start();
        locatorLauncher.waitOnStatusResponse(30L, 5L, TimeUnit.SECONDS);
        System.out.println(locatorLauncher.status());

        scheduleNightlyStats();
        locatorLauncher.waitOnLocator();
    }

    private LocatorLauncher buildLauncher() {
        String port = required("LOCATOR_PORT");
        LocatorLauncher.Builder builder = new LocatorLauncher.Builder()
                .setMemberName(get("MEMBER_NAME", clusterName + "-locator-" + port))
                .setPort(Integer.valueOf(port))
                .setWorkingDirectory(workingDirectory.toString())
                // The collector reads every member's MBeans through this locator's JMX manager
                .set("jmx-manager", "true")
                .set("jmx-manager-start", "true")
                .set("jmx-manager-port", get("JMX_MANAGER_PORT", "1099"))
                .setRedirectOutput(true);
        String bindAddress = get("BIND_ADDR", null);
        if (bindAddress != null) {
            builder.setBindAddress(bindAddress);
        }
        for (String key : properties.stringPropertyNames()) {
            if (key.startsWith(GEMFIRE_PREFIX)) {
                builder.set(key.substring(GEMFIRE_PREFIX.length()), get(key, ""));
            }
        }
        return builder.build();
    }

    private void scheduleNightlyStats() {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "nightly-stats");
            thread.setDaemon(true);
            return thread;
        });
        if (delivery != null) {
            logger.info("Nightly stats reports will also be uploaded to {}", delivery.location());
            // Catch up on reports an earlier run couldn't upload, e.g. while S3 was down
            scheduler.execute(this::uploadPending);
        }
        if (Boolean.parseBoolean(get("NIGHTLY_STATS_RUN_ON_STARTUP", "false"))) {
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
            NightlyStatsCollector collector =
                    new NightlyStatsCollector(ManagementFactory.getPlatformMBeanServer(), env, clusterName);
            String report = Json.write(collector.collect());

            Files.createDirectories(outputDirectory);
            Path file = outputDirectory.resolve(clusterName + "-" + LocalDate.now() + ".json");
            Path temporary = outputDirectory.resolve(file.getFileName() + ".tmp");
            Files.writeString(temporary, report);
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
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
        String value = get(key, null);
        if (value == null) {
            throw new IllegalArgumentException("Missing required property " + key);
        }
        return value;
    }

    /** The property, or else the environment variable, so secrets needn't be in the properties file */
    private String requiredOrEnv(String key, String environmentVariable) {
        String value = get(key, System.getenv(environmentVariable));
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing required property " + key
                    + " (or environment variable " + environmentVariable + ")");
        }
        return value;
    }

    /** Trims the value and strips double quotes, which startup property files often wrap values in. */
    private String get(String key, String defaultValue) {
        String value = properties.getProperty(key);
        if (value == null || value.replace("\"", "").isBlank()) {
            return defaultValue;
        }
        return value.replace("\"", "").trim();
    }
}
