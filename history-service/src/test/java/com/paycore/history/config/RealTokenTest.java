package com.paycore.history.config;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.paycore.history.support.KafkaContainerConfiguration;
import com.paycore.history.support.PostgresContainerConfiguration;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Exercises the real {@code NimbusJwtDecoder} and the real {@code JwtAuthenticationConverter},
 * instead of the {@code jwt()} post-processor every other test uses, which bypasses both. A JDK
 * {@code HttpServer} on a random free port stands in for AuthCore's JWKS endpoint, so a token
 * that genuinely carries the wrong issuer, has expired, or was signed by the wrong key proves
 * those checks actually run. In particular, the pinned issuer lives only in configuration: this
 * is the test that fails if {@code issuer-uri} is removed.
 *
 * <p>Deliberately does not extend {@link com.paycore.history.support.HistoryServiceTest}: that
 * base class points {@code jwk-set-uri} at an address nothing answers, which is exactly wrong
 * here.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresContainerConfiguration.class, KafkaContainerConfiguration.class})
class RealTokenTest {

    private static final RSAKey SIGNING_KEY = generateKey("history-service-test-key");
    private static final RSAKey OTHER_KEY = generateKey("some-other-key");
    private static final HttpServer JWKS_SERVER = startJwksServer();

    @Autowired
    private MockMvc mockMvc;

    @DynamicPropertySource
    static void jwkSetUri(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
                () -> "http://127.0.0.1:" + JWKS_SERVER.getAddress().getPort() + "/jwks");
    }

    @AfterAll
    static void stopJwksServer() {
        JWKS_SERVER.stop(0);
    }

    private static RSAKey generateKey(String keyId) {
        try {
            return new RSAKeyGenerator(2048).keyID(keyId).generate();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static HttpServer startJwksServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/jwks", exchange -> {
                byte[] body = new JWKSet(SIGNING_KEY.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (var out = exchange.getResponseBody()) {
                    out.write(body);
                }
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static String token(RSAKey key, String issuer, Instant expiry) throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .subject("user@acme")
                .issueTime(Date.from(Instant.now().minusSeconds(60)))
                .expirationTime(Date.from(expiry))
                .claim("scope", "payments:read")
                .claim("permissions", List.of("payments:read"))
                .claim("tenant", "acme")
                .build();
        // No `typ` header: AuthCore sets none today.
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims);
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }

    /** 404, not 401 or 403: authenticated, authorized, and the controller found no history. */
    @Test
    void aValidTokenPassesTheChain() throws Exception {
        String jwt = token(SIGNING_KEY, "http://localhost:8080", Instant.now().plusSeconds(300));

        mockMvc.perform(get("/payments/{id}/history", UUID.randomUUID())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("no history for this payment"));
    }

    @Test
    void wrongIssuerIsRejected() throws Exception {
        String jwt = token(SIGNING_KEY, "http://evil.example", Instant.now().plusSeconds(300));

        assertUnauthorized(jwt);
    }

    @Test
    void expiredTokenIsRejected() throws Exception {
        String jwt = token(SIGNING_KEY, "http://localhost:8080", Instant.now().minusSeconds(600));

        assertUnauthorized(jwt);
    }

    @Test
    void tokenSignedByADifferentKeyIsRejected() throws Exception {
        String jwt = token(OTHER_KEY, "http://localhost:8080", Instant.now().plusSeconds(300));

        assertUnauthorized(jwt);
    }

    @Test
    void aMalformedBearerValueIsRejected() throws Exception {
        assertUnauthorized("not-a-jwt");
    }

    private void assertUnauthorized(String bearerValue) throws Exception {
        mockMvc.perform(get("/payments/{id}/history", UUID.randomUUID())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerValue))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, startsWith("Bearer")))
                .andExpect(jsonPath("$.error").value("unauthorized"));
    }
}
