package com.example.gemfire.stats;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** Durable upload receipts live beside the reports and never contain credentials. */
final class ReportDelivery {

    private static final Logger logger = LogManager.getLogger();
    private static final DateTimeFormatter KEY_DATE = DateTimeFormatter.ofPattern("uuuu/MM/dd");
    private static final int MAX_UPLOADS_PER_RUN = 32;

    @FunctionalInterface
    interface ReportSink {
        void upload(String key, byte[] content, String sha256) throws Exception;
    }

    private final Path directory;
    private final String env;
    private final String clusterName;
    private final String destination;
    private final ReportSink sink;

    ReportDelivery(StatsProperties properties, String destination, ReportSink sink) {
        this.directory = properties.outputDirectory();
        this.env = properties.required("ENV");
        this.clusterName = properties.required("CLUSTER_NAME");
        this.destination = destination;
        this.sink = sink;
        requireSegment(env, "ENV");
        requireSegment(clusterName, "CLUSTER_NAME");
    }

    /** Newest first; stop after a failure so a storage outage costs at most one bounded request. */
    int uploadPending() throws Exception {
        int uploaded = 0;
        for (Path file : reports()) {
            byte[] content = Files.readAllBytes(file);
            String digest = sha256(content);
            String key = objectKey(file);
            String receipt = destination + "\n" + key + "\n" + digest + "\n";
            Path receiptFile = directory.resolve(".uploaded").resolve(file.getFileName() + ".receipt");
            if (receiptMatches(receiptFile, receipt)) {
                continue;
            }
            try {
                sink.upload(key, content, digest);
                writeReceipt(receiptFile, receipt);
            } catch (Exception e) {
                throw new IOException("Nightly stats upload pending for " + file + " (object " + key + ")", e);
            }
            logger.info("Nightly stats uploaded: {} -> {}", file, key);
            if (++uploaded == MAX_UPLOADS_PER_RUN) {
                break;
            }
        }
        return uploaded;
    }

    List<Path> reports() throws IOException {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (var files = Files.list(directory)) {
            return files.filter(file -> Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
                    .filter(file -> reportDate(file) != null)
                    .sorted(Comparator.comparing((Path file) -> file.getFileName().toString()).reversed())
                    .toList();
        }
    }

    String objectKey(Path file) {
        LocalDate date = reportDate(file);
        if (date == null) {
            throw new IllegalArgumentException("Not a daily report for " + clusterName + ": " + file);
        }
        return env + "/" + clusterName + "/" + date.format(KEY_DATE) + "/" + file.getFileName();
    }

    private LocalDate reportDate(Path file) {
        String name = file.getFileName().toString();
        String prefix = clusterName + "-";
        if (!name.startsWith(prefix) || !name.endsWith(".json") || name.length() != prefix.length() + 15) {
            return null;
        }
        try {
            return LocalDate.parse(name.substring(prefix.length(), name.length() - 5));
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private boolean receiptMatches(Path file, String expected) {
        try {
            return Files.isRegularFile(file) && Files.readString(file).equals(expected);
        } catch (IOException e) {
            logger.warn("Nightly stats receipt unreadable; will retry upload: {}", file);
            return false;
        }
    }

    private static void writeReceipt(Path file, String receipt) throws IOException {
        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), "receipt-", ".tmp");
        try {
            Files.writeString(temporary, receipt);
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void requireSegment(String value, String property) {
        if (!value.matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
            throw new IllegalArgumentException(property + " must use letters, numbers, dots, underscores or hyphens");
        }
    }
}
