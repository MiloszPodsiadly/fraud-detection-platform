package com.frauddetection.alert.feedback.snapshot;

import com.frauddetection.alert.domain.ScoredTransaction;
import com.frauddetection.alert.domain.ScoringOccurrenceOwnership;
import com.frauddetection.alert.engineintelligence.MlPredictionEvidenceProjection;
import com.frauddetection.alert.engineintelligence.MlPredictionEvidenceProjectionRepository;
import com.frauddetection.alert.feedback.FraudFeedbackRecord;
import com.frauddetection.common.events.intelligence.MlPredictionEvidence;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.Objects;

@Component
public class MlPredictionEvidenceSnapshotter {

    private final MlPredictionEvidenceProjectionRepository repository;

    public MlPredictionEvidenceSnapshotter(MlPredictionEvidenceProjectionRepository repository) {
        this.repository = Objects.requireNonNull(repository, "mlPredictionEvidenceProjectionRepository is required");
    }

    public void snapshot(
            FraudFeedbackRecord record,
            ScoredTransaction transaction,
            ScoringOccurrenceOwnership ownership
    ) {
        if (transaction.mlPredictionEvidenceOmissionReason() != null) {
            return;
        }
        try {
            MlPredictionEvidenceProjection projection = repository.findById(ownership.sourceEventId())
                    .orElseThrow(() -> evidenceSnapshotUnavailable(null));
            if (!matchesOccurrence(projection, transaction, ownership)) {
                throw evidenceSnapshotUnavailable(null);
            }
            validateExactEvidence(projection);
            apply(record, projection);
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw evidenceSnapshotUnavailable(exception);
        }
    }

    private boolean matchesOccurrence(
            MlPredictionEvidenceProjection projection,
            ScoredTransaction transaction,
            ScoringOccurrenceOwnership ownership
    ) {
        return Objects.equals(projection.getSourceEventId(), ownership.sourceEventId())
                && Objects.equals(projection.getTransactionId(), transaction.transactionId())
                && Objects.equals(projection.getCorrelationId(), transaction.correlationId())
                && Objects.equals(projection.getSourceEventCreatedAt(), ownership.sourceEventCreatedAt());
    }

    private void validateExactEvidence(MlPredictionEvidenceProjection projection) {
        new MlPredictionEvidence(
                projection.getMlScore(),
                projection.getMlRiskLevel(),
                projection.getModelName(),
                projection.getModelVersion(),
                projection.getFeatureContractVersion(),
                projection.getModelArtifactSha256(),
                projection.getSourceExecutionTimestamp()
        );
    }

    private void apply(FraudFeedbackRecord record, MlPredictionEvidenceProjection projection) {
        record.setMlModelName(projection.getModelName());
        record.setMlModelVersion(projection.getModelVersion());
        record.setMlFeatureContractVersion(projection.getFeatureContractVersion());
        record.setMlModelArtifactSha256(projection.getModelArtifactSha256());
    }

    private ResponseStatusException evidenceSnapshotUnavailable(RuntimeException cause) {
        return new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "FRAUD_FEEDBACK_ML_PREDICTION_EVIDENCE_UNAVAILABLE",
                cause
        );
    }
}
