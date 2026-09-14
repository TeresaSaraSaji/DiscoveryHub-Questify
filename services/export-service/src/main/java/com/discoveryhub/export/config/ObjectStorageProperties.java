package com.discoveryhub.export.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * MinIO connection and bucket layout (FR-6.4). Two buckets, two owners per {@code
 * infra/minio/create-buckets.sh}: a package is assembled under {@code staging} and only appears
 * under {@code packages} once it is complete, so a failed job never leaves a partial file in the
 * download path (FR-6.6).
 *
 * <p>There are two endpoints because there are two audiences, and in Docker they differ. {@code
 * endpoint} is where <i>this service</i> reaches MinIO — inside the compose network that is
 * {@code http://minio:9000}. {@code publicEndpoint} is the host a <i>browser</i> must use for the
 * presigned download link (FR-6.4), which cannot be the compose hostname because nothing outside
 * the network resolves it. Signing covers the {@code host} header, so a client that rewrites the
 * hostname itself gets {@code SignatureDoesNotMatch} — the link has to be minted against the
 * right host in the first place.
 *
 * <p>{@code publicEndpoint} defaults to {@code endpoint}, so running the service from a jar (both
 * are {@code localhost:9000}) needs no configuration at all.
 */
@ConfigurationProperties(prefix = "discoveryhub.export.storage")
public record ObjectStorageProperties(
        String endpoint,
        String publicEndpoint,
        String region,
        String accessKey,
        String secretKey,
        String stagingBucket,
        String packagesBucket,
        Duration downloadLinkTtl) {

    public ObjectStorageProperties {
        if (endpoint == null || endpoint.isBlank()) {
            endpoint = "http://localhost:9000";
        }
        if (publicEndpoint == null || publicEndpoint.isBlank()) {
            publicEndpoint = endpoint;
        }
        if (region == null || region.isBlank()) {
            region = "us-east-1";
        }
        if (accessKey == null || accessKey.isBlank()) {
            accessKey = "minioadmin";
        }
        if (secretKey == null || secretKey.isBlank()) {
            secretKey = "minioadmin";
        }
        if (stagingBucket == null || stagingBucket.isBlank()) {
            stagingBucket = "export-staging";
        }
        if (packagesBucket == null || packagesBucket.isBlank()) {
            packagesBucket = "export-packages";
        }
        if (downloadLinkTtl == null || downloadLinkTtl.isZero() || downloadLinkTtl.isNegative()) {
            downloadLinkTtl = Duration.ofMinutes(15);
        }
    }
}
