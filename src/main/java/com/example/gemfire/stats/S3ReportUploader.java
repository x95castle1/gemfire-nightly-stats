package com.example.gemfire.stats;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.EnvironmentVariableCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.profiles.ProfileFile;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.retries.StandardRetryStrategy;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/** Small synchronous S3 client. Upload attempts run only on the stats background thread. */
final class S3ReportUploader implements AutoCloseable {

    private final S3Client client;
    private final String bucket;
    private final String destination;

    S3ReportUploader(StatsProperties properties) {
        this(properties, EnvironmentVariableCredentialsProvider.create());
    }

    S3ReportUploader(StatsProperties properties, AwsCredentialsProvider credentials) {
        URI endpoint = URI.create(properties.get("NIGHTLY_STATS_S3_ENDPOINT", "http://localhost:8333"));
        if (!java.util.Set.of("http", "https").contains(endpoint.getScheme()) || endpoint.getHost() == null
                || endpoint.getUserInfo() != null || endpoint.getQuery() != null || endpoint.getFragment() != null) {
            throw new IllegalArgumentException("NIGHTLY_STATS_S3_ENDPOINT must be an HTTP(S) URL without credentials");
        }
        String region = properties.get("NIGHTLY_STATS_S3_REGION", "us-east-1");
        this.bucket = properties.get("NIGHTLY_STATS_S3_BUCKET", "gemfire-nightly-stats");
        this.destination = endpoint.normalize() + "\n" + region + "\n" + bucket;
        this.client = S3Client.builder()
                .endpointOverride(endpoint)
                .region(Region.of(region))
                .credentialsProvider(credentials)
                .forcePathStyle(true)
                // Reports are small byte arrays; ordinary PUT bodies work across S3-compatible stores.
                .serviceConfiguration(S3Configuration.builder().chunkedEncodingEnabled(false).build())
                .httpClientBuilder(UrlConnectionHttpClient.builder()
                        .connectionTimeout(Duration.ofSeconds(5))
                        .socketTimeout(Duration.ofSeconds(10)))
                .overrideConfiguration(config -> config
                        // This collector uses explicit settings and environment credentials, not host AWS profiles.
                        .defaultProfileFile(ProfileFile.builder()
                                .type(ProfileFile.Type.CONFIGURATION)
                                .content(new ByteArrayInputStream(new byte[0]))
                                .build())
                        .apiCallAttemptTimeout(Duration.ofSeconds(15))
                        .apiCallTimeout(Duration.ofSeconds(60))
                        .retryStrategy(StandardRetryStrategy.builder().maxAttempts(3).build()))
                .build();
    }

    String destination() {
        return destination;
    }

    void upload(String key, byte[] content, String sha256) {
        client.putObject(PutObjectRequest.builder()
                        .bucket(bucket)
                        .key(key)
                        .contentType("application/json")
                        .checksumSHA256(Base64.getEncoder().encodeToString(HexFormat.of().parseHex(sha256)))
                        .metadata(Map.of("sha256", sha256))
                        .build(),
                RequestBody.fromBytes(content));
    }

    byte[] download(String key) {
        return client.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(key).build()).asByteArray();
    }

    @Override
    public void close() {
        client.close();
    }
}
