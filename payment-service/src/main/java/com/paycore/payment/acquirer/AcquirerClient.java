package com.paycore.payment.acquirer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.util.UUID;

/**
 * The only code that talks to the acquirer. No retries, circuit breaker or bulkhead yet (M5):
 * one attempt, bounded by a connect and a read timeout.
 *
 * <p>Timeouts, refused connections and 5xx become {@code NoAnswer}. A 4xx is left to throw:
 * the state machine should make one impossible, so seeing one is a bug worth a 500.
 */
@Component
public class AcquirerClient {

    private static final Logger log = LoggerFactory.getLogger(AcquirerClient.class);

    private record AuthorizationCall(String reference, long amount, String currency, String cardToken) {
    }

    private record AuthorizationReply(String authorizationId, String reference, String status, String declineCode) {
    }

    private record ActionReply(String authorizationId, String status) {
    }

    private final RestClient restClient;

    public AcquirerClient(AcquirerProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(properties.connectTimeout())
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());
        this.restClient = RestClient.builder()
                .baseUrl(properties.baseUrl().toString())
                .requestFactory(requestFactory)
                .build();
    }

    /** {@code reference} is our payment id: the acquirer deduplicates on it. */
    public AuthorizeOutcome authorize(UUID reference, long amount, String currency, String cardToken) {
        AuthorizationReply reply;
        try {
            reply = restClient.post()
                    .uri("/authorizations")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new AuthorizationCall(reference.toString(), amount, currency, cardToken))
                    .retrieve()
                    .body(AuthorizationReply.class);
        } catch (ResourceAccessException | HttpServerErrorException noAnswer) {
            log.warn("acquirer gave no answer for payment {}: {}", reference, noAnswer.getMessage());
            return new AuthorizeOutcome.NoAnswer();
        }
        return switch (reply.status()) {
            case "APPROVED" -> new AuthorizeOutcome.Approved(reply.authorizationId());
            case "DECLINED" -> new AuthorizeOutcome.Declined(reply.authorizationId(), reply.declineCode());
            default -> throw new IllegalStateException("acquirer returned an unknown status: " + reply.status());
        };
    }

    public ActionOutcome capture(String authorizationId) {
        return act(authorizationId, "capture");
    }

    public ActionOutcome voidAuthorization(String authorizationId) {
        return act(authorizationId, "void");
    }

    private ActionOutcome act(String authorizationId, String action) {
        try {
            restClient.post()
                    .uri("/authorizations/{id}/{action}", authorizationId, action)
                    .retrieve()
                    .body(ActionReply.class);
            return new ActionOutcome.Completed();
        } catch (ResourceAccessException | HttpServerErrorException noAnswer) {
            log.warn("acquirer gave no answer to {} of {}: {}", action, authorizationId, noAnswer.getMessage());
            return new ActionOutcome.NoAnswer();
        }
    }
}
