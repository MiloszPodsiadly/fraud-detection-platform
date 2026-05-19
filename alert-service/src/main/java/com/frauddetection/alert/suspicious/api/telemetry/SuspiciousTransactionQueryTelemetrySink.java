package com.frauddetection.alert.suspicious.api.telemetry;

@FunctionalInterface
public interface SuspiciousTransactionQueryTelemetrySink {

    void record(SuspiciousTransactionQueryTelemetrySnapshot snapshot);

    static SuspiciousTransactionQueryTelemetrySink noop() {
        return snapshot -> {
        };
    }
}
