package com.frauddetection.enricher.service;

import com.frauddetection.common.events.source.SourceEnrichedPublicationClaim;
import com.frauddetection.common.events.source.SourceLineage;
import com.frauddetection.common.events.source.SourceLineageAccess;
import com.frauddetection.common.events.source.SourceOriginProofs;
import com.frauddetection.enricher.config.SourceEnrichedPublicationRecoveryProperties;
import com.frauddetection.enricher.messaging.TransactionEnrichedEventPublisher;
import com.frauddetection.enricher.persistence.FeatureNamespace;
import com.frauddetection.enricher.persistence.FeatureStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/** Bounded independent outbox drain; it only republishes the exact event returned by the lineage store. */
public final class SourceEnrichedPublicationRecovery {
    private static final Logger log = LoggerFactory.getLogger(SourceEnrichedPublicationRecovery.class);
    private final SourceLineageAccess lineage;
    private final TransactionEnrichedEventPublisher publisher;
    private final FeatureStore featureStore;
    private final SourceEnrichedPublicationRecoveryProperties properties;

    public SourceEnrichedPublicationRecovery(SourceLineageAccess lineage, TransactionEnrichedEventPublisher publisher,
                                            FeatureStore featureStore, SourceEnrichedPublicationRecoveryProperties properties) {
        this.lineage = lineage;
        this.publisher = publisher;
        this.featureStore = featureStore;
        this.properties = properties;
    }

    @Scheduled(initialDelayString = "\u0024{fraud.source-enriched-publications.recovery.initial-delay-millis:5000}",
            fixedDelayString = "\u0024{fraud.source-enriched-publications.recovery.fixed-delay-millis:30000}")
    public void recover() {
        int attempted = 0;
        try {
            for (int index = 0; index < properties.batchSize(); index++) {
                var claims = lineage.claimNextEnrichedPublications(1);
                if (claims.isEmpty()) { break; }
                SourceEnrichedPublicationClaim claim = claims.getFirst();
                attempted++;
                boolean historyVerified = false;
                boolean kafkaPublicationAttempted = false;
                try {
                    if (!claim.historyApplied()) {
                        featureStore.recordAttestedTransaction(claim.event(), FeatureNamespace.attested(claim.authority()),
                                com.frauddetection.common.events.source.SourceEventCommitment.attestedHistory(
                                        claim.authority().receiptId(), claim.event()));
                        SourceLineage applied = lineage.markEnrichedHistoryApplied(claim);
                        if (applied == null || applied.state() != SourceLineage.State.VERIFIED
                                || !claim.authority().eventCommitment().equals(applied.eventCommitment())) {
                            throw new IllegalStateException("FEATURE_LINEAGE_HISTORY_UNRESOLVED");
                        }
                    } else {
                        featureStore.verifyAttestedTransaction(claim.event(), FeatureNamespace.attested(claim.authority()),
                                com.frauddetection.common.events.source.SourceEventCommitment.attestedHistory(
                                        claim.authority().receiptId(), claim.event()));
                    }
                    historyVerified = true;
                    if (!claim.published()) {
                        if (!lineage.renewEnrichedPublicationLeaseForSend(claim, 10_000L)) {
                            lineage.deferEnrichedPublication(claim);
                            continue;
                        }
                        kafkaPublicationAttempted = true;
                        publisher.publish(claim.event(), SourceOriginProofs.Assertion.KNOWN_ATTESTED);
                        SourceLineage published = lineage.markEnrichedPublished(claim);
                        if (published == null || published.state() != SourceLineage.State.VERIFIED
                                || !claim.authority().eventCommitment().equals(published.eventCommitment())) {
                            lineage.recordEnrichedPublishFailure(claim);
                        }
                    }
                } catch (RuntimeException deliveryOrConfirmationFailure) {
                    if (!historyVerified) {
                        lineage.recordEnrichedHistoryFailure(claim,
                                EnrichedHistoryFailureClassifier.isPermanent(deliveryOrConfirmationFailure));
                    }
                    else if (kafkaPublicationAttempted && !claim.published()) { lineage.recordEnrichedPublishFailure(claim); }
                }
            }
        } catch (RuntimeException unavailable) {
            log.warn("Enriched publication recovery cycle failed before completion.");
        }
        var health = lineage.enrichedPublicationHealth();
        if (attempted > 0 || health.pending() > 0 || health.retryExhausted() > 0 || health.quarantined() > 0) {
            log.warn("Enriched publication recovery health pending={} leased={} retryExhausted={} quarantined={} oldestPendingAgeSeconds={} attempted={}",
                    health.pending(), health.leased(), health.retryExhausted(), health.quarantined(),
                    health.oldestPendingAgeSeconds(), attempted);
        }
    }
}
