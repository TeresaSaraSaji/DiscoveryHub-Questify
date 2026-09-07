package com.discoveryhub.search;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * P3 Search. Consumes {@code messages.archived} and indexes; consumes {@code holds.events} to keep
 * a cached {@code onHold} flag on each document so the hold filter stays inside a single query.
 */
@SpringBootApplication
public class SearchApplication {

    public static void main(String[] args) {
        SpringApplication.run(SearchApplication.class, args);
    }
}
