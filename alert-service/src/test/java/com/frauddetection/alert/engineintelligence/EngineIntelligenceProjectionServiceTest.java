package com.frauddetection.alert.engineintelligence;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.common.events.intelligence.EngineIntelligenceSummary;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EngineIntelligenceProjectionServiceTest {

    private static final Instant NOW = Instant.parse("2026-06-02T08:00:00Z");
    private static final Instant LATER = Instant.parse("2026-06-02T08:05:00Z");

    private final EngineIntelligenceProjectionRepository repository = mock(EngineIntelligenceProjectionRepository.class);
    private final EngineIntelligenceProjectionMapper mapper = new EngineIntelligenceProjectionMapper(
            new EngineIntelligenceProjectionPolicy(),
            Clock.fixed(NOW, ZoneOffset.UTC)
    );
    private final EngineIntelligenceProjectionService service = new EngineIntelligenceProjectionService(repository, mapper);

    @Test
    void nullEventReturnsInvalidShape() {
        EngineIntelligenceProjectionResult result = service.project(null);

        assertThat(result.projection()).isEmpty();
        assertThat(result.omissionReason()).contains(
                EngineIntelligenceProjectionOmissionReason.ENGINE_INTELLIGENCE_INVALID_SHAPE
        );
        verify(repository, never()).save(any());
    }

    @Test
    void oldEventWithoutEngineIntelligenceKeepsProjectionUnchanged() {
        EngineIntelligenceProjectionResult result = service.project(EngineIntelligenceProjectionTestFixtures.oldEvent());

        assertThat(result.projection()).isEmpty();
        assertThat(result.omissionReason()).contains(
                EngineIntelligenceProjectionOmissionReason.ENGINE_INTELLIGENCE_ABSENT
        );
        verify(repository, never()).save(any());
    }

    @Test
    void absentEngineIntelligenceDoesNotLogWarning() {
        Logger logger = (Logger) LoggerFactory.getLogger(EngineIntelligenceProjectionService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            service.project(EngineIntelligenceProjectionTestFixtures.oldEvent());
        } finally {
            logger.detachAppender(appender);
        }

        assertThat(appender.list).isEmpty();
    }

    @Test
    void minimalEngineIntelligenceEventProjectsInternally() {
        when(repository.findById("txn-fdp95-001")).thenReturn(Optional.empty());

        EngineIntelligenceProjectionResult result =
                service.project(EngineIntelligenceProjectionTestFixtures.event(
                        EngineIntelligenceProjectionTestFixtures.minimalSummary()
                ));

        assertThat(result.projection()).isPresent();
        verify(repository).save(any(EngineIntelligenceProjection.class));
    }

    @Test
    void fullBoundedEngineIntelligenceEventProjectsInternally() {
        when(repository.findById("txn-fdp95-001")).thenReturn(Optional.empty());

        EngineIntelligenceProjection projection = service.project(EngineIntelligenceProjectionTestFixtures.event(
                EngineIntelligenceProjectionTestFixtures.fullSummary()
        )).projection().orElseThrow();

        assertThat(projection.getEngines()).hasSize(2);
        assertThat(projection.getDiagnosticSignals()).hasSize(2);
        assertThat(projection.getWarnings()).hasSize(2);
    }

    @Test
    void sameEventProjectedTwiceDoesNotDuplicateEngineIntelligence() {
        AtomicReference<EngineIntelligenceProjection> state = new AtomicReference<>();
        when(repository.findById("txn-fdp95-001")).thenAnswer(invocation -> Optional.ofNullable(state.get()));
        when(repository.save(any(EngineIntelligenceProjection.class))).thenAnswer(invocation -> {
            EngineIntelligenceProjection projection = invocation.getArgument(0);
            state.set(projection);
            return projection;
        });

        var event = EngineIntelligenceProjectionTestFixtures.event(EngineIntelligenceProjectionTestFixtures.fullSummary());
        service.project(event);
        service.project(event);

        assertThat(state.get()).isNotNull();
        assertThat(state.get().getEngines()).hasSize(2);
        assertThat(state.get().getDiagnosticSignals()).hasSize(2);
        assertThat(state.get().getWarnings()).hasSize(2);
        assertThat(state.get().getCreatedAt()).isEqualTo(NOW);
        assertThat(state.get().getUpdatedAt()).isEqualTo(NOW);
    }

    @Test
    void replayPreservesCreatedAtAndRefreshesUpdatedAt() {
        AtomicReference<EngineIntelligenceProjection> state = new AtomicReference<>();
        when(repository.findById("txn-fdp95-001")).thenAnswer(invocation -> Optional.ofNullable(state.get()));
        when(repository.save(any(EngineIntelligenceProjection.class))).thenAnswer(invocation -> {
            EngineIntelligenceProjection projection = invocation.getArgument(0);
            state.set(projection);
            return projection;
        });
        EngineIntelligenceProjectionService first = serviceAt(NOW);
        EngineIntelligenceProjectionService second = serviceAt(LATER);
        var event = EngineIntelligenceProjectionTestFixtures.event(EngineIntelligenceProjectionTestFixtures.fullSummary());

        first.project(event);
        second.project(event);

        assertThat(state.get()).isNotNull();
        assertThat(state.get().getCreatedAt()).isEqualTo(NOW);
        assertThat(state.get().getUpdatedAt()).isEqualTo(LATER);
        assertThat(state.get().getEngines()).hasSize(2);
        assertThat(state.get().getDiagnosticSignals()).hasSize(2);
        assertThat(state.get().getWarnings()).hasSize(2);
    }

    @Test
    void engineIntelligenceProjectionDoesNotChangeAlertDecisioning() {
        when(repository.findById("txn-fdp95-001")).thenReturn(Optional.empty());
        var event = EngineIntelligenceProjectionTestFixtures.event(
                EngineIntelligenceProjectionTestFixtures.disagreementSummary()
        );

        service.project(event);

        assertThat(event.fraudScore()).isEqualTo(0.82d);
        assertThat(event.riskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(event.alertRecommended()).isTrue();
    }

    @Test
    void invalidEngineIntelligenceIsOmittedWithoutSave() {
        EngineIntelligenceSummary summary = mock(EngineIntelligenceSummary.class);
        when(summary.contractVersion()).thenReturn(2);
        when(repository.findById("txn-fdp95-001")).thenReturn(Optional.empty());

        EngineIntelligenceProjectionResult result =
                service.project(EngineIntelligenceProjectionTestFixtures.event(summary));

        assertThat(result.projection()).isEmpty();
        assertThat(result.omissionReason()).contains(
                EngineIntelligenceProjectionOmissionReason.ENGINE_INTELLIGENCE_UNSUPPORTED_CONTRACT_VERSION
        );
        verify(repository, never()).save(any());
    }

    @Test
    void repositoryFailureIsOmittedBoundedly() {
        when(repository.findById("txn-fdp95-001")).thenReturn(Optional.empty());
        when(repository.save(any(EngineIntelligenceProjection.class)))
                .thenThrow(new IllegalStateException("raw-secret-stacktrace"));

        EngineIntelligenceProjectionResult result = service.project(EngineIntelligenceProjectionTestFixtures.event(
                EngineIntelligenceProjectionTestFixtures.minimalSummary()
        ));

        assertThat(result.projection()).isEmpty();
        assertThat(result.omissionReason()).contains(
                EngineIntelligenceProjectionOmissionReason.ENGINE_INTELLIGENCE_PROJECTION_FAILED
        );
    }

    @Test
    void rawPayloadDoesNotAppearInExceptionMessageOrLogs() {
        when(repository.findById("txn-fdp95-001")).thenReturn(Optional.empty());
        when(repository.save(any(EngineIntelligenceProjection.class)))
                .thenThrow(new IllegalStateException("rawPayload-secret-stacktrace-token-endpoint-payload"));
        Logger logger = (Logger) LoggerFactory.getLogger(EngineIntelligenceProjectionService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        EngineIntelligenceProjectionValidationException validationException = catchThrowableOfType(
                () -> new EngineIntelligenceProjectionPolicy().validatedTransactionId("txn-rawPayload-secret"),
                EngineIntelligenceProjectionValidationException.class
        );

        EngineIntelligenceProjectionResult result;
        try {
            result = service.project(EngineIntelligenceProjectionTestFixtures.event(
                    EngineIntelligenceProjectionTestFixtures.minimalSummary()
            ));
        } finally {
            logger.detachAppender(appender);
        }

        assertThat(result.omissionReason()).contains(
                EngineIntelligenceProjectionOmissionReason.ENGINE_INTELLIGENCE_PROJECTION_FAILED
        );
        assertThat(validationException)
                .hasMessage(EngineIntelligenceProjectionOmissionReason.ENGINE_INTELLIGENCE_INVALID_SHAPE.name())
                .hasMessageNotContaining("rawPayload")
                .hasMessageNotContaining("secret");
        assertThat(logText(appender.list))
                .contains("Engine intelligence internal projection omitted.")
                .contains("ENGINE_INTELLIGENCE_PROJECTION_FAILED")
                .doesNotContain("rawPayload", "secret", "stacktrace", "token", "endpoint", "payload");
    }

    private EngineIntelligenceProjectionService serviceAt(Instant instant) {
        return new EngineIntelligenceProjectionService(
                repository,
                new EngineIntelligenceProjectionMapper(
                        new EngineIntelligenceProjectionPolicy(),
                        Clock.fixed(instant, ZoneOffset.UTC)
                )
        );
    }

    private String logText(List<ILoggingEvent> events) {
        return events.stream()
                .map(event -> event.getFormattedMessage() + " " + event.getKeyValuePairs())
                .reduce("", (left, right) -> left + "\n" + right);
    }
}
