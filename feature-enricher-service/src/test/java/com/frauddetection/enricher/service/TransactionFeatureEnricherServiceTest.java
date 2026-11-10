package com.frauddetection.enricher.service;

import com.frauddetection.common.events.contract.TransactionEnrichedEvent;
import com.frauddetection.common.testsupport.fixture.TransactionFixtures;
import com.frauddetection.common.events.source.SourceLineage;
import com.frauddetection.common.events.source.SourceLineageAccess;
import com.frauddetection.common.events.source.SourceEnrichedPublication;
import com.frauddetection.common.events.source.SourceScope;
import com.frauddetection.common.events.source.SourceOriginProofs;
import com.frauddetection.common.events.source.SourceOriginProperties;
import com.frauddetection.enricher.domain.EnrichedTransactionFeatures;
import com.frauddetection.enricher.domain.FeatureStoreSnapshot;
import com.frauddetection.enricher.exception.FeatureEnrichmentException;
import com.frauddetection.enricher.mapper.TransactionEnrichedEventMapper;
import com.frauddetection.enricher.messaging.TransactionEnrichedEventPublisher;
import com.frauddetection.enricher.persistence.FeatureStore;
import com.frauddetection.enricher.persistence.FeatureNamespace;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TransactionFeatureEnricherServiceTest {

    @Test
    void authenticatedLegacyIngressContinuesEnrichmentWhenSidecarIsUnavailable() {
        FeatureStore featureStore = mock(FeatureStore.class);
        TransactionFeatureCalculator calculator = mock(TransactionFeatureCalculator.class);
        TransactionEnrichedEventMapper mapper = mock(TransactionEnrichedEventMapper.class);
        TransactionEnrichedEventPublisher publisher = mock(TransactionEnrichedEventPublisher.class);
        SourceLineageAccess lineage = mock(SourceLineageAccess.class);
        var proofs = new SourceOriginProofs(new SourceOriginProperties(true, "origin-key-1",
                Map.of("origin-key-1", Base64.getEncoder().encodeToString(new byte[32]))));
        var event = TransactionFixtures.rawTransaction().build();
        var snapshot = new FeatureStoreSnapshot(0, BigDecimal.ZERO, List.of(), 0, null, false);
        var features = new EnrichedTransactionFeatures(Map.of("recentTransactionCount", 1));
        var enriched = TransactionFixtures.enrichedTransaction().build();
        when(featureStore.hasRecordedTransaction(event, FeatureNamespace.legacy())).thenReturn(false);
        when(featureStore.loadSnapshot(event, FeatureNamespace.legacy())).thenReturn(snapshot);
        when(calculator.calculate(event, snapshot)).thenReturn(features);
        when(mapper.toEvent(event, features)).thenReturn(enriched);
        var service = new TransactionFeatureEnricherService(featureStore, calculator, mapper, publisher, lineage, proofs);

        service.enrich(event, proofs.signRaw(event, SourceOriginProofs.Assertion.PROVEN_LEGACY));

        verify(lineage, never()).raw(event);
        verify(publisher).publish(enriched, SourceOriginProofs.Assertion.PROVEN_LEGACY);
        verify(featureStore).recordTransaction(event, FeatureNamespace.legacy());
    }

    @Test
    void signedAttestedOriginWithUnavailableLineageCannotFallBackToLegacyEnrichment() {
        FeatureStore featureStore = mock(FeatureStore.class);
        SourceLineageAccess lineage = mock(SourceLineageAccess.class);
        var proofs = new SourceOriginProofs(new SourceOriginProperties(true, "origin-key-1",
                Map.of("origin-key-1", Base64.getEncoder().encodeToString(new byte[32]))));
        var event = TransactionFixtures.rawTransaction().build();
        when(lineage.raw(event)).thenReturn(SourceLineage.unavailable(SourceLineage.State.UNAVAILABLE));
        var service = new TransactionFeatureEnricherService(featureStore, mock(TransactionFeatureCalculator.class),
                mock(TransactionEnrichedEventMapper.class), mock(TransactionEnrichedEventPublisher.class), lineage, proofs);

        assertThatThrownBy(() -> service.enrich(event, proofs.signRaw(event, SourceOriginProofs.Assertion.KNOWN_ATTESTED)))
                .isInstanceOf(FeatureEnrichmentException.class).hasMessage("FEATURE_ORIGIN_UNRESOLVED");

        verifyNoInteractions(featureStore);
    }

    @Test
    void shouldLoadSnapshotPublishEnrichedEventAndPersistState() {
        FeatureStore featureStore = mock(FeatureStore.class);
        TransactionFeatureCalculator calculator = mock(TransactionFeatureCalculator.class);
        TransactionEnrichedEventMapper mapper = mock(TransactionEnrichedEventMapper.class);
        TransactionEnrichedEventPublisher publisher = mock(TransactionEnrichedEventPublisher.class);

        var service = new TransactionFeatureEnricherService(featureStore, calculator, mapper, publisher);
        var event = TransactionFixtures.rawTransaction().build();
        var snapshot = new FeatureStoreSnapshot(1, BigDecimal.TEN, List.of(), 2, Instant.now(), true);
        var features = new EnrichedTransactionFeatures(Map.of("recentTransactionCount", 2));
        TransactionEnrichedEvent enrichedEvent = TransactionFixtures.enrichedTransaction().build();

        when(featureStore.loadSnapshot(event, FeatureNamespace.legacy())).thenReturn(snapshot);
        when(calculator.calculate(event, snapshot)).thenReturn(features);
        when(mapper.toEvent(event, features)).thenReturn(enrichedEvent);
        when(featureStore.hasRecordedTransaction(event, FeatureNamespace.legacy())).thenReturn(false);

        service.enrich(event);

        var inOrder = inOrder(featureStore, calculator, mapper, publisher);
        inOrder.verify(featureStore).hasRecordedTransaction(event, FeatureNamespace.legacy());
        inOrder.verify(featureStore).loadSnapshot(event, FeatureNamespace.legacy());
        inOrder.verify(calculator).calculate(event, snapshot);
        inOrder.verify(mapper).toEvent(event, features);
        inOrder.verify(publisher).publish(enrichedEvent);
        inOrder.verify(featureStore).recordTransaction(event, FeatureNamespace.legacy());
    }

    @Test
    void shouldSkipDuplicateRawTransactionEvent() {
        FeatureStore featureStore = mock(FeatureStore.class);
        TransactionFeatureCalculator calculator = mock(TransactionFeatureCalculator.class);
        TransactionEnrichedEventMapper mapper = mock(TransactionEnrichedEventMapper.class);
        TransactionEnrichedEventPublisher publisher = mock(TransactionEnrichedEventPublisher.class);

        var service = new TransactionFeatureEnricherService(featureStore, calculator, mapper, publisher);
        var event = TransactionFixtures.rawTransaction().build();

        when(featureStore.hasRecordedTransaction(event, FeatureNamespace.legacy())).thenReturn(true);

        service.enrich(event);

        verify(featureStore).hasRecordedTransaction(event, FeatureNamespace.legacy());
        verify(featureStore, never()).loadSnapshot(event, FeatureNamespace.legacy());
        verify(calculator, never()).calculate(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(mapper, never()).toEvent(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(publisher, never()).publish(org.mockito.ArgumentMatchers.any());
        verify(featureStore, never()).recordTransaction(event, FeatureNamespace.legacy());
    }

    @Test
    void publisherFailureDoesNotContinueToRedisRecording() {
        FeatureStore featureStore = mock(FeatureStore.class);
        TransactionFeatureCalculator calculator = mock(TransactionFeatureCalculator.class);
        TransactionEnrichedEventMapper mapper = mock(TransactionEnrichedEventMapper.class);
        TransactionEnrichedEventPublisher publisher = mock(TransactionEnrichedEventPublisher.class);
        var service = new TransactionFeatureEnricherService(featureStore, calculator, mapper, publisher);
        var event = TransactionFixtures.rawTransaction().build();
        var snapshot = new FeatureStoreSnapshot(0, BigDecimal.ZERO, List.of(), 0, null, false);
        var features = new EnrichedTransactionFeatures(Map.of("recentTransactionCount", 1));
        var enrichedEvent = TransactionFixtures.enrichedTransaction().build();

        when(featureStore.hasRecordedTransaction(event, FeatureNamespace.legacy())).thenReturn(false);
        when(featureStore.loadSnapshot(event, FeatureNamespace.legacy())).thenReturn(snapshot);
        when(calculator.calculate(event, snapshot)).thenReturn(features);
        when(mapper.toEvent(event, features)).thenReturn(enrichedEvent);
        doThrow(new FeatureEnrichmentException("simulated publisher failure", new RuntimeException("unknown ack")))
                .when(publisher).publish(enrichedEvent);

        assertThatThrownBy(() -> service.enrich(event))
                .isInstanceOf(FeatureEnrichmentException.class)
                .hasMessage("simulated publisher failure");

        var inOrder = inOrder(publisher, featureStore);
        inOrder.verify(publisher).publish(enrichedEvent);
        verify(featureStore, never()).recordTransaction(event, FeatureNamespace.legacy());
    }

    @Test
    void redisRecordingFailureHappensAfterEnrichedEventWasPublished() {
        FeatureStore featureStore = mock(FeatureStore.class);
        TransactionFeatureCalculator calculator = mock(TransactionFeatureCalculator.class);
        TransactionEnrichedEventMapper mapper = mock(TransactionEnrichedEventMapper.class);
        TransactionEnrichedEventPublisher publisher = mock(TransactionEnrichedEventPublisher.class);
        var service = new TransactionFeatureEnricherService(featureStore, calculator, mapper, publisher);
        var event = TransactionFixtures.rawTransaction().build();
        var snapshot = new FeatureStoreSnapshot(0, BigDecimal.ZERO, List.of(), 0, null, false);
        var features = new EnrichedTransactionFeatures(Map.of("recentTransactionCount", 1));
        var enrichedEvent = TransactionFixtures.enrichedTransaction().build();

        when(featureStore.hasRecordedTransaction(event, FeatureNamespace.legacy())).thenReturn(false);
        when(featureStore.loadSnapshot(event, FeatureNamespace.legacy())).thenReturn(snapshot);
        when(calculator.calculate(event, snapshot)).thenReturn(features);
        when(mapper.toEvent(event, features)).thenReturn(enrichedEvent);
        doThrow(new FeatureEnrichmentException("simulated Redis recording failure", new RuntimeException("Redis unavailable")))
                .when(featureStore).recordTransaction(event, FeatureNamespace.legacy());

        assertThatThrownBy(() -> service.enrich(event))
                .isInstanceOf(FeatureEnrichmentException.class)
                .hasMessage("simulated Redis recording failure");

        var inOrder = inOrder(publisher, featureStore);
        inOrder.verify(publisher).publish(enrichedEvent);
        inOrder.verify(featureStore).recordTransaction(event, FeatureNamespace.legacy());
    }

    @Test
    void unresolvedSourceOriginFailsClosedBeforeFeatureStoreAccess() {
        for (SourceLineage.State state : List.of(SourceLineage.State.UNVERIFIED, SourceLineage.State.UNAVAILABLE,
                SourceLineage.State.CONFLICT, SourceLineage.State.REVOKED)) {
            FeatureStore featureStore = mock(FeatureStore.class);
            var service = new TransactionFeatureEnricherService(featureStore, mock(TransactionFeatureCalculator.class),
                    mock(TransactionEnrichedEventMapper.class), mock(TransactionEnrichedEventPublisher.class), access(state));

            assertThatThrownBy(() -> service.enrich(TransactionFixtures.rawTransaction().build()))
                    .isInstanceOf(FeatureEnrichmentException.class)
                    .hasMessage("FEATURE_ORIGIN_UNRESOLVED");

            verifyNoInteractions(featureStore);
        }
    }

    @Test
    void unresolvedEnrichedBindingFailsBeforeKafkaOrRedisMutation() {
        FeatureStore featureStore = mock(FeatureStore.class);
        TransactionFeatureCalculator calculator = mock(TransactionFeatureCalculator.class);
        TransactionEnrichedEventMapper mapper = mock(TransactionEnrichedEventMapper.class);
        TransactionEnrichedEventPublisher publisher = mock(TransactionEnrichedEventPublisher.class);
        var event = TransactionFixtures.rawTransaction().build();
        var snapshot = new FeatureStoreSnapshot(0, BigDecimal.ZERO, List.of(), 0, null, false);
        var features = new EnrichedTransactionFeatures(Map.of("recentTransactionCount", 1));
        var candidate = TransactionFixtures.enrichedTransaction().build();
        SourceLineage rawAuthority = verifiedAuthority();
        SourceLineageAccess lineage = new SourceLineageAccess() {
            @Override public SourceLineage raw(com.frauddetection.common.events.contract.TransactionRawEvent input) { return rawAuthority; }
            @Override public SourceLineage enriched(TransactionEnrichedEvent output) { return SourceLineage.unavailable(SourceLineage.State.UNVERIFIED); }
            @Override public SourceLineage bindEnriched(com.frauddetection.common.events.contract.TransactionRawEvent input,
                                                        TransactionEnrichedEvent output) { return SourceLineage.unavailable(SourceLineage.State.CONFLICT); }
            @Override public SourceEnrichedPublication prepareEnrichedPublication(
                    com.frauddetection.common.events.contract.TransactionRawEvent input, TransactionEnrichedEvent output) {
                return SourceEnrichedPublication.unavailable(SourceLineage.State.CONFLICT);
            }
        };
        var service = new TransactionFeatureEnricherService(featureStore, calculator, mapper, publisher, lineage);
        when(featureStore.hasRecordedTransaction(event, FeatureNamespace.attested(rawAuthority))).thenReturn(false);
        when(featureStore.loadSnapshot(event, FeatureNamespace.attested(rawAuthority))).thenReturn(snapshot);
        when(calculator.calculate(event, snapshot)).thenReturn(features);
        when(mapper.toAttestedEvent(event, features)).thenReturn(candidate);

        assertThatThrownBy(() -> service.enrich(event))
                .isInstanceOf(FeatureEnrichmentException.class)
                .hasMessage("FEATURE_LINEAGE_UNRESOLVED");

        verify(publisher, never()).publish(org.mockito.ArgumentMatchers.any());
        verify(featureStore, never()).recordTransaction(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(featureStore, never()).recordAttestedTransaction(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void alreadyAcknowledgedDurablePublicationDoesNotPublishAgain() {
        FeatureStore featureStore = mock(FeatureStore.class);
        TransactionFeatureCalculator calculator = mock(TransactionFeatureCalculator.class);
        TransactionEnrichedEventMapper mapper = mock(TransactionEnrichedEventMapper.class);
        TransactionEnrichedEventPublisher publisher = mock(TransactionEnrichedEventPublisher.class);
        var event = TransactionFixtures.rawTransaction().build();
        var snapshot = new FeatureStoreSnapshot(0, BigDecimal.ZERO, List.of(), 0, null, false);
        var features = new EnrichedTransactionFeatures(Map.of("recentTransactionCount", 1));
        var enriched = TransactionFixtures.enrichedTransaction().build();
        SourceLineage rawAuthority = verifiedAuthority();
        SourceLineageAccess lineage = new SourceLineageAccess() {
            @Override public SourceLineage raw(com.frauddetection.common.events.contract.TransactionRawEvent input) { return rawAuthority; }
            @Override public SourceLineage enriched(TransactionEnrichedEvent output) { return rawAuthority; }
            @Override public SourceLineage bindEnriched(com.frauddetection.common.events.contract.TransactionRawEvent input,
                                                        TransactionEnrichedEvent output) { return rawAuthority; }
            @Override public SourceEnrichedPublication prepareEnrichedPublication(
                    com.frauddetection.common.events.contract.TransactionRawEvent input, TransactionEnrichedEvent output) {
                return new SourceEnrichedPublication(rawAuthority, enriched, true, true);
            }
        };
        var service = new TransactionFeatureEnricherService(featureStore, calculator, mapper, publisher, lineage);
        when(featureStore.hasRecordedTransaction(event, FeatureNamespace.attested(rawAuthority))).thenReturn(false);
        when(featureStore.loadSnapshot(event, FeatureNamespace.attested(rawAuthority))).thenReturn(snapshot);
        when(calculator.calculate(event, snapshot)).thenReturn(features);
        when(mapper.toAttestedEvent(event, features)).thenReturn(enriched);

        service.enrich(event);

        verify(publisher, never()).publish(org.mockito.ArgumentMatchers.any());
        FeatureNamespace attestedNamespace = FeatureNamespace.attested(rawAuthority);
        verify(featureStore).verifyAttestedTransaction(org.mockito.ArgumentMatchers.eq(enriched),
                org.mockito.ArgumentMatchers.eq(attestedNamespace), org.mockito.ArgumentMatchers.anyString());
    }

    private SourceLineageAccess access(SourceLineage.State state) {
        return new SourceLineageAccess() {
            private SourceLineage unavailable() { return SourceLineage.unavailable(state); }
            @Override public SourceLineage raw(com.frauddetection.common.events.contract.TransactionRawEvent event) { return unavailable(); }
            @Override public SourceLineage enriched(TransactionEnrichedEvent event) { return unavailable(); }
            @Override public SourceLineage bindEnriched(com.frauddetection.common.events.contract.TransactionRawEvent input,
                                                        TransactionEnrichedEvent output) { return unavailable(); }
        };
    }

    private SourceLineage verifiedAuthority() {
        SourceScope source = mock(SourceScope.class);
        when(source.issuer()).thenReturn("issuer");
        when(source.institution()).thenReturn("institution");
        when(source.source()).thenReturn("source");
        when(source.sourceEpoch()).thenReturn("epoch");
        SourceLineage authority = mock(SourceLineage.class);
        when(authority.state()).thenReturn(SourceLineage.State.VERIFIED);
        when(authority.source()).thenReturn(source);
        when(authority.receiptId()).thenReturn("a".repeat(64));
        return authority;
    }
}
