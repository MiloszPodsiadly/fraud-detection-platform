package com.frauddetection.common.events.intelligence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.frauddetection.common.events.engine.FraudEngineStatus;
import com.frauddetection.common.events.engine.FraudEngineType;
import com.frauddetection.common.events.enums.RiskLevel;

import java.time.Instant;
import java.util.List;

final class EngineIntelligenceTestSupport {
    static final Instant GENERATED_AT = Instant.parse("2026-06-01T06:00:00Z");

    private EngineIntelligenceTestSupport() {
    }

    static EngineIntelligenceSummary summary() {
        return summary(
                List.of(engine()),
                List.of(signal()),
                List.of(warning())
        );
    }

    static EngineIntelligenceSummary summary(
            List<EngineIntelligenceEngineResult> engines,
            List<EngineIntelligenceDiagnosticSignal> signals,
            List<EngineIntelligenceWarningSummary> warnings
    ) {
        return new EngineIntelligenceSummary(
                EngineIntelligenceSummary.CONTRACT_VERSION,
                GENERATED_AT,
                engines,
                comparison(),
                signals,
                warnings
        );
    }

    static EngineIntelligenceEngineResult engine() {
        return engine(List.of("HIGH_VELOCITY"));
    }

    static EngineIntelligenceEngineResult engine(List<String> reasonCodes) {
        return new EngineIntelligenceEngineResult(
                "rules.primary",
                FraudEngineType.RULES,
                FraudEngineStatus.AVAILABLE,
                RiskLevel.HIGH,
                EngineIntelligenceScoreBucket.HIGH,
                reasonCodes
        );
    }

    static EngineIntelligenceComparison comparison() {
        return new EngineIntelligenceComparison(
                EngineIntelligenceAgreementStatus.AGREEMENT,
                EngineIntelligenceRiskMismatchStatus.SAME_RISK_LEVEL,
                EngineIntelligenceScoreDeltaBucket.SMALL
        );
    }

    static EngineIntelligenceDiagnosticSignal signal() {
        return new EngineIntelligenceDiagnosticSignal(
                "rules.primary",
                FraudEngineType.RULES,
                FraudEngineStatus.AVAILABLE,
                EngineIntelligenceSignalCategory.FRAUD_SIGNAL,
                RiskLevel.HIGH,
                EngineIntelligenceScoreBucket.HIGH,
                "HIGH_VELOCITY"
        );
    }

    static EngineIntelligenceWarningSummary warning() {
        return new EngineIntelligenceWarningSummary(EngineIntelligenceWarningCode.EVIDENCE_UNSAFE_DROPPED, 1);
    }

    static ObjectMapper objectMapper() {
        return new ObjectMapper().registerModule(new JavaTimeModule());
    }
}
