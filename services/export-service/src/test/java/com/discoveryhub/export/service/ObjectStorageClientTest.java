package com.discoveryhub.export.service;

import com.discoveryhub.export.config.ObjectStorageProperties;
import io.minio.GetObjectArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The two MinIO clients are not interchangeable, and picking the wrong one is invisible until a
 * browser tries the link: inside Docker the service reaches {@code minio:9000} while the browser
 * needs {@code localhost:9000}, and because the presigned signature covers the {@code host}
 * header a link signed for the wrong one fails with {@code SignatureDoesNotMatch} rather than
 * anything that points at the cause.
 */
@ExtendWith(MockitoExtension.class)
class ObjectStorageClientTest {

    @Mock MinioClient internal;
    @Mock MinioClient presigning;

    private ObjectStorageClient client(String endpoint, String publicEndpoint) {
        return new ObjectStorageClient(internal, presigning, new ObjectStorageProperties(
                endpoint, publicEndpoint, "us-east-1", "minioadmin", "minioadmin",
                "export-staging", "export-packages", Duration.ofMinutes(15)));
    }

    @Test
    @DisplayName("a download link is signed by the client pointed at the browser-reachable host")
    void presignsWithThePublicClient() throws Exception {
        when(presigning.getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class)))
                .thenReturn("http://localhost:9000/export-packages/job.zip?X-Amz-Signature=abc");

        String url = client("http://minio:9000", "http://localhost:9000")
                .presignedDownloadUrl("job.zip");

        assertThat(url).startsWith("http://localhost:9000/");
        verify(presigning).getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class));
    }

    @Test
    @DisplayName("server-side reads never go through the presigning client")
    void fetchesWithTheInternalClient() throws Exception {
        when(internal.getObject(any(GetObjectArgs.class)))
                .thenReturn(new io.minio.GetObjectResponse(
                        null, "export-packages", "us-east-1", "job.zip",
                        new java.io.ByteArrayInputStream("zip".getBytes())));

        assertThat(client("http://minio:9000", "http://localhost:9000").fetchPackage("job.zip"))
                .isEqualTo("zip".getBytes());

        verifyNoInteractions(presigning);
    }

    @Test
    @DisplayName("an unset public endpoint means the one endpoint serves both, as it does from a jar")
    void publicEndpointDefaultsToTheInternalOne() {
        var props = new ObjectStorageProperties("http://localhost:9000", null,
                null, null, null, null, null, null);

        assertThat(props.publicEndpoint()).isEqualTo("http://localhost:9000");
        assertThat(props.region()).isEqualTo("us-east-1");
        assertThat(new ObjectStorageProperties(null, "  ", null, null, null, null, null, null)
                .publicEndpoint()).isEqualTo("http://localhost:9000");
    }
}
