package com.frauddetection.alert.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.frauddetection.alert.engineintelligence.EngineIntelligenceProjectionService;
import com.frauddetection.alert.mapper.ScoredTransactionDocumentMapper;
import com.frauddetection.alert.persistence.ScoredTransactionDocument;
import com.frauddetection.alert.persistence.ScoredTransactionRepository;
import com.frauddetection.common.events.contract.TransactionScoredEvent;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.same;
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
    void oldEventWithoutEngineIntelligenceKeepsProjectionUnchanged() {
        TransactionScoredEvent event = mock(TransactionScoredEvent.class);
        ScoredTransactionDocument document = new ScoredTransactionDocument();
        when(mapper.toDocument(event)).thenReturn(document);

        service.recordScoredTransaction(event);

        verify(repository).save(same(document));
        verify(projectionService).project(same(event));
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
    void rawExceptionDoesNotLeakToLogs() {
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

        assertThat(appender.list)
                .extracting(ILoggingEvent::getFormattedMessage)
                .allSatisfy(message -> assertThat(message).doesNotContain("raw-secret-stacktrace"));
    }
}
