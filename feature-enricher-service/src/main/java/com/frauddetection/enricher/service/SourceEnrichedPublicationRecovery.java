package com.frauddetection.enricher.service;

import com.frauddetection.common.events.source.SourceEnrichedPublicationClaim;
import com.frauddetection.common.events.source.SourceLineage;
import com.frauddetection.common.events.source.SourceLineageAccess;
import com.frauddetection.common.events.source.SourceOriginProofs;
import com.frauddetection.enricher.config.SourceEnrichedPublicationRecoveryProperties;
import com.frauddetection.enricher.messaging.TransactionEnrichedEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/** Bounded independent outbox drain; it only republishes the exact event returned by the lineage store. */
public final class SourceEnrichedPublicationRecovery {
    private static final Logger log = LoggerFactory.getLogger(SourceEnrichedPublicationRecovery.class);
    private final SourceLineageAccess lineage;
    private final TransactionEnrichedEventPublisher publisher;
    private final SourceEnrichedPublicationRecoveryProperties properties;

    public SourceEnrichedPublicationRecovery(SourceLineageAccess lineage, TransactionEnrichedEventPublisher publisher,
                                            SourceEnrichedPublicationRecoveryProperties properties) {
        this.lineage = lineage;
        this.publisher = publisher;
        this.properties = properties;
    }

    @Scheduled(initialDelayString = "\u0024{fraud.source-enriched-publications.recovery.initial-delay-millis:10000}",
            fixedDelayString = "\u0024{fraud.source-enriched-publications.recovery.fixed-delay-millis:5000}")
    public void recover() {
        int attempted = 0;
        try {
            for (SourceEnrichedPublicationClaim claim : lineage.claimNextEnrichedPublications(properties.batchSize())) {
                attempted++;
                try {
                    publisher.publish(claim.event(), SourceOriginProofs.Assertion.KNOWN_ATTESTED);
                    SourceLineage published = lineage.markEnrichedPublished(claim);
                    if (published == null || published.state() != SourceLineage.State.VERIFIED
                            || !claim.authority().eventCommitment().equals(published.eventCommitment())) {
                        lineage.recordEnrichedPublishFailure(claim);
                    }
                } catch (RuntimeException deliveryOrConfirmationFailure) {
                    lineage.recordEnrichedPublishFailure(claim);
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
