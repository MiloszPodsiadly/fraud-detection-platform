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
        if (legacy && featureStore.hasRecordedTransaction(event, namespace)) {
            log.info("Skipped duplicate raw transaction event.");
            return;
        }

        log.info("Started transaction feature enrichment.");

        SourceEnrichedPublication publication = null;
        TransactionEnrichedEvent enrichedEvent;
        if (!legacy && featureStore.hasRecordedTransaction(event, namespace)) {
            var lookup = sourceLineage.findEnrichedPublication(event);
            if (lookup == null || lookup.authority() == null
                    || lookup.authority().state() != com.frauddetection.common.events.source.SourceLineage.State.VERIFIED) {
                throw new FeatureEnrichmentException("FEATURE_LINEAGE_UNRESOLVED");
            }
            if (!origin.authority().receiptId().equals(lookup.authority().receiptId())
                    || !com.frauddetection.common.events.source.SourceEventCommitment.raw(event)
                            .equals(origin.authority().eventCommitment())) {
                throw new FeatureEnrichmentException("FEATURE_SOURCE_OBSERVATION_CONFLICT");
            }
            publication = lookup.publication();
            if (publication == null) {
                // A business transaction ID is not proof that this exact source observation was already processed.
                // The verified RAW lineage remains the durable reconciliation anchor for this conflict.
                throw new FeatureEnrichmentException("FEATURE_BUSINESS_TRANSACTION_ID_CONFLICT");
            }
            enrichedEvent = publication.event();
        } else {
            FeatureStoreSnapshot snapshot = featureStore.loadSnapshot(event, namespace);
            EnrichedTransactionFeatures features = transactionFeatureCalculator.calculate(event, snapshot);
            TransactionEnrichedEvent candidate = legacy
                    ? transactionEnrichedEventMapper.toEvent(event, features)
                    : transactionEnrichedEventMapper.toAttestedEvent(event, features);
            enrichedEvent = candidate;
            if (!legacy) {
                publication = sourceLineage.prepareEnrichedPublication(event, candidate);
                requirePrepared(publication);
                enrichedEvent = publication.event();
            }
        }
        if (!legacy) {
            requirePrepared(publication);
            if (!origin.authority().receiptId().equals(publication.authority().receiptId())) {
                throw new FeatureEnrichmentException("FEATURE_ORIGIN_UNRESOLVED");
            }
            enrichedEvent = publication.event();
            if (publication.historyApplied()) {
                featureStore.verifyAttestedTransaction(enrichedEvent, namespace,
                        com.frauddetection.common.events.source.SourceEventCommitment.attestedHistory(
                                publication.authority().receiptId(), enrichedEvent));
            }
            if (!publication.published() || !publication.historyApplied()) {
                SourceEnrichedPublicationClaim claim = sourceLineage.claimEnrichedPublication(event, enrichedEvent);
                if (claim.status() == SourceEnrichedPublicationClaim.Status.CLAIMED) {
                    if (!claim.historyApplied()) {
                        try {
                            featureStore.recordAttestedTransaction(claim.event(), namespace,
                                    com.frauddetection.common.events.source.SourceEventCommitment.attestedHistory(
                                            claim.authority().receiptId(), claim.event()));
                            var history = sourceLineage.markEnrichedHistoryApplied(claim);
                            if (history == null || history.state() != com.frauddetection.common.events.source.SourceLineage.State.VERIFIED
                                    || !enrichedEventCommitment(claim.event()).equals(history.eventCommitment())) {
                                throw new FeatureEnrichmentException("FEATURE_LINEAGE_HISTORY_UNRESOLVED");
                            }
                        } catch (RuntimeException historyFailure) {
                            sourceLineage.recordEnrichedHistoryFailure(claim);
                            throw historyFailure;
                        }
                    } else {
                        featureStore.verifyAttestedTransaction(claim.event(), namespace,
                                com.frauddetection.common.events.source.SourceEventCommitment.attestedHistory(
                                        claim.authority().receiptId(), claim.event()));
                    }
                    if (!claim.published()) {
                        if (!sourceLineage.renewEnrichedPublicationLeaseForSend(claim, 10_000L)) {
                            sourceLineage.deferEnrichedPublication(claim);
                            throw new FeatureEnrichmentException("FEATURE_LINEAGE_PUBLICATION_LEASE_UNRESOLVED");
                        }
                        try {
                            publish(claim.event(), origin.assertion());
                        } catch (RuntimeException publishFailure) {
                            sourceLineage.recordEnrichedPublishFailure(claim);
                            throw publishFailure;
                        }
                        var marked = sourceLineage.markEnrichedPublished(claim);
                        if (marked == null || marked.state() != com.frauddetection.common.events.source.SourceLineage.State.VERIFIED
                                || !enrichedEventCommitment(claim.event()).equals(marked.eventCommitment())) {
                            sourceLineage.recordEnrichedPublishFailure(claim);
                            throw new FeatureEnrichmentException("FEATURE_LINEAGE_PUBLICATION_UNRESOLVED");
                        }
                    }
                } else if (claim.status() == SourceEnrichedPublicationClaim.Status.ALREADY_PUBLISHED) {
                    featureStore.verifyAttestedTransaction(enrichedEvent, namespace,
                            com.frauddetection.common.events.source.SourceEventCommitment.attestedHistory(
                                    claim.authority().receiptId(), enrichedEvent));
                } else {
                    throw new FeatureEnrichmentException("FEATURE_LINEAGE_PUBLICATION_UNRESOLVED");
                }
            }
        } else {
            publish(enrichedEvent, origin.assertion());
        }
        if (legacy) { featureStore.recordTransaction(event, namespace); }

        log.info("Completed transaction feature enrichment.");
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
