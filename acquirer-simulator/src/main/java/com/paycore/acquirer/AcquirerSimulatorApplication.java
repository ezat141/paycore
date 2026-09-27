package com.paycore.acquirer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * A stand-in for an external card network. Deliberately unauthenticated: in production this
 * link would be a private, mutually authenticated connection outside the OAuth platform, so
 * putting AuthCore tokens on it here would model something that does not exist.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class AcquirerSimulatorApplication {

    public static void main(String[] args) {
        SpringApplication.run(AcquirerSimulatorApplication.class, args);
    }
}
