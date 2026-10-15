package com.example.gemfire.stats;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReportStoreTest {
    @TempDir Path directory;

    private Map<String, Object> report() {
        return Map.of("collected_at", "2026-10-16T00:30:00Z", "cluster", Map.of("env", "local", "cluster_name", "test"));
    }

    @Test
    void jobUsesConfiguredTimezoneAndKeepsPreviousReportOnCollectionFailure() throws Exception {
        ReportStore store = new ReportStore(directory, "test");
        ReportJob job = new ReportJob(store, Clock.fixed(Instant.parse("2026-10-16T00:30:00Z"), ZoneId.of("America/Toronto")));
        Path file = job.collect(this::report);
        assertEquals("test-2026-10-15.json", file.getFileName().toString());
        String original = Files.readString(file);
        assertThrows(IOException.class, () -> job.collect(() -> { throw new IOException("JMX disconnected"); }));
        assertEquals(original, Files.readString(file));
        assertEquals(1, store.reports().size());
        try (var files = Files.list(directory)) {
            assertFalse(files.anyMatch(path -> path.toString().endsWith(".tmp")));
        }
    }

    @Test
    void rejectsOverlappingCommandsAndReleasesTheLock() throws Exception {
        ReportStore store = new ReportStore(directory, "test");
        try (var lock = store.lock()) {
            assertThrows(IOException.class, store::lock);
        }
        try (var lock = store.lock()) {
            assertTrue(lock.fileLock().isValid());
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void exportsOnlySelectedReportsWithMatchingChecksums() throws Exception {
        ReportStore store = new ReportStore(directory.resolve("reports"), "test");
        Path selected = store.write(report(), LocalDate.parse("2026-10-15"));
        store.write(report(), LocalDate.parse("2026-10-14"));
        Files.writeString(store.directory.resolve("standalone.properties"), "SECRET=must-not-export");
        Files.createDirectories(store.directory.resolve(".uploaded"));
        Files.writeString(store.directory.resolve(".uploaded/receipt"), "private");
        Path archive = directory.resolve("handoff.zip");
        assertEquals(1, store.export(archive, "local", LocalDate.parse("2026-10-15"), LocalDate.parse("2026-10-15")));
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            assertEquals(2, zip.size());
            byte[] content = zip.getInputStream(zip.getEntry(selected.getFileName().toString())).readAllBytes();
            assertArrayEquals(Files.readAllBytes(selected), content);
            Map<String, Object> manifest = (Map<String, Object>) Json.parse(new String(
                    zip.getInputStream(zip.getEntry("manifest.json")).readAllBytes(), StandardCharsets.UTF_8));
            var entries = (java.util.List<Map<String, Object>>) manifest.get("reports");
            assertEquals("local", manifest.get("env"));
            assertEquals(ReportDelivery.sha256(content), entries.get(0).get("sha256"));
        }
        assertThrows(IOException.class, () -> store.export(archive, "local", LocalDate.MIN, LocalDate.MAX));
    }

    @Test
    void exportRejectsWrongIdentityAndDoesNotLeaveAPartialArchive() throws Exception {
        ReportStore store = new ReportStore(directory.resolve("reports"), "test");
        store.write(report(), LocalDate.parse("2026-10-15"));
        Path archive = directory.resolve("handoff.zip");
        assertThrows(IOException.class, () -> store.export(archive, "production", LocalDate.MIN, LocalDate.MAX));
        assertFalse(Files.exists(archive));
        assertThrows(IllegalArgumentException.class, () -> store.export(
                store.directory.resolve("handoff.zip"), "local", LocalDate.MIN, LocalDate.MAX));
    }

    @Test
    void writeErrorsArePropagated() throws Exception {
        Path blockingFile = Files.writeString(directory.resolve("not-a-directory"), "keep me");
        ReportStore store = new ReportStore(blockingFile, "test");
        assertThrows(IOException.class, () -> store.write(report(), LocalDate.now()));
        assertEquals("keep me", Files.readString(blockingFile));
    }
}
