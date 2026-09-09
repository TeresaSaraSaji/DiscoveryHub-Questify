package com.discoveryhub.archive.api;

import com.discoveryhub.archive.repository.MessageRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * Dashboard counts (FR-8.2). P2 contributes the total-messages and on-hold figures; the other
 * services contribute their own. Cheap aggregate queries — the corpus is 10k messages and the
 * indexes cover them.
 */
@RestController
@RequestMapping("/stats")
public class StatsController {

    private final MessageRepository messages;

    public StatsController(MessageRepository messages) {
        this.messages = messages;
    }

    @GetMapping
    public Map<String, Object> stats() {
        long total = messages.count();
        // Counted in the database, against idx_messages_on_hold. The previous findAll().stream()
        // loaded all 10,000 messages — bodies, to/cc lists and all — into the heap to count a
        // boolean, on an endpoint the dashboard polls.
        long onHold = messages.countByOnHoldTrue();
        Map<String, Object> out = new HashMap<>();
        out.put("totalMessages", total);
        out.put("onHold", onHold);
        return out;
    }
}
