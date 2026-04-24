package com.frauddetection.common.events.observability;

import org.slf4j.MDC;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

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

    private TraceContext() {
    }

    public static Scope open(String correlationId, String traceId, String transactionId, String alertId) {
        Map<String, String> previousValues = snapshot();
        putOrRemove(MDC_CORRELATION_ID, correlationId);
        putOrRemove(MDC_TRACE_ID, traceId);
        putOrRemove(MDC_TRANSACTION_ID, transactionId);
        putOrRemove(MDC_ALERT_ID, alertId);
        return new Scope(previousValues);
    }

    public static String currentCorrelationId() {
        return normalize(MDC.get(MDC_CORRELATION_ID));
    }

    public static String currentTraceId() {
        return normalize(MDC.get(MDC_TRACE_ID));
    }

    private static Map<String, String> snapshot() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put(MDC_CORRELATION_ID, MDC.get(MDC_CORRELATION_ID));
        values.put(MDC_TRACE_ID, MDC.get(MDC_TRACE_ID));
        values.put(MDC_TRANSACTION_ID, MDC.get(MDC_TRANSACTION_ID));
        values.put(MDC_ALERT_ID, MDC.get(MDC_ALERT_ID));
        return values;
    }

    private static void putOrRemove(String key, String value) {
        String normalized = normalize(value);
        if (normalized == null) {
            MDC.remove(key);
            return;
        }
        MDC.put(key, normalized);
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    public static final class Scope implements AutoCloseable {

        private final Map<String, String> previousValues;
        private boolean closed;

        private Scope(Map<String, String> previousValues) {
            this.previousValues = Objects.requireNonNull(previousValues, "previousValues must not be null");
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            previousValues.forEach(TraceContext::putOrRemove);
        }
    }
}
