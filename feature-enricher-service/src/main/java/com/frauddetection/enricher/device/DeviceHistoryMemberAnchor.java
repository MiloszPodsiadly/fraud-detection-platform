package com.frauddetection.enricher.device;

import org.bson.Document;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Objects;

/** Private, bounded source-seal membership reference retained independently of the three event copies. */
final class DeviceHistoryMemberAnchor {
    static final Duration RETENTION = Duration.ofDays(31).plusMinutes(5);

    private DeviceHistoryMemberAnchor() { }

    static Document fromSealedCandidate(DevicePseudonyms integrityKey, DeviceEpoch epoch, Document candidate,
                                        DeviceObservationStore.CoverageProof proof) {
        Objects.requireNonNull(integrityKey, "integrityKey");
        Objects.requireNonNull(epoch, "epoch");
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(proof, "proof");
        Instant eventTime = candidate.getDate("eventTime").toInstant();
        Document anchor = epoch.document(candidate.getString("_id"), candidate.getString("institution"), candidate.getString("source"))
                .append("institution", candidate.getString("institution"))
                .append("source", candidate.getString("source"))
                .append("sourceEvent", candidate.getString("sourceEvent"))
                .append("customer", candidate.getString("customer"))
                .append("device", candidate.getString("device"))
                .append("keyVersion", candidate.getInteger("keyVersion"))
                .append("sequence", candidate.getLong("sequence"))
                .append("eventTime", candidate.getDate("eventTime"))
                .append("provenanceCommitment", candidate.getString("provenanceCommitment"))
                .append("candidateIntegrityCommitment", candidate.getString("candidateIntegrityCommitment"))
                .append("coverageGeneration", proof.generation())
                .append("coverageCommitment", proof.coverageCommitment())
                .append("sealAssertionCommitment", proof.assertionCommitment())
                .append("expireAt", Date.from(eventTime.plus(RETENTION)));
        anchor.put("memberAnchorIntegrityCommitment", DeviceRecordIntegrity.historyMemberAnchor(integrityKey, anchor));
        return anchor;
    }

    static boolean matchesCopies(DevicePseudonyms integrityKey, DeviceEpoch epoch, Document anchor,
                                 Document observation, Document occurrence, Document candidate) {
        return epoch.matches(anchor)
                && DeviceRecordIntegrity.historyMemberAnchorMatches(integrityKey, anchor)
                && Objects.equals(anchor.getString("_id"), observation.getString("_id"))
                && Objects.equals(anchor.getString("_id"), occurrence.getString("_id"))
                && Objects.equals(anchor.getString("_id"), candidate.getString("_id"))
                && same(anchor, observation, "sourceIssuer", "sourceEpoch", "identityVersion", "institution", "source", "sourceEvent",
                        "customer", "device", "keyVersion", "eventTime", "provenanceCommitment")
                && same(anchor, occurrence, "sourceIssuer", "sourceEpoch", "identityVersion", "institution", "source", "sourceEvent",
                        "customer", "device", "keyVersion", "eventTime", "provenanceCommitment")
                && same(anchor, candidate, "sourceIssuer", "sourceEpoch", "identityVersion", "institution", "source", "sourceEvent",
                        "customer", "device", "keyVersion", "eventTime", "provenanceCommitment", "sequence", "candidateIntegrityCommitment")
                && Objects.equals(anchor.getString("candidateIntegrityCommitment"), occurrence.getString("candidateIntegrityCommitment"))
                && Objects.equals(observation.get("provenance"), occurrence.get("provenance"))
                && Objects.equals(observation.get("provenance"), candidate.get("provenance"))
                && Objects.equals(anchor.getDate("expireAt"), observation.getDate("expireAt"));
    }

    private static boolean same(Document left, Document right, String... fields) {
        for (String field : fields) {
            if (!Objects.equals(left.get(field), right.get(field))) { return false; }
        }
        return true;
    }
}
