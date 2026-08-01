package com.frauddetection.scoring.engine.velocity;

import com.frauddetection.common.events.engine.FraudEngineConfidence;
import com.frauddetection.common.events.engine.FraudEngineContribution;
import com.frauddetection.common.events.engine.FraudEngineContributionDirection;
import com.frauddetection.common.events.engine.FraudEngineEvidence;
import com.frauddetection.common.events.engine.FraudEngineEvidenceStatus;
import com.frauddetection.common.events.engine.FraudEngineEvidenceType;
import com.frauddetection.common.events.engine.FraudEngineResult;
import com.frauddetection.common.events.engine.FraudEngineStatus;
import com.frauddetection.scoring.engine.FraudSignalEvaluation;

import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

final class VelocityResultFactory {
    private static final String EVIDENCE_SOURCE = "VELOCITY";

    FraudSignalEvaluation availableResult(VelocitySignalPolicy.VelocityDecision decision) {
        return new FraudSignalEvaluation(
                FraudEngineStatus.AVAILABLE,
                decision.score(),
                decision.riskLevel(),
                FraudEngineConfidence.UNKNOWN,
                decision.reasonCodes(),
                contributions(decision),
                evidence(decision),
                null,
                null,
                null
        );
    }

    FraudSignalEvaluation operationalResult(VelocityInputValidation validation) {
        FraudEngineStatus status = validation.readiness() == VelocityInputReadiness.UNAVAILABLE
                ? FraudEngineStatus.UNAVAILABLE
                : FraudEngineStatus.DEGRADED;
        FraudEngineEvidenceStatus evidenceStatus = validation.readiness() == VelocityInputReadiness.UNAVAILABLE
                ? FraudEngineEvidenceStatus.UNAVAILABLE
                : FraudEngineEvidenceStatus.PARTIAL;
        String reasonCode = validation.reasonCode().wireValue();
        return new FraudSignalEvaluation(
                status,
                null,
                null,
                FraudEngineConfidence.UNKNOWN,
                List.of(reasonCode),
                List.of(),
                List.of(new FraudEngineEvidence(
                        FraudEngineEvidenceType.OPERATIONAL_FALLBACK,
                        reasonCode,
                        "Velocity status",
                        "Velocity diagnostic input was not usable.",
                        EVIDENCE_SOURCE,
                        evidenceStatus
                )),
                null,
                null,
                reasonCode
        );
    }

    private List<FraudEngineContribution> contributions(VelocitySignalPolicy.VelocityDecision decision) {
        return Stream.of(
                        contribution(decision.rapidBurst(), "RAPID_TRANSFER_PLN_BURST", 0.40d),
                        contribution(decision.highRate(), "TRANSACTION_VELOCITY_PER_MINUTE", 0.25d),
                        contribution(decision.highAmount() && !decision.rapidBurst(), "RECENT_AMOUNT_SUM_PLN", 0.15d)
                )
                .filter(Objects::nonNull)
                .toList();
    }

    private FraudEngineContribution contribution(boolean present, String feature, double weight) {
        if (!present) {
            return null;
        }
        return new FraudEngineContribution(feature, null, weight, FraudEngineContributionDirection.INCREASES_RISK);
    }

    private List<FraudEngineEvidence> evidence(VelocitySignalPolicy.VelocityDecision decision) {
        return decision.reasonCodes().stream()
                .map(reasonCode -> new FraudEngineEvidence(
                        FraudEngineEvidenceType.VELOCITY_SIGNAL,
                        reasonCode,
                        "Velocity signal",
                        "Bounded velocity diagnostic signal.",
                        EVIDENCE_SOURCE,
                        FraudEngineEvidenceStatus.AVAILABLE
                ))
                .toList();
    }
}
