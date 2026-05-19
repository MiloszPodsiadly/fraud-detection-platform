package com.frauddetection.alert.suspicious.api.telemetry;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class SuspiciousTransactionQueryTelemetrySlowLogTest {

    @Test
    void slowLogUsesOnlyBoundedFields(CapturedOutput output) {
        SuspiciousTransactionQueryTelemetryRecorder recorder =
                new SuspiciousTransactionQueryTelemetryRecorder(new SimpleMeterRegistry(), Duration.ofMillis(50));

        recorder.record(new SuspiciousTransactionQueryTelemetrySnapshot(
                "search",
                "success",
                "customer",
                "1",
                "1_10",
                "false",
                "true",
                "50_100ms",
                Duration.ofMillis(75)
        ));

        assertThat(output).contains(
                "slow_query",
                "endpoint=search",
                "outcome=success",
                "queryShape=customer",
                "filterCountBucket=1",
                "resultSizeBucket=1_10",
                "hasNext=false",
                "cursorUsed=true",
                "durationBucket=50_100ms"
        );
        assertThat(output).doesNotContain(
                "customer-secret",
                "alert-secret",
                "opaque-cursor",
                "suspicious-",
                "transaction-",
                "raw",
                "Exception:"
        );
    }

    @Test
    void recorderFailureLogDoesNotExposeExceptionMessage(CapturedOutput output) {
        SuspiciousTransactionQueryTelemetryRecorder recorder =
                new SuspiciousTransactionQueryTelemetryRecorder(null, Duration.ofMillis(500));

        recorder.record(new SuspiciousTransactionQueryTelemetrySnapshot(
                "read",
                "error",
                "id_lookup",
                "1",
                "unknown",
                "unknown",
                "unknown",
                "lt_50ms",
                Duration.ofMillis(1)
        ));

        assertThat(output)
                .contains("query telemetry recording failed")
                .doesNotContain("NullPointerException");
    }

    @Test
    void arbitrarySnapshotValuesDoNotReachSlowLog(CapturedOutput output) {
        SuspiciousTransactionQueryTelemetryRecorder recorder =
                new SuspiciousTransactionQueryTelemetryRecorder(new SimpleMeterRegistry(), Duration.ofMillis(50));

        recorder.record(new SuspiciousTransactionQueryTelemetrySnapshot(
                "customer-123",
                "exception-message-secret",
                "rawFilters={customerId=abc}",
                "account-456",
                "transaction-777",
                "alert-999",
                "cursor-eyJ-secret",
                "source-event-999",
                Duration.ofMillis(600)
        ));

        assertThat(output)
                .contains(
                        "endpoint=search",
                        "outcome=error",
                        "queryShape=unknown",
                        "filterCountBucket=0",
                        "resultSizeBucket=unknown",
                        "hasNext=unknown",
                        "cursorUsed=unknown",
                        "durationBucket=500ms_plus"
                )
                .doesNotContain(
                        "customer-123",
                        "account-456",
                        "alert-999",
                        "cursor-eyJ",
                        "transaction-777",
                        "source-event-999",
                        "correlation-123",
                        "rawFilters={customerId=abc}",
                        "exception-message-secret"
                );
    }
}
