package com.frauddetection.alert.fraudcase;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

import java.time.Duration;

@ConfigurationProperties(prefix = "app.fraud-cases.work-queue")
public record FraudCaseWorkQueueProperties(Duration sla, String cursorSigningSecret) {

    public FraudCaseWorkQueueProperties(Duration sla) {
        this(sla, "local-test-work-queue-cursor-signing-secret");
    }

    @ConstructorBinding
    public FraudCaseWorkQueueProperties(Duration sla, String cursorSigningSecret) {
        if (sla == null || sla.isZero() || sla.isNegative()) {
            throw new IllegalArgumentException("app.fraud-cases.work-queue.sla must be a positive duration.");
        }
        if (cursorSigningSecret == null || cursorSigningSecret.isBlank()) {
            throw new IllegalArgumentException("app.fraud-cases.work-queue.cursor-signing-secret must be configured.");
        }
        this.sla = sla;
        this.cursorSigningSecret = cursorSigningSecret;
    }
}
