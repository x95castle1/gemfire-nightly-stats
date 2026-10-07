package com.example.gemfire.stats;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.services.s3.model.S3Exception;

class S3ReportUploaderTest {

    private HttpServer server;
    private HttpHandler handler;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> handler.handle(exchange));
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void sendsSignedJsonWithPathStyleAddressingAndChecksum() throws Exception {
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<byte[]> body = new AtomicReference<>();
        AtomicReference<String> contentType = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> checksum = new AtomicReference<>();
        handler = exchange -> {
            path.set(exchange.getRequestURI().getPath());
            body.set(exchange.getRequestBody().readAllBytes());
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            checksum.set(exchange.getRequestHeaders().getFirst("x-amz-checksum-sha256"));
            respond(exchange, 200);
        };
        byte[] json = "{\"cluster\":\"test\"}".getBytes(StandardCharsets.UTF_8);
        String digest = ReportDelivery.sha256(json);
        try (S3ReportUploader uploader = uploader()) {
            uploader.upload("local/test/2026/10/07/test.json", json, digest);
        }
        assertEquals("/stats/local/test/2026/10/07/test.json", path.get());
        assertArrayEquals(json, body.get());
        assertEquals("application/json", contentType.get());
        assertTrue(authorization.get().startsWith("AWS4-HMAC-SHA256 Credential=test-key/"));
        assertEquals(Base64.getEncoder().encodeToString(HexFormat.of().parseHex(digest)), checksum.get());
    }

    @Test
    void retriesTransientFailuresWithTheSameBytes() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        AtomicReference<byte[]> lastBody = new AtomicReference<>();
        handler = exchange -> {
            lastBody.set(exchange.getRequestBody().readAllBytes());
            respond(exchange, attempts.incrementAndGet() < 3 ? 503 : 200);
        };
        byte[] json = "{}".getBytes(StandardCharsets.UTF_8);
        try (S3ReportUploader uploader = uploader()) {
            uploader.upload("report.json", json, ReportDelivery.sha256(json));
        }
        assertEquals(3, attempts.get());
        assertArrayEquals(json, lastBody.get());
    }

    @Test
    void stopsAfterThreeAttemptsForPersistentFailures() {
        AtomicInteger attempts = new AtomicInteger();
        handler = exchange -> {
            exchange.getRequestBody().readAllBytes();
            attempts.incrementAndGet();
            respond(exchange, 503);
        };
        byte[] json = "{}".getBytes(StandardCharsets.UTF_8);
        try (S3ReportUploader uploader = uploader()) {
            assertThrows(S3Exception.class, () -> uploader.upload("report.json", json, ReportDelivery.sha256(json)));
        }
        assertEquals(3, attempts.get());
    }

    @Test
    void doesNotRetryInvalidCredentials() {
        AtomicInteger attempts = new AtomicInteger();
        handler = exchange -> {
            exchange.getRequestBody().readAllBytes();
            attempts.incrementAndGet();
            respond(exchange, 403);
        };
        byte[] json = "{}".getBytes(StandardCharsets.UTF_8);
        try (S3ReportUploader uploader = uploader()) {
            assertThrows(S3Exception.class, () -> uploader.upload("report.json", json, ReportDelivery.sha256(json)));
        }
        assertEquals(1, attempts.get());
    }

    private S3ReportUploader uploader() {
        Properties properties = new Properties();
        properties.setProperty("NIGHTLY_STATS_S3_ENDPOINT", "http://127.0.0.1:" + server.getAddress().getPort());
        properties.setProperty("NIGHTLY_STATS_S3_BUCKET", "stats");
        return new S3ReportUploader(new StatsProperties(properties),
                StaticCredentialsProvider.create(AwsBasicCredentials.create("test-key", "test-secret")));
    }

    private static void respond(HttpExchange exchange, int status) throws IOException {
        if (status == 200) {
            exchange.sendResponseHeaders(200, -1);
        } else {
            String code = status == 403 ? "AccessDenied" : "SlowDown";
            byte[] error = ("<Error><Code>" + code + "</Code><Message>Test failure</Message></Error>")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/xml");
            exchange.sendResponseHeaders(status, error.length);
            exchange.getResponseBody().write(error);
        }
        exchange.close();
    }
}
