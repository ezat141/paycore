package com.paycore.payment.acquirer;

import com.paycore.payment.support.FakeAcquirer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

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
        acquirer.slowResponse(Duration.ofSeconds(1));
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

    private String approvedId() {
        return ((AuthorizeOutcome.Approved) client.authorize(UUID.randomUUID(), 1999, "USD", "tok_approve"))
                .authorizationId();
    }

    private static AcquirerClient clientFor(URI baseUrl) {
        return new AcquirerClient(new AcquirerProperties(baseUrl, Duration.ofMillis(500), Duration.ofMillis(300)));
    }
}
