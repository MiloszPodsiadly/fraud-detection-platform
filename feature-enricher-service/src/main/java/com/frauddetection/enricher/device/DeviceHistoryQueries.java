package com.frauddetection.enricher.device;

import org.bson.conversions.Bson;
import com.mongodb.client.model.Projections;

import java.time.Instant;
import java.util.Date;

import static com.mongodb.client.model.Filters.*;

/** Shared query shapes for sealed Stage A membership checks and private Stage B history reads. */
final class DeviceHistoryQueries {
    private DeviceHistoryQueries() { }

    static Bson membership(DeviceEpoch epoch, String institution, String source, int keyVersion, Instant windowStart,
                           Instant asOf, String customer, String device) {
        return and(epoch.filter(institution, source), eq("keyVersion", keyVersion), gte("eventTime", Date.from(windowStart)),
                lt("eventTime", Date.from(asOf)), or(eq("customer", customer), eq("device", device)));
    }

    static Bson observationMembership(Bson base, String customer, String device) {
        return and(base, or(eq("customer", customer), eq("device", device)));
    }

    static Bson observationMembershipProjection() {
        return Projections.include("_id", "schemaVersion", "sourceIssuer", "identityVersion", "sourceEpoch", "institution", "source",
                "sourceEvent", "transaction", "customer", "device", "keyVersion", "sequence", "eventTime",
                "sourceObservedAt", "payloadCommitment", "provenance", "provenanceCommitment", "expireAt",
                "membershipIntegrityCommitment");
    }

    static Bson historyMemberAnchorProjection() {
        return Projections.include("_id", "schemaVersion", "sourceIssuer", "identityVersion", "sourceEpoch", "institution", "source",
                "sourceEvent", "customer", "device", "keyVersion", "eventTime", "provenanceCommitment",
                "sequence", "candidateIntegrityCommitment", "coverageGeneration", "coverageCommitment", "sealAssertionCommitment",
                "expireAt", "memberAnchorIntegrityCommitment");
    }

    static Bson occurrenceMembershipProjection() {
        return Projections.include("_id", "schemaVersion", "sourceIssuer", "identityVersion", "sourceEpoch", "institution", "source",
                "sourceEvent", "transaction", "customer", "device", "keyVersion", "sequence", "sourceObservedAt",
                "eventTime", "payloadCommitment", "evidenceCommitment", "provenance", "provenanceCommitment",
                "candidateIntegrityCommitment", "membershipIntegrityCommitment", "snapshotIntegrityCommitment",
                "stageACommitment", "populationCommitment", "coverageCommitment", "coverageAuthority", "coverageStart",
                "expireAt", "historyMembershipCount", "historyMembershipCommitment");
    }

    static Bson candidateMembershipProjection() {
        return Projections.include("_id", "schemaVersion", "sourceIssuer", "identityVersion", "sourceEpoch", "institution", "source",
                "sourceEvent", "transaction", "customer", "device", "keyVersion", "sequence", "eventTime",
                "sourceObservedAt", "payloadCommitment", "provenance", "provenanceCommitment",
                "candidateIntegrityCommitment", "candidateStateIntegrityCommitment", "status", "terminalReason",
                "terminalExpireAt");
    }

    static Bson retainedMembership(DeviceEpoch epoch, String institution, String source, int keyVersion, Instant windowStart,
                                   Instant asOf, String customer, String device, Instant now) {
        return and(membership(epoch, institution, source, keyVersion, windowStart, asOf, customer, device),
                gt("expireAt", Date.from(now)));
    }

    static Bson committedStageAMembership(DeviceEpoch epoch, String institution, String source, int keyVersion,
                                          Instant windowStart, Instant asOf, String customer, String device, Instant now) {
        return and(membership(epoch, institution, source, keyVersion, windowStart, asOf, customer, device),
                eq("status", "COMMITTED"), gt("terminalExpireAt", Date.from(now)));
    }

    static org.bson.Document historySort() { return new org.bson.Document("eventTime", 1).append("sourceEvent", 1); }
}
