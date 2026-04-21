package com.frauddetection.simulator.domain;

import com.frauddetection.simulator.api.ReplaySourceType;

import java.time.Instant;

public record ReplayRun(
        ReplayState state,
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
    public static ReplayRun idle() {
        return new ReplayRun(ReplayState.IDLE, null, null, 0, 0, 0, null, null, null, "Replay has not started yet.");
    }
}
