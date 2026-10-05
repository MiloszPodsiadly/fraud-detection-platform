package com.frauddetection.alert.engineintelligence;

import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.mongodb.client.result.DeleteResult;
import com.mongodb.client.result.UpdateResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EngineIntelligencePendingProjectionWorkerTest {

    private static final Instant NOW = Instant.parse("2026-10-04T18:00:00Z");

    @Test
    void projectsClaimedEnvelopeAndDeletesItThroughLeaseFence() {
        MongoTemplate mongoTemplate = mock(MongoTemplate.class);
        EngineIntelligenceProjectionService projectionService = mock(EngineIntelligenceProjectionService.class);
        EngineIntelligencePendingProjection work = pendingWork();
        when(mongoTemplate.findAndModify(
                any(Query.class),
                any(Update.class),
                any(FindAndModifyOptions.class),
                eq(EngineIntelligencePendingProjection.class)
        )).thenReturn(work, (EngineIntelligencePendingProjection) null);
        when(projectionService.projectDeferredOccurrence(
                work.getTransactionId(),
                work.getSourceEventId(),
                work.getSourceEventCreatedAt(),
                work.getSourceEventFingerprint(),
                work.getEngineIntelligence()
        )).thenReturn(EngineIntelligenceProjectionResult.projected(mock(EngineIntelligenceProjection.class)));
        when(mongoTemplate.remove(any(Query.class), eq(EngineIntelligencePendingProjection.class)))
                .thenReturn(DeleteResult.acknowledged(1));
        when(mongoTemplate.count(any(Query.class), eq(EngineIntelligencePendingProjection.class)))
                .thenReturn(0L);
        when(mongoTemplate.updateMulti(any(Query.class), any(Update.class), eq(EngineIntelligencePendingProjection.class)))
                .thenReturn(UpdateResult.acknowledged(0L, 0L, null));

        worker(mongoTemplate, projectionService).recoverPendingProjections();

        ArgumentCaptor<Query> deleteQuery = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate).remove(deleteQuery.capture(), eq(EngineIntelligencePendingProjection.class));
        String fence = deleteQuery.getValue().getQueryObject().toString();
        assertThat(fence).contains("evt-fdp95-001", "PROCESSING", "lease-token", "leaseExpiresAt", "$gt");
    }

    @Test
    void pendingBaselineIsRescheduledWithoutHoldingThePollThread() {
        MongoTemplate mongoTemplate = mock(MongoTemplate.class);
        EngineIntelligenceProjectionService projectionService = mock(EngineIntelligenceProjectionService.class);
        EngineIntelligencePendingProjection work = pendingWork();
        when(mongoTemplate.findAndModify(any(), any(), any(), eq(EngineIntelligencePendingProjection.class)))
                .thenReturn(work, (EngineIntelligencePendingProjection) null);
        when(projectionService.projectDeferredOccurrence(any(), any(), any(), any(), any()))
                .thenThrow(new EngineIntelligenceProjectionService.CurrentScoringOccurrencePendingException());
        when(mongoTemplate.count(any(Query.class), eq(EngineIntelligencePendingProjection.class)))
                .thenReturn(1L, 0L);
        when(mongoTemplate.updateMulti(any(Query.class), any(Update.class), eq(EngineIntelligencePendingProjection.class)))
                .thenReturn(UpdateResult.acknowledged(0L, 0L, null));

        worker(mongoTemplate, projectionService).recoverPendingProjections();

        ArgumentCaptor<Update> update = ArgumentCaptor.forClass(Update.class);
        verify(mongoTemplate).updateFirst(
                any(Query.class),
                update.capture(),
                eq(EngineIntelligencePendingProjection.class)
        );
        String reschedule = update.getValue().getUpdateObject().toString();
        assertThat(reschedule).contains("PENDING", "availableAt", "BASELINE_OCCURRENCE_PENDING");
        assertThat(reschedule).contains("leaseToken", "leaseExpiresAt");
    }

    private EngineIntelligencePendingProjectionWorker worker(
            MongoTemplate mongoTemplate,
            EngineIntelligenceProjectionService projectionService
    ) {
        return new EngineIntelligencePendingProjectionWorker(
                mongoTemplate,
                projectionService,
                new EngineIntelligencePendingProjectionProperties(
                        true,
                        10,
                        5,
                        Duration.ofSeconds(5),
                        Duration.ofSeconds(30),
                        Duration.ofHours(1)
                ),
                new AlertServiceMetrics(new SimpleMeterRegistry()),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    private EngineIntelligencePendingProjection pendingWork() {
        var event = EngineIntelligenceProjectionTestFixtures.event(
                EngineIntelligenceProjectionTestFixtures.minimalSummary()
        );
        EngineIntelligencePendingProjection work = new EngineIntelligencePendingProjection();
        work.setSourceEventId(event.eventId());
        work.setTransactionId(event.transactionId());
        work.setSourceEventCreatedAt(event.createdAt());
        work.setSourceEventFingerprint("a".repeat(64));
        work.setEngineIntelligence(event.engineIntelligence());
        work.setStatus(EngineIntelligencePendingProjectionStatus.PROCESSING);
        work.setAttempts(1);
        work.setCreatedAt(NOW.minusSeconds(30));
        work.setLeaseToken("lease-token");
        work.setLeaseExpiresAt(NOW.plusSeconds(30));
        return work;
    }
}
