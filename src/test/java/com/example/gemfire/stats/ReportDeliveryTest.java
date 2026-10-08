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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReportDeliveryTest {

    private static final String KEY_PREFIX = "nightly-stats/local/test-cluster";

    @TempDir
    Path directory;

    private final List<String> keys = new ArrayList<>();
    private final List<byte[]> contents = new ArrayList<>();

    @Test
    void receiptsSurviveRestartAndSkipUnchangedReports() throws Exception {
        Path report = report("2026-10-07", "{\"value\":1}");
        assertEquals(1, delivery("http://s3/bucket-a", this::capture).uploadPending());
        assertTrue(Files.exists(receipt(report)));
        // A new instance, as after a locator restart
        assertEquals(0, delivery("http://s3/bucket-a", this::capture).uploadPending());
        assertEquals(List.of(KEY_PREFIX + "/test-cluster-2026-10-07.json"), keys);
        assertArrayEquals(Files.readAllBytes(report), contents.get(0));
    }

    @Test
    void aChangedReportReplacesTheSameObject() throws Exception {
        Path report = report("2026-10-07", "{\"value\":1}");
        delivery("http://s3/bucket-a", this::capture).uploadPending();
        Files.writeString(report, "{\"value\":2}");
        assertEquals(1, delivery("http://s3/bucket-a", this::capture).uploadPending());
        assertEquals(keys.get(0), keys.get(1));
        assertArrayEquals(Files.readAllBytes(report), contents.get(1));
    }

    @Test
    void aNewDestinationUploadsAgain() throws Exception {
        report("2026-10-07", "{}");
        delivery("http://s3/bucket-a", this::capture).uploadPending();
        assertEquals(1, delivery("http://s3/bucket-b", this::capture).uploadPending());
    }

    @Test
    void aFailedUploadLeavesNoReceiptAndIsRetried() throws Exception {
        Path report = report("2026-10-07", "{}");
        ReportDelivery failing = delivery("http://s3/bucket-a", (key, content) -> {
            throw new IOException("S3 is down");
        });
        assertThrows(IOException.class, failing::uploadPending);
        assertFalse(Files.exists(receipt(report)));
        assertTrue(Files.exists(report));
        assertEquals(1, delivery("http://s3/bucket-a", this::capture).uploadPending());
    }

    @Test
    void anOutageStopsThePassAfterTheNewestReport() throws Exception {
        report("2026-10-06", "{}");
        report("2026-10-07", "{}");
        ReportDelivery failing = delivery("http://s3/bucket-a", (key, content) -> {
            keys.add(key);
            throw new IOException("S3 is down");
        });
        assertThrows(IOException.class, failing::uploadPending);
        assertEquals(List.of(KEY_PREFIX + "/test-cluster-2026-10-07.json"), keys);
        assertEquals(2, delivery("http://s3/bucket-a", this::capture).uploadPending());
    }

    @Test
    void skipsTemporaryFilesOtherClustersAndBadDates() throws Exception {
        Files.writeString(directory.resolve("test-cluster-2026-10-07.json.tmp"), "partial");
        Files.writeString(directory.resolve("other-cluster-2026-10-07.json"), "{}");
        Files.writeString(directory.resolve("test-cluster-2026-02-30.json"), "{}");
        report("2026-10-07", "{}");
        assertEquals(1, delivery("http://s3/bucket-a", this::capture).uploadPending());
    }

    @Test
    void aBacklogIsSpreadOverPasses() throws Exception {
        for (int day = 0; day <= ReportDelivery.MAX_UPLOADS_PER_PASS; day++) {
            report(LocalDate.of(2026, 1, 1).plusDays(day).toString(), "{}");
        }
        assertEquals(ReportDelivery.MAX_UPLOADS_PER_PASS, delivery("http://s3/bucket-a", this::capture).uploadPending());
        assertEquals(1, delivery("http://s3/bucket-a", this::capture).uploadPending());
    }

    private ReportDelivery delivery(String destination, ReportDelivery.Sink sink) {
        return new ReportDelivery(directory, "test-cluster", KEY_PREFIX, destination, sink);
    }

    private Path report(String date, String content) throws IOException {
        return Files.writeString(directory.resolve("test-cluster-" + date + ".json"), content);
    }

    private Path receipt(Path report) {
        return directory.resolve(".uploaded").resolve(report.getFileName() + ".receipt");
    }

    private void capture(String key, byte[] content) {
        keys.add(key);
        contents.add(content.clone());
    }
}
