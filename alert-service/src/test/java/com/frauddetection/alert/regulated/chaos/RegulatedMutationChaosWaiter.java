package com.frauddetection.alert.regulated.chaos;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Supplier;

public final class RegulatedMutationChaosWaiter {

    private RegulatedMutationChaosWaiter() {
    }

    public static String waitUntil(
            String description,
            Duration timeout,
            Duration interval,
            Supplier<ProbeResult> probe
    ) {
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(interval, "interval");
        Objects.requireNonNull(probe, "probe");

        long deadline = System.nanoTime() + timeout.toNanos();
        String lastObserved = "not attempted";
        while (System.nanoTime() < deadline) {
            ProbeResult result = Objects.requireNonNull(probe.get(), "probe result");
            lastObserved = result.observed();
            if (result.satisfied()) {
                return result.observed();
            }
            sleep(interval);
        }
        throw new IllegalStateException(description + " timed out after " + timeout + "; last_observed=" + lastObserved);
    }

    public record ProbeResult(boolean satisfied, String observed) {
        public static ProbeResult satisfied(String observed) {
            return new ProbeResult(true, observed);
        }

        public static ProbeResult waiting(String observed) {
            return new ProbeResult(false, observed);
        }

        public ProbeResult {
            Objects.requireNonNull(observed, "observed");
        }
    }

    private static void sleep(Duration interval) {
        try {
            Thread.sleep(interval.toMillis());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for FDP chaos deterministic probe", exception);
        }
    }
}
