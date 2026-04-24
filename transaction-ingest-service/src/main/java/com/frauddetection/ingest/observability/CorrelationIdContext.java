package com.frauddetection.ingest.observability;

import com.frauddetection.common.events.observability.TraceContext;

public final class CorrelationIdContext {

    public static final String CORRELATION_ID_HEADER = TraceContext.HTTP_CORRELATION_ID_HEADER;
    public static final String CORRELATION_ID_ATTRIBUTE = CorrelationIdContext.class.getName() + ".correlationId";

    private CorrelationIdContext() {
    }
}
