package com.frauddetection.alert.engineintelligence;

import com.frauddetection.alert.persistence.ScoringOccurrenceFingerprint;
import com.frauddetection.common.events.contract.TransactionScoredEvent;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EngineIntelligencePendingProjectionServiceTest {

    private final MongoTemplate mongoTemplate = mock(MongoTemplate.class);
    private final EngineIntelligencePendingProjectionService service =
            new EngineIntelligencePendingProjectionService(
                    mongoTemplate,
                    new EngineIntelligenceProjectionPolicy()
            );

    @Test
    void persistsOnlyBoundedProjectionEnvelopeBeforeReturning() {
        TransactionScoredEvent event = EngineIntelligenceProjectionTestFixtures.event(
                EngineIntelligenceProjectionTestFixtures.minimalSummary()
        );

        service.defer(event);

        ArgumentCaptor<EngineIntelligencePendingProjection> captor =
                ArgumentCaptor.forClass(EngineIntelligencePendingProjection.class);
        verify(mongoTemplate).insert(captor.capture());
        EngineIntelligencePendingProjection stored = captor.getValue();
        assertThat(stored.getSourceEventId()).isEqualTo(event.eventId());
        assertThat(stored.getTransactionId()).isEqualTo(event.transactionId());
        assertThat(stored.getSourceEventCreatedAt()).isEqualTo(event.createdAt());
        assertThat(stored.getSourceEventCreatedAtText()).isEqualTo(event.createdAt().toString());
        assertThat(stored.getSourceEventCreatedAtEpochSecond()).isEqualTo(event.createdAt().getEpochSecond());
        assertThat(stored.getSourceEventCreatedAtNano()).isEqualTo(event.createdAt().getNano());
        assertThat(stored.getSourceEventFingerprint()).isEqualTo(ScoringOccurrenceFingerprint.from(event));
        assertThat(stored.getEngineIntelligence()).isEqualTo(event.engineIntelligence());
        assertThat(stored.getStatus()).isEqualTo(EngineIntelligencePendingProjectionStatus.PENDING);
        assertThat(EngineIntelligencePendingProjection.class.getDeclaredFields())
                .extracting(java.lang.reflect.Field::getName)
                .doesNotContain("customerId", "accountId", "featureSnapshot", "rawPayload", "mlPredictionEvidence");
    }

    @Test
    void conflictingDuplicateSourceIdentityFailsClosed() {
        TransactionScoredEvent event = EngineIntelligenceProjectionTestFixtures.event(
                EngineIntelligenceProjectionTestFixtures.minimalSummary()
        );
        doThrow(new DuplicateKeyException("duplicate")).when(mongoTemplate).insert(
                org.mockito.ArgumentMatchers.any(EngineIntelligencePendingProjection.class)
        );
        EngineIntelligencePendingProjection existing = new EngineIntelligencePendingProjection();
        existing.setSourceEventId(event.eventId());
        existing.setTransactionId("different-transaction");
        when(mongoTemplate.findById(event.eventId(), EngineIntelligencePendingProjection.class))
                .thenReturn(existing);

        assertThatThrownBy(() -> service.defer(event))
                .isInstanceOf(EngineIntelligenceProjectionService.SourceOccurrencePayloadConflictException.class)
                .hasMessage("SOURCE_OCCURRENCE_PAYLOAD_CONFLICT");
    }
}
