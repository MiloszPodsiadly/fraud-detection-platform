package com.frauddetection.alert.messaging;

import com.frauddetection.alert.engineintelligence.MlPredictionEvidenceProjectionReason;
import com.frauddetection.alert.engineintelligence.MlPredictionEvidenceProjectionResult;
import com.frauddetection.alert.engineintelligence.MlPredictionEvidenceProjectionService;
import com.frauddetection.alert.engineintelligence.MlPredictionEvidenceProjectionStatus;
import com.frauddetection.common.events.contract.TransactionScoredEvent;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Objects;

@Component
public class MlPredictionEvidenceEventListener {

    private final MlPredictionEvidenceProjectionService projectionService;

    public MlPredictionEvidenceEventListener(MlPredictionEvidenceProjectionService projectionService) {
        this.projectionService = Objects.requireNonNull(projectionService, "projectionService is required");
    }

    @KafkaListener(
            id = "mlPredictionEvidenceSourceListener",
            topics = "${app.kafka.topics.transaction-scored}",
            groupId = "${app.kafka.consumer.ml-prediction-evidence-group-id}",
            containerFactory = "mlPredictionEvidenceKafkaListenerContainerFactory"
    )
    public void onMessage(TransactionScoredEvent event) {
        MlPredictionEvidenceProjectionResult result = projectionService.project(event);
        if (result.status() == MlPredictionEvidenceProjectionStatus.FAILED) {
            MlPredictionEvidenceProjectionReason reason = result.reason()
                    .orElse(MlPredictionEvidenceProjectionReason.UNKNOWN_FAILURE);
            if (reason == MlPredictionEvidenceProjectionReason.STORE_UNAVAILABLE
                    || reason == MlPredictionEvidenceProjectionReason.UNKNOWN_FAILURE) {
                throw new MlPredictionEvidenceTransientProcessingException(reason);
            }
            throw new MlPredictionEvidencePermanentProcessingException(reason);
        }
    }
}
