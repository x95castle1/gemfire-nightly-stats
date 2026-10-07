package com.example.gemfire.stats;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReportDeliveryTest {

    @TempDir
    Path directory;

    private final List<String> keys = new ArrayList<>();
    private final List<byte[]> contents = new ArrayList<>();

    @Test
    void receiptsSurviveRestartAndSkipIdenticalContent() throws Exception {
        Path report = report("2026-10-07", "{\"value\":1}");
        assertEquals(1, delivery("bucket-a", this::capture).uploadPending());
        assertTrue(Files.exists(receipt(report)));
        assertEquals(0, delivery("bucket-a", this::capture).uploadPending());
        assertEquals(List.of("local/test-cluster/2026/10/07/test-cluster-2026-10-07.json"), keys);
        assertArrayEquals(Files.readAllBytes(report), contents.get(0));
    }

    @Test
    void changedContentReplacesTheSameDaysObject() throws Exception {
        Path report = report("2026-10-07", "{\"value\":1}");
        delivery("bucket-a", this::capture).uploadPending();
        Files.writeString(report, "{\"value\":2}");
        assertEquals(1, delivery("bucket-a", this::capture).uploadPending());
        assertEquals(keys.get(0), keys.get(1));
        assertArrayEquals(Files.readAllBytes(report), contents.get(1));
    }

    @Test
    void changingDestinationUploadsAgain() throws Exception {
        report("2026-10-07", "{}");
        delivery("bucket-a", this::capture).uploadPending();
        assertEquals(1, delivery("bucket-b", this::capture).uploadPending());
    }

    @Test
    void failureLeavesNoReceiptAndRetriesAfterRestart() throws Exception {
        Path report = report("2026-10-07", "{}");
        var failed = delivery("bucket-a", (key, bytes, digest) -> { throw new IOException("Storage offline"); });
        assertThrows(IOException.class, failed::uploadPending);
        assertFalse(Files.exists(receipt(report)));
        assertTrue(Files.exists(report));
        assertEquals(1, delivery("bucket-a", this::capture).uploadPending());
    }

    @Test
    void storageOutageStopsTheBatchAfterTheNewestReport() throws Exception {
        report("2026-10-06", "{}");
        report("2026-10-07", "{}");
        var failed = delivery("bucket-a", (key, bytes, digest) -> {
            keys.add(key);
            throw new IOException("Storage offline");
        });
        assertThrows(IOException.class, failed::uploadPending);
        assertEquals(List.of("local/test-cluster/2026/10/07/test-cluster-2026-10-07.json"), keys);
        assertEquals(2, delivery("bucket-a", this::capture).uploadPending());
    }

    @Test
    void ignoresTemporaryFilesOtherClustersAndInvalidDates() throws Exception {
        Files.writeString(directory.resolve("test-cluster-2026-10-07.json.tmp"), "partial");
        Files.writeString(directory.resolve("other-cluster-2026-10-07.json"), "{}");
        Files.writeString(directory.resolve("test-cluster-2026-02-30.json"), "{}");
        report("2026-10-07", "{}");
        assertEquals(1, delivery("bucket-a", this::capture).uploadPending());
    }

    @Test
    void boundsBacklogWorkAndResumesOnTheNextPass() throws Exception {
        for (int day = 0; day < 33; day++) {
            report(LocalDate.of(2026, 1, 1).plusDays(day).toString(), "{}");
        }
        assertEquals(32, delivery("bucket-a", this::capture).uploadPending());
        assertEquals(1, delivery("bucket-a", this::capture).uploadPending());
    }

    private ReportDelivery delivery(String destination, ReportDelivery.ReportSink sink) {
        Properties properties = new Properties();
        properties.setProperty("ENV", "local");
        properties.setProperty("CLUSTER_NAME", "test-cluster");
        properties.setProperty("LOG_DIRECTORY", directory.toString());
        properties.setProperty("NIGHTLY_STATS_DIR", directory.toString());
        return new ReportDelivery(new StatsProperties(properties), destination, sink);
    }

    private Path report(String date, String content) throws IOException {
        return Files.writeString(directory.resolve("test-cluster-" + date + ".json"), content);
    }

    private Path receipt(Path report) {
        return directory.resolve(".uploaded").resolve(report.getFileName() + ".receipt");
    }

    private void capture(String key, byte[] content, String digest) {
        keys.add(key);
        contents.add(content.clone());
        assertEquals(ReportDelivery.sha256(content), digest);
    }
}
