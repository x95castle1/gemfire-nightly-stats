package com.example.gemfire.stats.standalone;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NightlyStatsMainTest {
    @TempDir Path directory;

    private Properties settings() {
        Properties p = new Properties();
        p.setProperty("ENV", "local");
        p.setProperty("CLUSTER_NAME", "test");
        p.setProperty("REPORT_DIR", directory.resolve("reports").toString());
        p.setProperty("JMX_HOST", "localhost");
        return p;
    }

    private Map<String, Object> report(StatsSettings ignored) {
        return Map.of("collected_at", "2026-10-15T12:00:00Z", "cluster", Map.of("env", "local", "cluster_name", "test"));
    }

    private ReportStore store() {
        return new ReportStore(directory.resolve("reports/local/test"), "test");
    }

    private record Result(int code, String output, String error) {
        @SuppressWarnings("unchecked")
        Map<String, Object> summary() { return (Map<String, Object>) Json.parse(output); }
    }

    private Result run(String command, Properties settings, NightlyStatsMain.Source source, String... extra) throws Exception {
        Path config = directory.resolve("settings.properties");
        try (var output = Files.newOutputStream(config)) { settings.store(output, null); }
        List<String> args = new ArrayList<>(List.of(command, "--config", config.toString()));
        args.addAll(List.of(extra));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = NightlyStatsMain.run(args.toArray(String[]::new), new PrintStream(out), new PrintStream(err), source);
        return new Result(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    @Test
    void localCollectionIgnoresS3SettingsAndProducesAReport() throws Exception {
        Properties p = settings();
        p.setProperty("DELIVERY_MODE", "local");
        p.setProperty("S3_ENDPOINT", "not even a URI");
        Result result = run("collect", p, this::report);
        assertEquals(0, result.code(), result.error());
        assertEquals(Boolean.TRUE, result.summary().get("collection_succeeded"));
        assertNull(result.summary().get("pending_uploads"));
        assertEquals(1, store().reports().size());
        assertEquals(report(null), Json.parse(Files.readString(store().reports().get(0))));
    }

    @Test
    void offlineCommandsDoNotNeedJmxOrS3Credentials() throws Exception {
        Properties p = settings();
        p.remove("JMX_HOST");
        NightlyStatsMain.Source unused = ignored -> { throw new AssertionError("Offline command connected to JMX"); };
        store().write(report(null), LocalDate.parse("2026-10-15").atStartOfDay(ZoneOffset.UTC));
        Result status = run("status", p, unused);
        assertEquals(0, status.code(), status.error());
        assertEquals(1L, status.summary().get("saved_reports"));
        Result export = run("export", p, unused, "--output", directory.resolve("bundle.zip").toString());
        assertEquals(0, export.code(), export.error());
        p.setProperty("DELIVERY_MODE", "s3");
        p.setProperty("S3_ENDPOINT", "http://127.0.0.1:1");
        p.setProperty("S3_BUCKET", "bucket");
        Result pending = run("status", p, unused);
        assertEquals(0, pending.code(), pending.error());
        assertEquals(1L, pending.summary().get("pending_uploads"));
    }

    @Test
    void outageIsDeferredAndOfflineBackfillUsesReceipts() throws Exception {
        AtomicInteger response = new AtomicInteger(503);
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(response.get(), -1);
            exchange.close();
        });
        server.start();
        try {
            Properties p = s3Settings(server.getAddress().getPort());
            Result deferred = run("collect", p, this::report);
            assertEquals(0, deferred.code(), deferred.error());
            assertEquals(1L, deferred.summary().get("pending_uploads"));
            assertTrue(deferred.error().contains("Upload deferred"));
            p.setProperty("UPLOAD_REQUIRED", "true");
            assertEquals(4, run("collect", p, this::report).code());
            assertEquals(2, store().reports().size());
            response.set(200);
            p.remove("JMX_HOST");
            NightlyStatsMain.Source unused = ignored -> { throw new AssertionError("Backfill connected to JMX"); };
            Result uploaded = run("upload-pending", p, unused);
            assertEquals(0, uploaded.code(), uploaded.error());
            assertEquals(0L, uploaded.summary().get("pending_uploads"));
            int before = requests.get();
            assertEquals(0, run("upload-pending", p, unused).code());
            assertEquals(before, requests.get());
        } finally { server.stop(0); }
    }

    @Test
    void backfillStillRunsWhenCollectionFails() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        try {
            store().write(report(null), LocalDate.parse("2026-10-14").atStartOfDay(ZoneOffset.UTC));
            Result result = run("collect", s3Settings(server.getAddress().getPort()), ignored -> {
                throw new IOException("Cluster unreachable");
            });
            assertEquals(3, result.code(), result.error());
            assertEquals(1L, result.summary().get("uploaded"));
            assertEquals(0L, result.summary().get("pending_uploads"));
        } finally { server.stop(0); }
    }

    @Test
    void invalidConfigurationFailsBeforeCollection() throws Exception {
        Properties p = settings();
        p.setProperty("ENV", "../outside");
        assertEquals(2, run("collect", p, ignored -> { throw new AssertionError(); }).code());
        p = settings();
        p.setProperty("DELIVERY_MODE", "typo");
        assertEquals(2, run("collect", p, this::report).code());
        p = settings();
        p.setProperty("UPLOAD_REQUIRED", "true");
        assertEquals(2, run("collect", p, this::report).code());
        p = settings();
        p.setProperty("DELIVERY_MODE", "s3");
        assertEquals(2, run("collect", p, this::report).code());
        assertEquals(2, run("status", settings(), this::report, "--from", "2026-10-15").code());
        assertEquals(2, run("export", settings(), this::report, "--output", directory.resolve("x.zip").toString(),
                "--from", "2026-10-16", "--to", "2026-10-15").code());
    }

    @Test
    void storageFailureReturnsNonzero() throws Exception {
        Properties p = settings();
        Path file = Files.writeString(directory.resolve("blocking-file"), "do not replace");
        p.setProperty("REPORT_DIR", file.toString());
        assertEquals(3, run("collect", p, this::report).code());
        assertEquals("do not replace", Files.readString(file));
    }

    private Properties s3Settings(int port) {
        Properties p = settings();
        p.setProperty("DELIVERY_MODE", "s3");
        p.setProperty("S3_ENDPOINT", "http://127.0.0.1:" + port);
        p.setProperty("S3_BUCKET", "bucket");
        p.setProperty("S3_ACCESS_KEY", "key");
        p.setProperty("S3_SECRET_KEY", "secret");
        return p;
    }

    @Test
    void partialUploadFailureReportsCompletedUploadsAndRemainingBacklog() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(attempts.incrementAndGet() == 1 ? 200 : 503, -1);
            exchange.close();
        });
        server.start();
        try {
            for (int day = 13; day <= 15; day++) store().write(report(null), LocalDate.of(2026, 10, day).atStartOfDay(ZoneOffset.UTC));
            Result result = run("upload-pending", s3Settings(server.getAddress().getPort()), this::report);
            assertEquals(4, result.code(), result.error());
            assertEquals(1L, result.summary().get("uploaded"));
            assertEquals(2L, result.summary().get("pending_uploads"));
            assertEquals("2026-10-13", result.summary().get("oldest_pending_date"));
            assertEquals(2, attempts.get());
        } finally { server.stop(0); }
    }

    @Test
    void requiredDeliveryReportsABoundedPassAsIncompleteUntilBacklogIsDrained() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        try {
            for (int day = 0; day <= ReportDelivery.MAX_UPLOADS_PER_PASS; day++) {
                store().write(report(null), LocalDate.of(2026, 1, 1).plusDays(day).atStartOfDay(ZoneOffset.UTC));
            }
            Properties p = s3Settings(server.getAddress().getPort());
            p.setProperty("UPLOAD_REQUIRED", "true");
            Result first = run("upload-pending", p, this::report);
            assertEquals(4, first.code(), first.error());
            assertEquals(1L, first.summary().get("pending_uploads"));
            assertEquals(32L, first.summary().get("uploaded"));
            Result second = run("upload-pending", p, this::report);
            assertEquals(0, second.code(), second.error());
            assertEquals(1L, second.summary().get("uploaded"));
        } finally { server.stop(0); }
    }

    @Test
    void refusedConnectionStillProducesAnUploadErrorAndPreservesTheReport() throws Exception {
        int port;
        try (ServerSocket unused = new ServerSocket(0)) { port = unused.getLocalPort(); }
        Result result = run("collect", s3Settings(port), this::report);
        assertEquals(0, result.code(), result.error());
        assertEquals(Boolean.TRUE, result.summary().get("collection_succeeded"));
        assertEquals(1L, result.summary().get("pending_uploads"));
        String error = assertInstanceOf(String.class, result.summary().get("upload_error"));
        assertFalse(error.isBlank());
        assertTrue(result.error().contains(error), result.error());
        assertEquals(1, store().reports().size());
    }
}
