package com.discoveryhub.export.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

class ObjectStoragePropertiesTest {

    private static ObjectStorageProperties props(String stagingBucket, String packagesBucket,
                                                 String stagingPrefix, String packagesPrefix) {
        return new ObjectStorageProperties("http://localhost:9000", "", "k", "s",
                stagingBucket, packagesBucket, stagingPrefix, packagesPrefix, Duration.ofMinutes(15));
    }

    @Test
    void theLocalTwoBucketLayoutLeavesKeysUnprefixed() {
        // The existing MinIO deployment passes no prefixes, and its object keys must not move —
        // packages already written under a bare key have to stay reachable.
        ObjectStorageProperties p = props("export-staging", "export-packages", null, null);

        assertThat(p.stagingKey("job-1.zip")).isEqualTo("job-1.zip");
        assertThat(p.packagesKey("job-1.zip")).isEqualTo("job-1.zip");
    }

    @Test
    void aPrefixGetsExactlyOneTrailingSlashAndNoLeadingOne() {
        assertThat(props("b", "b", "exports/staging", "/exports/packages//").stagingKey("j.zip"))
                .isEqualTo("exports/staging/j.zip");
        assertThat(props("b", "b", "exports/staging", "/exports/packages//").packagesKey("j.zip"))
                .isEqualTo("exports/packages/j.zip");
    }

    @Test
    void oneBucketWithDistinctPrefixesIsTheSupportedS3Layout() {
        assertThatCode(() -> props("my-bucket", "my-bucket", "staging/", "packages/"))
                .doesNotThrowAnyException();
    }

    @Test
    void oneBucketWithTheSamePrefixIsRefusedRatherThanEatingEveryPackage() {
        // promote() copies staging->packages then deletes the staging copy. If both resolve to one
        // location that is a self-copy followed by a delete: the job reports COMPLETED and the
        // package is gone. Startup is the only safe place to catch it.
        assertThatThrownBy(() -> props("my-bucket", "my-bucket", "exports/", "exports/"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be the same location");
    }

    @Test
    void oneBucketWithPrefixesForgottenEntirelyIsRefusedToo() {
        // The likely typo: both bucket variables pointed at the single real bucket, prefixes left
        // unset because the two-bucket default never needed them.
        assertThatThrownBy(() -> props("my-bucket", "my-bucket", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be the same location");
    }

    @Test
    void blankRegionStaysBlankSoTheMinioClientIsLeftAlone() {
        assertThat(props("a", "b", null, null).region()).isEmpty();
        assertThat(new ObjectStorageProperties("http://localhost:9000", "  eu-west-1  ", "k", "s",
                "a", "b", null, null, Duration.ofMinutes(15)).region()).isEqualTo("eu-west-1");
    }
}
