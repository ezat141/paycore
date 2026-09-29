package com.paycore.payment.acquirer;

import com.paycore.payment.support.FakeAcquirer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.HttpClientErrorException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** No Spring: the client is plain Java over a JDK HttpClient. */
class AcquirerClientTest {

    private static final FakeAcquirer acquirer = FakeAcquirer.start();

    private final AcquirerClient client = clientFor(acquirer.baseUrl());

    @AfterAll
    static void stop() {
        acquirer.close();
    }

    @BeforeEach
    void reset() {
        acquirer.reset();
        acquirer.slowResponse(Duration.ofSeconds(3));
    }

    @Test
    void anApprovalCarriesTheAuthorizationId() {
        AuthorizeOutcome outcome = client.authorize(UUID.randomUUID(), 1999, "USD", "tok_approve");

        assertThat(outcome).isInstanceOfSatisfying(AuthorizeOutcome.Approved.class,
                approved -> assertThat(approved.authorizationId()).startsWith("auth_"));
    }

    @Test
    void aDeclineCarriesItsCode() {
        AuthorizeOutcome outcome = client.authorize(UUID.randomUUID(), 1999, "USD", "tok_decline_funds");

        assertThat(outcome).isInstanceOfSatisfying(AuthorizeOutcome.Declined.class,
                declined -> assertThat(declined.declineCode()).isEqualTo("insufficient_funds"));
    }

    @Test
    void aTimeoutIsNoAnswerNotAnError() {
        assertThat(client.authorize(UUID.randomUUID(), 1999, "USD", "tok_timeout"))
                .isInstanceOf(AuthorizeOutcome.NoAnswer.class);
    }

    @Test
    void aServerErrorIsNoAnswer() {
        assertThat(client.authorize(UUID.randomUUID(), 1999, "USD", "tok_error"))
                .isInstanceOf(AuthorizeOutcome.NoAnswer.class);
    }

    @Test
    void anUnreachableAcquirerIsNoAnswer() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        AcquirerClient unreachable = clientFor(URI.create("http://127.0.0.1:" + closedPort));

        assertThat(unreachable.authorize(UUID.randomUUID(), 1999, "USD", "tok_approve"))
                .isInstanceOf(AuthorizeOutcome.NoAnswer.class);
    }

    @Test
    void theReferenceIsWhatTheAcquirerDeduplicatesOn() {
        UUID reference = UUID.randomUUID();
        client.authorize(reference, 1999, "USD", "tok_approve");
        client.authorize(reference, 1999, "USD", "tok_approve");

        assertThat(acquirer.authorizeCalls(reference)).isEqualTo(2);
        assertThat(acquirer.authorizationsRecorded()).isEqualTo(1);
    }

    @Test
    void captureAndVoidComplete() {
        String captured = approvedId();
        String voided = approvedId();

        assertThat(client.capture(captured)).isInstanceOf(ActionOutcome.Completed.class);
        assertThat(client.voidAuthorization(voided)).isInstanceOf(ActionOutcome.Completed.class);
        assertThat(acquirer.captureCalls()).isEqualTo(1);
        assertThat(acquirer.voidCalls()).isEqualTo(1);
    }

    @Test
    void aFailedCaptureIsNoAnswer() {
        String id = approvedId();
        acquirer.failNextActions(1);

        assertThat(client.capture(id)).isInstanceOf(ActionOutcome.NoAnswer.class);
    }

    @Test
    void aClientErrorIsABugAndThrows() {
        assertThatThrownBy(() -> client.capture("auth_unknown"))
                .isInstanceOf(HttpClientErrorException.NotFound.class);
    }

    /**
     * The read timeout in Spring 7 is a deadline on the whole exchange, so a stall that starts
     * only after the response headers have already arrived must still surface as {@code NoAnswer},
     * not escape as a raw exception.
     */
    @Test
    void aStallAfterTheHeadersIsNoAnswer() {
        try (Stub stalling = stallingAfterHeaders(Duration.ofSeconds(2))) {
            AcquirerClient stalled = clientFor(stalling.baseUrl());

            assertThat(stalled.authorize(UUID.randomUUID(), 1999, "USD", "tok_approve"))
                    .isInstanceOf(AuthorizeOutcome.NoAnswer.class);
        }
    }

    @Test
    void aStallAfterTheHeadersIsNoAnswerForCapture() {
        try (Stub stalling = stallingAfterHeaders(Duration.ofSeconds(2))) {
            AcquirerClient stalled = clientFor(stalling.baseUrl());

            assertThat(stalled.capture("auth_x")).isInstanceOf(ActionOutcome.NoAnswer.class);
        }
    }

    @Test
    void anUnreadableBodyIsNoAnswer() {
        try (Stub truncated = respondingWith(200, "{\"authorizationId\":")) {
            AcquirerClient broken = clientFor(truncated.baseUrl());

            assertThat(broken.authorize(UUID.randomUUID(), 1999, "USD", "tok_approve"))
                    .isInstanceOf(AuthorizeOutcome.NoAnswer.class);
        }
    }

    @Test
    void aReplyWithoutAStatusIsNoAnswer() {
        try (Stub noStatus = respondingWith(200, "{\"authorizationId\":\"auth_x\"}")) {
            AcquirerClient broken = clientFor(noStatus.baseUrl());

            assertThat(broken.authorize(UUID.randomUUID(), 1999, "USD", "tok_approve"))
                    .isInstanceOf(AuthorizeOutcome.NoAnswer.class);
        }
    }

    @Test
    void anUnrecognisedStatusIsNoAnswer() {
        try (Stub pending = respondingWith(200, "{\"authorizationId\":\"auth_x\",\"status\":\"PENDING_REVIEW\"}")) {
            AcquirerClient broken = clientFor(pending.baseUrl());

            assertThat(broken.authorize(UUID.randomUUID(), 1999, "USD", "tok_approve"))
                    .isInstanceOf(AuthorizeOutcome.NoAnswer.class);
        }
    }

    private String approvedId() {
        return ((AuthorizeOutcome.Approved) client.authorize(UUID.randomUUID(), 1999, "USD", "tok_approve"))
                .authorizationId();
    }

    private static AcquirerClient clientFor(URI baseUrl) {
        return new AcquirerClient(new AcquirerProperties(baseUrl, Duration.ofMillis(500), Duration.ofSeconds(1)));
    }

    /** A minimal one-off HTTP stub for a response shape {@link FakeAcquirer} cannot produce. */
    private record Stub(HttpServer server) implements AutoCloseable {

        URI baseUrl() {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    private static Stub stallingAfterHeaders(Duration stallFor) {
        return stub(exchange -> {
            try {
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, 0); // 0 length: headers flush now, body is chunked
                Thread.sleep(stallFor);
                byte[] body = "{\"authorizationId\":\"auth_x\",\"status\":\"APPROVED\"}"
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseBody().write(body);
            } catch (IOException | InterruptedException clientGaveUp) {
                // The client timed out and closed the connection while we slept: expected.
            } finally {
                exchange.close();
            }
        });
    }

    private static Stub respondingWith(int status, String body) {
        return stub(exchange -> {
            try {
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
            } finally {
                exchange.close();
            }
        });
    }

    private static Stub stub(HttpHandler handler) {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", handler);
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            server.start();
            return new Stub(server);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
