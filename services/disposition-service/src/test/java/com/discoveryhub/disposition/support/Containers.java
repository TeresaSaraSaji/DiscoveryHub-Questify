package com.discoveryhub.disposition.support;

import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The containers the integration tests share, started once for the whole build.
 *
 * <p>Started in a static initialiser rather than through {@code @Testcontainers} / {@code
 * @Container} for two reasons. The ordering one is decisive: {@code @DynamicPropertySource} is
 * evaluated while Spring builds the application context, and with the JUnit extension the
 * container is not guaranteed to be running by then — the failure is a "mapped port can only be
 * obtained after the container is started" during context load, which reads like a Spring problem
 * and is not one. A static initialiser runs on first class access, which is necessarily before
 * anything can ask it for a port.
 *
 * <p>The second reason is time. Two Postgres instances and a Kafka broker per test class would
 * dominate the build; started once and shared, they cost one startup for the module. Ryuk stops
 * them when the JVM exits, so there is nothing to tear down. Tests are responsible for cleaning
 * their own rows — see each class's {@code @BeforeEach}.
 */
public final class Containers {

    /** This service's own database: retention policy and the run ledger, migrated by Flyway. */
    public static final PostgreSQLContainer DISPOSITION_DB =
            new PostgreSQLContainer("postgres:17.11")
                    .withDatabaseName("disposition")
                    .withUsername("disposition")
                    .withPassword("disposition");

    /**
     * A stand-in for P2's. This service never migrates it — the schema is built from P2's own
     * migration file by {@link ArchiveSchema}, so a column rename in P2 fails the build here.
     */
    public static final PostgreSQLContainer ARCHIVE_DB =
            new PostgreSQLContainer("postgres:17.11")
                    .withDatabaseName("archive")
                    .withUsername("archive")
                    .withPassword("archive");

    /** Matches the broker in docker-compose, so the serdes and topic behaviour are the real ones. */
    public static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.2.1");

    static {
        DISPOSITION_DB.start();
        ARCHIVE_DB.start();
        KAFKA.start();
    }

    private Containers() {
    }
}
