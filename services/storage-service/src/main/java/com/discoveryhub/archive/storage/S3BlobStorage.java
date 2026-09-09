package com.discoveryhub.archive.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * Optional "after use" offload store: S3 (real AWS S3 or any S3-compatible endpoint such as
 * MinIO). Created only when {@code discoveryhub.archive.storage.s3.enabled=true}; otherwise the
 * S3Client bean is absent and this bean is not instantiated, so an S3-less deployment pays no
 * connection cost and needs no credentials.
 *
 * <p>Object keys are {@code <keyPrefix><messageId>/<attachmentId>}; the bucket is the one from
 * {@link StorageProperties.S3}.
 */
@Component
@ConditionalOnProperty(prefix = "discoveryhub.archive.storage.s3", name = "enabled", havingValue = "true")
public class S3BlobStorage implements BlobStorage {

    private static final Logger log = LoggerFactory.getLogger(S3BlobStorage.class);

    private final S3Client s3;
    private final String bucket;
    private final String keyPrefix;

    public S3BlobStorage(S3Client s3, StorageProperties props) {
        this.s3 = s3;
        this.bucket = props.s3().bucket();
        this.keyPrefix = normalizePrefix(props.s3().keyPrefix());
        log.info("S3 attachment offload -> bucket={} prefix={} endpoint={}",
                bucket, keyPrefix, props.s3().endpoint());
    }

    /** Bucket the offload copy lives in; recorded on each attachment row so a read is self-describing. */
    public String bucket() {
        return bucket;
    }

    @Override
    public String store(String messageId, String attachmentId, byte[] bytes) {
        String key = keyPrefix + messageId + "/" + attachmentId;
        s3.putObject(PutObjectRequest.builder().bucket(bucket).key(key).build(),
                RequestBody.fromBytes(bytes == null ? new byte[0] : bytes));
        return key;
    }

    @Override
    public byte[] load(String location) {
        ResponseBytes<GetObjectResponse> resp =
                s3.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(location).build());
        return resp.asByteArray();
    }

    @Override
    public void delete(String location) {
        s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(location).build());
    }

    private static String normalizePrefix(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return "";
        }
        return prefix.endsWith("/") ? prefix : prefix + "/";
    }
}
