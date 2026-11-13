package com.frauddetection.common.events.observability;

import java.util.List;

public final class TraceContext {

    public static final String HTTP_CORRELATION_ID_HEADER = "X-Correlation-Id";
    public static final String KAFKA_CORRELATION_ID_HEADER = "correlationId";
    public static final String KAFKA_TRACE_ID_HEADER = "traceId";
    public static final String KAFKA_TRANSACTION_ID_HEADER = "transactionId";
    public static final String KAFKA_ALERT_ID_HEADER = "alertId";

    public static final String MDC_CORRELATION_ID = "correlationId";
    public static final String MDC_TRACE_ID = "traceId";
    public static final String MDC_TRANSACTION_ID = "transactionId";
    public static final String MDC_ALERT_ID = "alertId";
    private static final List<String> SENSITIVE_MDC_KEYS = List.of(
            MDC_CORRELATION_ID, MDC_TRACE_ID, MDC_TRANSACTION_ID, MDC_ALERT_ID);
    private static final ThreadLocal<Context> CURRENT = new ThreadLocal<>();

    private TraceContext() {
    }

    public static Scope open(String correlationId, String traceId, String transactionId, String alertId) {
        Context previousContext = CURRENT.get();
        SENSITIVE_MDC_KEYS.forEach(org.slf4j.MDC::remove);
        CURRENT.set(new Context(normalize(correlationId), normalize(traceId), normalize(transactionId), normalize(alertId)));
        return new Scope(previousContext);
    }

    public static String currentCorrelationId() {
        Context context = CURRENT.get();
        return context == null ? null : context.correlationId;
    }

    public static String currentTraceId() {
        Context context = CURRENT.get();
        return context == null ? null : context.traceId;
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private record Context(String correlationId, String traceId, String transactionId, String alertId) { }

    public static final class Scope implements AutoCloseable {

        private final Context previousContext;
        private boolean closed;

        private Scope(Context previousContext) {
            this.previousContext = previousContext;
        }

        @Override
        public void close() {
            if (closed) {
                SENSITIVE_MDC_KEYS.forEach(org.slf4j.MDC::remove);
                return;
            }
            closed = true;
            try {
                if (previousContext == null) { CURRENT.remove(); }
                else { CURRENT.set(previousContext); }
            } finally {
                // Sensitive identifiers never return to the loggable MDC, including after nested scopes.
                SENSITIVE_MDC_KEYS.forEach(org.slf4j.MDC::remove);
            }
        }
    }
}
