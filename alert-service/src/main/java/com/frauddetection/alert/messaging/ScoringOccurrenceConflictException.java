package com.frauddetection.alert.messaging;

import com.frauddetection.alert.domain.ScoringOccurrenceAdmissionResult;

public class ScoringOccurrenceConflictException extends RuntimeException {

    private final ScoringOccurrenceAdmissionResult.ReasonCode reasonCode;

    public ScoringOccurrenceConflictException(ScoringOccurrenceAdmissionResult.ReasonCode reasonCode) {
        super("SCORING_OCCURRENCE_CONFLICT");
        this.reasonCode = reasonCode;
    }

    public ScoringOccurrenceAdmissionResult.ReasonCode reasonCode() {
        return reasonCode;
    }
}
