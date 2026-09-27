package com.paycore.payment.support;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Base for every test that needs the running service. Every subclass carries identical
 * configuration, so Spring caches one context — and one set of containers — for all of them.
 * A test needing a <em>different</em> configuration must not extend this; see RedisDownTest.
 */
@SpringBootTest(properties = "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:9999/jwks")
@AutoConfigureMockMvc
@Import(PostgresContainerConfiguration.class)
public abstract class PaymentServiceTest {

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected JdbcClient jdbc;

    @BeforeEach
    void resetDatabase() {
        jdbc.sql("TRUNCATE payments").update();
    }
}
