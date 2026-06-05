package com.frauddetection.alert.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.frauddetection.alert.engineintelligence.EngineIntelligenceProjectionMapper;
import com.frauddetection.alert.engineintelligence.EngineIntelligenceProjectionOmissionReason;
import com.frauddetection.alert.engineintelligence.EngineIntelligenceProjectionPolicy;
import com.frauddetection.alert.engineintelligence.EngineIntelligenceProjectionRepository;
import com.frauddetection.alert.engineintelligence.EngineIntelligenceProjectionResult;
import com.frauddetection.alert.engineintelligence.EngineIntelligenceProjectionService;
import com.frauddetection.alert.mapper.ScoredTransactionDocumentMapper;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.alert.persistence.ScoredTransactionDocument;
import com.frauddetection.alert.persistence.ScoredTransactionRepository;
import com.frauddetection.common.events.contract.TransactionScoredEvent;
import com.frauddetection.common.events.enums.RiskLevel;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
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
    private final TransactionMonitoringService service = new TransactionMonitoringService(
            repository,
            mapper,
            mock(MongoTemplate.class),
            new ScoredTransactionSearchPolicy(),
            projectionService
    );

    @Test
    void oldEventStillInvokesInternalProjectionBoundaryAfterBaseSave() {
        TransactionScoredEvent event = mock(TransactionScoredEvent.class);
        ScoredTransactionDocument document = new ScoredTransactionDocument();
        when(mapper.toDocument(event)).thenReturn(document);

        service.recordScoredTransaction(event);

        verify(repository).save(same(document));
        verify(projectionService).project(same(event));
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
                )
        );
        TransactionScoredEvent event = mock(TransactionScoredEvent.class);
        when(event.transactionId()).thenReturn("txn-fdp95-old");
        when(event.fraudScore()).thenReturn(0.82d);
        when(event.riskLevel()).thenReturn(RiskLevel.HIGH);
        when(event.alertRecommended()).thenReturn(true);
        when(event.reasonCodes()).thenReturn(List.of("HIGH_VELOCITY"));

        assertThatCode(() -> monitoringService.recordScoredTransaction(event)).doesNotThrowAnyException();

        var captor = org.mockito.ArgumentCaptor.forClass(ScoredTransactionDocument.class);
        verify(baseRepository).save(captor.capture());
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
        when(projectionService.project(event)).thenReturn(EngineIntelligenceProjectionResult.omitted(
                EngineIntelligenceProjectionOmissionReason.ENGINE_INTELLIGENCE_INVALID_SHAPE
        ));

        assertThatCode(() -> service.recordScoredTransaction(event)).doesNotThrowAnyException();

        verify(repository).save(same(document));
    }

    @Test
    void projectionExceptionDoesNotBreakBaseProjection() {
        TransactionScoredEvent event = mock(TransactionScoredEvent.class);
        ScoredTransactionDocument document = new ScoredTransactionDocument();
        when(mapper.toDocument(event)).thenReturn(document);
        doThrow(new IllegalStateException("raw-secret-stacktrace")).when(projectionService).project(event);

        assertThatCode(() -> service.recordScoredTransaction(event)).doesNotThrowAnyException();

        verify(repository).save(same(document));
    }

    @Test
    void unexpectedProjectionRuntimeExceptionLogsBoundedMessageWithoutRawException() {
        TransactionScoredEvent event = mock(TransactionScoredEvent.class);
        when(mapper.toDocument(event)).thenReturn(new ScoredTransactionDocument());
        doThrow(new IllegalStateException("raw-secret-stacktrace")).when(projectionService).project(event);
        Logger logger = (Logger) LoggerFactory.getLogger(TransactionMonitoringService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            service.recordScoredTransaction(event);
        } finally {
            logger.detachAppender(appender);
        }

        String logText = appender.list.stream()
                .map(eventLog -> eventLog.getFormattedMessage() + " " + eventLog.getKeyValuePairs())
                .reduce("", (left, right) -> left + "\n" + right);
        assertThat(logText)
                .contains("Engine intelligence internal projection omitted.")
                .contains("ENGINE_INTELLIGENCE_PROJECTION_FAILED")
                .doesNotContain("raw-secret-stacktrace");
    }
}
