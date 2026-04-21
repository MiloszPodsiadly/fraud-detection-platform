package com.frauddetection.enricher.service;

import com.frauddetection.common.events.contract.TransactionEnrichedEvent;
import com.frauddetection.common.events.model.Money;
import com.frauddetection.common.testsupport.fixture.TransactionFixtures;
import com.frauddetection.enricher.domain.EnrichedTransactionFeatures;
import com.frauddetection.enricher.domain.FeatureStoreSnapshot;
import com.frauddetection.enricher.mapper.TransactionEnrichedEventMapper;
import com.frauddetection.enricher.messaging.TransactionEnrichedEventPublisher;
import com.frauddetection.enricher.persistence.FeatureStore;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TransactionFeatureEnricherServiceTest {

    @Test
    void shouldLoadSnapshotPublishEnrichedEventAndPersistState() {
        FeatureStore featureStore = mock(FeatureStore.class);
        TransactionFeatureCalculator calculator = mock(TransactionFeatureCalculator.class);
        TransactionEnrichedEventMapper mapper = mock(TransactionEnrichedEventMapper.class);
        TransactionEnrichedEventPublisher publisher = mock(TransactionEnrichedEventPublisher.class);

        var service = new TransactionFeatureEnricherService(featureStore, calculator, mapper, publisher);
        var event = TransactionFixtures.rawTransaction().build();
        var snapshot = new FeatureStoreSnapshot(1, BigDecimal.TEN, 2, Instant.now(), true);
        var features = new EnrichedTransactionFeatures(
                2,
                "PT15M",
                new Money(new BigDecimal("20.00"), "USD"),
                "PT15M",
                0.13d,
                3,
                false,
                false,
                false,
                List.of(),
                Map.of("recentTransactionCount", 2)
        );
        TransactionEnrichedEvent enrichedEvent = TransactionFixtures.enrichedTransaction().build();

        when(featureStore.loadSnapshot(event)).thenReturn(snapshot);
        when(calculator.calculate(event, snapshot)).thenReturn(features);
        when(mapper.toEvent(event, features)).thenReturn(enrichedEvent);
        when(featureStore.hasRecordedTransaction(event)).thenReturn(false);

        service.enrich(event);

        var inOrder = inOrder(featureStore, calculator, mapper, publisher);
        inOrder.verify(featureStore).hasRecordedTransaction(event);
        inOrder.verify(featureStore).loadSnapshot(event);
        inOrder.verify(calculator).calculate(event, snapshot);
        inOrder.verify(mapper).toEvent(event, features);
        inOrder.verify(publisher).publish(enrichedEvent);
        inOrder.verify(featureStore).recordTransaction(event);
    }

    @Test
    void shouldSkipDuplicateRawTransactionEvent() {
        FeatureStore featureStore = mock(FeatureStore.class);
        TransactionFeatureCalculator calculator = mock(TransactionFeatureCalculator.class);
        TransactionEnrichedEventMapper mapper = mock(TransactionEnrichedEventMapper.class);
        TransactionEnrichedEventPublisher publisher = mock(TransactionEnrichedEventPublisher.class);

        var service = new TransactionFeatureEnricherService(featureStore, calculator, mapper, publisher);
        var event = TransactionFixtures.rawTransaction().build();

        when(featureStore.hasRecordedTransaction(event)).thenReturn(true);

        service.enrich(event);

        verify(featureStore).hasRecordedTransaction(event);
        verify(featureStore, never()).loadSnapshot(event);
        verify(calculator, never()).calculate(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(mapper, never()).toEvent(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(publisher, never()).publish(org.mockito.ArgumentMatchers.any());
        verify(featureStore, never()).recordTransaction(event);
    }
}
