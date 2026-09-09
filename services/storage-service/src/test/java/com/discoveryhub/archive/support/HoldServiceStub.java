package com.discoveryhub.archive.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

/**
 * A stand-in for P4 Case &amp; Hold, which does not exist yet.
 *
 * <p>A real HTTP server on a real socket rather than a mocked {@code RestClient}, because the
 * behaviour under test is largely about what happens when P4 <i>misbehaves</i> — refuses a
 * connection, returns an empty body, times out. A mock can only return what it is told to return,
 * and the fail-closed path is precisely the one that a mock would let pass by simulating a clean
 * "not held" where reality would have produced an exception. {@link #stop()} gives a genuine
 * connection refused, which is what an unreachable P4 actually looks like.
 *
 * <p>Uses the JDK's own {@code com.sun.net.httpserver}, so no new dependency for three endpoints.
 */
public final class HoldServiceStub implements AutoCloseable {

    private final HttpServer server;

    /** Message ids P4 will report as held by {@code GET /holds/check}. */
    private final Set<String> heldMessageIds = new HashSet<>();

    /** Raw JSON body for {@code GET /holds/active}. Empty array means no hold is in force. */
    private volatile String activeHoldsJson = "[]";

    /** Raw JSON body for {@code POST /holds/evidence-check}. */
    private volatile String evidenceJson = "[]";

    /** When true, every endpoint answers 500 — a P4 that is up but broken. */
    private volatile boolean failing = false;

    public HoldServiceStub() {
        try {
            // Port 0: the OS picks a free one, so parallel test classes cannot collide.
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException ex) {
            throw new IllegalStateException("could not start the P4 stub", ex);
        }
        server.createContext("/holds/check", this::holdCheck);
        server.createContext("/holds/active", exchange -> respond(exchange, activeHoldsJson));
        server.createContext("/holds/evidence-check", exchange -> respond(exchange, evidenceJson));
        server.start();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public HoldServiceStub hold(String... messageIds) {
        heldMessageIds.addAll(Set.of(messageIds));
        return this;
    }

    public HoldServiceStub activeHolds(String json) {
        this.activeHoldsJson = json;
        return this;
    }

    public HoldServiceStub evidence(String json) {
        this.evidenceJson = json;
        return this;
    }

    public HoldServiceStub failing(boolean failing) {
        this.failing = failing;
        return this;
    }

    public void reset() {
        heldMessageIds.clear();
        activeHoldsJson = "[]";
        evidenceJson = "[]";
        failing = false;
    }

    /** Stop answering entirely. Subsequent calls get a connection refused, not an error body. */
    public void stop() {
        server.stop(0);
    }

    @Override
    public void close() {
        stop();
    }

    private void holdCheck(HttpExchange exchange) throws IOException {
        String query = exchange.getRequestURI().getQuery();
        String messageId = query == null ? "" : query.replaceFirst("^messageId=", "");
        respond(exchange, "{\"held\":" + heldMessageIds.contains(messageId) + "}");
    }

    private void respond(HttpExchange exchange, String body) throws IOException {
        if (failing) {
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
            return;
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
