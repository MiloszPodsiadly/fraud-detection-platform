package com.frauddetection.common.events.observability;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class TraceContextTest {

    @Test
    void keepsBusinessIdentifiersOutOfMdcAndRestoresNestedContext() {
        MDC.put(TraceContext.MDC_CORRELATION_ID, "existing-correlation");
        MDC.put(TraceContext.MDC_TRACE_ID, "existing-trace");
        MDC.put(TraceContext.MDC_TRANSACTION_ID, "existing-transaction");
        MDC.put("service", "common-events-test");

        try (TraceContext.Scope ignored = TraceContext.open("corr-1", "trace-1", "txn-1", "alert-1")) {
            assertThat(TraceContext.currentCorrelationId()).isEqualTo("corr-1");
            assertThat(TraceContext.currentTraceId()).isEqualTo("trace-1");
            assertThat(MDC.get(TraceContext.MDC_CORRELATION_ID)).isNull();
            assertThat(MDC.get(TraceContext.MDC_TRACE_ID)).isNull();
            assertThat(MDC.get(TraceContext.MDC_TRANSACTION_ID)).isNull();
            assertThat(MDC.get(TraceContext.MDC_ALERT_ID)).isNull();
            assertThat(MDC.get("service")).isEqualTo("common-events-test");

            try (TraceContext.Scope nested = TraceContext.open("corr-2", "trace-2", "txn-2", "alert-2")) {
                assertThat(TraceContext.currentCorrelationId()).isEqualTo("corr-2");
                assertThat(TraceContext.currentTraceId()).isEqualTo("trace-2");
                assertThat(MDC.get(TraceContext.MDC_TRANSACTION_ID)).isNull();
            }
            assertThat(TraceContext.currentCorrelationId()).isEqualTo("corr-1");
            assertThat(TraceContext.currentTraceId()).isEqualTo("trace-1");
        }

        assertThat(MDC.get(TraceContext.MDC_CORRELATION_ID)).isNull();
        assertThat(MDC.get(TraceContext.MDC_TRACE_ID)).isNull();
        assertThat(MDC.get(TraceContext.MDC_TRANSACTION_ID)).isNull();
        assertThat(MDC.get(TraceContext.MDC_ALERT_ID)).isNull();
        assertThat(MDC.get("service")).isEqualTo("common-events-test");
        assertThat(TraceContext.currentCorrelationId()).isNull();
        assertThat(TraceContext.currentTraceId()).isNull();
        MDC.clear();
    }

    @Test
    void capturedPostCloseLogContainsNoPreexistingSensitiveMdcValues() {
        Logger logger = (Logger) LoggerFactory.getLogger(TraceContextTest.class);
        Level previousLevel = logger.getLevel();
        boolean previousAdditive = logger.isAdditive();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.setLevel(Level.INFO);
        logger.setAdditive(false);
        logger.addAppender(appender);
        try {
            MDC.put(TraceContext.MDC_CORRELATION_ID, "canary-correlation");
            MDC.put(TraceContext.MDC_TRACE_ID, "canary-trace");
            MDC.put(TraceContext.MDC_TRANSACTION_ID, "canary-transaction");
            MDC.put(TraceContext.MDC_ALERT_ID, "canary-alert");
            MDC.put("service", "privacy-test");
            try (TraceContext.Scope ignored = TraceContext.open("request-correlation", "request-trace",
                    "request-transaction", "request-alert")) {
                assertThat(MDC.get("service")).isEqualTo("privacy-test");
            }

            logger.atInfo().addKeyValue("operation", "scope-close-test").log("post-close application log");

            assertThat(appender.list).hasSize(1);
            ILoggingEvent event = appender.list.getFirst();
            String captured = event.getFormattedMessage() + " " + event.getMDCPropertyMap() + " " + event.getKeyValuePairs();
            assertThat(captured).contains("post-close application log", "privacy-test", "scope-close-test")
                    .doesNotContain("canary-correlation", "canary-trace", "canary-transaction", "canary-alert",
                            "request-correlation", "request-trace", "request-transaction", "request-alert");
            assertThat(event.getMDCPropertyMap()).containsEntry("service", "privacy-test")
                    .doesNotContainKeys(TraceContext.MDC_CORRELATION_ID, TraceContext.MDC_TRACE_ID,
                            TraceContext.MDC_TRANSACTION_ID, TraceContext.MDC_ALERT_ID);
        } finally {
            MDC.clear();
            logger.detachAppender(appender);
            appender.stop();
            logger.setLevel(previousLevel);
            logger.setAdditive(previousAdditive);
        }
    }

    @Test
    void repeatedNestedCloseRemainsIdempotentAndKeepsMdcClear() {
        try {
            MDC.put("service", "nested-scope-test");
            TraceContext.Scope outer = TraceContext.open("outer-correlation", "outer-trace", null, null);
            TraceContext.Scope inner = TraceContext.open("inner-correlation", "inner-trace", null, null);

            inner.close();
            assertThat(TraceContext.currentCorrelationId()).isEqualTo("outer-correlation");
            MDC.put(TraceContext.MDC_ALERT_ID, "late-sensitive-canary");
            inner.close();

            assertThat(TraceContext.currentCorrelationId()).isEqualTo("outer-correlation");
            assertThat(MDC.get(TraceContext.MDC_ALERT_ID)).isNull();
            assertThat(MDC.get("service")).isEqualTo("nested-scope-test");

            outer.close();
            outer.close();
            assertThat(TraceContext.currentCorrelationId()).isNull();
            assertThat(TraceContext.currentTraceId()).isNull();
            assertThat(MDC.get(TraceContext.MDC_CORRELATION_ID)).isNull();
            assertThat(MDC.get(TraceContext.MDC_TRACE_ID)).isNull();
            assertThat(MDC.get(TraceContext.MDC_TRANSACTION_ID)).isNull();
            assertThat(MDC.get(TraceContext.MDC_ALERT_ID)).isNull();
            assertThat(MDC.get("service")).isEqualTo("nested-scope-test");
        } finally {
            MDC.clear();
        }
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
            assertThat(afterFailure).containsExactly(null, null, null, null, null, null);

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
