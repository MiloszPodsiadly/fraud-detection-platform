package com.frauddetection.ingest.observability;

public final class CorrelationIdContext {

    public static final String CORRELATION_ID_HEADER = "X-Correlation-Id";
    public static final String CORRELATION_ID_ATTRIBUTE = CorrelationIdContext.class.getName() + ".correlationId";

    private CorrelationIdContext() {
    }
}
