package com.frauddetection.scoring.orchestration.aggregation;

import com.frauddetection.common.events.intelligence.EngineIntelligenceSummary;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;

import static com.frauddetection.scoring.orchestration.aggregation.EngineIntelligenceEmissionTestSupport.provider;
import static com.frauddetection.scoring.orchestration.aggregation.EngineIntelligenceEmissionTestSupport.request;
import static com.frauddetection.scoring.orchestration.aggregation.EngineIntelligenceEmissionTestSupport.service;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class EngineIntelligenceEmissionMetricsTest {

    @Test
    void disabledRecordsSkippedDisabledOnly() {
        EngineIntelligenceEmissionMetrics metrics = mock(EngineIntelligenceEmissionMetrics.class);
        ObjectProvider<EngineIntelligenceDiagnosticEnrichmentPipeline> provider = provider(
                mock(EngineIntelligenceDiagnosticEnrichmentPipeline.class)
        );
        var service = new EngineIntelligenceEmissionService(
                new com.frauddetection.scoring.config.EngineIntelligenceEmissionProperties(false),
                provider,
                metrics
        );

        assertThat(service.emitIfEnabled(request())).isEmpty();
        verify(metrics).recordSkippedDisabled();
        verify(metrics, never()).recordAttempt();
        verify(metrics, never()).recordSuccess();
        verify(metrics, never()).recordOmitted(any());
        verify(metrics, never()).recordLatency(any());
        verifyNoInteractions(provider);
    }

    @Test
    void enabledSuccessRecordsAttemptSuccessLatency() {
        EngineIntelligenceEmissionMetrics metrics = mock(EngineIntelligenceEmissionMetrics.class);
        EngineIntelligenceDiagnosticEnrichmentPipeline pipeline =
                mock(EngineIntelligenceDiagnosticEnrichmentPipeline.class);
        EngineIntelligenceSummary summary = mock(EngineIntelligenceSummary.class);
        when(pipeline.enrich(any())).thenReturn(Optional.of(summary));

        assertThat(service(true, pipeline, metrics).emitIfEnabled(request())).contains(summary);
        verify(metrics).recordAttempt();
        verify(metrics).recordSuccess();
        verify(metrics, never()).recordOmitted(any());
        verify(metrics).recordLatency(any(Duration.class));
    }

    @Test
    void enabledEmptyRecordsEmptyResultOmissionAndLatency() {
        EngineIntelligenceEmissionMetrics metrics = mock(EngineIntelligenceEmissionMetrics.class);
        EngineIntelligenceDiagnosticEnrichmentPipeline pipeline =
                mock(EngineIntelligenceDiagnosticEnrichmentPipeline.class);
        when(pipeline.enrich(any())).thenReturn(Optional.empty());

        assertThat(service(true, pipeline, metrics).emitIfEnabled(request())).isEmpty();
        verify(metrics).recordAttempt();
        verify(metrics).recordOmitted(EngineIntelligenceEmissionOmissionReason.EMPTY_RESULT);
        verify(metrics, never()).recordSuccess();
        verify(metrics).recordLatency(any(Duration.class));
    }

    @Test
    void missingPipelineRecordsPipelineUnavailableAndLatency() {
        EngineIntelligenceEmissionMetrics metrics = mock(EngineIntelligenceEmissionMetrics.class);

        assertThat(service(true, null, metrics).emitIfEnabled(request())).isEmpty();
        verify(metrics).recordAttempt();
        verify(metrics).recordOmitted(EngineIntelligenceEmissionOmissionReason.PIPELINE_UNAVAILABLE);
        verify(metrics, never()).recordSuccess();
        verify(metrics).recordLatency(any(Duration.class));
    }

    @Test
    void runtimeFailureRecordsUnknownFailureAndLatency() {
        EngineIntelligenceEmissionMetrics metrics = mock(EngineIntelligenceEmissionMetrics.class);
        EngineIntelligenceDiagnosticEnrichmentPipeline pipeline =
                mock(EngineIntelligenceDiagnosticEnrichmentPipeline.class);
        when(pipeline.enrich(any())).thenThrow(new IllegalStateException("raw-secret-must-not-be-a-label"));

        assertThat(service(true, pipeline, metrics).emitIfEnabled(request())).isEmpty();
        verify(metrics).recordAttempt();
        verify(metrics).recordOmitted(EngineIntelligenceEmissionOmissionReason.UNKNOWN_FAILURE);
        verify(metrics, never()).recordSuccess();
        verify(metrics).recordLatency(any(Duration.class));
    }

    @Test
    void metricsFailureDoesNotChangeEmissionResult() {
        EngineIntelligenceEmissionMetrics metrics = mock(EngineIntelligenceEmissionMetrics.class);
        EngineIntelligenceDiagnosticEnrichmentPipeline pipeline =
                mock(EngineIntelligenceDiagnosticEnrichmentPipeline.class);
        EngineIntelligenceSummary summary = mock(EngineIntelligenceSummary.class);
        doThrow(new IllegalStateException("metrics-backend-failure")).when(metrics).recordLatency(any());
        when(pipeline.enrich(any())).thenReturn(Optional.of(summary));

        assertThat(service(true, pipeline, metrics).emitIfEnabled(request())).contains(summary);
    }

    @Test
    void metricsDoNotAcceptRawExceptionMessage() {
        assertThat(parameterTypes()).doesNotContain(String.class, Throwable.class, Exception.class);
    }

    @Test
    void metricsApiDoesNotAcceptRawOrHighCardinalityInputs() throws Exception {
        String source = Files.readString(moduleRoot().resolve(
                "src/main/java/com/frauddetection/scoring/orchestration/aggregation/EngineIntelligenceEmissionMetrics.java"
        ));

        assertThat(source).doesNotContain(
                "transactionId",
                "customerId",
                "accountId",
                "cardId",
                "merchantId",
                "endpoint",
                "payload"
        );
        assertThat(Arrays.asList(EngineIntelligenceEmissionOmissionReason.values())).containsExactly(
                EngineIntelligenceEmissionOmissionReason.DISABLED,
                EngineIntelligenceEmissionOmissionReason.PIPELINE_UNAVAILABLE,
                EngineIntelligenceEmissionOmissionReason.EMPTY_RESULT,
                EngineIntelligenceEmissionOmissionReason.ORCHESTRATOR_FAILURE,
                EngineIntelligenceEmissionOmissionReason.AGGREGATION_FAILURE,
                EngineIntelligenceEmissionOmissionReason.MAPPER_FAILURE,
                EngineIntelligenceEmissionOmissionReason.UNKNOWN_FAILURE
        );
    }

    private Class<?>[] parameterTypes() {
        return Arrays.stream(EngineIntelligenceEmissionMetrics.class.getDeclaredMethods())
                .flatMap(method -> Arrays.stream(method.getParameterTypes()))
                .toArray(Class<?>[]::new);
    }

    private Path moduleRoot() {
        Path current = Path.of(".").toAbsolutePath().normalize();
        return Files.exists(current.resolve("src/main")) ? current : current.resolve("fraud-scoring-service");
    }
}
