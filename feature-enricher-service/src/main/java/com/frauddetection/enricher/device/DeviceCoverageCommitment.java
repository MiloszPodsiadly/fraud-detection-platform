package com.frauddetection.enricher.device;

import org.bson.Document;
import java.util.ArrayList;
import java.util.List;

/** Ordered commitment to the exact staged observations covered by one signed generation. */
final class DeviceCoverageCommitment {
    private DeviceCoverageCommitment() { }
    static String compute(DeviceEpoch epoch, DeviceObservationStore.CoverageSeal seal, List<Document> orderedCandidates) {
        var proof = seal.proof();
        List<String> fields = new ArrayList<>(List.of(epoch.scopeId(seal.institutionToken(), seal.sourceCode()), proof.populationCommitment(),
                Long.toString(proof.sequenceOrigin()), Long.toString(proof.fromSequence()), Long.toString(seal.throughSequence()),
                seal.coverageStart().toString(), seal.watermark().toString(), Integer.toString(seal.keyVersion()),
                Long.toString(proof.generation()), proof.previousCommitment()));
        for (Document candidate : orderedCandidates) {
            fields.add(DeviceEpoch.digest("device-coverage-leaf/v1", candidate.getString("sourceEvent"),
                    candidate.getLong("sequence").toString(), candidate.getString("provenanceCommitment")));
        }
        return DeviceEpoch.digest("device-coverage-manifest/v1", fields.toArray(String[]::new));
    }
}
