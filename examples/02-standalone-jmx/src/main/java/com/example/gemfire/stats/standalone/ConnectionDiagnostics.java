package com.example.gemfire.stats.standalone;

import java.io.IOException;
import java.io.PrintStream;
import java.net.ConnectException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.net.ssl.SSLException;
import org.apache.geode.internal.net.SSLConfigurationFactory;
import org.apache.geode.internal.security.SecurableCommunicationChannel;

/** Independent, bounded diagnostics. Never opens a report store, writes receipts or uploads a report. */
final class ConnectionDiagnostics {
    private final StatsSettings settings;
    private final NightlyStatsMain.Source source;
    private final List<Map<String, Object>> checks = new ArrayList<>();
    private PrintStream err;
    private int exitCode;

    ConnectionDiagnostics(StatsSettings settings, NightlyStatsMain.Source source) {
        this.settings = settings;
        this.source = source;
    }

    int run(boolean testUpload, PrintStream out, PrintStream error) {
        err = error;
        String mode;
        Path directory;
        try {
            String env = StatsSettings.segment("ENV", settings.required("ENV"));
            String cluster = StatsSettings.segment("CLUSTER_NAME", settings.required("CLUSTER_NAME"));
            mode = settings.mode("local");
            if ((testUpload || settings.bool("UPLOAD_REQUIRED", false)) && mode.equals("local")) {
                throw new IllegalArgumentException("Test uploads and UPLOAD_REQUIRED require DELIVERY_MODE=s3");
            }
            // Validate the same settings used by collect, without initializing delivery or creating state.
            settings.bool("UPLOAD_REQUIRED", false);
            settings.positiveInt("MIN_FREE_SPACE_MB", 100);
            ZoneId.of(settings.get("REPORT_TIMEZONE", ZoneId.systemDefault().getId()));
            directory = Path.of(settings.get("REPORT_DIR", "reports")).resolve(env).resolve(cluster);
            add("configuration", "PASS", "Collector labels, delivery mode and report settings are valid.");
        } catch (Exception e) {
            fail("configuration", 2, "Invalid collector configuration; check ENV, CLUSTER_NAME, DELIVERY_MODE, "
                    + "UPLOAD_REQUIRED, REPORT_DIR, REPORT_TIMEZONE and MIN_FREE_SPACE_MB. "
                    + "--test-upload requires DELIVERY_MODE=s3.");
            for (String name : List.of("report_directory", "jmx_configuration", "jmx_certificates", "jmx",
                    "s3_configuration", "s3_connectivity", "s3_tls", "s3_upload")) {
                add(name, "SKIPPED", "Collector configuration is invalid.");
            }
            return finish(out);
        }
        try {
            probeDirectory(directory);
            add("report_directory", "PASS", "Report directory creation, write, read and temporary-file cleanup succeeded.");
        } catch (Exception e) {
            fail("report_directory", 3, "Report-directory probe failed; check REPORT_DIR and parent directory permissions. " + category(e));
        }
        checkJmx();
        checkS3(mode, testUpload);
        return finish(out);
    }

    private void checkJmx() {
        try {
            RemoteJmx.validate(settings);
            add("jmx_configuration", "PASS", "JMX endpoint, authentication settings and timeout are valid.");
        } catch (Exception e) {
            fail("jmx_configuration", 2, "Invalid JMX configuration; check JMX_HOST, JMX_PORT, JMX_TIMEOUT_SECONDS "
                    + "and username/password or token authentication.");
            add("jmx_certificates", "SKIPPED", "JMX configuration is invalid.");
            add("jmx", "SKIPPED", "JMX configuration is invalid.");
            return;
        }
        try {
            var ssl = SSLConfigurationFactory.getSSLConfigForComponent(settings.gemfireProperties(),
                    SecurableCommunicationChannel.JMX);
            if (!ssl.isEnabled()) {
                add("jmx_certificates", "SKIPPED", "JMX TLS is disabled.");
            } else if (ssl.useDefaultSSLContext()) {
                add("jmx_certificates", "WARN", "JMX uses the JVM default SSL context; the connection check validates its TLS handshake.");
            } else {
                boolean warning = false;
                warning |= inspectStore("JMX truststore", ssl.getTruststore(), ssl.getTruststoreType(), ssl.getTruststorePassword(), false);
                warning |= inspectStore("JMX keystore", ssl.getKeystore(), ssl.getKeystoreType(), ssl.getKeystorePassword(), true);
                add("jmx_certificates", warning ? "WARN" : "PASS",
                        "Configured JMX stores are readable and loadable; the connection check validates server trust and authentication.");
            }
            if (ssl.isEnabled() && !ssl.doEndpointIdentification()) {
                add("jmx_hostname_verification", "WARN", "JMX endpoint identification is disabled; consider gemfire.ssl-endpoint-identification-enabled=true.");
            }
        } catch (Exception e) {
            fail("jmx_certificates", 2, "Cannot load JMX TLS settings/stores; check gemfire.ssl-* paths, types and passwords. " + category(e));
            add("jmx", "SKIPPED", "JMX certificate configuration must be corrected first.");
            return;
        }
        if (Thread.currentThread().isInterrupted()) {
            add("jmx", "SKIPPED", "Diagnostics interrupted.");
            return;
        }
        err.println("Checking JMX connection and management reads (bounded by JMX_TIMEOUT_SECONDS)...");
        try {
            Map<String, Object> snapshot = source.collect(settings);
            if (snapshot == null) throw new IOException("No JMX snapshot");
            add("jmx", "PASS", "Connected, authenticated and exercised collector management reads; the snapshot was discarded.");
            if (snapshot.get("members") instanceof List<?> members) {
                long missing = members.stream().filter(member -> member instanceof Map<?, ?> data && data.get("tls") == null).count();
                add("jmx_tls_metadata", missing > 0 ? "WARN" : "PASS", missing > 0
                        ? "TLS metadata was unavailable for " + missing + " member(s); check describe-config permissions and member availability."
                        : "Member TLS metadata reads succeeded.");
            }
        } catch (Exception e) {
            interrupted(e);
            fail("jmx", 3, "JMX connection or management reads failed. " + reason(e));
        }
    }

