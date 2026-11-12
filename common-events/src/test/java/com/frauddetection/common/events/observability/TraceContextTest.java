package com.frauddetection.common.events.observability;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.assertj.core.api.Assertions.assertThat;

class TraceContextTest {

    @Test
    void keepsBusinessIdentifiersOutOfMdcAndRestoresNestedContext() {
        MDC.put(TraceContext.MDC_CORRELATION_ID, "existing-correlation");
        MDC.put(TraceContext.MDC_TRANSACTION_ID, "existing-transaction");

        try (TraceContext.Scope ignored = TraceContext.open("corr-1", "trace-1", "txn-1", "alert-1")) {
            assertThat(TraceContext.currentCorrelationId()).isEqualTo("corr-1");
            assertThat(TraceContext.currentTraceId()).isEqualTo("trace-1");
            assertThat(MDC.get(TraceContext.MDC_CORRELATION_ID)).isNull();
            assertThat(MDC.get(TraceContext.MDC_TRACE_ID)).isNull();
            assertThat(MDC.get(TraceContext.MDC_TRANSACTION_ID)).isNull();
            assertThat(MDC.get(TraceContext.MDC_ALERT_ID)).isNull();

            try (TraceContext.Scope nested = TraceContext.open("corr-2", "trace-2", "txn-2", "alert-2")) {
                assertThat(TraceContext.currentCorrelationId()).isEqualTo("corr-2");
                assertThat(TraceContext.currentTraceId()).isEqualTo("trace-2");
                assertThat(MDC.get(TraceContext.MDC_TRANSACTION_ID)).isNull();
            }
            assertThat(TraceContext.currentCorrelationId()).isEqualTo("corr-1");
            assertThat(TraceContext.currentTraceId()).isEqualTo("trace-1");
        }

        assertThat(MDC.get(TraceContext.MDC_CORRELATION_ID)).isEqualTo("existing-correlation");
        assertThat(MDC.get(TraceContext.MDC_TRACE_ID)).isNull();
        assertThat(MDC.get(TraceContext.MDC_TRANSACTION_ID)).isEqualTo("existing-transaction");
        assertThat(MDC.get(TraceContext.MDC_ALERT_ID)).isNull();
        assertThat(TraceContext.currentCorrelationId()).isNull();
        assertThat(TraceContext.currentTraceId()).isNull();
        MDC.clear();
    }
}
