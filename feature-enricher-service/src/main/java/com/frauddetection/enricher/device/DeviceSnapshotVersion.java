package com.frauddetection.enricher.device;

import org.bson.Document;
import java.util.Arrays;
import java.util.Objects;

/** Immutable Stage A version covers facts and their complete retained authority/retention metadata. */
final class DeviceSnapshotVersion {
    private DeviceSnapshotVersion() { }
    static String commitment(Document row) {
        return commitment(row, DeviceObservationStore.Provenance.from(row), DeviceObservationStore.CoverageAuthority.from(row));
    }
    static String commitment(Document row, DeviceObservationStore.Provenance p) {
        return commitment(row, p, DeviceObservationStore.CoverageAuthority.from(row));
    }
    static String commitment(Document row, DeviceObservationStore.Provenance p, DeviceObservationStore.CoverageAuthority c) {
        boolean hasMembershipCommitment = row.containsKey("historyMembershipCommitment");
        boolean hasMembershipCount = row.containsKey("historyMembershipCount");
        if (hasMembershipCommitment != hasMembershipCount) {
            throw new IllegalStateException("DEVICE_STORE_CORRUPT_SNAPSHOT");
        }
        String[] fields = {row.getString("_id"), row.getString("sourceIssuer"), row.getString("sourceEpoch"),
                row.getString("institution"), row.getString("source"), row.getString("sourceEvent"), row.getString("transaction"),
                row.getString("customer"), row.getString("device"), row.getInteger("keyVersion").toString(), row.getLong("sequence").toString(),
                row.getDate("eventTime").toInstant().toString(), row.getString("payloadCommitment"), row.getString("evidenceCommitment"), p.commitment(),
                Objects.toString(row.getString("candidateIntegrityCommitment"), ""),
                Objects.toString(row.getString("membershipIntegrityCommitment"), ""),
                row.getString("populationCommitment"), row.getString("coverageCommitment"), c.issuer(), c.keyVersion(), c.policyVersion(), c.keyCommitment(),
                c.authorizationCommitment(), c.payloadCommitment(), c.coverageId(), c.proofTime().toString(),
                row.getDate("coverageStart").toInstant().toString(), row.getDate("expireAt").toInstant().toString()};
        if (hasMembershipCommitment) {
            fields = Arrays.copyOf(fields, fields.length + 2);
            fields[fields.length - 2] = row.getInteger("historyMembershipCount").toString();
            fields[fields.length - 1] = row.getString("historyMembershipCommitment");
            return DeviceEpoch.digest("device-stage-a-version/v2", fields);
        }
        return DeviceEpoch.digest("device-stage-a-version/v1", fields);
    }
    static void require(Document row) {
        if (!commitment(row).equals(row.getString("stageACommitment"))) { throw new IllegalStateException("DEVICE_STORE_CORRUPT_SNAPSHOT"); }
    }
    static void require(Document row, DeviceObservationStore.Provenance provenance) {
        if (!commitment(row, provenance).equals(row.getString("stageACommitment"))) { throw new IllegalStateException("DEVICE_STORE_CORRUPT_SNAPSHOT"); }
    }
    static void require(Document row, DeviceObservationStore.Provenance provenance,
                        DeviceObservationStore.CoverageAuthority coverageAuthority) {
        if (!commitment(row, provenance, coverageAuthority).equals(row.getString("stageACommitment"))) {
            throw new IllegalStateException("DEVICE_STORE_CORRUPT_SNAPSHOT");
        }
    }
}
