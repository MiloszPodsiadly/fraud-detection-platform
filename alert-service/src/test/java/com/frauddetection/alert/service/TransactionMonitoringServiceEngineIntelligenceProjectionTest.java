package com.frauddetection.alert.service;

import com.frauddetection.alert.domain.ScoringOccurrenceAdmissionResult;
import com.frauddetection.alert.mapper.ScoredTransactionDocumentMapper;
import com.frauddetection.alert.persistence.ScoredTransactionDocument;
import com.frauddetection.alert.persistence.ScoredTransactionProjectionWriter;
import com.frauddetection.alert.persistence.ScoredTransactionRepository;
import com.frauddetection.common.events.contract.TransactionScoredEvent;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TransactionMonitoringServiceEngineIntelligenceProjectionTest {

    private final ScoredTransactionDocumentMapper mapper = mock(ScoredTransactionDocumentMapper.class);
    private final ScoredTransactionProjectionWriter projectionWriter = mock(ScoredTransactionProjectionWriter.class);
    private final TransactionMonitoringService service = new TransactionMonitoringService(
            mock(ScoredTransactionRepository.class),
            mapper,
            mock(MongoTemplate.class),
            new ScoredTransactionSearchPolicy(),
            projectionWriter
    );

    @Test
    void baselineMonitoringOwnsOnlyAuthoritativeScoredTransactionAdmission() {
        TransactionScoredEvent event = mock(TransactionScoredEvent.class);
        ScoredTransactionDocument document = new ScoredTransactionDocument();
        ScoringOccurrenceAdmissionResult admission = new ScoringOccurrenceAdmissionResult(
                ScoringOccurrenceAdmissionResult.Outcome.APPLIED_NEW,
                ScoringOccurrenceAdmissionResult.ReasonCode.FIRST_OCCURRENCE_ACCEPTED
        );
        when(mapper.toDocument(event)).thenReturn(document);
        when(projectionWriter.write(document)).thenReturn(admission);

        assertThat(service.recordScoredTransaction(event)).isSameAs(admission);

        verify(projectionWriter).write(same(document));
    }

    @Test
    void rejectedOccurrenceRemainsRejectedWithoutDependentPersistence() {
        TransactionScoredEvent event = mock(TransactionScoredEvent.class);
        when(mapper.toDocument(event)).thenReturn(new ScoredTransactionDocument());
        ScoringOccurrenceAdmissionResult rejected = new ScoringOccurrenceAdmissionResult(
                ScoringOccurrenceAdmissionResult.Outcome.STALE_REJECTED,
                ScoringOccurrenceAdmissionResult.ReasonCode.OLDER_OCCURRENCE_REJECTED
        );
        when(projectionWriter.write(any())).thenReturn(rejected);

        assertThat(service.recordScoredTransaction(event)).isSameAs(rejected);
    }
}
