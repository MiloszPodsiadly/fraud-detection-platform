package com.frauddetection.alert.security.telemetry;

import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@ExtendWith(OutputCaptureExtension.class)
class SecurityDeniedAccessTelemetryNoRawExceptionMessageTest {

    @Test
    void runtimeRecorderFailureDoesNotLeakExceptionMessage(CapturedOutput output) {
        SecurityDeniedAccessTelemetryRecorder recorder = new SecurityDeniedAccessTelemetryRecorder(mock(MeterRegistry.class));

        recorder.record(new SecurityDeniedAccessSnapshot(
                "suspicious_transaction_read",
                "forbidden",
                "GET",
                "authenticated"
        ));

        assertThat(output)
                .contains("Security denied-access telemetry recording failed")
                .contains("routeGroup=suspicious_transaction_read", "outcome=forbidden", "method=GET", "authState=authenticated")
                .doesNotContain(
                        "NullPointerException",
                        "customerId=customer-secret",
                        "cursor=cursor-secret",
                        "token=token-secret"
                );
    }
}
