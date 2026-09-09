package com.discoveryhub.export.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * MinIO connection and bucket layout (FR-6.4). Two buckets, two owners per {@code
 * infra/minio/create-buckets.sh}: a package is assembled under {@code staging} and only appears
 * under {@code packages} once it is complete, so a failed job never leaves a partial file in the
 * download path (FR-6.6).
 */
@ConfigurationProperties(prefix = "discoveryhub.export.storage")
public record ObjectStorageProperties(
        String endpoint,
        String accessKey,
        String secretKey,
        String stagingBucket,
        String packagesBucket,
        Duration downloadLinkTtl) {

    public ObjectStorageProperties {
        if (endpoint == null || endpoint.isBlank()) {
            endpoint = "http://localhost:9000";
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
