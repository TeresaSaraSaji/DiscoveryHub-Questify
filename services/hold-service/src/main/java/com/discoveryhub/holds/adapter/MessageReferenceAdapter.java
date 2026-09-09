package com.discoveryhub.holds.adapter;

import com.discoveryhub.contracts.Message;
import org.springframework.stereotype.Component;

/**
 * Adapter (structural) between the frozen {@link Message} contract (P2's read API) and the
 * hold-service's {@link MessageReference}. The scope resolvers work only in {@code MessageReference}
 * terms; this class is the single place that knows how to extract those terms from a message.
 *
 * <p>Adapting here rather than having resolvers import {@code Message} keeps a change to the
 * message shape (a renamed field, a moved attachment) from rippling into three resolvers — it
 * ripples into one adapter. It also drops the heavy fields (attachments, participants) that scope
 * matching never reads, so a resolver test builds a tiny {@code MessageReference} instead of a
 * full {@code Message}.
 */
@Component
public final class MessageReferenceAdapter {

    public MessageReference adapt(Message message) {
        return new MessageReference(
                message.messageId(),
                message.custodianId(),
                message.sentAt(),
                message.subject(),
                message.body());
    }
}
