package com.example.gemfire.stats.embedded;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Individual reports on disk. Files and receipts are kept until an operator removes them. */
public final class ReportStore {
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter
            .ofPattern("uuuu-MM-dd'T'HHmmss.SSSxx", Locale.ROOT).withResolverStyle(ResolverStyle.STRICT);

    final Path directory;
    final String cluster;

    public Path directory() {
        return directory;
    }

    public String cluster() {
        return cluster;
    }

    public ReportStore(Path directory, String cluster) {
        this.directory = directory;
        this.cluster = cluster;
    }

    public Path write(Map<String, Object> report, ZonedDateTime timestamp) throws IOException {
        Files.createDirectories(directory);
        Path file = directory.resolve(cluster + "-" + TIMESTAMP.format(timestamp) + "-" + UUID.randomUUID() + ".json");
        Path temporary = Files.createTempFile(directory, ".report-", ".tmp");
        try {
            Files.writeString(temporary, Json.write(report));
            // Publish the completed file under a new name. Never replace an existing report.
            Files.move(temporary, file);
        } finally {
            Files.deleteIfExists(temporary);
        }
        return file;
    }

    public List<Path> reports() throws IOException {
        if (!Files.exists(directory)) return List.of();
        try (var files = Files.list(directory)) {
            return files.filter(file -> Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
                    .filter(file -> date(file) != null)
                    .sorted(Comparator.comparing((Path file) -> timestamp(file).toInstant())
                            .thenComparing(file -> file.getFileName().toString()).reversed())
                    .toList();
        }
    }

    public LocalDate date(Path file) {
        OffsetDateTime timestamp = timestamp(file);
        return timestamp == null ? null : timestamp.toLocalDate();
    }

    private OffsetDateTime timestamp(Path file) {
        String name = file.getFileName().toString();
        String prefix = cluster + "-";
        if (!name.startsWith(prefix) || !name.endsWith(".json")) return null;
        String suffix = name.substring(prefix.length(), name.length() - 5);
        try {
            // Keep existing daily reports available for status, exports and pending uploads.
            if (suffix.length() == 10) return LocalDate.parse(suffix).atStartOfDay().atOffset(ZoneOffset.UTC);
            int separator = suffix.length() - 37; // '-' followed by a canonical UUID
            if (separator <= 0 || suffix.charAt(separator) != '-') return null;
            String id = suffix.substring(separator + 1);
            if (!UUID.fromString(id).toString().equals(id)) return null;
            return OffsetDateTime.parse(suffix.substring(0, separator), TIMESTAMP);
        } catch (DateTimeParseException | IllegalArgumentException e) {
            return null;
        }
    }

    /** Held for the whole command, including uploads, so another process cannot collect or upload concurrently. */
    public Lock lock() throws IOException {
        Files.createDirectories(directory);
        FileChannel channel = FileChannel.open(directory.resolve(".collector.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            FileLock lock = channel.tryLock();
            if (lock == null) throw new IOException("Another collector command is using " + directory);
            return new Lock(channel, lock);
        } catch (IOException | OverlappingFileLockException e) {
            channel.close();
            throw new IOException("Cannot lock " + directory + ": another command may be running", e);
        }
    }

    public record Lock(FileChannel channel, FileLock fileLock) implements AutoCloseable {
        @Override
        public void close() throws IOException {
            try {
                fileLock.release();
            } finally {
                channel.close();
            }
        }
    }

    static void verifyIdentity(byte[] content, String env, String cluster) throws IOException {
        try {
            Object parsed = Json.parse(new String(content, java.nio.charset.StandardCharsets.UTF_8));
            if (parsed instanceof Map<?, ?> report && report.get("cluster") instanceof Map<?, ?> labels
                    && env.equals(labels.get("env")) && cluster.equals(labels.get("cluster_name"))) return;
        } catch (RuntimeException e) {
            throw new IOException("Report is not valid JSON", e);
        }
        throw new IOException("Report identity does not match ENV=" + env + ", CLUSTER_NAME=" + cluster);
    }

    /** ZIP contains only reports and a SHA-256 manifest, never settings, locks or credentials. */
    public int export(Path output, String env, LocalDate from, LocalDate to) throws IOException {
        List<Path> selected = reports().stream()
                .filter(file -> !date(file).isBefore(from) && !date(file).isAfter(to)).toList();
        if (selected.isEmpty()) throw new IOException("No reports in the selected date range");
        Path target = output.toAbsolutePath().normalize();
        if (Files.exists(target)) throw new IOException("Export already exists: " + target);
        // An export must not masquerade as a report or replace a lock/receipt.
        if (target.startsWith(directory.toAbsolutePath().normalize())) {
            throw new IllegalArgumentException("Export output must be outside the report directory");
        }
        Path temporary = Files.createTempFile(target.getParent(), ".stats-export-", ".tmp");
        try {
            List<Map<String, Object>> entries = new ArrayList<>();
            try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(temporary))) {
                for (Path file : selected) {
                    byte[] content = Files.readAllBytes(file);
                    verifyIdentity(content, env, cluster);
                    String name = file.getFileName().toString();
                    zip.putNextEntry(new ZipEntry(name));
                    zip.write(content);
                    zip.closeEntry();
                    entries.add(Map.of("file", name, "bytes", content.length, "sha256", ReportDelivery.sha256(content)));
                }
                Map<String, Object> manifest = new LinkedHashMap<>();
                manifest.put("env", env);
                manifest.put("cluster_name", cluster);
                manifest.put("reports", entries);
                zip.putNextEntry(new ZipEntry("manifest.json"));
                zip.write(Json.write(manifest).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                zip.closeEntry();
            }
            Files.move(temporary, target);
        } finally {
            Files.deleteIfExists(temporary);
        }
        return selected.size();
    }
}
