package com.frauddetection.alert.messaging;

import com.frauddetection.alert.engineintelligence.EngineIntelligenceProjectionService;
import com.frauddetection.alert.engineintelligence.EngineIntelligencePendingProjectionService;
import com.frauddetection.common.events.contract.TransactionScoredEvent;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class EngineIntelligenceProjectionEventListenerTest {

    private final EngineIntelligenceProjectionService projectionService = mock(EngineIntelligenceProjectionService.class);
    private final EngineIntelligencePendingProjectionService pendingProjectionService =
            mock(EngineIntelligencePendingProjectionService.class);
    private final EngineIntelligenceProjectionEventListener listener =
            new EngineIntelligenceProjectionEventListener(projectionService, pendingProjectionService);

    @Test
    void delegatesToOccurrenceAwareProjectionBoundary() {
        TransactionScoredEvent event = mock(TransactionScoredEvent.class);

        listener.onMessage(event);

        verify(projectionService).projectCurrentOccurrence(event);
    }

    @Test
    void storeFailureEscapesForKafkaRetryAndDurableDeadLetterHandoff() {
        TransactionScoredEvent event = mock(TransactionScoredEvent.class);
        doThrow(new IllegalStateException("bounded failure")).when(projectionService).projectCurrentOccurrence(event);

        assertThatThrownBy(() -> listener.onMessage(event))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("bounded failure");
    }

    @Test
    void baselineOrderingGapIsDurablyDeferredBeforeKafkaAcknowledgement() {
        TransactionScoredEvent event = mock(TransactionScoredEvent.class);
        doThrow(new EngineIntelligenceProjectionService.CurrentScoringOccurrencePendingException())
                .when(projectionService).projectCurrentOccurrence(event);

        listener.onMessage(event);

        verify(pendingProjectionService).defer(event);
    }
}
