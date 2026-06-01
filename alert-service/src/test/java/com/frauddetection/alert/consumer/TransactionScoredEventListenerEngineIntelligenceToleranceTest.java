package com.frauddetection.alert.consumer;

import com.frauddetection.alert.config.KafkaTopicProperties;
import com.frauddetection.alert.messaging.TransactionScoredEventListener;
import com.frauddetection.alert.service.AlertManagementUseCase;
import com.frauddetection.alert.service.TransactionMonitoringUseCase;
import com.frauddetection.common.events.contract.TransactionScoredEvent;
import com.frauddetection.common.events.observability.TraceContext;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class TransactionScoredEventListenerEngineIntelligenceToleranceTest {

    @Test
    void listenerAcceptsFullBoundedEngineIntelligenceEvent() {
        TransactionScoredEvent event = AlertServiceTransactionScoredEventFixtureLoader.fullBoundedEngineIntelligence();
        AlertManagementUseCase alertManagementUseCase = mock(AlertManagementUseCase.class);
        TransactionMonitoringUseCase transactionMonitoringUseCase = mock(TransactionMonitoringUseCase.class);
        TransactionScoredEventListener listener = new TransactionScoredEventListener(
                alertManagementUseCase,
                transactionMonitoringUseCase,
                new KafkaTopicProperties(
                        "transactions.scored",
                        "fraud.alerts",
                        "fraud.decisions",
                        "transactions.dead-letter"
                )
        );
        String traceId = "trace-fdp93-listener";

        doAnswer(invocation -> {
            assertListenerTraceContext(event, traceId);
            return null;
        }).when(transactionMonitoringUseCase).recordScoredTransaction(same(event));
        doAnswer(invocation -> {
            assertListenerTraceContext(event, traceId);
            return null;
        }).when(alertManagementUseCase).handleScoredTransaction(same(event));

        try (TraceContext.Scope ignored = TraceContext.open("prior-correlation", "prior-trace", "prior-transaction", null)) {
            assertThatCode(() -> listener.onMessage(event, traceId)).doesNotThrowAnyException();

            assertThat(TraceContext.currentCorrelationId()).isEqualTo("prior-correlation");
            assertThat(TraceContext.currentTraceId()).isEqualTo("prior-trace");
        }

        verify(transactionMonitoringUseCase, times(1)).recordScoredTransaction(same(event));
        verify(alertManagementUseCase, times(1)).handleScoredTransaction(same(event));
    }

    private void assertListenerTraceContext(TransactionScoredEvent event, String traceId) {
        assertThat(TraceContext.currentCorrelationId()).isEqualTo(event.correlationId());
        assertThat(TraceContext.currentTraceId()).isEqualTo(traceId);
    }
}
