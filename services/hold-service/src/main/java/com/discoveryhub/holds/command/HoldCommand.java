package com.discoveryhub.holds.command;

/**
 * Command (behavioural) for an asynchronous hold operation. Each concrete command encapsulates a
 * request — place this hold, release this hold — as an object with its own {@link #execute()},
 * so the Kafka listener that triggers it (the Invoker) does not know <i>how</i> a hold is placed,
 * only that it has a command to run. That decouples the trigger (a message on
 * {@code holds.commands}) from the work (resolve scope, persist coverage, publish events), and a
 * new operation — say, "re-scope this hold after the case's custodians changed" — is a new command
 * class, not a new branch in the listener.
 *
 * <p>Commands carry their dependencies (resolver, repositories, publisher) by reference, so they
 * are not serialised to Kafka — only a lightweight {@link HoldCommandMessage} goes on the wire, and
 * the {@link HoldCommandFactory} rehydrates the full command with its dependencies on the consumer
 * side. The pattern's value is the encapsulation on the consumer, not the wire format.
 */
public interface HoldCommand {

    /** The hold this command operates on. */
    String holdId();

    /** The operation type, for logging and audit correlation. */
    String type();

    /** Perform the work. Implementations update the hold's status and publish events. */
    void execute();
}
