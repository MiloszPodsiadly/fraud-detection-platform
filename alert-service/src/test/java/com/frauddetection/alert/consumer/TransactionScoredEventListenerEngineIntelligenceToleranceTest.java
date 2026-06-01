package com.frauddetection.alert.consumer;

import com.frauddetection.alert.config.KafkaTopicProperties;
import com.frauddetection.alert.messaging.TransactionScoredEventListener;
import com.frauddetection.alert.service.AlertManagementUseCase;
import com.frauddetection.alert.service.TransactionMonitoringUseCase;
import com.frauddetection.common.events.contract.TransactionScoredEvent;
import com.frauddetection.common.events.observability.TraceContext;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class TransactionScoredEventListenerEngineIntelligenceToleranceTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("reviewedFixtures")
    void listenerAcceptsAllReviewedTransactionScoredEventFixtures(String fixtureName, TransactionScoredEvent event) {
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
        String traceId = "trace-fdp93-listener-" + fixtureName;

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

    private static Stream<Arguments> reviewedFixtures() {
        return Stream.of(
                Arguments.of("old-without-engine-intelligence",
                        AlertServiceTransactionScoredEventFixtureLoader.oldWithoutEngineIntelligence()),
                Arguments.of("minimal-engine-intelligence",
                        AlertServiceTransactionScoredEventFixtureLoader.minimalEngineIntelligence()),
                Arguments.of("full-bounded-engine-intelligence",
                        AlertServiceTransactionScoredEventFixtureLoader.fullBoundedEngineIntelligence()),
                Arguments.of("unknown-nested-engine-intelligence-fields",
                        AlertServiceTransactionScoredEventFixtureLoader.unknownNestedEngineIntelligenceFields()),
                Arguments.of("unknown-top-level-field",
                        AlertServiceTransactionScoredEventFixtureLoader.unknownTopLevelField())
        );
    }

    private void assertListenerTraceContext(TransactionScoredEvent event, String traceId) {
        assertThat(TraceContext.currentCorrelationId()).isEqualTo(event.correlationId());
        assertThat(TraceContext.currentTraceId()).isEqualTo(traceId);
    }
}
