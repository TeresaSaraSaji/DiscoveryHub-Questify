package com.discoveryhub.export.service;

import com.discoveryhub.export.config.ObjectStorageProperties;
import io.minio.CopyObjectArgs;
import io.minio.CopySource;
import io.minio.GetObjectArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.http.Method;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Object storage for export packages (FR-6.4), split across two buckets on purpose (see {@code
 * infra/minio/create-buckets.sh}): a package is written to {@code staging} first, and only
 * appears under {@code key} in {@code packages} once {@link #promote} has completed. A caller
 * polling the packages bucket, or the download endpoint, can therefore never observe a partial
 * package — the object simply does not exist there yet (FR-6.6).
 */
@Component
public class ObjectStorageClient {

    private final MinioClient client;
    private final ObjectStorageProperties props;

    public ObjectStorageClient(MinioClient client, ObjectStorageProperties props) {
        this.client = client;
        this.props = props;
    }

    /** Stage the built package. Not yet visible under its final key in the packages bucket. */
    public void stage(String key, byte[] bytes) {
        put(props.stagingBucket(), props.stagingKey(key), bytes);
    }

    /**
     * Server-side copy from staging to packages, then remove the staging copy. The packages
     * bucket only gains the object once this call returns, so a job that fails partway through
     * {@link PackageBuilder#build} never leaves anything for a downloader to find.
     */
    public void promote(String key) {
        try {
            client.copyObject(CopyObjectArgs.builder()
                    .bucket(props.packagesBucket())
                    .object(props.packagesKey(key))
                    .source(CopySource.builder()
                            .bucket(props.stagingBucket()).object(props.stagingKey(key)).build())
                    .build());
            client.removeObject(RemoveObjectArgs.builder()
                    .bucket(props.stagingBucket()).object(props.stagingKey(key)).build());
        } catch (Exception e) {
            throw new IllegalStateException("failed to promote export package " + key + " to packages bucket", e);
        }
    }

    /** Best-effort cleanup of a staged object that never got promoted (a build that failed). */
    public void discardStaged(String key) {
        try {
            client.removeObject(RemoveObjectArgs.builder()
                    .bucket(props.stagingBucket()).object(props.stagingKey(key)).build());
        } catch (Exception ignored) {
            // Nothing downstream depends on staging being tidy; the object is unreachable either way.
        }
    }

    /**
     * Best-effort compensating removal of a package that {@link #promote} already copied into the
     * packages bucket before some later step failed (the DB save that records COMPLETED, the
     * completion audit publish). Without this, a job that ends up FAILED can still leave a fully
     * downloadable package behind — the exact "a failed job never leaves anything for a
     * downloader to find" invariant (FR-6.6) that split staging from packages in the first place.
     * A no-op if the object never made it to packages (e.g. promote itself threw).
     */
    public void discardPackage(String key) {
        try {
            client.removeObject(RemoveObjectArgs.builder()
                    .bucket(props.packagesBucket()).object(props.packagesKey(key)).build());
        } catch (Exception ignored) {
            // Best-effort, same as discardStaged: nothing downstream depends on this succeeding,
            // and an object that was never promoted has nothing to remove anyway.
        }
    }

    public byte[] fetchPackage(String key) {
        try (InputStream in = client.getObject(GetObjectArgs.builder()
                .bucket(props.packagesBucket()).object(props.packagesKey(key)).build())) {
            return in.readAllBytes();
        } catch (Exception e) {
            throw new IllegalStateException("failed to fetch export package " + key, e);
        }
    }

    /** An expiring download link (FR-6.4) — never a permanent one, since packages can be re-run. */
    public String presignedDownloadUrl(String key) {
        try {
            return client.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET)
                    .bucket(props.packagesBucket())
                    .object(props.packagesKey(key))
                    .expiry((int) props.downloadLinkTtl().toSeconds())
                    .build());
        } catch (Exception e) {
            throw new IllegalStateException("failed to presign download url for " + key, e);
        }
    }

    private void put(String bucket, String key, byte[] bytes) {
        try (InputStream in = new ByteArrayInputStream(bytes)) {
            client.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(key)
                    .stream(in, bytes.length, -1)
                    .contentType("application/zip")
                    .build());
        } catch (IOException e) {
            throw new IllegalStateException("failed to read package bytes for upload", e);
        } catch (Exception e) {
            throw new IllegalStateException("failed to upload " + key + " to " + bucket, e);
        }
    }
}
