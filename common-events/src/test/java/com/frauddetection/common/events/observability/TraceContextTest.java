package com.frauddetection.common.events.observability;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.assertj.core.api.Assertions.assertThat;

class TraceContextTest {

    @Test
    void shouldPopulateAndRestoreMdcValues() {
        MDC.put(TraceContext.MDC_CORRELATION_ID, "existing-correlation");

        try (TraceContext.Scope ignored = TraceContext.open("corr-1", "trace-1", "txn-1", "alert-1")) {
            assertThat(MDC.get(TraceContext.MDC_CORRELATION_ID)).isEqualTo("corr-1");
            assertThat(MDC.get(TraceContext.MDC_TRACE_ID)).isEqualTo("trace-1");
            assertThat(MDC.get(TraceContext.MDC_TRANSACTION_ID)).isEqualTo("txn-1");
            assertThat(MDC.get(TraceContext.MDC_ALERT_ID)).isEqualTo("alert-1");
        }

        assertThat(MDC.get(TraceContext.MDC_CORRELATION_ID)).isEqualTo("existing-correlation");
        assertThat(MDC.get(TraceContext.MDC_TRACE_ID)).isNull();
        assertThat(MDC.get(TraceContext.MDC_TRANSACTION_ID)).isNull();
        assertThat(MDC.get(TraceContext.MDC_ALERT_ID)).isNull();
        MDC.clear();
    }
}
