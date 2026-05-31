package com.frauddetection.scoring.orchestration.runtime;

import java.time.Duration;
import java.util.Objects;
import java.util.regex.Pattern;

public record FraudEngineExecutionPolicy(
        String engineId,
        Duration deadline,
        boolean required
) {
    static final Duration MAX_DEADLINE = Duration.ofSeconds(5);
    private static final Pattern BOUNDED_ENGINE_ID = Pattern.compile("[a-z0-9._-]{1,64}");

    public FraudEngineExecutionPolicy {
        Objects.requireNonNull(engineId, "engineId is required");
        if (!BOUNDED_ENGINE_ID.matcher(engineId).matches()) {
            throw new IllegalArgumentException("engineId must be bounded");
        }
        validateDeadline(deadline);
    }

    static void validateDeadline(Duration deadline) {
        Objects.requireNonNull(deadline, "deadline is required");
        if (deadline.isZero() || deadline.isNegative()) {
            throw new IllegalArgumentException("deadline must be positive");
        }
        if (deadline.compareTo(MAX_DEADLINE) > 0) {
            throw new IllegalArgumentException("deadline exceeds bounded maximum");
        }
    }
}
