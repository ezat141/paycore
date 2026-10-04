package com.paycore.history.support;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

/** Base for every history-service test needing the running service; subclasses share one context. */
@SpringBootTest(properties = {
        // A paused database must make queries FAIL, not hang: otherwise a database outage never
        // reaches the error handler, and the "retry without limit" behaviour is never exercised.
        "spring.datasource.hikari.connection-timeout=1000",
        "spring.datasource.hikari.data-source-properties.socketTimeout=2",
        // HikariCP does not clamp validation-timeout (5s by default) to connection-timeout. Without
        // this, a retry that borrows an aged idle connection blocks 5s in isValid() while the
        // database is paused, and each attempt takes about 6s instead of about 3s.
        "spring.datasource.hikari.validation-timeout=500",
        // Tests inject tokens and never contact AuthCore; the URI only has to parse.
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:9999/jwks"})
@AutoConfigureMockMvc
@Import({PostgresContainerConfiguration.class, KafkaContainerConfiguration.class})
public abstract class HistoryServiceTest {

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected JdbcClient jdbc;

    @BeforeEach
    void resetState() {
        jdbc.sql("TRUNCATE payment_history").update();
    }
}
