package com.frauddetection.enricher.graph;

/** Read states are deliberately distinct; there is no verified-empty state. */
public enum GraphProjectionState {
    VERIFIED_BOUNDED,
    PENDING,
    UNAVAILABLE,
    DENIED,
    CONFLICT
}
