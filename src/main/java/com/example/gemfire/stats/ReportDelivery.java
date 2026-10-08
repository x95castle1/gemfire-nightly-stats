package com.example.gemfire.stats;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Uploads the daily reports in a directory that haven't been uploaded yet. Each successful upload
 * writes a receipt to {@code <directory>/.uploaded/} holding the destination, object key and SHA-256
 * of the file, so a report is uploaded again only when it, or where it goes, changes. A failed upload
 * leaves no receipt, so the next pass retries it.
 */
final class ReportDelivery {

    private static final Logger logger = LogManager.getLogger();

    /** Caps one pass after a long outage. The rest go on the next pass. */
    static final int MAX_UPLOADS_PER_PASS = 32;
    private static final String RECEIPTS_DIRECTORY = ".uploaded";
    /** yyyy-MM-dd.json, after "<CLUSTER_NAME>-" */
    private static final int DATE_AND_EXTENSION_LENGTH = 15;

    @FunctionalInterface
    interface Sink {
        void upload(String key, byte[] content) throws Exception;
    }

    private final Path directory;
    private final String clusterName;
    private final String keyPrefix;
    private final String destination;
    private final Sink sink;

    /**
     * @param keyPrefix the object key before the file name, e.g. nightly-stats/local/test-cluster
     * @param destination where the objects go, e.g. http://localhost:8333/gemfire-stats. Part of each
     *        receipt, so pointing at another store or bucket uploads everything again.
     */
    ReportDelivery(Path directory, String clusterName, String keyPrefix, String destination, Sink sink) {
        this.directory = directory;
        this.clusterName = clusterName;
        this.keyPrefix = keyPrefix;
        this.destination = destination;
        this.sink = sink;
    }

    /** Where reports go, for log messages */
    String location() {
        return destination + "/" + keyPrefix + "/";
    }

    /**
     * Uploads pending reports, newest first, and returns how many it uploaded. Stops at the first
     * failure, so while S3 is down each pass costs one failed request.
     */
    int uploadPending() throws IOException, InterruptedException {
        int uploaded = 0;
        for (Path file : reports()) {
            if (uploaded == MAX_UPLOADS_PER_PASS) {
                break;
            }
            byte[] content = Files.readAllBytes(file);
            String key = keyPrefix + "/" + file.getFileName();
            String receipt = destination + "\n" + key + "\n" + sha256(content) + "\n";
            Path receiptFile = directory.resolve(RECEIPTS_DIRECTORY).resolve(file.getFileName() + ".receipt");
            if (receipt.equals(readReceipt(receiptFile))) {
                continue;
            }
            try {
                sink.upload(key, content);
            } catch (InterruptedException | IOException e) {
                throw e;
            } catch (Exception e) {
                throw new IOException(e.getMessage(), e);
            }
            writeReceipt(receiptFile, receipt);
            logger.info("Nightly stats uploaded {} to {}/{}", file.getFileName(), destination, key);
            uploaded++;
        }
        return uploaded;
    }

    /** This cluster's daily reports, newest first. Skips temporary files, symbolic links and other clusters' reports. */
    List<Path> reports() throws IOException {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (var files = Files.list(directory)) {
            return files.filter(file -> Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
                    .filter(file -> isDailyReport(file.getFileName().toString()))
                    .sorted(Comparator.comparing((Path file) -> file.getFileName().toString()).reversed())
                    .toList();
        }
    }

    /** <CLUSTER_NAME>-<yyyy-MM-dd>.json, with a real date */
    private boolean isDailyReport(String name) {
        String prefix = clusterName + "-";
        if (!name.startsWith(prefix) || !name.endsWith(".json")
                || name.length() != prefix.length() + DATE_AND_EXTENSION_LENGTH) {
            return false;
        }
        try {
            LocalDate.parse(name.substring(prefix.length(), name.length() - ".json".length()));
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    private static String readReceipt(Path file) {
        try {
            return Files.isRegularFile(file) ? Files.readString(file) : null;
        } catch (IOException e) {
            logger.warn("Nightly stats couldn't read upload receipt {}, so it uploads that report again", file);
            return null;
        }
    }

    private static void writeReceipt(Path file, String receipt) throws IOException {
        Files.createDirectories(file.getParent());
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temporary, receipt);
        Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
