package com.discoveryhub.export.config;

import io.minio.MinioClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MinioConfig {

    @Bean
    public MinioClient minioClient(ObjectStorageProperties props) {
        MinioClient.Builder builder = MinioClient.builder()
                .endpoint(props.endpoint())
                .credentials(props.accessKey(), props.secretKey());
        // Only when set. AWS signs every request against a region and rejects a wrong one; MinIO
        // ignores it entirely, so leaving the builder untouched keeps the local path as it was.
        if (!props.region().isEmpty()) {
            builder.region(props.region());
        }
        return builder.build();
    }
}
