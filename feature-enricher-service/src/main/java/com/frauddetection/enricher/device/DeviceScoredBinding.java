package com.frauddetection.enricher.device;

import com.frauddetection.common.events.source.SourceScoredOccurrence;
import org.bson.Document;

/** Append-once private link; the scored fact must already be published and source-authorized. */
final class DeviceScoredBinding {
    private DeviceScoredBinding() { }
    static Document expected(Document occurrence, SourceScoredOccurrence scored) {
        DeviceSnapshotVersion.require(occurrence);
        var p = DeviceObservationStore.Provenance.from(occurrence);
        String snapshot = DeviceAssertions.token(occurrence.getString("evidenceCommitment"));
        String provenance = DeviceAssertions.token(occurrence.getString("provenanceCommitment"));
        var source = scored.source();
        String sourceScope = DeviceEpoch.digest("device-scored-source-scope/v1", source.issuer(), source.institution(), source.source(),
                source.sourceEpoch(), source.signingKeyVersion(), source.sourceEventId(), source.eventTime().toString(), source.purpose(),
                source.audience(), source.payloadCommitment());
        String commitment = DeviceEpoch.digest("device-scored-binding/v1", occurrence.getString("_id"), occurrence.getString("sourceIssuer"),
                occurrence.getString("sourceEpoch"), p.receiptId(), p.rawCommitment(), p.enrichedCommitment(), snapshot, provenance, occurrence.getString("stageACommitment"),
                sourceScope, scored.scoredEventId(), scored.scoredCreatedAt().toString(), scored.scoredFingerprint(), scored.runId());
        return new Document("version", 1).append("eventId", scored.scoredEventId()).append("createdAt", scored.scoredCreatedAt().toString())
                .append("fingerprint", scored.scoredFingerprint()).append("runId", scored.runId()).append("snapshotCommitment", snapshot)
                .append("sourceScopeCommitment", sourceScope).append("provenanceCommitment", provenance)
                .append("stageACommitment", occurrence.getString("stageACommitment")).append("commitment", commitment);
    }
    static boolean matches(Document occurrence, SourceScoredOccurrence scored) {
        return expected(occurrence, scored).equals(occurrence.get("scoredBinding", Document.class));
    }
}
