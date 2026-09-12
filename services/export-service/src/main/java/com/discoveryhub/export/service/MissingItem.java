package com.discoveryhub.export.service;

/**
 * One message that was in the export's scope but is not in the archive any more, recorded in the
 * package's manifest rather than dropped.
 *
 * <p>The usual cause is disposition: the message reached the end of its retention period and was
 * destroyed, which is the system working as intended and is recorded in the audit trail as
 * {@code disposition.deleted}. A case that named it as evidence still names it, because removing
 * evidence from a case is a separate act by a person.
 *
 * <p>{@code reason} is deliberately what this service actually knows — that P2 answered 404 —
 * rather than an inference about why. P5 cannot tell a disposed message from one removed some
 * other way, and the audit trail can, so the manifest points at the id and leaves the
 * determination to whoever reads the trail.
 */
public record MissingItem(String messageId, String reason) {

    static MissingItem notInArchive(String messageId) {
        return new MissingItem(messageId, "not present in the archive when the package was built");
    }
}
