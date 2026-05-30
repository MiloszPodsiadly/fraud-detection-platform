package com.frauddetection.scoring.engine.rules;

import com.frauddetection.common.events.engine.FraudEngineConfidence;
import com.frauddetection.common.events.engine.FraudEngineContribution;
import com.frauddetection.common.events.engine.FraudEngineContributionDirection;
import com.frauddetection.common.events.engine.FraudEngineEvidence;
import com.frauddetection.common.events.engine.FraudEngineEvidenceStatus;
import com.frauddetection.common.events.engine.FraudEngineEvidenceType;
import com.frauddetection.common.events.engine.FraudEngineResult;
import com.frauddetection.common.events.engine.FraudEngineStatus;
import com.frauddetection.common.events.engine.FraudEngineType;
import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.common.events.features.FraudFeatureContract;
import com.frauddetection.scoring.context.ScoringContext;
import com.frauddetection.scoring.engine.FraudEngineDescriptor;
import com.frauddetection.scoring.engine.FraudSignalEngine;
import com.frauddetection.scoring.features.FeatureSnapshotReader;
import com.frauddetection.scoring.features.FeatureSnapshotReaderFactory;
import com.frauddetection.scoring.features.FeatureSnapshotValue;
import com.frauddetection.scoring.features.FeatureSnapshotValueStatus;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class RuleBasedSignalEngine implements FraudSignalEngine {

    private static final String ENGINE_ID = "rules.primary";
    private static final String ENGINE_LANGUAGE = "java";
    private static final String ENGINE_VERSION = "1.0.0";
    private static final String EVIDENCE_SOURCE = "RULES";
    private static final double BASE_SCORE = 0.05d;
    private static final double HIGH_THRESHOLD = 0.75d;
    private static final double CRITICAL_THRESHOLD = 0.90d;
    private static final BigDecimal AMOUNT_ACTIVITY_THRESHOLD = new BigDecimal("1000");
    private static final BigDecimal RECENT_AMOUNT_THRESHOLD = new BigDecimal("5000");
    private static final BigDecimal RAPID_TRANSFER_TOTAL_THRESHOLD = new BigDecimal("20000");

    private final FeatureSnapshotReaderFactory readerFactory;

    public RuleBasedSignalEngine(FeatureSnapshotReaderFactory readerFactory) {
        this.readerFactory = Objects.requireNonNull(readerFactory, "readerFactory is required");
    }

    @Override
    public FraudEngineResult evaluate(ScoringContext context) {
        Objects.requireNonNull(context, "context is required");
        Instant startedAt = Instant.now();
        FeatureSnapshotReader reader = readerFactory.from(context);
        EvaluationState state = new EvaluationState();

        evaluateBooleanSignal(reader.booleanValue(FraudFeatureContract.DEVICE_NOVELTY),
                RuleBasedSignalReasonCode.DEVICE_NOVELTY_SIGNAL, FraudEngineEvidenceType.DEVICE_SIGNAL, 0.10d, state);
        evaluateBooleanSignal(reader.booleanValue(FraudFeatureContract.COUNTRY_MISMATCH),
                RuleBasedSignalReasonCode.COUNTRY_MISMATCH_SIGNAL, FraudEngineEvidenceType.RULE_MATCH, 0.12d, state);
        evaluateBooleanSignal(reader.booleanValue(FraudFeatureContract.PROXY_OR_VPN_DETECTED),
                RuleBasedSignalReasonCode.PROXY_OR_VPN_SIGNAL, FraudEngineEvidenceType.DEVICE_SIGNAL, 0.10d, state);
        evaluateBooleanSignal(reader.booleanValue(FraudFeatureContract.RAPID_TRANSFER_BURST),
                RuleBasedSignalReasonCode.RAPID_TRANSFER_BURST_SIGNAL, FraudEngineEvidenceType.VELOCITY_SIGNAL, 0.45d, state);
        evaluateBooleanSignal(reader.booleanValue(FraudFeatureContract.RAPID_TRANSFER_FRAUD_CASE_CANDIDATE),
                RuleBasedSignalReasonCode.RAPID_TRANSFER_PATTERN_MATCHED, FraudEngineEvidenceType.VELOCITY_SIGNAL, 0.20d, state);

        evaluateIntegerThreshold(reader.integerValue(FraudFeatureContract.RECENT_TRANSACTION_COUNT), 5,
                RuleBasedSignalReasonCode.VELOCITY_THRESHOLD_EXCEEDED, FraudEngineEvidenceType.VELOCITY_SIGNAL, 0.10d, state);
        evaluateIntegerThreshold(reader.integerValue(FraudFeatureContract.HIGH_RISK_FLAG_COUNT), 1,
                RuleBasedSignalReasonCode.HIGH_RISK_FLAGS_PRESENT, FraudEngineEvidenceType.RULE_MATCH, 0.14d, state);
        evaluateIntegerThreshold(reader.integerValue(FraudFeatureContract.RAPID_TRANSFER_COUNT), 2,
                RuleBasedSignalReasonCode.RAPID_TRANSFER_BURST_SIGNAL, FraudEngineEvidenceType.VELOCITY_SIGNAL, 0.20d, state);
        evaluateDoubleThreshold(reader.doubleValue(FraudFeatureContract.TRANSACTION_VELOCITY_PER_MINUTE), 5.0d,
                RuleBasedSignalReasonCode.VELOCITY_THRESHOLD_EXCEEDED, FraudEngineEvidenceType.VELOCITY_SIGNAL, 0.12d, state);
        evaluateDecimalThreshold(reader.decimalValue(FraudFeatureContract.CURRENT_TRANSACTION_AMOUNT_PLN),
                AMOUNT_ACTIVITY_THRESHOLD, RuleBasedSignalReasonCode.AMOUNT_ACTIVITY_THRESHOLD_EXCEEDED,
                FraudEngineEvidenceType.RULE_MATCH, 0.0d, state);
        evaluateDecimalThreshold(reader.decimalValue(FraudFeatureContract.RECENT_AMOUNT_SUM_PLN),
                RECENT_AMOUNT_THRESHOLD, RuleBasedSignalReasonCode.AMOUNT_ACTIVITY_THRESHOLD_EXCEEDED,
                FraudEngineEvidenceType.RULE_MATCH, 0.10d, state);
        evaluateDecimalThreshold(reader.decimalValue(FraudFeatureContract.RAPID_TRANSFER_TOTAL_PLN),
                RAPID_TRANSFER_TOTAL_THRESHOLD, RuleBasedSignalReasonCode.RAPID_TRANSFER_BURST_SIGNAL,
                FraudEngineEvidenceType.VELOCITY_SIGNAL, 0.45d, state);

        long latencyMs = Math.max(0L, Duration.between(startedAt, Instant.now()).toMillis());
        if (state.invalidStatus != null) {
            return degradedResultFor(state.invalidStatus, latencyMs, Instant.now());
        }
        return availableResult(state, latencyMs, Instant.now());
    }

    @Override
    public FraudEngineDescriptor descriptor() {
        return new FraudEngineDescriptor(ENGINE_ID, FraudEngineType.RULES, ENGINE_LANGUAGE, ENGINE_VERSION, true);
    }

    static FraudEngineResult degradedResultFor(FeatureSnapshotValueStatus status, long latencyMs, Instant generatedAt) {
        RuleBasedSignalReasonCode reasonCode = switch (status) {
            case INVALID_TYPE -> RuleBasedSignalReasonCode.FEATURE_STATUS_INVALID;
            case WRONG_ACCESSOR -> RuleBasedSignalReasonCode.FEATURE_STATUS_WRONG_ACCESSOR;
            case NOT_ALLOWED -> RuleBasedSignalReasonCode.FEATURE_STATUS_NOT_ALLOWED;
            case PRESENT, MISSING -> throw new IllegalArgumentException("status is not a degraded feature status");
        };
        return new FraudEngineResult(
                ENGINE_ID,
                FraudEngineType.RULES,
                ENGINE_LANGUAGE,
                FraudEngineStatus.DEGRADED,
                null,
                null,
                FraudEngineConfidence.UNKNOWN,
                List.of(reasonCode.wireValue()),
                List.of(),
                List.of(new FraudEngineEvidence(
                        FraudEngineEvidenceType.OPERATIONAL_FALLBACK,
                        reasonCode.wireValue(),
                        "Rule feature status",
                        "Bounded rule adapter diagnostic.",
                        EVIDENCE_SOURCE,
                        FraudEngineEvidenceStatus.PARTIAL
                )),
                latencyMs,
                null,
                null,
                reasonCode.wireValue(),
                generatedAt
        );
    }

    private FraudEngineResult availableResult(EvaluationState state, long latencyMs, Instant generatedAt) {
        double cappedScore = Math.min(state.score, 0.99d);
        return new FraudEngineResult(
                ENGINE_ID,
                FraudEngineType.RULES,
                ENGINE_LANGUAGE,
                FraudEngineStatus.AVAILABLE,
                cappedScore,
                riskLevel(cappedScore),
                state.reasonCodes.isEmpty() ? FraudEngineConfidence.LOW : FraudEngineConfidence.MEDIUM,
                List.copyOf(state.reasonCodes),
                List.copyOf(state.contributions),
                List.copyOf(state.evidence),
                latencyMs,
                null,
                null,
                null,
                generatedAt
        );
    }

    private void evaluateBooleanSignal(
            FeatureSnapshotValue<Boolean> value,
            RuleBasedSignalReasonCode reasonCode,
            FraudEngineEvidenceType evidenceType,
            double weight,
            EvaluationState state
    ) {
        if (!isPresentOrSkipped(value, state)) {
            return;
        }
        if (Boolean.TRUE.equals(value.value())) {
            state.addSignal(reasonCode, evidenceType, weight);
        }
    }

    private void evaluateIntegerThreshold(
            FeatureSnapshotValue<Integer> value,
            int threshold,
            RuleBasedSignalReasonCode reasonCode,
            FraudEngineEvidenceType evidenceType,
            double weight,
            EvaluationState state
    ) {
        if (!isPresentOrSkipped(value, state)) {
            return;
        }
        if (value.value() >= threshold) {
            state.addSignal(reasonCode, evidenceType, weight);
        }
    }

    private void evaluateDoubleThreshold(
            FeatureSnapshotValue<Double> value,
            double threshold,
            RuleBasedSignalReasonCode reasonCode,
            FraudEngineEvidenceType evidenceType,
            double weight,
            EvaluationState state
    ) {
        if (!isPresentOrSkipped(value, state)) {
            return;
        }
        if (value.value() >= threshold) {
            state.addSignal(reasonCode, evidenceType, weight);
        }
    }

    private void evaluateDecimalThreshold(
            FeatureSnapshotValue<BigDecimal> value,
            BigDecimal threshold,
            RuleBasedSignalReasonCode reasonCode,
            FraudEngineEvidenceType evidenceType,
            double weight,
            EvaluationState state
    ) {
        if (!isPresentOrSkipped(value, state)) {
            return;
        }
        if (value.value().compareTo(threshold) >= 0) {
            state.addSignal(reasonCode, evidenceType, weight);
        }
    }

    private boolean isPresentOrSkipped(FeatureSnapshotValue<?> value, EvaluationState state) {
        if (value.status() == FeatureSnapshotValueStatus.PRESENT) {
            return true;
        }
        if (value.status() == FeatureSnapshotValueStatus.MISSING) {
            return false;
        }
        state.invalidStatus = value.status();
        return false;
    }

    private RiskLevel riskLevel(double score) {
        if (score >= CRITICAL_THRESHOLD) {
            return RiskLevel.CRITICAL;
        }
        if (score >= HIGH_THRESHOLD) {
            return RiskLevel.HIGH;
        }
        if (score >= 0.45d) {
            return RiskLevel.MEDIUM;
        }
        return RiskLevel.LOW;
    }

    private static final class EvaluationState {
        private double score = BASE_SCORE;
        private final Set<String> reasonCodes = new LinkedHashSet<>();
        private final List<FraudEngineContribution> contributions = new ArrayList<>();
        private final List<FraudEngineEvidence> evidence = new ArrayList<>();
        private FeatureSnapshotValueStatus invalidStatus;

        private void addSignal(
                RuleBasedSignalReasonCode reasonCode,
                FraudEngineEvidenceType evidenceType,
                double weight
        ) {
            if (reasonCodes.add(reasonCode.wireValue())) {
                score += weight;
                contributions.add(new FraudEngineContribution(
                        reasonCode.wireValue(),
                        null,
                        weight,
                        FraudEngineContributionDirection.INCREASES_RISK
                ));
                evidence.add(new FraudEngineEvidence(
                        evidenceType,
                        reasonCode.wireValue(),
                        titleFor(reasonCode),
                        "Bounded rule signal matched.",
                        EVIDENCE_SOURCE,
                        FraudEngineEvidenceStatus.AVAILABLE
                ));
            }
        }

        private String titleFor(RuleBasedSignalReasonCode reasonCode) {
            return switch (reasonCode) {
                case DEVICE_NOVELTY_SIGNAL -> "Device novelty signal";
                case COUNTRY_MISMATCH_SIGNAL -> "Country mismatch signal";
                case PROXY_OR_VPN_SIGNAL -> "Proxy network signal";
                case RAPID_TRANSFER_BURST_SIGNAL -> "Rapid transfer signal";
                case HIGH_RISK_FLAGS_PRESENT -> "High risk flags signal";
                case VELOCITY_THRESHOLD_EXCEEDED -> "Velocity threshold signal";
                case AMOUNT_ACTIVITY_THRESHOLD_EXCEEDED -> "Amount activity signal";
                case RAPID_TRANSFER_PATTERN_MATCHED -> "Rapid transfer pattern";
                case FEATURE_STATUS_INVALID, FEATURE_STATUS_WRONG_ACCESSOR, FEATURE_STATUS_NOT_ALLOWED ->
                        "Rule feature status";
            };
        }
    }
}
