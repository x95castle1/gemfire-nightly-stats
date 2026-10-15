package com.example.gemfire.stats;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Daily reports on disk. Files and receipts are kept until an operator removes them. */
final class ReportStore {
    final Path directory;
    final String cluster;

    ReportStore(Path directory, String cluster) {
        this.directory = directory;
        this.cluster = cluster;
    }

    Path write(Map<String, Object> report, LocalDate date) throws IOException {
        Files.createDirectories(directory);
        Path file = directory.resolve(cluster + "-" + date + ".json");
        Path temporary = Files.createTempFile(directory, ".report-", ".tmp");
        try {
            Files.writeString(temporary, Json.write(report));
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temporary);
        }
        return file;
    }

    List<Path> reports() throws IOException {
        if (!Files.exists(directory)) return List.of();
        try (var files = Files.list(directory)) {
            return files.filter(file -> Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
                    .filter(file -> date(file) != null)
                    .sorted(Comparator.comparing((Path file) -> file.getFileName().toString()).reversed())
                    .toList();
        }
    }

    LocalDate date(Path file) {
        String name = file.getFileName().toString();
        String prefix = cluster + "-";
        if (!name.startsWith(prefix) || !name.endsWith(".json") || name.length() != prefix.length() + 15) return null;
        try {
            return LocalDate.parse(name.substring(prefix.length(), name.length() - 5));
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** Held for the whole command, including uploads, so another process cannot replace its reports. */
    Lock lock() throws IOException {
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

    record Lock(FileChannel channel, FileLock fileLock) implements AutoCloseable {
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

    /** ZIP contains only daily reports and a SHA-256 manifest, never settings, locks or credentials. */
    int export(Path output, String env, LocalDate from, LocalDate to) throws IOException {
        List<Path> selected = reports().stream()
                .filter(file -> !date(file).isBefore(from) && !date(file).isAfter(to)).toList();
        if (selected.isEmpty()) throw new IOException("No reports in the selected date range");
        Path target = output.toAbsolutePath().normalize();
        if (Files.exists(target)) throw new IOException("Export already exists: " + target);
        // An export must not masquerade as a daily report or replace a lock/receipt.
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
