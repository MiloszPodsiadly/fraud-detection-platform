package com.frauddetection.enricher.device;

import com.frauddetection.enricher.graph.GraphEvidenceRef;

import java.time.Instant;
import java.util.Objects;

/** Owner-resolved, read-only capability. It must be re-resolved before each Graph write or read. */
public final class VerifiedDeviceGraphRelation {
    private final GraphEvidenceRef reference;
    private final Instant validUntil;

    VerifiedDeviceGraphRelation(GraphEvidenceRef reference, Instant validUntil) {
        this.reference = Objects.requireNonNull(reference, "DEVICE_GRAPH_REFERENCE_REQUIRED");
        this.validUntil = Objects.requireNonNull(validUntil, "DEVICE_GRAPH_EXPIRY_REQUIRED");
        if (validUntil.isAfter(reference.expiresAt())) {
            throw new IllegalArgumentException("DEVICE_GRAPH_EXPIRY_MISMATCH");
        }
    }

    public GraphEvidenceRef reference() { return reference; }
    public Instant validUntil() { return validUntil; }

    @Override public String toString() { return "VerifiedDeviceGraphRelation[redacted]"; }
}
