package com.discoveryhub.export.config;

import io.minio.MinioClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
public class MinioConfig {

    /** Server-side calls: staging, promotion, and fetching a package back to verify it. */
    @Bean
    @Primary
    public MinioClient minioClient(ObjectStorageProperties props) {
        return MinioClient.builder()
                .endpoint(props.endpoint())
                .credentials(props.accessKey(), props.secretKey())
                .build();
    }

    /**
     * Presigning only, against the host a browser can actually reach — the signature commits to
     * the {@code host} header, so the link has to be signed for the host that will request it.
     *
     * <p><b>The region must be set explicitly.</b> Without it the SDK resolves the bucket's
     * region by calling {@code GetBucketLocation} on its own endpoint before it signs, and this
     * client's endpoint is by definition one this process cannot reach — inside Docker,
     * presigning against {@code localhost:9000} then fails with a bare connection refused that
     * looks nothing like a configuration problem. With the region supplied, signing is purely
     * local and no connection is ever opened.
     */
    @Bean
    public MinioClient presigningMinioClient(ObjectStorageProperties props) {
        return MinioClient.builder()
                .endpoint(props.publicEndpoint())
                .region(props.region())
                .credentials(props.accessKey(), props.secretKey())
                .build();
    }
}
