package com.discoveryhub.disposition.run;

import com.discoveryhub.contracts.MessageType;
import com.discoveryhub.disposition.config.RetentionDefaults;
import com.discoveryhub.disposition.domain.RetentionPolicyEntity;
import com.discoveryhub.disposition.messaging.AuditEvents;
import com.discoveryhub.disposition.messaging.DispositionKafkaPublisher;
import com.discoveryhub.disposition.repository.RetentionPolicyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The retention policy (FR-5.1): read it, change it, and seed it on a fresh database.
 *
 * <p>The policy lives in the database rather than in {@code application.yml} because FR-5.1 asks
 * for it to be configurable and for the demo to be able to set it to minutes. A restart to change
 * a number is not configurable in any useful sense, and a value that only exists in a container's
 * environment cannot be shown in the UI or audited when it changes. Config seeds the table on
 * first start; the API owns it afterwards.
 */
@Service
public class RetentionPolicyService {

    private static final Logger log = LoggerFactory.getLogger(RetentionPolicyService.class);

    private final RetentionPolicyRepository policies;
    private final RetentionDefaults defaults;
    private final DispositionKafkaPublisher publisher;
    private final AuditEvents audit;

    public RetentionPolicyService(RetentionPolicyRepository policies, RetentionDefaults defaults,
                                  DispositionKafkaPublisher publisher, AuditEvents audit) {
        this.policies = policies;
        this.defaults = defaults;
        this.publisher = publisher;
        this.audit = audit;
    }

    /**
     * Inserts a policy row for every {@link MessageType} that does not have one yet.
     *
     * <p>Runs on startup, and only fills gaps — an operator who set email retention to two minutes
     * for a demo must not find it back at seven years after a restart. That also makes adding a
     * new communication type safe: it gets the configured default on the next boot and nothing
     * else moves.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void seedMissingPolicies() {
        for (MessageType type : MessageType.values()) {
            if (policies.findById(type).isEmpty()) {
                Duration seed = defaults.seedFor(type);
                policies.save(new RetentionPolicyEntity(type, seed, "seed"));
                log.info("seeded retention policy {} = {}", type, seed);
            }
        }
    }

    @Transactional(readOnly = true)
    public Map<MessageType, Duration> currentPeriods() {
        Map<MessageType, Duration> result = new LinkedHashMap<>();
        for (RetentionPolicyEntity policy : policies.findAll()) {
            result.put(policy.getMessageType(), policy.period());
        }
        return result;
    }

    @Transactional(readOnly = true)
    public Optional<RetentionPolicyEntity> find(MessageType type) {
        return policies.findById(type);
    }

    @Transactional(readOnly = true)
    public Iterable<RetentionPolicyEntity> findAll() {
        return policies.findAll();
    }

    /**
     * Change the retention period for a type. Audited (FR-7.1), because shortening a retention
     * period is an instruction to destroy data on the next sweep and belongs in the chain of
     * custody next to the deletions it causes.
     *
     * @throws IllegalArgumentException if the period is not positive
     */
    @Transactional
    public RetentionPolicyEntity updatePeriod(MessageType type, Duration period, String actor) {
        RetentionPolicyEntity policy = policies.findById(type).orElse(null);
        Duration previous = policy == null ? null : policy.period();
        if (policy == null) {
            policy = new RetentionPolicyEntity(type, period, actor);
        } else {
            policy.setPeriod(period);
            policy.setUpdatedAt(Instant.now());
            policy.setUpdatedBy(actor);
        }
        RetentionPolicyEntity saved = policies.save(policy);
        log.info("retention policy {} changed from {} to {} by {}", type, previous, period, actor);
        publisher.publishAudit(audit.retentionPolicyChanged(type, previous, period, actor));
        return saved;
    }

    /**
     * The cutoff per type for a sweep starting now: a message sent at or before its type's cutoff
     * is past retention.
     */
    @Transactional(readOnly = true)
    public Map<MessageType, Instant> cutoffs(Instant now) {
        Map<MessageType, Instant> cutoffs = new LinkedHashMap<>();
        for (RetentionPolicyEntity policy : policies.findAll()) {
            cutoffs.put(policy.getMessageType(), now.minus(policy.period()));
        }
        return cutoffs;
    }
}
