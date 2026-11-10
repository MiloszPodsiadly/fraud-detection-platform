package com.frauddetection.enricher.service;

import com.frauddetection.common.events.contract.TransactionEnrichedEvent;
import com.frauddetection.common.events.contract.TransactionRawEvent;
import com.frauddetection.common.events.source.SourceLineageAccess;
import com.frauddetection.common.events.source.SourceEnrichedPublication;
import com.frauddetection.common.events.source.SourceLineage;
import com.frauddetection.common.events.source.SourceOriginProofs;
import com.frauddetection.common.events.source.SourceEnrichedPublicationClaim;
import com.frauddetection.enricher.domain.EnrichedTransactionFeatures;
import com.frauddetection.enricher.domain.FeatureStoreSnapshot;
import com.frauddetection.enricher.exception.FeatureEnrichmentException;
import com.frauddetection.enricher.mapper.TransactionEnrichedEventMapper;
import com.frauddetection.enricher.messaging.TransactionEnrichedEventPublisher;
import com.frauddetection.enricher.persistence.FeatureNamespace;
import com.frauddetection.enricher.persistence.FeatureStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class TransactionFeatureEnricherService implements TransactionFeatureEnrichmentUseCase {

    private static final Logger log = LoggerFactory.getLogger(TransactionFeatureEnricherService.class);

    private final FeatureStore featureStore;
    private final TransactionFeatureCalculator transactionFeatureCalculator;
    private final TransactionEnrichedEventMapper transactionEnrichedEventMapper;
    private final TransactionEnrichedEventPublisher transactionEnrichedEventPublisher;
    private final SourceLineageAccess sourceLineage;
    private final SourceOriginProofs originProofs;

    public TransactionFeatureEnricherService(
            FeatureStore featureStore,
            TransactionFeatureCalculator transactionFeatureCalculator,
            TransactionEnrichedEventMapper transactionEnrichedEventMapper,
            TransactionEnrichedEventPublisher transactionEnrichedEventPublisher
    ) {
        this(featureStore, transactionFeatureCalculator, transactionEnrichedEventMapper, transactionEnrichedEventPublisher,
                SourceLineageAccess.legacyOnly(), null);
    }

    @Autowired
    public TransactionFeatureEnricherService(FeatureStore featureStore, TransactionFeatureCalculator calculator,
                                            TransactionEnrichedEventMapper mapper, TransactionEnrichedEventPublisher publisher,
                                            ObjectProvider<SourceLineageAccess> lineage,
                                            ObjectProvider<SourceOriginProofs> originProofs) {
        this(featureStore, calculator, mapper, publisher,
                lineage.getIfAvailable(SourceLineageAccess::disabled), originProofs.getIfAvailable());
    }

    public TransactionFeatureEnricherService(FeatureStore featureStore, TransactionFeatureCalculator transactionFeatureCalculator,
                                            TransactionEnrichedEventMapper transactionEnrichedEventMapper,
                                            TransactionEnrichedEventPublisher transactionEnrichedEventPublisher, SourceLineageAccess lineage) {
        this(featureStore, transactionFeatureCalculator, transactionEnrichedEventMapper, transactionEnrichedEventPublisher, lineage, null);
    }

    public TransactionFeatureEnricherService(FeatureStore featureStore, TransactionFeatureCalculator transactionFeatureCalculator,
                                            TransactionEnrichedEventMapper transactionEnrichedEventMapper,
                                            TransactionEnrichedEventPublisher transactionEnrichedEventPublisher, SourceLineageAccess lineage,
                                            SourceOriginProofs originProofs) {
        this.featureStore = featureStore;
        this.transactionFeatureCalculator = transactionFeatureCalculator;
        this.transactionEnrichedEventMapper = transactionEnrichedEventMapper;
        this.transactionEnrichedEventPublisher = transactionEnrichedEventPublisher;
        this.sourceLineage = java.util.Objects.requireNonNull(lineage);
        this.originProofs = originProofs;
    }

    @Override
    public void enrich(TransactionRawEvent event) {
        enrich(event, null);
    }

    @Override
    public void enrich(TransactionRawEvent event, String originProof) {
        OriginResolution origin = resolveOrigin(event, originProof);
        boolean legacy = origin.assertion() == SourceOriginProofs.Assertion.PROVEN_LEGACY;
        FeatureNamespace namespace = legacy ? FeatureNamespace.legacy() : FeatureNamespace.attested(origin.authority());
        if (featureStore.hasRecordedTransaction(event, namespace)) {
            log.atInfo()
                    .addKeyValue("transactionId", event.transactionId())
                    .addKeyValue("customerId", event.customerId())
                    .addKeyValue("correlationId", event.correlationId())
                    .log("Skipped duplicate raw transaction event.");
            return;
        }

        log.atInfo()
                .addKeyValue("transactionId", event.transactionId())
                .addKeyValue("customerId", event.customerId())
                .addKeyValue("correlationId", event.correlationId())
                .log("Started transaction feature enrichment.");

        FeatureStoreSnapshot snapshot = featureStore.loadSnapshot(event, namespace);
        EnrichedTransactionFeatures features = transactionFeatureCalculator.calculate(event, snapshot);
        TransactionEnrichedEvent candidate = legacy
                ? transactionEnrichedEventMapper.toEvent(event, features)
                : transactionEnrichedEventMapper.toAttestedEvent(event, features);
        TransactionEnrichedEvent enrichedEvent = candidate;
        if (!legacy) {
            SourceEnrichedPublication publication = sourceLineage.prepareEnrichedPublication(event, candidate);
            requirePrepared(publication);
            if (!origin.authority().receiptId().equals(publication.authority().receiptId())) {
                throw new FeatureEnrichmentException("FEATURE_ORIGIN_UNRESOLVED");
            }
            enrichedEvent = publication.event();
            if (!publication.published()) {
                SourceEnrichedPublicationClaim claim = sourceLineage.claimEnrichedPublication(event, enrichedEvent);
                if (claim.status() == SourceEnrichedPublicationClaim.Status.CLAIMED) {
                    try {
                        publish(claim.event(), origin.assertion());
                    } catch (RuntimeException publishFailure) {
                        sourceLineage.recordEnrichedPublishFailure(claim);
                        throw publishFailure;
                    }
                    var marked = sourceLineage.markEnrichedPublished(claim);
                    if (marked == null || marked.state() != com.frauddetection.common.events.source.SourceLineage.State.VERIFIED
                            || !enrichedEventCommitment(enrichedEvent).equals(marked.eventCommitment())) {
                        sourceLineage.recordEnrichedPublishFailure(claim);
                        throw new FeatureEnrichmentException("FEATURE_LINEAGE_PUBLICATION_UNRESOLVED");
                    }
                } else if (claim.status() != SourceEnrichedPublicationClaim.Status.ALREADY_PUBLISHED) {
                    throw new FeatureEnrichmentException("FEATURE_LINEAGE_PUBLICATION_UNRESOLVED");
                }
            }
        } else {
            publish(enrichedEvent, origin.assertion());
        }
        featureStore.recordTransaction(event, namespace);

        log.atInfo()
                .addKeyValue("transactionId", event.transactionId())
                .addKeyValue("correlationId", event.correlationId())
                .addKeyValue("featureSnapshotKeys", enrichedEvent.featureSnapshot().keySet())
                .log("Completed transaction feature enrichment.");
    }

    private OriginResolution resolveOrigin(TransactionRawEvent event, String token) {
        if (originProofs != null) {
            SourceOriginProofs.Verification proof = originProofs.verifyRaw(event, token);
            if (proof.status() == SourceOriginProofs.Status.INVALID) { throw new FeatureEnrichmentException("FEATURE_ORIGIN_UNRESOLVED"); }
            if (proof.status() == SourceOriginProofs.Status.VERIFIED) {
                if (proof.assertion() == SourceOriginProofs.Assertion.PROVEN_LEGACY) { return new OriginResolution(proof.assertion(), null); }
                SourceLineage authority = sourceLineage.raw(event);
                if (authority == null || authority.state() != SourceLineage.State.VERIFIED) {
                    throw new FeatureEnrichmentException("FEATURE_ORIGIN_UNRESOLVED");
                }
                return new OriginResolution(proof.assertion(), authority);
            }
        } else if (sourceLineage.isLegacyOnly()) {
            return new OriginResolution(SourceOriginProofs.Assertion.PROVEN_LEGACY, null);
        }
        SourceLineage authority = sourceLineage.raw(event);
        if (authority != null && authority.state() == SourceLineage.State.VERIFIED) {
            return new OriginResolution(SourceOriginProofs.Assertion.KNOWN_ATTESTED, authority);
        }
        throw new FeatureEnrichmentException("FEATURE_ORIGIN_UNRESOLVED");
    }

    private void publish(TransactionEnrichedEvent event, SourceOriginProofs.Assertion assertion) {
        if (originProofs == null) { transactionEnrichedEventPublisher.publish(event); }
        else { transactionEnrichedEventPublisher.publish(event, assertion); }
    }

    private static void requirePrepared(SourceEnrichedPublication publication) {
        if (publication == null || publication.authority() == null
                || publication.authority().state() != com.frauddetection.common.events.source.SourceLineage.State.VERIFIED
                || publication.event() == null) {
            throw new FeatureEnrichmentException("FEATURE_LINEAGE_UNRESOLVED");
        }
    }

    private static String enrichedEventCommitment(TransactionEnrichedEvent event) {
        return com.frauddetection.common.events.source.SourceEventCommitment.enriched(event);
    }

    private record OriginResolution(SourceOriginProofs.Assertion assertion, SourceLineage authority) { }
}
