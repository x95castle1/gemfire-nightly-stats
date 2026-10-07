package com.example.gemfire.stats;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/** Manually retry pending reports, optionally checking the newest report against the stored bytes. */
public final class UploadReports {

    private UploadReports() {
    }

    public static void main(String... args) throws Exception {
        if (args.length < 1 || args.length > 2 || (args.length == 2 && !args[1].equals("--verify"))) {
            throw new IllegalArgumentException("Usage: UploadReports <locator.properties> [--verify]");
        }
        StatsProperties properties = StatsProperties.load(Path.of(args[0]));
        if (!properties.uploadEnabled()) {
            throw new IllegalArgumentException("Set NIGHTLY_STATS_S3_ENABLED=true to upload reports");
        }
        try (S3ReportUploader uploader = new S3ReportUploader(properties)) {
            ReportDelivery delivery = new ReportDelivery(properties, uploader.destination(), uploader::upload);
            int count = delivery.uploadPending();
            System.out.println("Uploaded " + count + " pending report(s)");
            if (args.length == 2) {
                var reports = delivery.reports();
                if (reports.isEmpty()) {
                    throw new IllegalStateException("No reports to verify in " + properties.outputDirectory());
                }
                Path latest = reports.get(0);
                String key = delivery.objectKey(latest);
                if (!Arrays.equals(Files.readAllBytes(latest), uploader.download(key))) {
                    throw new IllegalStateException("Stored JSON differs from " + latest);
                }
                System.out.println("Verified uploaded bytes match " + latest + " (object " + key + ")");
            }
        }
    }
}
