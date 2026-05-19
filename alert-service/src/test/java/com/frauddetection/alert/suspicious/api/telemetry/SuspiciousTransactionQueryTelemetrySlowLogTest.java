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
}
