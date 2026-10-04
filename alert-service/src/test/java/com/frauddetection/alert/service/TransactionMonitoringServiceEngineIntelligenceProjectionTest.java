package com.frauddetection.alert.service;

import com.frauddetection.alert.engineintelligence.EngineIntelligenceProjectionMapper;
import com.frauddetection.alert.engineintelligence.EngineIntelligenceProjectionOmissionReason;
import com.frauddetection.alert.engineintelligence.EngineIntelligenceProjectionPolicy;
import com.frauddetection.alert.engineintelligence.EngineIntelligenceProjectionRepository;
import com.frauddetection.alert.engineintelligence.EngineIntelligenceProjectionResult;
import com.frauddetection.alert.engineintelligence.EngineIntelligenceProjectionService;
import com.frauddetection.alert.mapper.ScoredTransactionDocumentMapper;
import com.frauddetection.alert.domain.ScoringOccurrenceAdmissionResult;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.alert.persistence.ScoredTransactionDocument;
import com.frauddetection.alert.persistence.ScoredTransactionProjectionWriter;
import com.frauddetection.alert.persistence.ScoredTransactionRepository;
import com.frauddetection.common.events.contract.TransactionScoredEvent;
import com.frauddetection.common.events.enums.RiskLevel;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TransactionMonitoringServiceEngineIntelligenceProjectionTest {

    private final ScoredTransactionRepository repository = mock(ScoredTransactionRepository.class);
    private final ScoredTransactionDocumentMapper mapper = mock(ScoredTransactionDocumentMapper.class);
    private final EngineIntelligenceProjectionService projectionService = mock(EngineIntelligenceProjectionService.class);
    private final ScoredTransactionProjectionWriter projectionWriter = mock(ScoredTransactionProjectionWriter.class);
    private final TransactionMonitoringService service = new TransactionMonitoringService(
            repository,
            mapper,
            mock(MongoTemplate.class),
            new ScoredTransactionSearchPolicy(),
            projectionService,
            projectionWriter
    );

    @BeforeEach
    void acceptBaseProjectionByDefault() {
        when(projectionWriter.write(any())).thenReturn(new ScoringOccurrenceAdmissionResult(
                ScoringOccurrenceAdmissionResult.Outcome.APPLIED_NEW,
                ScoringOccurrenceAdmissionResult.ReasonCode.FIRST_OCCURRENCE_ACCEPTED
        ));
    }

    @Test
    void eventStillInvokesInternalProjectionBoundaryAfterBaseProjectionWrite() {
        TransactionScoredEvent event = mock(TransactionScoredEvent.class);
        ScoredTransactionDocument document = new ScoredTransactionDocument();
        ScoringOccurrenceAdmissionResult admission = new ScoringOccurrenceAdmissionResult(
                ScoringOccurrenceAdmissionResult.Outcome.APPLIED_NEW,
                ScoringOccurrenceAdmissionResult.ReasonCode.FIRST_OCCURRENCE_ACCEPTED
        );
        when(mapper.toDocument(event)).thenReturn(document);
        when(projectionWriter.write(document)).thenReturn(admission);

        ScoringOccurrenceAdmissionResult result = service.recordScoredTransaction(event);

        assertThat(result).isSameAs(admission);
        verify(projectionWriter).write(same(document));
        verify(projectionService).projectCurrentOccurrence(same(event));
    }

    @Test
    void rejectedOccurrenceCannotReachEngineIntelligenceProjection() {
        TransactionScoredEvent event = mock(TransactionScoredEvent.class);
        ScoredTransactionDocument document = new ScoredTransactionDocument();
        ScoringOccurrenceAdmissionResult rejected = new ScoringOccurrenceAdmissionResult(
                ScoringOccurrenceAdmissionResult.Outcome.STALE_REJECTED,
                ScoringOccurrenceAdmissionResult.ReasonCode.OLDER_OCCURRENCE_REJECTED
        );
        when(mapper.toDocument(event)).thenReturn(document);
        when(projectionWriter.write(document)).thenReturn(rejected);

        ScoringOccurrenceAdmissionResult result = service.recordScoredTransaction(event);

        assertThat(result).isSameAs(rejected);
        verify(projectionService, never()).projectCurrentOccurrence(any());
    }

    @Test
    void oldEventWithoutEngineIntelligenceDoesNotCreateProjectionDocumentThroughMonitoringService() {
        ScoredTransactionRepository baseRepository = mock(ScoredTransactionRepository.class);
        EngineIntelligenceProjectionRepository projectionRepository = mock(EngineIntelligenceProjectionRepository.class);
        ScoredTransactionDocumentMapper documentMapper = new ScoredTransactionDocumentMapper();
        TransactionMonitoringService monitoringService = new TransactionMonitoringService(
                baseRepository,
                documentMapper,
                mock(MongoTemplate.class),
                new ScoredTransactionSearchPolicy(),
                new EngineIntelligenceProjectionService(
                        projectionRepository,
                        new EngineIntelligenceProjectionMapper(new EngineIntelligenceProjectionPolicy()),
                        new AlertServiceMetrics(new SimpleMeterRegistry())
                ),
                projectionWriter
        );
        TransactionScoredEvent event = mock(TransactionScoredEvent.class);
        when(event.eventId()).thenReturn("event-current-1");
        when(event.createdAt()).thenReturn(java.time.Instant.parse("2026-10-03T12:00:00Z"));
        when(event.transactionId()).thenReturn("txn-fdp95-old");
        when(event.fraudScore()).thenReturn(0.82d);
        when(event.riskLevel()).thenReturn(RiskLevel.HIGH);
        when(event.alertRecommended()).thenReturn(true);
        when(event.reasonCodes()).thenReturn(List.of("HIGH_VELOCITY"));

        assertThatCode(() -> monitoringService.recordScoredTransaction(event)).doesNotThrowAnyException();

        var captor = org.mockito.ArgumentCaptor.forClass(ScoredTransactionDocument.class);
        verify(projectionWriter).write(captor.capture());
        verify(projectionRepository, never()).save(any());
        assertThat(captor.getValue().getFraudScore()).isEqualTo(0.82d);
        assertThat(captor.getValue().getRiskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(captor.getValue().getAlertRecommended()).isTrue();
    }

    @Test
    void projectionServiceReturnsOmissionDoesNotBreakBaseProjection() {
        TransactionScoredEvent event = mock(TransactionScoredEvent.class);
        ScoredTransactionDocument document = new ScoredTransactionDocument();
        when(mapper.toDocument(event)).thenReturn(document);
        when(projectionService.projectCurrentOccurrence(event)).thenReturn(EngineIntelligenceProjectionResult.omitted(
                EngineIntelligenceProjectionOmissionReason.ENGINE_INTELLIGENCE_INVALID_SHAPE
        ));

        assertThatCode(() -> service.recordScoredTransaction(event)).doesNotThrowAnyException();

        verify(projectionWriter).write(same(document));
    }

    @Test
    void projectionExceptionPropagatesForOccurrenceTransactionRollback() {
        TransactionScoredEvent event = mock(TransactionScoredEvent.class);
        ScoredTransactionDocument document = new ScoredTransactionDocument();
        when(mapper.toDocument(event)).thenReturn(document);
        doThrow(new IllegalStateException("raw-secret-stacktrace")).when(projectionService).projectCurrentOccurrence(event);

        assertThatThrownBy(() -> service.recordScoredTransaction(event))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("raw-secret-stacktrace");

        verify(projectionWriter).write(same(document));
    }
}
