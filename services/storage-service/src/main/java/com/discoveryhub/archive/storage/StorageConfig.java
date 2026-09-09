package com.discoveryhub.archive.storage;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

import java.net.URI;

/**
 * Wires the storage layer. The local-disk store ({@link LocalBlobStorage}) is always on; the
 * {@link S3Client} bean (and therefore {@link S3BlobStorage}) is created only when
 * {@code discoveryhub.archive.storage.s3.enabled=true}, so a deployment that does not want the S3
 * offload never opens an S3 connection.
 */
@Configuration
@EnableConfigurationProperties(StorageProperties.class)
public class StorageConfig {

    @Bean
    @ConditionalOnProperty(prefix = "discoveryhub.archive.storage.s3", name = "enabled", havingValue = "true")
    public S3Client s3Client(StorageProperties props) {
        StorageProperties.S3 cfg = props.s3();
        var builder = S3Client.builder()
                .region(Region.of(cfg.region()))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(cfg.pathStyleAccess())
                        .build());
        if (cfg.accessKey() != null && !cfg.accessKey().isBlank()
                && cfg.secretKey() != null && !cfg.secretKey().isBlank()) {
            builder.credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(cfg.accessKey(), cfg.secretKey())));
        } else {
            builder.credentialsProvider(DefaultCredentialsProvider.create());
        }
        if (cfg.endpoint() != null && !cfg.endpoint().isBlank()) {
            builder.endpointOverride(URI.create(cfg.endpoint()));
        }
        return builder.build();
    }
}
