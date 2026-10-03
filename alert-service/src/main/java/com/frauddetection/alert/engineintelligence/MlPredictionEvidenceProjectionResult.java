package com.frauddetection.alert.engineintelligence;

import java.util.Objects;
import java.util.Optional;

public record MlPredictionEvidenceProjectionResult(
        MlPredictionEvidenceProjectionStatus status,
        Optional<MlPredictionEvidenceProjectionReason> reason
) {
    public MlPredictionEvidenceProjectionResult {
        Objects.requireNonNull(status, "status is required");
        reason = Objects.requireNonNull(reason, "reason is required");
        if ((status == MlPredictionEvidenceProjectionStatus.PROJECTED
                || status == MlPredictionEvidenceProjectionStatus.IDEMPOTENT_REPLAY) && reason.isPresent()) {
            throw new IllegalArgumentException("successful evidence projection cannot have a failure reason");
        }
        if ((status == MlPredictionEvidenceProjectionStatus.OMITTED
                || status == MlPredictionEvidenceProjectionStatus.FAILED) && reason.isEmpty()) {
            throw new IllegalArgumentException("unsuccessful evidence projection requires a reason");
        }
    }

    public static MlPredictionEvidenceProjectionResult projected() {
        return new MlPredictionEvidenceProjectionResult(
                MlPredictionEvidenceProjectionStatus.PROJECTED,
                Optional.empty()
        );
    }

    public static MlPredictionEvidenceProjectionResult idempotentReplay() {
        return new MlPredictionEvidenceProjectionResult(
                MlPredictionEvidenceProjectionStatus.IDEMPOTENT_REPLAY,
                Optional.empty()
        );
    }

    public static MlPredictionEvidenceProjectionResult omitted(MlPredictionEvidenceProjectionReason reason) {
        return new MlPredictionEvidenceProjectionResult(
                MlPredictionEvidenceProjectionStatus.OMITTED,
                Optional.of(reason)
        );
    }

    public static MlPredictionEvidenceProjectionResult failed(MlPredictionEvidenceProjectionReason reason) {
        return new MlPredictionEvidenceProjectionResult(
                MlPredictionEvidenceProjectionStatus.FAILED,
                Optional.of(reason)
        );
    }
}
