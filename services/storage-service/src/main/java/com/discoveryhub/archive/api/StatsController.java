package com.discoveryhub.archive.api;

import com.discoveryhub.archive.domain.MessageEntity;
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
        long onHold = messages.findAll().stream().filter(MessageEntity::isOnHold).count();
        Map<String, Object> out = new HashMap<>();
        out.put("totalMessages", total);
        out.put("onHold", onHold);
        return out;
    }
}
