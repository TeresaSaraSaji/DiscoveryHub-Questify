package com.discoveryhub.disposition.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
// Boot 4 moved this out of org.springframework.boot.autoconfigure.jdbc and into the per-technology
// spring-boot-jdbc module. Every Boot 3 example on the internet still shows the old package, and
// the failure is a plain "cannot find symbol" that looks like a missing dependency rather than a
// relocation. Same split that already caught this repo with Flyway and Kafka.
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * Two datasources, and the distinction between them is the whole architecture of this service.
 *
 * <ul>
 *   <li><b>{@code dispositionDataSource} (primary)</b> — {@code postgres-disposition}. This
 *       service's own database: retention policy and the run ledger. JPA and Flyway bind to it
 *       because it is {@code @Primary}, and it is the only schema this service migrates.</li>
 *   <li><b>{@code archiveDataSource}</b> — P2's {@code postgres-archive}. Reached only through
 *       {@code archive.JdbcArchiveGateway}, only by {@link JdbcTemplate}, and only for the
 *       eligibility query and the guarded delete. No entities, no repositories, and above all no
 *       Flyway: adding a second migration history to that database would make P2 fail on startup
 *       under {@code ddl-auto: validate}.</li>
 * </ul>
 *
 * <p>Declaring any {@code DataSource} bean makes Boot's datasource auto-configuration back off
 * entirely, so both have to be declared here — including the primary one that would otherwise be
 * free. Miss the {@code @Primary} and JPA cannot choose between them, which surfaces as an
 * {@code EntityManagerFactory} failure that says nothing about datasources.
 */
@Configuration
public class DataSourceConfig {

    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource")
    public DataSourceProperties dispositionDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource.hikari")
    public DataSource dispositionDataSource(
            @Qualifier("dispositionDataSourceProperties") DataSourceProperties props) {
        return props.initializeDataSourceBuilder().type(HikariDataSource.class).build();
    }

    @Bean
    @ConfigurationProperties("discoveryhub.disposition.archive.datasource")
    public DataSourceProperties archiveDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean
    @ConfigurationProperties("discoveryhub.disposition.archive.datasource.hikari")
    public DataSource archiveDataSource(
            @Qualifier("archiveDataSourceProperties") DataSourceProperties props) {
        return props.initializeDataSourceBuilder().type(HikariDataSource.class).build();
    }

    /**
     * The only handle on P2's database anywhere in this service. Everything that touches the
     * archive goes through {@code archive.JdbcArchiveGateway}, which takes this bean — so the
     * coupling is one class wide and greppable, rather than spread across repositories.
     */
    @Bean
    public JdbcTemplate archiveJdbcTemplate(@Qualifier("archiveDataSource") DataSource archiveDataSource) {
        return new JdbcTemplate(archiveDataSource);
    }
}
