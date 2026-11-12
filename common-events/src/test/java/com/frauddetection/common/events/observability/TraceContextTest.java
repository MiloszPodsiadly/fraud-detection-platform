package com.frauddetection.common.events.observability;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

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

    @Test
    void exceptionAndWorkerReuseRestoreContextWithoutLeakingThePreviousRequest() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            String[] afterFailure = worker.submit(() -> {
                MDC.put(TraceContext.MDC_CORRELATION_ID, "preexisting-correlation");
                try {
                    try (TraceContext.Scope ignored = TraceContext.open("sensitive-correlation", "sensitive-trace",
                            "sensitive-transaction", "sensitive-alert")) {
                        throw new IllegalStateException("expected-test-failure");
                    }
                } catch (IllegalStateException expected) {
                    // Scope closure must happen for exceptional control flow.
                }
                String[] snapshot = {TraceContext.currentCorrelationId(), TraceContext.currentTraceId(),
                        MDC.get(TraceContext.MDC_CORRELATION_ID), MDC.get(TraceContext.MDC_TRACE_ID),
                        MDC.get(TraceContext.MDC_TRANSACTION_ID), MDC.get(TraceContext.MDC_ALERT_ID)};
                MDC.clear();
                return snapshot;
            }).get(5, TimeUnit.SECONDS);
            assertThat(afterFailure).containsExactly(null, null, "preexisting-correlation", null, null, null);

            String[] reusedWorker = worker.submit(() -> {
                assertThat(TraceContext.currentCorrelationId()).isNull();
                try (TraceContext.Scope ignored = TraceContext.open("next-correlation", "next-trace", null, null)) {
                    assertThat(TraceContext.currentCorrelationId()).isEqualTo("next-correlation");
                    assertThat(TraceContext.currentTraceId()).isEqualTo("next-trace");
                    assertThat(MDC.get(TraceContext.MDC_CORRELATION_ID)).isNull();
                }
                return new String[]{TraceContext.currentCorrelationId(), TraceContext.currentTraceId()};
            }).get(5, TimeUnit.SECONDS);
            assertThat(reusedWorker).containsExactly(null, null);
        } finally {
            worker.shutdownNow();
            MDC.clear();
        }
    }

    @Test
    void contextDoesNotPropagateToAChildThreadWithoutAnExplicitHeaderBoundary() throws Exception {
        AtomicReference<String> correlation = new AtomicReference<>("unset");
        AtomicReference<String> trace = new AtomicReference<>("unset");
        try (TraceContext.Scope ignored = TraceContext.open("request-correlation", "request-trace", "transaction", null)) {
            Thread child = new Thread(() -> {
                correlation.set(TraceContext.currentCorrelationId());
                trace.set(TraceContext.currentTraceId());
            });
            child.start();
            child.join(TimeUnit.SECONDS.toMillis(5));
            assertThat(child.isAlive()).isFalse();
        }
        assertThat(correlation.get()).isNull();
        assertThat(trace.get()).isNull();
    }
}
