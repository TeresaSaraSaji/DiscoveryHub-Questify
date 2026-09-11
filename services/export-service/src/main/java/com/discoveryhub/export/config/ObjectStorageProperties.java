package com.discoveryhub.export.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * MinIO connection and bucket layout (FR-6.4). Two buckets, two owners per {@code
 * infra/minio/create-buckets.sh}: a package is assembled under {@code staging} and only appears
 * under {@code packages} once it is complete, so a failed job never leaves a partial file in the
 * download path (FR-6.6).
 *
 * <p>The same settings point at real AWS S3 — the MinIO SDK speaks the S3 API. A single bucket is
 * the normal shape there, since buckets are a global namespace and an account usually has one for
 * this purpose: set both buckets to it and give the two halves different prefixes. What must stay
 * true is that the staging and packages locations are <em>distinct</em>, which the constructor
 * enforces rather than trusting.
 */
@ConfigurationProperties(prefix = "discoveryhub.export.storage")
public record ObjectStorageProperties(
        String endpoint,
        String region,
        String accessKey,
        String secretKey,
        String stagingBucket,
        String packagesBucket,
        String stagingPrefix,
        String packagesPrefix,
        Duration downloadLinkTtl) {

    public ObjectStorageProperties {
        if (endpoint == null || endpoint.isBlank()) {
            endpoint = "http://localhost:9000";
        }
        // Blank is the correct default, not a region name: MinIO does not need one, and letting the
        // SDK leave it unset keeps the local path exactly as it was. AWS does need it.
        region = region == null ? "" : region.strip();
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
        stagingPrefix = normalizePrefix(stagingPrefix);
        packagesPrefix = normalizePrefix(packagesPrefix);
        if (downloadLinkTtl == null || downloadLinkTtl.isZero() || downloadLinkTtl.isNegative()) {
            downloadLinkTtl = Duration.ofMinutes(15);
        }

        // Refuse to start rather than eat packages. promote() is a copy from staging to packages
        // followed by a delete of the staging copy; if the two resolve to the same bucket *and*
        // prefix, that is a copy of an object onto itself followed by a delete of it — every
        // export would report COMPLETED and leave nothing to download. The one-bucket layout makes
        // this a plausible typo (both buckets set, prefixes forgotten), so it is checked here
        // where it is a startup failure instead of silent data loss at the end of a long job.
        if (stagingBucket.equals(packagesBucket) && stagingPrefix.equals(packagesPrefix)) {
            throw new IllegalArgumentException(
                    "export staging and packages must not be the same location, but both resolve to '"
                            + stagingBucket + "/" + stagingPrefix + "'. Sharing one bucket is fine — give "
                            + "staging-prefix and packages-prefix different values.");
        }
    }

    /** Empty, or exactly one trailing slash and no leading one, so {@code prefix + key} is a valid key. */
    private static String normalizePrefix(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return "";
        }
        String normalized = prefix.strip().replaceAll("^/+", "").replaceAll("/+$", "");
        return normalized.isEmpty() ? "" : normalized + "/";
    }

    /** The full object key for a staged package. */
    public String stagingKey(String key) {
        return stagingPrefix + key;
    }

    /** The full object key for a promoted package. */
    public String packagesKey(String key) {
        return packagesPrefix + key;
    }
}
