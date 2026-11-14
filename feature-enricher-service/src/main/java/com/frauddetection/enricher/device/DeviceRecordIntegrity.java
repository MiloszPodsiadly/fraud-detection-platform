package com.frauddetection.enricher.device;

import org.bson.Document;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Date;
import java.util.Objects;

/** Domain-separated MACs bind verified claims to their private Mongo representation without retaining proof bytes. */
final class DeviceRecordIntegrity {
    private static final String[] CANDIDATE_COMMITMENT_FIELDS = {"_id", "sourceIssuer", "identityVersion", "sourceEpoch", "institution", "source",
            "sourceEvent", "transaction", "customer", "device", "keyVersion", "sequence", "eventTime", "sourceObservedAt",
            "payloadCommitment", "provenanceCommitment"};
    private DeviceRecordIntegrity() { }

    static String candidate(DevicePseudonyms key, Document row) {
        return candidate(key, row, DeviceObservationStore.Provenance.from(row).commitment());
    }

    private static String candidate(DevicePseudonyms key, Document row, String provenanceCommitment) {
        return key.integrity("candidate", row.getString("_id"), row.getString("sourceIssuer"),
                row.getInteger("identityVersion").toString(), row.getString("sourceEpoch"), row.getString("institution"),
                row.getString("source"), row.getString("sourceEvent"), row.getString("transaction"), row.getString("customer"),
                row.getString("device"), row.getInteger("keyVersion").toString(), row.getLong("sequence").toString(),
                row.getDate("eventTime").toInstant().toString(), row.getDate("sourceObservedAt").toInstant().toString(),
                row.getString("payloadCommitment"), provenanceCommitment);
    }

    static boolean candidateMatches(DevicePseudonyms key, Document row) {
        return candidateMatches(key, row, DeviceObservationStore.Provenance.from(row));
    }

    static boolean candidateMatches(DevicePseudonyms key, Document row, DeviceObservationStore.Provenance provenance) {
        return matches(row.getString("candidateIntegrityCommitment"), candidate(key, row, provenance.commitment()));
    }

    static boolean candidateInputsMatch(Document authenticated, Document copy) {
        for (String field : CANDIDATE_COMMITMENT_FIELDS) {
            if (!Objects.equals(authenticated.get(field), copy.get(field))) { return false; }
        }
        return true;
    }

    static String candidateState(DevicePseudonyms key, Document row) {
        return key.integrity("candidate-state", value(row.getString("_id")),
                value(row.getString("candidateIntegrityCommitment")), value(row.getString("status")),
                value(row.getString("terminalReason")), instant(row, "terminalExpireAt"));
    }

    static boolean candidateStateMatches(DevicePseudonyms key, Document row) {
        return matches(row.getString("candidateStateIntegrityCommitment"), candidateState(key, row));
    }

    static String historyMemberAnchor(DevicePseudonyms key, Document row) {
        return key.integrity("device-history-member-anchor-v1", row.getString("_id"), row.getString("sourceIssuer"),
                integer(row, "identityVersion"), row.getString("sourceEpoch"), row.getString("institution"),
                row.getString("source"), row.getString("sourceEvent"), row.getString("customer"), row.getString("device"),
                integer(row, "keyVersion"), value(row.getLong("sequence").toString()), instant(row, "eventTime"),
                row.getString("provenanceCommitment"), row.getString("candidateIntegrityCommitment"),
                value(row.getLong("coverageGeneration").toString()), row.getString("coverageCommitment"),
                row.getString("sealAssertionCommitment"), instant(row, "expireAt"));
    }

    static boolean historyMemberAnchorMatches(DevicePseudonyms key, Document row) {
        return matches(row.getString("memberAnchorIntegrityCommitment"), historyMemberAnchor(key, row));
    }

    static String membership(DevicePseudonyms key, Document row) {
        return membership(key, row, DeviceObservationStore.Provenance.from(row).commitment());
    }

    private static String membership(DevicePseudonyms key, Document row, String provenanceCommitment) {
        return key.integrity("membership", row.getString("_id"), row.getString("sourceIssuer"),
                row.getInteger("identityVersion").toString(), row.getString("sourceEpoch"), row.getString("institution"),
                row.getString("source"), row.getString("sourceEvent"), row.getString("customer"), row.getString("device"),
                row.getInteger("keyVersion").toString(), row.getDate("eventTime").toInstant().toString(),
                instant(row, "expireAt"), provenanceCommitment);
    }

    static boolean membershipMatches(DevicePseudonyms key, Document row) {
        return membershipMatches(key, row, DeviceObservationStore.Provenance.from(row));
    }

    static boolean membershipMatches(DevicePseudonyms key, Document row, DeviceObservationStore.Provenance provenance) {
        return matches(row.getString("membershipIntegrityCommitment"), membership(key, row, provenance.commitment()));
    }

    static String snapshot(DevicePseudonyms key, Document row) {
        return key.integrity("snapshot", row.getString("_id"), row.getString("stageACommitment"));
    }

    static boolean snapshotMatches(DevicePseudonyms key, Document row) {
        return matches(row.getString("snapshotIntegrityCommitment"), snapshot(key, row));
    }

    static String coverage(DevicePseudonyms key, Document row) {
        var authority = row.get("coverageAuthority", Document.class);
        return key.integrity("coverage", row.getString("_id"), row.getString("sourceIssuer"),
                row.getInteger("identityVersion").toString(), row.getString("sourceEpoch"), row.getString("institution"),
                row.getString("source"), row.getString("populationCommitment"), row.getLong("sequenceOrigin").toString(),
                row.getLong("sealedSequence").toString(), row.getLong("coverageGeneration").toString(),
                row.getString("coverageCommitment"), instant(row, "coverageStart"), instant(row, "continuousSince"),
                instant(row, "watermark"), integer(row, "keyVersion"), value(row.getString("sealAssertionCommitment")),
                Boolean.toString(Boolean.TRUE.equals(row.getBoolean("invalidated"))),
                authority == null ? "-" : value(authority.getString("issuer")), authority == null ? "-" : value(authority.getString("keyVersion")),
                authority == null ? "-" : value(authority.getString("policyVersion")), authority == null ? "-" : value(authority.getString("keyCommitment")),
                authority == null ? "-" : value(authority.getString("authorizationCommitment")), authority == null ? "-" : value(authority.getString("payloadCommitment")),
                authority == null ? "-" : value(authority.getString("coverageId")), authority == null ? "-" : instant(authority, "proofTime"));
    }

    static boolean coverageMatches(DevicePseudonyms key, Document row) {
        String stored = row.getString("coverageIntegrityCommitment");
        return stored != null && matches(stored, coverage(key, row));
    }

    private static String integer(Document row, String field) {
        Integer value = row.getInteger(field);
        return value == null ? "-" : value.toString();
    }

    private static String instant(Document row, String field) {
        Date value = row.getDate(field);
        return value == null ? "-" : value.toInstant().toString();
    }

    private static String value(String field) { return field == null || field.isBlank() ? "-" : field; }

    private static boolean matches(String stored, String expected) {
        return stored != null && MessageDigest.isEqual(stored.getBytes(StandardCharsets.US_ASCII),
                expected.getBytes(StandardCharsets.US_ASCII));
    }
}
