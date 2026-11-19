package com.frauddetection.enricher.graph;

import java.time.Instant;

/** Typed owner result; absence, denial, transient lag, and conflicting proof remain distinct. */
record GraphRelationResolution(State state, GraphReadReason reason,
                               GraphEvidenceRef evidence, Instant validUntil) {
    enum State { VERIFIED, PENDING, UNAVAILABLE, DENIED, CONFLICT }

    GraphRelationResolution {
        if (state == null || reason == null
                || (state == State.VERIFIED
                ? evidence == null || validUntil == null || reason != GraphReadReason.NONE
                    || !validUntil.isAfter(evidence.asOf()) || validUntil.isAfter(evidence.expiresAt())
                : evidence != null || validUntil != null || reason == GraphReadReason.NONE)) {
            throw new IllegalArgumentException("GRAPH_OWNER_RESOLUTION_INVALID");
        }
    }

    static GraphRelationResolution verified(GraphEvidenceRef evidence, Instant validUntil) {
        return new GraphRelationResolution(State.VERIFIED, GraphReadReason.NONE, evidence, validUntil);
    }
    static GraphRelationResolution pending(GraphReadReason reason) {
        return new GraphRelationResolution(State.PENDING, reason, null, null);
    }
    static GraphRelationResolution unavailable(GraphReadReason reason) {
        return new GraphRelationResolution(State.UNAVAILABLE, reason, null, null);
    }
    static GraphRelationResolution denied(GraphReadReason reason) {
        return new GraphRelationResolution(State.DENIED, reason, null, null);
    }
    static GraphRelationResolution conflict() {
        return new GraphRelationResolution(State.CONFLICT, GraphReadReason.CONFLICTING_EVIDENCE, null, null);
    }
}
