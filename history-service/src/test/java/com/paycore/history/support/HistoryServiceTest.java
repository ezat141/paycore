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
        "spring.datasource.hikari.data-source-properties.socketTimeout=2"})
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
