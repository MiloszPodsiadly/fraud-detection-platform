package com.frauddetection.enricher.device;

import org.bson.Document;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Immutable Stage A version covers facts and their complete retained authority/retention metadata. */
final class DeviceSnapshotVersion {
    private DeviceSnapshotVersion() { }
    static String commitment(Document row) {
        var p = DeviceObservationStore.Provenance.from(row);
        var c = DeviceObservationStore.CoverageAuthority.from(row);
        List<String> fields = new ArrayList<>(List.of(row.getString("_id"), row.getString("sourceIssuer"), row.getString("sourceEpoch"),
                row.getString("institution"), row.getString("source"), row.getString("sourceEvent"), row.getString("transaction"),
                row.getString("customer"), row.getString("device"), row.getInteger("keyVersion").toString(), row.getLong("sequence").toString(),
                row.getDate("eventTime").toInstant().toString(), row.getString("payloadCommitment"), row.getString("evidenceCommitment"), p.commitment(),
                Objects.toString(row.getString("candidateIntegrityCommitment"), ""),
                Objects.toString(row.getString("membershipIntegrityCommitment"), ""),
                row.getString("populationCommitment"), row.getString("coverageCommitment"), c.issuer(), c.keyVersion(), c.policyVersion(), c.keyCommitment(),
                c.authorizationCommitment(), c.payloadCommitment(), c.coverageId(), c.proofTime().toString(),
                row.getDate("coverageStart").toInstant().toString(), row.getDate("expireAt").toInstant().toString()));
        if (row.containsKey("historyMembershipCommitment") || row.containsKey("historyMembershipCount")) {
            if (!row.containsKey("historyMembershipCommitment") || !row.containsKey("historyMembershipCount")) {
                throw new IllegalStateException("DEVICE_STORE_CORRUPT_SNAPSHOT");
            }
            fields.add(row.getInteger("historyMembershipCount").toString());
            fields.add(row.getString("historyMembershipCommitment"));
            return DeviceEpoch.digest("device-stage-a-version/v2", fields.toArray(String[]::new));
        }
        return DeviceEpoch.digest("device-stage-a-version/v1", fields.toArray(String[]::new));
    }
    static void require(Document row) {
        if (!commitment(row).equals(row.getString("stageACommitment"))) { throw new IllegalStateException("DEVICE_STORE_CORRUPT_SNAPSHOT"); }
    }
}