    private boolean inspectStore(String label, String filename, String type, String password, boolean identity) throws Exception {
        if (filename == null || filename.isBlank()) return false;
        Path file = Path.of(filename);
        if (!Files.isRegularFile(file) || !Files.isReadable(file)) throw new IOException("Store is not readable");
        char[] pass = password == null ? new char[0] : password.toCharArray();
        try {
            KeyStore store = KeyStore.getInstance(type == null || type.isBlank() ? "JKS" : type);
            try (var input = Files.newInputStream(file)) { store.load(input, pass); }
            if (store.size() == 0) throw new IOException("Store is empty");
            boolean warning = false;
            boolean privateKey = false;
            for (String alias : Collections.list(store.aliases())) {
                if (identity && store.isKeyEntry(alias)) privateKey |= store.getKey(alias, pass) instanceof PrivateKey;
                if (store.getCertificate(alias) instanceof X509Certificate certificate) {
                    if (certificate.getNotAfter().toInstant().isBefore(Instant.now().plus(30, ChronoUnit.DAYS))
                            || certificate.getNotBefore().toInstant().isAfter(Instant.now())) {
                        warning = true;
                    }
                }
            }
            if (identity && !privateKey) throw new IOException("Client keystore has no private key");
            if (warning) add("jmx_certificate_validity", "WARN", label + " contains a certificate outside its validity period or expiring within 30 days.");
            return warning;
        } finally {
            java.util.Arrays.fill(pass, '\0');
        }
    }

    private void checkS3(String mode, boolean testUpload) {
        if (mode.equals("local")) {
            for (String name : List.of("s3_configuration", "s3_connectivity", "s3_tls", "s3_upload")) {
                add(name, "SKIPPED", "DELIVERY_MODE=local; no S3 requests were made.");
            }
            return;
        }
        S3Uploader uploader;
        URI endpoint;
        String prefix;
        try {
            endpoint = S3Uploader.validEndpoint(URI.create(settings.required("S3_ENDPOINT")));
            String bucket = StatsSettings.segment("S3_BUCKET", settings.required("S3_BUCKET"));
            prefix = settings.get("S3_PREFIX", "nightly-stats").replaceAll("^/+|/+$", "");
            uploader = new S3Uploader(endpoint, bucket, settings.get("S3_REGION", "us-east-1"),
                    settings.required("S3_ACCESS_KEY"), settings.required("S3_SECRET_KEY"));
            add("s3_configuration", "PASS", "S3 endpoint, bucket and required credentials are configured; authentication has not yet been verified.");
            if (List.of("S3_TLS_CA_FILE", "S3_TLS_KEYSTORE", "S3_TLS_KEYSTORE_TYPE", "S3_TLS_KEYSTORE_PASSWORD", "S3_TLS_EXPIRY_WARN_DAYS")
                    .stream().anyMatch(key -> settings.get(key, null) != null)) {
                add("s3_certificate_configuration", "WARN", "S3_TLS_* properties are proposed features and currently ignored; HTTPS uses the JVM default SSL context.");
            }
        } catch (Exception e) {
            fail("s3_configuration", 2, "Invalid S3 configuration; check S3_ENDPOINT, S3_BUCKET, S3_REGION, S3_ACCESS_KEY and S3_SECRET_KEY. " + category(e));
            for (String name : List.of("s3_connectivity", "s3_tls", "s3_upload")) add(name, "SKIPPED", "S3 configuration is invalid.");
            return;
        }
        if (Thread.currentThread().isInterrupted()) {
            for (String name : List.of("s3_connectivity", "s3_tls", "s3_upload")) add(name, "SKIPPED", "Diagnostics interrupted.");
            return;
        }
        boolean connected = false;
        err.println("Checking S3 endpoint connectivity and TLS (no remote writes)...");
        try {
            int status = uploader.probe();
            connected = true;
            add("s3_connectivity", status >= 500 || status / 100 == 3 ? "WARN" : "PASS",
                    "Endpoint responded to an unsigned HEAD with HTTP " + status + ". "
                    + "This does not establish bucket existence, authentication or upload permission; redirects are not followed.");
        } catch (Exception e) {
            interrupted(e);
            fail("s3_connectivity", 4, "S3 endpoint probe failed. " + reason(e));
        }
        if (endpoint.getScheme().equals("https")) {
            add("s3_tls", connected ? "PASS" : "SKIPPED", connected
                    ? "HTTPS handshake succeeded using JVM trust and hostname verification."
                    : "No successful HTTPS handshake; see s3_connectivity.");
        } else {
            add("s3_tls", "WARN", "Plain HTTP is configured; S3 traffic is not encrypted.");
        }
        if (!testUpload) {
            add("s3_upload", "SKIPPED", "Upload authorization was not tested. Use --test-upload to leave a unique diagnostic object in S3.");
        } else if (Thread.currentThread().isInterrupted()) {
            add("s3_upload", "SKIPPED", "Diagnostics interrupted.");
        } else {
            String key = (prefix.isEmpty() ? "" : prefix + "/") + settings.required("ENV") + "/"
                    + settings.required("CLUSTER_NAME") + "/_diagnostics/check-" + UUID.randomUUID() + ".txt";
            Map<String, Object> result;
            try {
                uploader.put(key, ("GemFire collector connection check at " + Instant.now() + "\n").getBytes(StandardCharsets.UTF_8), "text/plain");
                result = add("s3_upload", "PASS", "Test upload succeeded. The diagnostic object is retained; no report or receipt was created.");
            } catch (Exception e) {
                interrupted(e);
                result = fail("s3_upload", 4, "Test upload failed. " + reason(e)
                        + " An interrupted request may have left the diagnostic object in S3.");
            }
            result.put("object_key", key);
        }
    }

