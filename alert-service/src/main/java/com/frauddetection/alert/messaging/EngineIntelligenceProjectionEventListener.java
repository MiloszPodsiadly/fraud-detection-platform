package com.frauddetection.alert.messaging;

import com.frauddetection.alert.engineintelligence.EngineIntelligenceProjectionService;
import com.frauddetection.alert.engineintelligence.EngineIntelligencePendingProjectionService;
import com.frauddetection.common.events.contract.TransactionScoredEvent;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Objects;

@Component
public class EngineIntelligenceProjectionEventListener {

    private final EngineIntelligenceProjectionService projectionService;
    private final EngineIntelligencePendingProjectionService pendingProjectionService;

    public EngineIntelligenceProjectionEventListener(
            EngineIntelligenceProjectionService projectionService,
            EngineIntelligencePendingProjectionService pendingProjectionService
    ) {
        this.projectionService = Objects.requireNonNull(projectionService, "projectionService is required");
        this.pendingProjectionService = Objects.requireNonNull(
                pendingProjectionService,
                "pendingProjectionService is required"
        );
    }

    @KafkaListener(
            id = "engineIntelligenceProjectionSourceListener",
            topics = "${app.kafka.topics.transaction-scored}",
            groupId = "${app.kafka.consumer.engine-intelligence-group-id}",
            containerFactory = "engineIntelligenceKafkaListenerContainerFactory"
    )
    public void onMessage(TransactionScoredEvent event) {
        try {
            projectionService.projectCurrentOccurrence(event);
        } catch (EngineIntelligenceProjectionService.CurrentScoringOccurrencePendingException exception) {
            pendingProjectionService.defer(event);
        }
    }
}
