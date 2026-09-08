package com.discoveryhub.archive.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where attachment bytes live. The bytes are stored OUT of PostgreSQL now: the primary copy is
 * always on local disk (served by the read API), and S3 is an optional "after use" offload copy
 * for durability / long-term retrieval. The {@code attachments} table keeps only metadata plus a
 * pointer ({@code storage_location}) and, when S3 is enabled, the offload key + bucket.
 */
@ConfigurationProperties(prefix = "discoveryhub.archive.storage")
public record StorageProperties(Local local, S3 s3) {

    public StorageProperties {
        if (local == null) {
            local = new Local(null);
        }
        if (s3 == null) {
            s3 = new S3(false, null, null, null, null, null, false, null);
        }
    }

    /** Local-disk primary store. */
    public record Local(String baseDir) {
        public Local {
            if (baseDir == null || baseDir.isBlank()) {
                baseDir = "./data/attachments";
            }
        }
    }

    /** Optional S3 offload store. Works against real S3 or any S3-compatible endpoint (MinIO). */
    public record S3(boolean enabled, String endpoint, String region, String bucket,
                      String accessKey, String secretKey, boolean pathStyleAccess, String keyPrefix) {
        public S3 {
            if (region == null || region.isBlank()) {
                region = "us-east-1";
            }
            if (bucket == null || bucket.isBlank()) {
                bucket = "discoveryhub-archive";
            }
            if (keyPrefix == null) {
                keyPrefix = "attachments/";
            }
        }
    }
}
