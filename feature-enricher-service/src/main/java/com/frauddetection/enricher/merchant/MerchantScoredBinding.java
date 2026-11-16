package com.frauddetection.enricher.merchant;

import com.frauddetection.common.events.source.SourceScoredOccurrence;
import com.frauddetection.common.events.source.SourceScope;
import com.frauddetection.common.events.source.MongoSourceOccurrenceStore;
import com.frauddetection.enricher.merchant.MerchantObservationStore.Availability;
import com.frauddetection.enricher.merchant.MerchantObservationStore.CoverageGeneration;
import com.frauddetection.enricher.merchant.MerchantObservationStore.Observation;
import org.bson.Document;

import java.util.Objects;

/** Append-once commitment from an immutable Stage A window to the exact published initial SCORED fact. */
final class MerchantScoredBinding {
    private MerchantScoredBinding() { }

    static Document expected(Document candidate, Observation observation, MerchantAssertionAnchor anchor,
                             CoverageGeneration coverageGeneration, SourceScoredOccurrence scored) {
        MerchantAssertionAnchor coverageAnchor = coverageGeneration == null ? null : coverageGeneration.anchor();
        if (candidate == null || observation == null || anchor == null || coverageGeneration == null
                || coverageAnchor == null || scored == null
                || anchor.role() != MerchantAssertionVerifier.Role.OBSERVATION
                || coverageAnchor.role() != MerchantAssertionVerifier.Role.COVERAGE
                || !coverageGeneration.covers(observation.sequence())
                || !Objects.equals(candidate.getString("_id"), MerchantObservationDocumentCodec.eventId(observation))
                || !"COMMITTED".equals(candidate.getString("status"))
                || !Availability.COMPLETE_WINDOW.name().equals(candidate.getString("availability"))
                || !Objects.equals(candidate.getString("evidenceCommitment"), anchor.commitment())
                || !Objects.equals(candidate.getLong("stageCoverageGeneration"), coverageGeneration.generation())
                || !Objects.equals(candidate.getString("stageCoverageCommitment"), coverageGeneration.commitment())
                || !commitment(candidate.getString("snapshotCommitment"))
                || !commitment(candidate.getString("historyCommitment"))) {
            throw invalid();
        }
        MerchantObservationAssertion claim;
        try { claim = MerchantAssertionCodec.parseObservation(anchor.assertionBytes()); }
        catch (RuntimeException malformed) { throw invalid(); }
        SourceScope source = scored.source();
        if (source == null || scored.scoredCreatedAt() == null || scored.scoredEventId() == null
                || !MongoSourceOccurrenceStore.INITIAL_RUN.equals(scored.runId())
                || !scored.scoredEventId().matches("[A-Za-z0-9._:-]{1,128}")
                || !commitment(scored.scoredFingerprint())
                || !claim.sourceIssuer().equals(source.issuer()) || !claim.institution().equals(source.institution())
                || !claim.source().equals(source.source()) || !claim.sourceEpoch().equals(source.sourceEpoch())
                || !claim.sourceEventId().equals(source.sourceEventId()) || !claim.eventTime().equals(source.eventTime())
                || !claim.receiptId().equals(scored.receiptId())
                || !claim.enrichedCommitment().equals(scored.enrichedCommitment())
                || claim.identityVersion() != observation.scope().identityVersion()
                || !claim.merchantPseudonym().equals(observation.merchant())
                || !claim.transactionCommitment().equals(observation.transaction())
                || !claim.occurrenceCommitment().equals(observation.occurrence())
                || claim.sequence() != observation.sequence() || !claim.eventTime().equals(observation.eventTime())
                || !claim.sourceObservedAt().equals(observation.sourceObservedAt())
                || !claim.enrichedCommitment().equals(observation.payloadCommitment())) {
            throw invalid();
        }
        String sourceScopeCommitment = MerchantCommitment.sha256("MERCHANT_SCORED_BINDING_UNAVAILABLE",
                "merchant-scored-source-scope/v1", source.issuer(), source.institution(), source.source(), source.sourceEpoch(),
                source.signingKeyVersion(), source.sourceEventId(), source.eventTime(), source.purpose(), source.audience(), source.payloadCommitment());
        String merchantCommitment = MerchantCommitment.sha256("MERCHANT_SCORED_BINDING_UNAVAILABLE",
                "merchant-scored-identity/v1", observation.scope().issuer(), observation.scope().institution(),
                observation.scope().source(), observation.scope().epoch(), observation.scope().identityVersion(), observation.merchant());
        String inputCommitment = MerchantObservationDocumentCodec.inputCommitment(observation);
        String snapshotCommitment = candidate.getString("snapshotCommitment");
        String historyCommitment = candidate.getString("historyCommitment");
        String commitment = MerchantCommitment.sha256("MERCHANT_SCORED_BINDING_UNAVAILABLE", "merchant-scored-binding/v2",
                candidate.getString("_id"), inputCommitment, sourceScopeCommitment,
                coverageGeneration.generation(), coverageGeneration.fromSequence(), coverageGeneration.throughSequence(),
                coverageAnchor.commitment(),
                scored.receiptId(), claim.rawCommitment(),
                claim.enrichedCommitment(), observation.scope().identityVersion(), merchantCommitment, snapshotCommitment,
                historyCommitment, scored.scoredEventId(), scored.scoredCreatedAt(), scored.scoredFingerprint(), scored.runId());
        return new Document("version", 1).append("eventId", scored.scoredEventId())
                .append("createdAt", scored.scoredCreatedAt().toString()).append("fingerprint", scored.scoredFingerprint())
                .append("runId", scored.runId()).append("sourceScopeCommitment", sourceScopeCommitment)
                .append("coverageCommitment", coverageAnchor.commitment())
                .append("coverageGeneration", coverageGeneration.generation())
                .append("coverageFromSequence", coverageGeneration.fromSequence())
                .append("coverageThroughSequence", coverageGeneration.throughSequence())
                .append("receiptId", scored.receiptId()).append("rawCommitment", claim.rawCommitment())
                .append("enrichedCommitment", claim.enrichedCommitment())
                .append("identityVersion", observation.scope().identityVersion()).append("merchantCommitment", merchantCommitment)
                .append("inputCommitment", inputCommitment).append("snapshotCommitment", snapshotCommitment)
                .append("historyCommitment", historyCommitment).append("commitment", commitment);
    }

    static boolean matches(Document manifest, Document candidate, Observation observation,
                           MerchantAssertionAnchor anchor, CoverageGeneration coverageGeneration,
                           SourceScoredOccurrence scored) {
        if (manifest == null) { return false; }
        try { return expected(candidate, observation, anchor, coverageGeneration, scored).equals(manifest.get("scoredBinding", Document.class)); }
        catch (RuntimeException invalid) { return false; }
    }

    private static boolean commitment(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }

    private static IllegalStateException invalid() { return new IllegalStateException("MERCHANT_SCORED_BINDING_INVALID"); }
}
