package com.frauddetection.simulator.api;

import java.time.Instant;

public record ReplayStatusResponse(
        String state,
        ReplaySourceType sourceType,
        Integer requestedEvents,
        long processedEvents,
        long publishedEvents,
        long failedEvents,
        Long throttleMillis,
        Instant startedAt,
        Instant finishedAt,
        String message
) {
}
