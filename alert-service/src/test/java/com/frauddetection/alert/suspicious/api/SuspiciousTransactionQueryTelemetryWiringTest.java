package com.frauddetection.alert.suspicious.api;

import com.frauddetection.alert.audit.read.SensitiveReadAuditService;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.alert.suspicious.api.telemetry.SuspiciousTransactionQueryTelemetryClassifier;
import com.frauddetection.alert.suspicious.api.telemetry.SuspiciousTransactionQueryTelemetryRecorder;
import com.frauddetection.alert.suspicious.api.telemetry.SuspiciousTransactionQueryTelemetrySink;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.Mockito.mock;

class SuspiciousTransactionQueryTelemetryWiringTest {

    @Test
    void suspiciousTransactionReadControllerRequiresTelemetryClassifier() {
        assertThatNullPointerException().isThrownBy(() -> new SuspiciousTransactionReadController(
                        mock(SuspiciousTransactionReadService.class),
                        mock(SensitiveReadAuditService.class),
                        mock(AlertServiceMetrics.class),
                        null,
                        snapshot -> {
                        }
                ))
                .withMessageContaining("queryTelemetryClassifier is required");
    }

    @Test
    void suspiciousTransactionReadControllerRequiresTelemetrySink() {
        assertThatNullPointerException().isThrownBy(() -> new SuspiciousTransactionReadController(
                        mock(SuspiciousTransactionReadService.class),
                        mock(SensitiveReadAuditService.class),
                        mock(AlertServiceMetrics.class),
                        new SuspiciousTransactionQueryTelemetryClassifier(),
                        null
                ))
                .withMessageContaining("queryTelemetrySink is required");
    }

    @Test
    void productionContextHasSuspiciousTransactionQueryTelemetryRecorderBean() {
        try (AnnotationConfigApplicationContext context = telemetryContext()) {
            assertThat(context.getBean(SuspiciousTransactionQueryTelemetryRecorder.class)).isNotNull();
            assertThat(context.getBean(SuspiciousTransactionQueryTelemetrySink.class))
                    .isInstanceOf(SuspiciousTransactionQueryTelemetryRecorder.class);
        }
    }

    @Test
    void productionContextDoesNotUseNoopTelemetrySink() {
        try (AnnotationConfigApplicationContext context = telemetryContext()) {
            SuspiciousTransactionQueryTelemetrySink sink = context.getBean(SuspiciousTransactionQueryTelemetrySink.class);

            assertThat(sink).isInstanceOf(SuspiciousTransactionQueryTelemetryRecorder.class);
            assertThat(sink.getClass().getName()).doesNotContain("noop", "Noop");
        }
    }

    private AnnotationConfigApplicationContext telemetryContext() {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.registerBean(MeterRegistry.class, SimpleMeterRegistry::new);
        context.registerBean(SuspiciousTransactionQueryTelemetryClassifier.class);
        context.registerBean(
                SuspiciousTransactionQueryTelemetryRecorder.class,
                () -> new SuspiciousTransactionQueryTelemetryRecorder(
                        context.getBean(MeterRegistry.class),
                        Duration.ofMillis(500)
                )
        );
        context.refresh();
        return context;
    }
}
