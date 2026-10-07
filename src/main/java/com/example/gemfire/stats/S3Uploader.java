package com.example.gemfire.stats;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Uploads objects to an S3-compatible store (SeaweedFS, MinIO, AWS S3) with one signed PUT each.
 * Uses the JDK's HttpClient and AWS Signature Version 4 rather than the AWS SDK, whose libraries
 * could clash with the ones GemFire puts on the locator's classpath.
 *
 * <p>Requests are path-style ({@code <endpoint>/<bucket>/<key>}), which SeaweedFS and MinIO need.
 */
final class S3Uploader {

    private static final DateTimeFormatter AMZ_DATE = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    /** How much of an error response to put in the exception message */
    private static final int MAX_ERROR_BODY = 500;

    private final URI endpoint;
    private final String bucket;
    private final String region;
    private final String accessKey;
    private final String secretKey;
    private final Clock clock;
    private final HttpClient client;

    S3Uploader(URI endpoint, String bucket, String region, String accessKey, String secretKey) {
        this(endpoint, bucket, region, accessKey, secretKey, Clock.systemUTC());
    }

    S3Uploader(URI endpoint, String bucket, String region, String accessKey, String secretKey, Clock clock) {
        if (!"http".equals(endpoint.getScheme()) && !"https".equals(endpoint.getScheme())) {
            throw new IllegalArgumentException("S3 endpoint must be an http:// or https:// URL: " + endpoint);
        }
        this.endpoint = withoutDefaultPort(endpoint);
        this.bucket = bucket;
        this.region = region;
        this.accessKey = accessKey;
        this.secretKey = secretKey;
        this.clock = clock;
        this.client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /** Where an object goes, for log messages, e.g. s3://gemfire-stats/nightly-stats/x.json */
    String location(String key) {
        return "s3://" + bucket + "/" + key;
    }

    /** Uploads body as the object key, replacing any existing object. Throws on any non-2xx response. */
    void put(String key, byte[] body, String contentType) throws IOException, InterruptedException {
        String canonicalUri = "/" + encodePath(bucket) + "/" + encodePath(key);
        String amzDate = ZonedDateTime.now(clock.withZone(ZoneOffset.UTC)).format(AMZ_DATE);
        String payloadHash = hex(sha256(body));

        // Signed headers. HttpClient adds Host itself, from the same URI, so its value matches.
        Map<String, String> headers = new TreeMap<>();
        headers.put("content-type", contentType);
        headers.put("host", hostHeader(endpoint));
        headers.put("x-amz-content-sha256", payloadHash);
        headers.put("x-amz-date", amzDate);
        String authorization = authorization("PUT", canonicalUri, headers, payloadHash, amzDate);

        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl(endpoint) + canonicalUri))
                .timeout(REQUEST_TIMEOUT)
                .PUT(HttpRequest.BodyPublishers.ofByteArray(body))
                .header("Authorization", authorization);
        headers.forEach((name, value) -> {
            if (!name.equals("host")) {
                request.header(name, value);
            }
        });

        HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            String error = response.body();
            if (error.length() > MAX_ERROR_BODY) {
                error = error.substring(0, MAX_ERROR_BODY) + "...";
            }
            throw new IOException("PUT " + location(key) + " to " + endpoint + " returned HTTP "
                    + response.statusCode() + ": " + error.strip());
        }
    }

    /**
     * The SigV4 Authorization header for a request with no query string. headers must be sorted with
     * lower-case names, and must include host and x-amz-date.
     */
    String authorization(String method, String canonicalUri, Map<String, String> headers, String payloadHash,
            String amzDate) {
        String date = amzDate.substring(0, 8);
        String scope = date + "/" + region + "/s3/aws4_request";
        String signedHeaders = String.join(";", headers.keySet());
        String canonicalHeaders = headers.entrySet().stream()
                .map(header -> header.getKey() + ":" + header.getValue().strip() + "\n")
                .collect(Collectors.joining());
        String canonicalRequest = String.join("\n",
                method, canonicalUri, "", canonicalHeaders, signedHeaders, payloadHash);
        String stringToSign = String.join("\n",
                "AWS4-HMAC-SHA256", amzDate, scope, hex(sha256(canonicalRequest.getBytes(StandardCharsets.UTF_8))));

        byte[] signingKey = hmac(("AWS4" + secretKey).getBytes(StandardCharsets.UTF_8), date);
        signingKey = hmac(signingKey, region);
        signingKey = hmac(signingKey, "s3");
        signingKey = hmac(signingKey, "aws4_request");
        String signature = hex(hmac(signingKey, stringToSign));

        return "AWS4-HMAC-SHA256 Credential=" + accessKey + "/" + scope
                + ", SignedHeaders=" + signedHeaders + ", Signature=" + signature;
    }

    /** URI-encodes each segment of an S3 key the way SigV4 expects: everything but A-Z a-z 0-9 - . _ ~ and / */
    static String encodePath(String path) {
        StringBuilder out = new StringBuilder();
        for (byte b : path.getBytes(StandardCharsets.UTF_8)) {
            char c = (char) (b & 0xff);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '.' || c == '_' || c == '~' || c == '/') {
                out.append(c);
            } else {
                out.append('%').append(String.format("%02X", b & 0xff));
            }
        }
        return out.toString();
    }

    /** Drops :80 from http and :443 from https, so the signed Host header matches the one HttpClient sends. */
    static URI withoutDefaultPort(URI uri) {
        int port = uri.getPort();
        boolean defaultPort = ("http".equals(uri.getScheme()) && port == 80)
                || ("https".equals(uri.getScheme()) && port == 443);
        return defaultPort ? URI.create(uri.getScheme() + "://" + uri.getHost()) : uri;
    }

    static String hostHeader(URI uri) {
        return uri.getPort() == -1 ? uri.getHost() : uri.getHost() + ":" + uri.getPort();
    }

    /** scheme://host[:port], ignoring any path or trailing slash on the endpoint */
    private static String baseUrl(URI uri) {
        return uri.getScheme() + "://" + hostHeader(uri);
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] hmac(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }
}
