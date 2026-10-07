package com.example.gemfire.stats;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.nio.charset.StandardCharsets;
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
    private final S3Uploader uploader;
    private final String s3Prefix;

    public NightlyStatsLocatorStart(Properties properties) {
        this.properties = properties;
        this.env = required("ENV");
        this.clusterName = required("CLUSTER_NAME");
        this.workingDirectory = Paths.get(required("LOG_DIRECTORY"));
        this.outputDirectory = Paths.get(get("NIGHTLY_STATS_DIR", workingDirectory.resolve("nightly-stats").toString()));
        this.runAt = LocalTime.parse(get("NIGHTLY_STATS_TIME", "02:00"));
        String s3Endpoint = get("S3_ENDPOINT", null);
        this.uploader = s3Endpoint == null ? null : new S3Uploader(URI.create(s3Endpoint),
                required("S3_BUCKET"),
                get("S3_REGION", "us-east-1"),
                requiredOrEnv("S3_ACCESS_KEY", "AWS_ACCESS_KEY_ID"),
                requiredOrEnv("S3_SECRET_KEY", "AWS_SECRET_ACCESS_KEY"));
        this.s3Prefix = get("S3_PREFIX", "nightly-stats").replaceAll("^/+|/+$", "");
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
        if (uploader != null) {
            logger.info("Nightly stats reports will also be uploaded to {} at {}",
                    uploader.location(objectKey("<file>")), get("S3_ENDPOINT", null));
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
        ZonedDateTime next = now.with(runAt);
        return next.isAfter(now) ? next : next.plusDays(1);
    }

    private void writeReport() {
        Path file;
        byte[] report;
        try {
            NightlyStatsCollector collector =
                    new NightlyStatsCollector(ManagementFactory.getPlatformMBeanServer(), env, clusterName);
            report = Json.write(collector.collect()).getBytes(StandardCharsets.UTF_8);

            Files.createDirectories(outputDirectory);
            file = outputDirectory.resolve(clusterName + "-" + LocalDate.now() + ".json");
            Path temporary = outputDirectory.resolve(file.getFileName() + ".tmp");
            Files.write(temporary, report);
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            logger.info("Nightly stats written to {}", file);
        } catch (Exception e) {
            // Never let a failed collection affect the locator; the next run tries again
            logger.error("Nightly stats collection failed", e);
            return;
        }
        if (uploader != null) {
            upload(file, report);
        }
    }

    /** Uploads a report that's already on disk. A failed upload is logged and leaves the file in place. */
    private void upload(Path file, byte[] report) {
        String key = objectKey(file.getFileName().toString());
        try {
            uploader.put(key, report, "application/json");
            logger.info("Nightly stats uploaded to {}", uploader.location(key));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.error("Nightly stats upload of {} was interrupted", file);
        } catch (Exception e) {
            logger.error("Nightly stats upload of {} to {} failed. The report is still on disk.",
                    file, uploader.location(key), e);
        }
    }

    /** <S3_PREFIX>/<ENV>/<CLUSTER_NAME>/<file name>, so clusters sharing a bucket stay apart */
    private String objectKey(String fileName) {
        String key = env + "/" + clusterName + "/" + fileName;
        return s3Prefix.isEmpty() ? key : s3Prefix + "/" + key;
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
