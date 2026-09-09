package com.discoveryhub.archive.api;

import com.discoveryhub.archive.repository.MessageHoldStatusRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * Dashboard counts (FR-8.2). P2 contributes the total-messages and on-hold figures; the other
 * services contribute their own. Both come from the {@code message_hold_status} table — cheap
 * aggregate queries, no need to touch Mongo for a count.
 */
@RestController
@RequestMapping("/stats")
public class StatsController {

    private final MessageHoldStatusRepository holdStatuses;

    public StatsController(MessageHoldStatusRepository holdStatuses) {
        this.holdStatuses = holdStatuses;
    }

    @GetMapping
    public Map<String, Object> stats() {
        long total = holdStatuses.count();
        long onHold = holdStatuses.countByOnHoldTrue();
        Map<String, Object> out = new HashMap<>();
        out.put("totalMessages", total);
        out.put("onHold", onHold);
        return out;
    }
}
