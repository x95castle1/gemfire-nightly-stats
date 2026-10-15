package com.example.gemfire.stats;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class S3UploaderTest {

    // AWS's published SigV4 example: "Example: PUT Object" in "Signature Calculations for the
    // Authorization Header: Transferring Payload in a Single Chunk" (Amazon S3 API reference)
    private static final String EXAMPLE_ACCESS_KEY = "AKIAIOSFODNN7EXAMPLE";
    private static final String EXAMPLE_SECRET_KEY = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY";

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void signsTheAwsPutObjectExample() {
        S3Uploader uploader = new S3Uploader(URI.create("https://examplebucket.s3.amazonaws.com"),
                "examplebucket", "us-east-1", EXAMPLE_ACCESS_KEY, EXAMPLE_SECRET_KEY);
        String payloadHash = "44ce7dd67c959e0d3524ffac1771dfbba87d2b6b4b4e99e42034a8b803f8b072";
        Map<String, String> headers = new TreeMap<>();
        headers.put("date", "Fri, 24 May 2013 00:00:00 GMT");
        headers.put("host", "examplebucket.s3.amazonaws.com");
        headers.put("x-amz-content-sha256", payloadHash);
        headers.put("x-amz-date", "20130524T000000Z");
        headers.put("x-amz-storage-class", "REDUCED_REDUNDANCY");

        String authorization = uploader.authorization("PUT", "/" + S3Uploader.encodePath("test$file.text"),
                headers, payloadHash, "20130524T000000Z");

        assertEquals("AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/20130524/us-east-1/s3/aws4_request, "
                + "SignedHeaders=date;host;x-amz-content-sha256;x-amz-date;x-amz-storage-class, "
                + "Signature=98ad721746da40c64f1a55b78f14c238d841ea1380cd77a1b5971af0ece108bd", authorization);
    }

    @Test
    void encodesKeysForTheCanonicalUri() {
        assertEquals("nightly-stats/local/test-cluster/test-cluster-2026-10-07.json",
                S3Uploader.encodePath("nightly-stats/local/test-cluster/test-cluster-2026-10-07.json"));
        assertEquals("a%20b/c%2Bd/%C3%A9~_.", S3Uploader.encodePath("a b/c+d/é~_."));
    }

    @Test
    void dropsDefaultPortsSoTheSignedHostMatches() {
        assertEquals("example.com", S3Uploader.hostHeader(S3Uploader.withoutDefaultPort(URI.create("http://example.com:80"))));
        assertEquals("example.com", S3Uploader.hostHeader(S3Uploader.withoutDefaultPort(URI.create("https://example.com:443"))));
        assertEquals("localhost:8333", S3Uploader.hostHeader(S3Uploader.withoutDefaultPort(URI.create("http://localhost:8333/"))));
    }

    @Test
    void putsTheObjectPathStyleWithSignedHeaders() throws Exception {
        AtomicReference<String> method = new AtomicReference<>();
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<String> host = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> contentType = new AtomicReference<>();
        AtomicReference<String> payloadHash = new AtomicReference<>();
        AtomicReference<byte[]> body = new AtomicReference<>();
        URI endpoint = startServer(200, "", exchange -> {
            method.set(exchange.getRequestMethod());
            path.set(exchange.getRequestURI().getRawPath());
            host.set(exchange.getRequestHeaders().getFirst("Host"));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            payloadHash.set(exchange.getRequestHeaders().getFirst("x-amz-content-sha256"));
            body.set(exchange.getRequestBody().readAllBytes());
        });
        Clock clock = Clock.fixed(Instant.parse("2026-10-07T02:00:00Z"), ZoneOffset.UTC);
        S3Uploader uploader = new S3Uploader(endpoint, "gemfire-stats", "us-east-1", "key", "secret", clock);
        byte[] report = "{\"a\": 1}\n".getBytes(StandardCharsets.UTF_8);

        uploader.put("nightly-stats/local/c/c-2026-10-07.json", report, "application/json");

        assertEquals("PUT", method.get());
        assertEquals("/gemfire-stats/nightly-stats/local/c/c-2026-10-07.json", path.get());
        // HttpClient must send the same Host value that was signed
        assertEquals(S3Uploader.hostHeader(endpoint), host.get());
        assertEquals("application/json", contentType.get());
        assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(report)), payloadHash.get());
        assertTrue(authorization.get().startsWith("AWS4-HMAC-SHA256 Credential=key/20261007/us-east-1/s3/aws4_request, "
                + "SignedHeaders=content-type;host;x-amz-content-sha256;x-amz-date, Signature="), authorization.get());
        assertArrayEquals(report, body.get());
    }

    @Test
    void failsOnAnErrorResponse() throws Exception {
        URI endpoint = startServer(403, "<Error><Code>SignatureDoesNotMatch</Code></Error>", exchange -> { });
        S3Uploader uploader = new S3Uploader(endpoint, "gemfire-stats", "us-east-1", "key", "wrong");

        IOException e = assertThrows(IOException.class,
                () -> uploader.put("x.json", new byte[0], "application/json"));

        assertTrue(e.getMessage().contains("HTTP 403"), e.getMessage());
        assertTrue(e.getMessage().contains("SignatureDoesNotMatch"), e.getMessage());
    }

    @Test
    void rejectsANonHttpEndpoint() {
        assertThrows(IllegalArgumentException.class,
                () -> new S3Uploader(URI.create("localhost:8333"), "b", "us-east-1", "key", "secret"));
    }

    private interface RequestCheck {
        void accept(com.sun.net.httpserver.HttpExchange exchange) throws IOException;
    }

    /** Starts a server on a free port that records each request, then answers with status and body */
    private URI startServer(int status, String responseBody, RequestCheck check) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            check.accept(exchange);
            byte[] response = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, response.length == 0 ? -1 : response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }
}
