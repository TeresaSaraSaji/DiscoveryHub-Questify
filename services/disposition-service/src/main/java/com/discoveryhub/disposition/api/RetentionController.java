package com.discoveryhub.disposition.api;

import com.discoveryhub.contracts.MessageType;
import com.discoveryhub.disposition.domain.RetentionPolicyEntity;
import com.discoveryhub.disposition.run.RetentionPolicyService;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;

/**
 * Retention policy per communication type (FR-5.1).
 *
 * <p>Periods are ISO-8601 durations, so the same field expresses both the production value and the
 * demo one: {@code P2555D} for seven years, {@code PT2M} for two minutes. A change takes effect on
 * the next sweep with no backfill, because eligibility is computed per run rather than stored per
 * message.
 */
@RestController
@RequestMapping("/retention/policies")
public class RetentionController {

    private final RetentionPolicyService policies;

    public RetentionController(RetentionPolicyService policies) {
        this.policies = policies;
    }

    @GetMapping
    public Iterable<RetentionPolicyEntity> list() {
        return policies.findAll();
    }

    @GetMapping("/{type}")
    public RetentionPolicyEntity get(@PathVariable("type") MessageType type) {
        return policies.find(type)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "no retention policy for type: " + type));
    }

    /**
     * Set the retention period for a type.
     *
     * <p>Audited, because shortening a retention period is an instruction to destroy data on the
     * next sweep, and the chain of custody should show who gave it.
     */
    @PutMapping("/{type}")
    public RetentionPolicyEntity update(@PathVariable("type") MessageType type,
                                        @RequestBody RetentionPolicyRequest request,
                                        @RequestParam(name = "actor", defaultValue = "investigator") String actor) {
        try {
            return policies.updatePeriod(type, request.period(), actor);
        } catch (IllegalArgumentException ex) {
            // A non-positive period would make the entire corpus eligible on the next sweep. 400,
            // not a 500 from a constraint violation at flush time.
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        }
    }

    /**
     * @param period ISO-8601 duration, e.g. {@code P2555D} (seven years) or {@code PT2M} (two
     *               minutes, for the demo). Must be positive.
     */
    public record RetentionPolicyRequest(@NotNull Duration period) {
    }
}
