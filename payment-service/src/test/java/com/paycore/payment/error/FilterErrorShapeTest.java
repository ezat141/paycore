package com.paycore.payment.error;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.paycore.payment.support.PostgresContainerConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Failures raised inside the security filters, before MVC: they end in an ERROR dispatch to
 * {@code /error}, which MockMvc never performs. So this runs a real server on a random port and
 * calls it over HTTP. Without {@link JsonErrorController}, both answers are Boot's default body,
 * with a {@code timestamp} and a capitalised {@code error}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        // Nothing listens here: verifying any token needs AuthCore's keys and cannot get them.
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://127.0.0.1:1/jwks",
        "paycore.outbox.relay-enabled=false",
        "spring.kafka.admin.auto-create=false"})
@Import(PostgresContainerConfiguration.class)
class FilterErrorShapeTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final HttpClient http = HttpClient.newHttpClient();

    @LocalServerPort
    int port;

    @Test
    void anUnreachableJwksIsA500InThePlatformShape() throws Exception {
        HttpResponse<String> response = get("/payments", wellFormedToken());

        assertThat(response.statusCode()).isEqualTo(500);
        assertPlatformShape(response, "internal_server_error", 500);
    }

    @Test
    void aFirewallRejectionIsA400InThePlatformShape() throws Exception {
        HttpResponse<String> response = get("/payments;x=1", null);

        assertThat(response.statusCode()).isEqualTo(400);
        assertPlatformShape(response, "bad_request", 400);
    }

    private static void assertPlatformShape(HttpResponse<String> response, String error, int status) {
        assertThat(response.headers().firstValue("Content-Type")).get().asString().startsWith("application/json");
        JsonNode body = JSON.readTree(response.body());
        assertThat(body.get("error").asString()).isEqualTo(error);
        assertThat(body.get("status").asInt()).isEqualTo(status);
        assertThat(body.has("path")).isTrue();
        assertThat(body.has("timestamp")).isFalse();
        assertThat(body.has("detail")).isFalse();
    }

    private HttpResponse<String> get(String path, String bearer) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET();
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** Signed, unexpired, the right issuer: nothing wrong with it except that its key can't be fetched. */
    private static String wellFormedToken() throws Exception {
        RSAKey key = new RSAKeyGenerator(2048).keyID("unreachable-key").generate();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer("http://localhost:8080")
                .subject("user@acme")
                .issueTime(Date.from(Instant.now().minusSeconds(60)))
                .expirationTime(Date.from(Instant.now().plusSeconds(300)))
                .claim("scope", "payments:read")
                .claim("permissions", List.of("payments:read"))
                .claim("tenant", "acme")
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims);
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }
}