    private static void probeDirectory(Path directory) throws IOException {
        List<Path> missing = new ArrayList<>();
        for (Path path = directory.toAbsolutePath(); path != null && !Files.exists(path); path = path.getParent()) missing.add(path);
        List<Path> created = new ArrayList<>();
        Path temporary = null;
        try {
            for (int i = missing.size() - 1; i >= 0; i--) {
                Path path = missing.get(i);
                try { Files.createDirectory(path); created.add(path); }
                catch (FileAlreadyExistsException e) { if (!Files.isDirectory(path)) throw e; }
            }
            temporary = Files.createTempFile(directory, ".collector-check-", ".tmp");
            Files.writeString(temporary, "collector directory check\n");
            if (!Files.readString(temporary).equals("collector directory check\n")) throw new IOException("Probe read mismatch");
        } finally {
            if (temporary != null) Files.deleteIfExists(temporary);
            for (int i = created.size() - 1; i >= 0; i--) {
                try { Files.deleteIfExists(created.get(i)); }
                catch (DirectoryNotEmptyException ignored) { /* Preserve files created concurrently by the collector. */ }
            }
        }
    }

    private Map<String, Object> add(String name, String status, String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", name);
        result.put("status", status);
        result.put("message", message);
        checks.add(result);
        err.println(status + " " + name + ": " + message);
        return result;
    }

    private Map<String, Object> fail(String name, int code, String message) {
        // Configuration errors take precedence, then local/JMX errors, then S3 failures.
        if (exitCode == 0 || code < exitCode) exitCode = code;
        return add(name, "FAIL", message);
    }

    private int finish(PrintStream out) {
        String status = exitCode != 0 ? "FAIL" : checks.stream().anyMatch(c -> c.get("status").equals("WARN")) ? "WARN" : "PASS";
        out.print(Json.write(Map.of("command", "check", "checked_at", Instant.now().toString(),
                "status", status, "exit_code", exitCode, "checks", checks)));
        return exitCode;
    }

    private static void interrupted(Exception e) {
        if (e instanceof InterruptedException) Thread.currentThread().interrupt();
    }

    /** Do not echo server bodies, exception messages, URLs or credentials into diagnostics. */
    private static String category(Exception e) { return "(" + e.getClass().getSimpleName() + ")"; }

    private static String reason(Exception e) {
        // Bound traversal even if a third-party exception contains a cyclic cause chain.
        Throwable cause = e;
        for (int depth = 0; cause != null && depth < 16; depth++, cause = cause.getCause()) {
            if (cause instanceof S3Uploader.HttpStatusException response) {
                return "HTTP " + response.statusCode + "; check bucket existence, endpoint, credentials and write permissions.";
            }
            if (cause instanceof SSLException) return "TLS validation failed; check server trust, hostname, validity and required client certificates.";
            if (cause instanceof SecurityException) return "Authentication or management permissions were rejected.";
            if (cause instanceof UnknownHostException || cause instanceof java.nio.channels.UnresolvedAddressException) return "Host name could not be resolved.";
            if (cause instanceof ConnectException) return "Connection failed; check the host, port, firewall and advertised RMI address.";
            if (cause instanceof HttpTimeoutException || cause instanceof java.util.concurrent.TimeoutException) return "Connection/read deadline exceeded.";
        }
        return "Check endpoint availability, credentials and permissions. " + category(e);
    }
}
