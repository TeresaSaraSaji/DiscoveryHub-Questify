package com.discoveryhub.contracts;

/**
 * A person whose communications are under management. Custodians are referenced by
 * {@code custodianId} from messages, cases, and legal holds.
 */
public record Custodian(
        String custodianId,
        String displayName,
        String email,
        String department,
        String title) {
}
