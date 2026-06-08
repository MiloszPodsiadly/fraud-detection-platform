package com.frauddetection.alert.engineintelligence.feedback;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;

@Document(collection = "engine_intelligence_feedback")
@CompoundIndexes({
        @CompoundIndex(
                name = "engine_intelligence_feedback_idempotency_idx",
                def = "{'submittedBy': 1, 'transactionId': 1, 'idempotencyKeyHash': 1}",
                unique = true
        ),
        @CompoundIndex(
                name = "engine_intelligence_feedback_transaction_created_idx",
                def = "{'transactionId': 1, 'createdAt': -1}"
        ),
        @CompoundIndex(
                name = "engine_intelligence_feedback_transaction_submitted_feedback_idx",
                def = "{'transactionId': 1, 'submittedAt': -1, 'feedbackId': 1}"
        ),
        @CompoundIndex(
                name = "engine_intelligence_feedback_dataset_submitted_idx",
                def = "{'submittedAt': -1, 'feedbackId': 1}"
        )
})
public class EngineIntelligenceFeedbackDocument {

    @Id
    private final String feedbackId;
    private final String transactionId;
    private final boolean engineIntelligenceAvailable;
    private final EngineIntelligenceFeedbackType feedbackType;
    private final EngineIntelligenceFeedbackUsefulness usefulness;
    private final EngineIntelligenceFeedbackAccuracyAssessment accuracyAssessment;
    private final List<String> selectedReasonCodes;
    private final String submittedBy;
    private final Instant submittedAt;
    private final String correlationId;
    private final String idempotencyKeyHash;
    private final String requestPayloadHash;
    private final Instant createdAt;

    public EngineIntelligenceFeedbackDocument(
            String feedbackId,
            String transactionId,
            boolean engineIntelligenceAvailable,
            EngineIntelligenceFeedbackType feedbackType,
            EngineIntelligenceFeedbackUsefulness usefulness,
            EngineIntelligenceFeedbackAccuracyAssessment accuracyAssessment,
            List<String> selectedReasonCodes,
            String submittedBy,
            Instant submittedAt,
            String correlationId,
            String idempotencyKeyHash,
            String requestPayloadHash,
            Instant createdAt
    ) {
        this.feedbackId = feedbackId;
        this.transactionId = transactionId;
        this.engineIntelligenceAvailable = engineIntelligenceAvailable;
        this.feedbackType = feedbackType;
        this.usefulness = usefulness;
        this.accuracyAssessment = accuracyAssessment;
        this.selectedReasonCodes = selectedReasonCodes == null ? List.of() : List.copyOf(selectedReasonCodes);
        this.submittedBy = submittedBy;
        this.submittedAt = submittedAt;
        this.correlationId = correlationId;
        this.idempotencyKeyHash = idempotencyKeyHash;
        this.requestPayloadHash = requestPayloadHash;
        this.createdAt = createdAt;
    }

    public String getFeedbackId() {
        return feedbackId;
    }

    public String getTransactionId() {
        return transactionId;
    }

    public boolean isEngineIntelligenceAvailable() {
        return engineIntelligenceAvailable;
    }

    public EngineIntelligenceFeedbackType getFeedbackType() {
        return feedbackType;
    }

    public EngineIntelligenceFeedbackUsefulness getUsefulness() {
        return usefulness;
    }

    public EngineIntelligenceFeedbackAccuracyAssessment getAccuracyAssessment() {
        return accuracyAssessment;
    }

    public List<String> getSelectedReasonCodes() {
        return selectedReasonCodes;
    }

    public String getSubmittedBy() {
        return submittedBy;
    }

    public Instant getSubmittedAt() {
        return submittedAt;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public String getIdempotencyKeyHash() {
        return idempotencyKeyHash;
    }

    public String getRequestPayloadHash() {
        return requestPayloadHash;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
