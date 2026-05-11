package com.frauddetection.alert.fraudcase;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "app.fraud-cases.work-queue")
public record FraudCaseWorkQueueProperties(Duration sla) {

    public FraudCaseWorkQueueProperties {
        if (sla == null || sla.isZero() || sla.isNegative()) {
            throw new IllegalArgumentException("app.fraud-cases.work-queue.sla must be a positive duration.");
        }
    }
}
