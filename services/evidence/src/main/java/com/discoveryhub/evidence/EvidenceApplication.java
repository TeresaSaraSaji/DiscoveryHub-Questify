package com.discoveryhub.evidence;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * P5 Evidence and Audit. Async export jobs with manifests and checksums, plus the append-only
 * audit log fed by every other service.
 *
 * <p>There is no update or delete path here, by design. Append-only is enforced at the API surface
 * and again by table permissions (FR-7.3).
 */
@SpringBootApplication
public class EvidenceApplication {

    public static void main(String[] args) {
        SpringApplication.run(EvidenceApplication.class, args);
    }
}
