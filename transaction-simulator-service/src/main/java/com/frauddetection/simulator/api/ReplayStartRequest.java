package com.frauddetection.simulator.api;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

public record ReplayStartRequest(
        @NotNull ReplaySourceType sourceType,
        @NotNull @Positive Integer maxEvents,
        @NotNull @PositiveOrZero Long throttleMillis
) {
}
