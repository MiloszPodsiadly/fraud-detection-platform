package com.frauddetection.scoring.source;

import com.frauddetection.common.events.source.MongoSourceOccurrenceStore;
import com.frauddetection.common.events.contract.TransactionEnrichedEvent;
import com.frauddetection.scoring.messaging.TransactionScoredEventPublisher;
import org.junit.jupiter.api.Test;


import static com.frauddetection.common.events.source.MongoSourceOccurrenceStore.Status.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SourceOccurrencePublisherTest {
    @Test
    void brokerFailureReleasesLeaseAndRecordsBoundedRecoveryCode() {
        var store = mock(MongoSourceOccurrenceStore.class);
        var prepared = new MongoSourceOccurrenceStore.Result(PENDING, "occurrence-key", null);
        var claimed = new MongoSourceOccurrenceStore.Result(PUBLISHING, "occurrence-key", null, "lease-token");
        when(store.resume(nullable(TransactionEnrichedEvent.class), eq("initial")))
                .thenReturn(new MongoSourceOccurrenceStore.Result(ABSENT, null, null));
        when(store.prepare(nullable(TransactionEnrichedEvent.class), eq("initial"), any())).thenReturn(prepared);
        when(store.claim("occurrence-key")).thenReturn(claimed);
        when(store.recordPublishFailure("occurrence-key", "lease-token", "BROKER_ACK_UNRESOLVED"))
                .thenReturn(new MongoSourceOccurrenceStore.Result(PENDING, null, null));

        var publisher = new SourceOccurrencePublisher(store, event -> { throw new IllegalStateException("broker unavailable"); });
        assertThatThrownBy(() -> publisher.publish(null, "initial", () -> null))
                .hasMessage("SOURCE_SCORED_PUBLICATION_UNRESOLVED");
        verify(store).recordPublishFailure("occurrence-key", "lease-token", "BROKER_ACK_UNRESOLVED");
        verify(store, never()).confirm(anyString(), any(), anyString());
    }

    @Test
    void redriveUsesStoredOccurrenceAndDoesNotRecompute() {
        var store = mock(MongoSourceOccurrenceStore.class);
        var redriven = new MongoSourceOccurrenceStore.Result(PENDING, "occurrence-key", null);
        var claimed = new MongoSourceOccurrenceStore.Result(PUBLISHING, "occurrence-key", null, "lease-token");
        var published = new MongoSourceOccurrenceStore.Result(PUBLISHED, "occurrence-key", null);
        when(store.redrive("occurrence-key", MongoSourceOccurrenceStore.RedriveReason.BROKER_RECOVERED)).thenReturn(redriven);
        when(store.claim("occurrence-key")).thenReturn(claimed);
        when(store.confirm("occurrence-key", null, "lease-token")).thenReturn(published);
        var publisher = new SourceOccurrencePublisher(store, event -> { });

        assertThat(publisher.redriveExhausted("occurrence-key", MongoSourceOccurrenceStore.RedriveReason.BROKER_RECOVERED)).isTrue();
        verify(store).redrive("occurrence-key", MongoSourceOccurrenceStore.RedriveReason.BROKER_RECOVERED);
        verify(store).confirm("occurrence-key", null, "lease-token");
    }

    @Test
    void exhaustedOccurrenceIsNotReportedAsRecoveredWhenRedriveIsDenied() {
        var store = mock(MongoSourceOccurrenceStore.class);
        when(store.redrive("occurrence-key", MongoSourceOccurrenceStore.RedriveReason.OPERATOR_RECONCILIATION))
                .thenReturn(new MongoSourceOccurrenceStore.Result(RETRY_EXHAUSTED, null, null));
        var publisher = new SourceOccurrencePublisher(store, event -> { });

        assertThat(publisher.redriveExhausted("occurrence-key", MongoSourceOccurrenceStore.RedriveReason.OPERATOR_RECONCILIATION)).isFalse();
        verify(store, never()).claim(anyString());
    }
}
