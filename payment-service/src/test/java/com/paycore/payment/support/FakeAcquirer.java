package com.paycore.payment.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * The parts of spec §6's acquirer contract that payment-service's tests exercise, in-process.
 * Same card-token behaviours as the real simulator — {@code tok_timeout} records the approval
 * <em>before</em> stalling — plus the counters and failure switches a test needs. One behaviour is
 * the fake's own: {@code tok_refuse} answers authorize with {@code 409} and records nothing,
 * standing in for any 4xx refusal (the simulator's is a mismatched reference). It does not model
 * the simulator's 409 on a mismatched reference itself, its capture/void latency, or its 400 on a
 * blank reference.
 */
public final class FakeAcquirer implements AutoCloseable {

    private static final Duration DEFAULT_SLOW_RESPONSE = Duration.ofSeconds(3);

    private static final class Auth {
        final String id;
        final String reference;
        final String declineCode;
        volatile String status;

        Auth(String id, String reference, String status, String declineCode) {
            this.id = id;
            this.reference = reference;
            this.status = status;
            this.declineCode = declineCode;
        }

        Map<String, Object> toJson() {
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("authorizationId", id);
            json.put("reference", reference);
            json.put("status", status);
            json.put("declineCode", declineCode);
            return json;
        }
    }

    private final HttpServer server;
    private final JsonMapper json = JsonMapper.builder().build();
    private final Map<String, Auth> byReference = new ConcurrentHashMap<>();
    private final Map<String, Auth> byId = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> authorizeCallsByReference = new ConcurrentHashMap<>();
    private final AtomicInteger captureCalls = new AtomicInteger();
    private final AtomicInteger voidCalls = new AtomicInteger();
    private final AtomicInteger failingActions = new AtomicInteger();
    private volatile Duration authorizeDelay = Duration.ZERO;
    private volatile Duration slowResponse = DEFAULT_SLOW_RESPONSE;
    private volatile Consumer<String> onAuthorize = reference -> { };

    private FakeAcquirer(HttpServer server) {
        this.server = server;
    }

    public static FakeAcquirer start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            FakeAcquirer fake = new FakeAcquirer(server);
            server.createContext("/authorizations", fake::handle);
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            server.start();
            return fake;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public URI baseUrl() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    public int authorizeCalls(UUID reference) {
        AtomicInteger calls = authorizeCallsByReference.get(reference.toString());
        return calls == null ? 0 : calls.get();
    }

    public int totalAuthorizeCalls() {
        return authorizeCallsByReference.values().stream().mapToInt(AtomicInteger::get).sum();
    }

    /** Distinct authorizations created — a retry that deduplicated does not add one. */
    public int authorizationsRecorded() {
        return byReference.size();
    }

    public int captureCalls() {
        return captureCalls.get();
    }

    public int voidCalls() {
        return voidCalls.get();
    }

    public void authorizeDelay(Duration delay) {
        this.authorizeDelay = delay;
    }

    public void slowResponse(Duration delay) {
        this.slowResponse = delay;
    }

    /** The next {@code count} capture/void calls answer 500 without doing anything. */
    public void failNextActions(int count) {
        failingActions.set(count);
    }

    /**
     * Runs synchronously inside the authorize handler, once the reference is known but before any
     * response is sent — a hook for a test to observe state (typically the database, through a
     * separate pooled connection) at the instant the acquirer is called, such as proving a row was
     * already committed (spec §5.3).
     */
    public void onAuthorize(Consumer<String> hook) {
        this.onAuthorize = hook;
    }

    /**
     * Does not stop a handler that is already sleeping in {@link #authorize} or {@link #act}:
     * a test that leaves a delayed request in flight must let it finish, or use a fresh reference,
     * before the next test resets state under it.
     */
    public void reset() {
        byReference.clear();
        byId.clear();
        authorizeCallsByReference.clear();
        captureCalls.set(0);
        voidCalls.set(0);
        failingActions.set(0);
        authorizeDelay = Duration.ZERO;
        slowResponse = DEFAULT_SLOW_RESPONSE;
        onAuthorize = reference -> { };
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) {
        try {
            String[] path = exchange.getRequestURI().getPath().split("/");
            if (!"POST".equals(exchange.getRequestMethod())) {
                send(exchange, 405, Map.of());
            } else if (path.length == 2) {
                authorize(exchange);
            } else if (path.length == 4) {
                act(exchange, path[2], path[3]);
            } else {
                send(exchange, 404, Map.of());
            }
        } catch (IOException | InterruptedException clientGone) {
            // The client timed out and closed the connection while we slept: exactly the case
            // being simulated. Nothing to do.
        } finally {
            exchange.close();
        }
    }

    @SuppressWarnings("unchecked")
    private void authorize(HttpExchange exchange) throws IOException, InterruptedException {
        Map<String, Object> request = json.readValue(exchange.getRequestBody(), Map.class);
        String reference = (String) request.get("reference");
        String cardToken = (String) request.get("cardToken");
        authorizeCallsByReference.computeIfAbsent(reference, r -> new AtomicInteger()).incrementAndGet();
        onAuthorize.accept(reference);
        Thread.sleep(authorizeDelay);

        if ("tok_error".equals(cardToken)) {
            send(exchange, 500, Map.of("error", "simulated acquirer failure"));
            return;
        }
        if ("tok_refuse".equals(cardToken)) {
            send(exchange, 409, Map.of("error", "simulated acquirer refusal"));
            return;
        }
        boolean[] created = {false};
        Auth auth = byReference.computeIfAbsent(reference, r -> {
            created[0] = true;
            boolean declined = "tok_decline_funds".equals(cardToken);
            Auth fresh = new Auth("auth_" + UUID.randomUUID(), r,
                    declined ? "DECLINED" : "APPROVED", declined ? "insufficient_funds" : null);
            byId.put(fresh.id, fresh);
            return fresh;
        });
        if (created[0] && "tok_timeout".equals(cardToken)) {
            Thread.sleep(slowResponse);
        }
        send(exchange, 200, auth.toJson());
    }

    private void act(HttpExchange exchange, String authorizationId, String action) throws IOException {
        (("capture".equals(action)) ? captureCalls : voidCalls).incrementAndGet();
        if (failingActions.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
            send(exchange, 500, Map.of("error", "simulated acquirer failure"));
            return;
        }
        Auth auth = byId.get(authorizationId);
        if (auth == null) {
            send(exchange, 404, Map.of());
            return;
        }
        String target = "capture".equals(action) ? "CAPTURED" : "VOIDED";
        synchronized (auth) {
            if (!auth.status.equals(target)) {
                if (!auth.status.equals("APPROVED")) {
                    send(exchange, 409, Map.of("error", "cannot move " + auth.status + " to " + target));
                    return;
                }
                auth.status = target;
            }
        }
        send(exchange, 200, Map.of("authorizationId", auth.id, "status", auth.status));
    }

    private void send(HttpExchange exchange, int status, Map<String, ?> body) throws IOException {
        byte[] bytes = json.writeValueAsBytes(body);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }
}
