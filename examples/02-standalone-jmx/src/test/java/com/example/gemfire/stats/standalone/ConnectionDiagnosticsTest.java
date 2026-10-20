package com.example.gemfire.stats.standalone;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConnectionDiagnosticsTest {
    @TempDir Path directory;

    private Properties settings() {
        Properties p = new Properties();
        p.setProperty("ENV", "demo");
        p.setProperty("CLUSTER_NAME", "cluster");
        p.setProperty("JMX_HOST", "localhost");
        p.setProperty("REPORT_DIR", directory.resolve("reports").toString());
        return p;
    }

    private Properties s3Settings(int port) {
        Properties p = settings();
        p.setProperty("DELIVERY_MODE", "s3");
        p.setProperty("S3_ENDPOINT", "http://127.0.0.1:" + port);
        p.setProperty("S3_BUCKET", "bucket");
        p.setProperty("S3_ACCESS_KEY", "diagnostic-key");
        p.setProperty("S3_SECRET_KEY", "sensitive-diagnostic-secret");
        return p;
    }

    private record Result(int code, Map<String, Object> json, String error) {
        @SuppressWarnings("unchecked")
        Map<String, Object> check(String name) {
            return ((List<Map<String, Object>>) json.get("checks")).stream()
                    .filter(row -> row.get("name").equals(name)).findFirst().orElseThrow();
        }
        String status(String name) { return check(name).get("status").toString(); }
    }

    private Map<String, Object> snapshot(StatsSettings ignored) {
        return Map.of("members", List.of(Map.of("tls", Map.of("ssl_protocols", "any"))));
    }

    @SuppressWarnings("unchecked")
    private Result run(Properties properties, NightlyStatsMain.Source source, String... flags) throws Exception {
        Path config = directory.resolve("check.properties");
        try (var output = Files.newOutputStream(config)) { properties.store(output, null); }
        List<String> args = new ArrayList<>(List.of("check", "--config", config.toString()));
        args.addAll(List.of(flags));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = NightlyStatsMain.run(args.toArray(String[]::new), new PrintStream(out), new PrintStream(err), source);
        return new Result(code, out.size() == 0 ? Map.of() : (Map<String, Object>) Json.parse(out.toString()), err.toString());
    }

    @Test
    void localCheckDiscardsSnapshotAndRemovesItsTemporaryDirectoryTree() throws Exception {
        Properties p = settings();
        p.setProperty("S3_ENDPOINT", "invalid and ignored in local mode");
        Result result = run(p, this::snapshot);
        assertEquals(0, result.code(), result.error());
        assertEquals("PASS", result.json().get("status"));
        assertEquals("PASS", result.status("report_directory"));
        assertEquals("PASS", result.status("jmx"));
        assertEquals("SKIPPED", result.status("s3_connectivity"));
        assertFalse(Files.exists(directory.resolve("reports")));
        assertTrue(result.error().contains("PASS jmx:"));
    }

    @Test
    void existingReportsReceiptsLocksAndHealthStateAreUntouched() throws Exception {
        Path reports = directory.resolve("reports/demo/cluster");
        Files.createDirectories(reports.resolve(".uploaded"));
        List<Path> files = List.of(reports.resolve("saved.json"), reports.resolve(".uploaded/saved.receipt"),
                reports.resolve(".collector-state.json"), reports.resolve(".collector.lock"));
        for (Path file : files) Files.writeString(file, "preserve me");
        try (var lock = java.nio.channels.FileChannel.open(reports.resolve(".collector.lock"), java.nio.file.StandardOpenOption.WRITE);
             var ignored = lock.lock()) {
            assertEquals(0, run(settings(), this::snapshot).code());
        }
        for (Path file : files) assertEquals("preserve me", Files.readString(file));
        try (var entries = Files.list(reports)) { assertEquals(4, entries.count()); }
    }

    @Test
    void unsignedHeadReachingForbiddenEndpointDoesNotClaimUploadAuthorization() throws Exception {
        List<String> methods = new ArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            methods.add(exchange.getRequestMethod());
            assertEquals("/bucket", exchange.getRequestURI().getPath());
            assertNull(exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.sendResponseHeaders(403, -1);
            exchange.close();
        });
        server.start();
        try {
            Result result = run(s3Settings(server.getAddress().getPort()), this::snapshot);
            assertEquals(0, result.code(), result.error());
            assertEquals(List.of("HEAD"), methods);
            assertEquals("PASS", result.status("s3_connectivity"));
            assertEquals("WARN", result.status("s3_tls"));
            assertEquals("SKIPPED", result.status("s3_upload"));
            assertFalse(Files.exists(directory.resolve("reports")));
        } finally { server.stop(0); }
    }

    @Test
    void explicitUploadsUseUniqueDiagnosticKeysAndCreateNoLocalState() throws Exception {
        List<String> keys = new ArrayList<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            if (exchange.getRequestMethod().equals("PUT")) {
                keys.add(exchange.getRequestURI().getPath());
                authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                assertEquals("text/plain", exchange.getRequestHeaders().getFirst("Content-Type"));
                assertTrue(new String(exchange.getRequestBody().readAllBytes()).startsWith("GemFire collector connection check"));
            }
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        try {
            Properties p = s3Settings(server.getAddress().getPort());
            for (int i = 0; i < 2; i++) {
                Result result = run(p, this::snapshot, "--test-upload");
                assertEquals(0, result.code(), result.error());
                assertEquals("PASS", result.status("s3_upload"));
                assertEquals(keys.get(i), "/bucket/" + result.check("s3_upload").get("object_key"));
            }
            assertEquals(2, keys.stream().distinct().count());
            assertTrue(keys.get(0).startsWith("/bucket/nightly-stats/demo/cluster/_diagnostics/check-"));
            assertTrue(authorization.get().startsWith("AWS4-HMAC-SHA256 Credential=diagnostic-key/"));
            assertFalse(Files.exists(directory.resolve("reports")));
        } finally { server.stop(0); }
    }

    @Test
    void failedUploadReportsStatusWithoutLeakingServerResponseOrCredentials() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            if (exchange.getRequestMethod().equals("PUT")) {
                exchange.getRequestBody().readAllBytes();
                byte[] body = "sensitive-diagnostic-secret secret-server-response".getBytes();
                exchange.sendResponseHeaders(403, body.length);
                exchange.getResponseBody().write(body);
            } else exchange.sendResponseHeaders(403, -1);
            exchange.close();
        });
        server.start();
        try {
            Result result = run(s3Settings(server.getAddress().getPort()), this::snapshot, "--test-upload");
            assertEquals(4, result.code(), result.error());
            assertEquals("FAIL", result.status("s3_upload"));
            assertTrue(result.error().contains("HTTP 403"));
            assertNotNull(result.check("s3_upload").get("object_key"));
            assertFalse((result.error() + result.json()).contains("sensitive-diagnostic-secret"));
            assertFalse((result.error() + result.json()).contains("secret-server-response"));
        } finally { server.stop(0); }
    }

    @Test
    void independentChecksContinueAfterBadJmxConfigurationAndStorageFailure() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> { requests.incrementAndGet(); exchange.sendResponseHeaders(200, -1); exchange.close(); });
        server.start();
        try {
            Properties p = s3Settings(server.getAddress().getPort());
            p.setProperty("JMX_PORT", "70000");
            Path blocker = Files.writeString(directory.resolve("blocker"), "preserve");
            p.setProperty("REPORT_DIR", blocker.toString());
            Result result = run(p, ignored -> { throw new AssertionError("Invalid JMX must not connect"); });
            assertEquals(2, result.code(), result.error());
            assertEquals("FAIL", result.status("report_directory"));
            assertEquals("FAIL", result.status("jmx_configuration"));
            assertEquals("SKIPPED", result.status("jmx"));
            assertEquals("PASS", result.status("s3_connectivity"));
            assertEquals(1, requests.get());
            assertEquals("preserve", Files.readString(blocker));
        } finally { server.stop(0); }
    }

    @Test
    void invalidGlobalConfigAndLocalTestUploadFailWithoutConnecting() throws Exception {
        Properties p = settings();
        p.setProperty("ENV", "../bad");
        NightlyStatsMain.Source unused = ignored -> { throw new AssertionError("Invalid configuration must not connect"); };
        Result invalid = run(p, unused);
        assertEquals(2, invalid.code());
        assertEquals("FAIL", invalid.status("configuration"));
        assertEquals("SKIPPED", invalid.status("jmx"));
        assertEquals(2, run(settings(), unused, "--test-upload").code());
        assertFalse(Files.exists(directory.resolve("reports")));
    }

    @Test
    void missingS3CredentialsAreReportedWithoutTrustingEnvironmentCredentials() throws Exception {
        Properties p = s3Settings(1);
        p.remove("S3_ACCESS_KEY");
        Result result = run(p, this::snapshot);
        assertEquals(2, result.code());
        assertEquals("FAIL", result.status("s3_configuration"));
        assertEquals("PASS", result.status("jmx"));
        assertEquals("SKIPPED", result.status("s3_connectivity"));
    }

    @Test
    void reportsJmxAuthenticationFailureAndMissingOptionalMetadataWithoutEchoingMessages() throws Exception {
        Result rejected = run(settings(), ignored -> { throw new SecurityException("secret-authentication-response"); });
        assertEquals(3, rejected.code());
        assertTrue(rejected.error().contains("permissions were rejected"));
        assertFalse((rejected.error() + rejected.json()).contains("secret-authentication-response"));
        Map<String, Object> member = new java.util.HashMap<>();
        member.put("tls", null);
        Result partial = run(settings(), ignored -> Map.of("members", List.of(member)));
        assertEquals(0, partial.code());
        assertEquals("WARN", partial.status("jmx_tls_metadata"));
        assertEquals("WARN", partial.json().get("status"));
    }

    @Test
    void validatesFlagPlacementAndRejectsDuplicates() throws Exception {
        assertEquals(2, run(settings(), this::snapshot, "--test-upload", "--test-upload").code());
        assertEquals(2, run(settings(), this::snapshot, "--test-upload", "false").code());
        ByteArrayOutputStream error = new ByteArrayOutputStream();
        assertEquals(2, NightlyStatsMain.run(new String[] {"status", "--test-upload"},
                new PrintStream(new ByteArrayOutputStream()), new PrintStream(error), this::snapshot));
    }

    @Test
    void refusedS3EndpointFailsWithUsefulCategory() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
        Result result = run(s3Settings(port), this::snapshot);
        assertEquals(4, result.code());
        assertTrue(result.error().contains("Connection failed"));
        assertEquals("FAIL", result.status("s3_connectivity"));
    }

    @Test
    void checksExistingJmxStoresAndRejectsBadPasswordsWithoutConnecting() throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12");
        store.load(null, "correct-password".toCharArray());
        store.setEntry("key", new KeyStore.SecretKeyEntry(new javax.crypto.spec.SecretKeySpec(new byte[16], "AES")),
                new KeyStore.PasswordProtection("correct-password".toCharArray()));
        Path file = directory.resolve("truststore.p12");
        try (var output = Files.newOutputStream(file)) { store.store(output, "correct-password".toCharArray()); }
        Properties p = settings();
        p.setProperty("gemfire.ssl-enabled-components", "jmx");
        p.setProperty("gemfire.ssl-truststore", file.toString());
        p.setProperty("gemfire.ssl-truststore-type", "PKCS12");
        p.setProperty("gemfire.ssl-truststore-password", "wrong-sensitive-password");
        Result result = run(p, ignored -> { throw new AssertionError("Bad store must not connect"); });
        assertEquals(2, result.code());
        assertEquals("FAIL", result.status("jmx_certificates"));
        assertEquals("SKIPPED", result.status("jmx"));
        assertFalse((result.error() + result.json()).contains("wrong-sensitive-password"));
    }

    @Test
    void validatesHttpsTrustAndHostnameUsingTheJvmDefaultContext() throws Exception {
        Path store = directory.resolve("server.p12");
        Process keytool = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                "-genkeypair", "-alias", "server", "-keyalg", "RSA", "-keysize", "2048", "-storetype", "PKCS12",
                "-keystore", store.toString(), "-storepass", "test-password", "-dname", "CN=localhost",
                "-ext", "SAN=dns:localhost", "-validity", "2", "-noprompt")
                .redirectErrorStream(true).redirectOutput(directory.resolve("keytool.log").toFile()).start();
        try {
            assertTrue(keytool.waitFor(15, TimeUnit.SECONDS));
            assertEquals(0, keytool.exitValue(), Files.readString(directory.resolve("keytool.log")));
        } finally { keytool.destroyForcibly(); }
        KeyStore keys = KeyStore.getInstance("PKCS12");
        try (var input = Files.newInputStream(store)) { keys.load(input, "test-password".toCharArray()); }
        KeyManagerFactory managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        managers.init(keys, "test-password".toCharArray());
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(managers.getKeyManagers(), null, null);
        HttpsServer server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(context));
        AtomicInteger accepted = new AtomicInteger();
        server.createContext("/", exchange -> { accepted.incrementAndGet(); exchange.sendResponseHeaders(200, -1); exchange.close(); });
        server.start();
        SSLContext previous = SSLContext.getDefault();
        try {
            Properties p = s3Settings(server.getAddress().getPort());
            p.setProperty("S3_ENDPOINT", "https://127.0.0.1:" + server.getAddress().getPort());
            Result result = run(p, this::snapshot);
            assertEquals(4, result.code(), result.error());
            assertTrue(result.error().contains("TLS validation failed"));
            assertEquals(0, accepted.get());
            var trust = javax.net.ssl.TrustManagerFactory.getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());
            trust.init(keys);
            SSLContext trusted = SSLContext.getInstance("TLS");
            trusted.init(null, trust.getTrustManagers(), null);
            SSLContext.setDefault(trusted);
            p.setProperty("S3_ENDPOINT", "https://localhost:" + server.getAddress().getPort());
            Result valid = run(p, this::snapshot);
            assertEquals(0, valid.code(), valid.error());
            assertEquals("PASS", valid.status("s3_tls"));
            assertEquals(1, accepted.get());
            p.setProperty("S3_ENDPOINT", "https://127.0.0.1:" + server.getAddress().getPort());
            Result mismatch = run(p, this::snapshot);
            assertEquals(4, mismatch.code(), mismatch.error());
            assertTrue(mismatch.error().contains("TLS validation failed"));
            assertEquals(1, accepted.get(), "Hostname mismatch must fail before HTTP is sent");
        } finally { SSLContext.setDefault(previous); server.stop(0); }
    }
}
