package com.frauddetection.alert.observability.regulated;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

@Component
public class RegulatedMutationRecoveryMetricsRecorder {

    private final MeterRegistry meterRegistry;
    private final AtomicLong recoveryRequired = new AtomicLong(0);
    private final AtomicLong oldestAgeSeconds = new AtomicLong(0);
    private final AtomicLong failedTerminal = new AtomicLong(0);
    private final AtomicLong repeatedFailures = new AtomicLong(0);

    public RegulatedMutationRecoveryMetricsRecorder(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        Gauge.builder("regulated_mutation_recovery_required_count", recoveryRequired, AtomicLong::get)
                .register(meterRegistry);
        Gauge.builder("regulated_mutation_recovery_oldest_age_seconds", oldestAgeSeconds, AtomicLong::get)
                .register(meterRegistry);
        Gauge.builder("regulated_mutation_recovery_failed_terminal_count", failedTerminal, AtomicLong::get)
                .register(meterRegistry);
        Gauge.builder("regulated_mutation_recovery_repeated_failures_count", repeatedFailures, AtomicLong::get)
                .register(meterRegistry);
    }

    public void recordBacklog(long recoveryRequiredCount, Long oldestRecoveryAgeSeconds) {
        recoveryRequired.set(Math.max(0L, recoveryRequiredCount));
        oldestAgeSeconds.set(oldestRecoveryAgeSeconds == null ? 0L : Math.max(0L, oldestRecoveryAgeSeconds));
    }

    public void recordBacklog(
            long recoveryRequiredCount,
            Long oldestRecoveryAgeSeconds,
            long failedTerminalCount,
            long repeatedFailureCount
    ) {
        recordBacklog(recoveryRequiredCount, oldestRecoveryAgeSeconds);
        failedTerminal.set(Math.max(0L, failedTerminalCount));
        repeatedFailures.set(Math.max(0L, repeatedFailureCount));
    }

    public void recordOutcome(String outcome) {
        Counter.builder("regulated_mutation_recovery_outcome_total")
                .tag("outcome", normalizeOutcome(outcome))
                .register(meterRegistry)
                .increment();
    }

    private String normalizeOutcome(String outcome) {
        return switch (outcome) {
            case "RECOVERED", "STILL_PENDING", "RECOVERY_REQUIRED", "FAILED_TERMINAL" -> outcome;
            default -> "RECOVERY_REQUIRED";
        };
    }
}
