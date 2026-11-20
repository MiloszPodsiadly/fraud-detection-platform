package com.frauddetection.enricher.graph;

/** Admission outcomes distinguish retryable owner lag from terminal denial and conflict. */
enum GraphAdmissionState {
    ADMITTED,
    PENDING,
    UNAVAILABLE,
    DENIED,
    CONFLICT
}
