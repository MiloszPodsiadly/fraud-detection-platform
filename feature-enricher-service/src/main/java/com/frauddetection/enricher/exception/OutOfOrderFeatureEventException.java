package com.frauddetection.enricher.exception;

/** Terminal source-scoped event-time regression; scoring from an incomplete snapshot is forbidden. */
public final class OutOfOrderFeatureEventException extends FeatureEnrichmentException {
    public static final String CODE = "FEATURE_EVENT_TIME_OUT_OF_ORDER";

    public OutOfOrderFeatureEventException() {
        super(CODE);
    }
}
