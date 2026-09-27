package com.paycore.acquirer;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * @param latency      added to every authorization, so the network feels less like localhost
 * @param slowResponse how long a {@code tok_timeout} authorization stalls <em>after</em> it has
 *                     been recorded — long enough that the caller gives up on a charge that
 *                     actually happened
 */
@ConfigurationProperties("simulator")
public record SimulatorProperties(
        @DefaultValue("0ms") Duration latency,
        @DefaultValue("5s") Duration slowResponse) {
}
