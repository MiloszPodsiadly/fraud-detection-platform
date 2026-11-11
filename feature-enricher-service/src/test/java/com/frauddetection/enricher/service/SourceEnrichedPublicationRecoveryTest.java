package com.frauddetection.enricher.service;

import com.frauddetection.common.events.contract.TransactionEnrichedEvent;
import com.frauddetection.common.events.source.SourceEnrichedPublicationClaim;
import com.frauddetection.common.events.source.SourceLineage;
import com.frauddetection.common.events.source.SourceLineageAccess;
import com.frauddetection.common.events.source.SourceScope;
import com.frauddetection.common.testsupport.fixture.TransactionFixtures;
import com.frauddetection.enricher.config.SourceEnrichedPublicationRecoveryProperties;
import com.frauddetection.enricher.messaging.TransactionEnrichedEventPublisher;
import com.frauddetection.enricher.persistence.FeatureNamespace;
import com.frauddetection.enricher.persistence.FeatureStore;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SourceEnrichedPublicationRecoveryTest {

    @Test
    void redisVerificationFailureDoesNotSpendKafkaPublicationAttempt() {
        TransactionEnrichedEvent event = TransactionFixtures.enrichedTransaction().build();
        SourceLineage authority = verifiedAuthority();
        var claim = new SourceEnrichedPublicationClaim(SourceEnrichedPublicationClaim.Status.CLAIMED, authority,
                event, "b".repeat(64), "claim-token", 1, false, true);
        SourceLineageAccess lineage = mock(SourceLineageAccess.class);
        when(lineage.claimNextEnrichedPublications(1)).thenReturn(List.of(claim));
        when(lineage.enrichedPublicationHealth()).thenReturn(new SourceLineageAccess.EnrichedPublicationHealth(0, 0, 0, 0, 0));

        FeatureStore featureStore = mock(FeatureStore.class);
        doThrow(new IllegalStateException("Redis history marker unavailable"))
                .when(featureStore).verifyAttestedTransaction(eq(event), any(FeatureNamespace.class), anyString());
        TransactionEnrichedEventPublisher publisher = mock(TransactionEnrichedEventPublisher.class);
        var properties = new SourceEnrichedPublicationRecoveryProperties(true, 1, 0, 1_000);

        new SourceEnrichedPublicationRecovery(lineage, publisher, featureStore, properties).recover();

        verify(publisher, never()).publish(any(), any());
        verify(lineage, never()).recordEnrichedPublishFailure(claim);
        verify(lineage).recordEnrichedHistoryFailure(claim, false);
    }

    private static SourceLineage verifiedAuthority() {
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
