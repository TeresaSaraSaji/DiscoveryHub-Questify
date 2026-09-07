package com.discoveryhub.cases;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * P4 Case and Hold. Cases, custodians, evidence, and legal holds.
 *
 * <p>Holds are the hard part: scopes overlap, so a message is released only when the last hold
 * covering it is lifted, and a scope can be too large to resolve inside a request — hence the
 * fan-out through {@code holds.commands}.
 */
@SpringBootApplication
public class CasesApplication {

    public static void main(String[] args) {
        SpringApplication.run(CasesApplication.class, args);
    }
}
