package com.frauddetection.enricher.device;

import com.frauddetection.common.events.source.SourceOccurrenceAccess;

import java.time.Instant;
import java.util.Objects;

/** Owner-private typed view reconstructed from the committed Device ledger. */
record GraphOccurrenceSnapshot(String ownerReference, String institution, String source,
                               String deviceToken, int pseudonymKeyVersion, Instant eventTime,
                               DeviceObservationStore.Provenance provenance,
                               DeviceObservationStore.CoverageAuthority occurrenceCoverageAuthority,
                               String populationCommitment, String coverageCommitment, long coverageGeneration,
                               Instant coverageStart, Instant coverageWatermark, Instant expiresAt,
                               String stageASnapshotCommitment, SourceOccurrenceAccess.Reference scoredReference) {
    GraphOccurrenceSnapshot {
        Objects.requireNonNull(ownerReference);
        Objects.requireNonNull(institution);
        Objects.requireNonNull(source);
        Objects.requireNonNull(deviceToken);
        Objects.requireNonNull(eventTime);
        Objects.requireNonNull(provenance);
        Objects.requireNonNull(occurrenceCoverageAuthority);
        Objects.requireNonNull(populationCommitment);
        Objects.requireNonNull(coverageCommitment);
        Objects.requireNonNull(coverageStart);
        Objects.requireNonNull(coverageWatermark);
        Objects.requireNonNull(expiresAt);
        Objects.requireNonNull(stageASnapshotCommitment);
        Objects.requireNonNull(scoredReference);
        if (pseudonymKeyVersion < 1 || pseudonymKeyVersion > 65_535 || coverageGeneration < 1) {
            throw new IllegalArgumentException("DEVICE_GRAPH_SNAPSHOT_INVALID");
        }
    }

    @Override public String toString() { return "GraphOccurrenceSnapshot[redacted]"; }
}
