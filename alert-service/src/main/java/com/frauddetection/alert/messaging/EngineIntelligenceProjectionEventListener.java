package com.frauddetection.alert.messaging;

import com.frauddetection.alert.engineintelligence.EngineIntelligenceProjectionService;
import com.frauddetection.common.events.contract.TransactionScoredEvent;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Objects;

@Component
public class EngineIntelligenceProjectionEventListener {

    private final EngineIntelligenceProjectionService projectionService;

    public EngineIntelligenceProjectionEventListener(EngineIntelligenceProjectionService projectionService) {
        this.projectionService = Objects.requireNonNull(projectionService, "projectionService is required");
    }

    @KafkaListener(
            id = "engineIntelligenceProjectionSourceListener",
            topics = "${app.kafka.topics.transaction-scored}",
            groupId = "${app.kafka.consumer.engine-intelligence-group-id}",
            containerFactory = "transactionScoredKafkaListenerContainerFactory"
    )
    public void onMessage(TransactionScoredEvent event) {
        projectionService.projectCurrentOccurrence(event);
    }
}
