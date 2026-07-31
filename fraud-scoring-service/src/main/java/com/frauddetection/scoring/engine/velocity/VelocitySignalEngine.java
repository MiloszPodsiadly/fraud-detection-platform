package com.frauddetection.scoring.engine.velocity;

import com.frauddetection.common.events.engine.FraudEngineConfidence;
import com.frauddetection.common.events.engine.FraudEngineContribution;
import com.frauddetection.common.events.engine.FraudEngineContributionDirection;
import com.frauddetection.common.events.engine.FraudEngineEvidence;
import com.frauddetection.common.events.engine.FraudEngineEvidenceStatus;
import com.frauddetection.common.events.engine.FraudEngineEvidenceType;
import com.frauddetection.common.events.engine.FraudEngineIdentityContract;
import com.frauddetection.common.events.engine.FraudEngineResult;
import com.frauddetection.common.events.engine.FraudEngineStatus;
import com.frauddetection.common.events.engine.FraudEngineType;
import com.frauddetection.common.events.features.FraudFeatureContract;
import com.frauddetection.scoring.context.ScoringContext;
import com.frauddetection.scoring.engine.FraudEngineDescriptor;
import com.frauddetection.scoring.engine.FraudSignalEngine;
import com.frauddetection.scoring.features.FeatureSnapshotReader;
import com.frauddetection.scoring.features.FeatureSnapshotReaderFactory;
import com.frauddetection.scoring.features.FeatureSnapshotValue;
import com.frauddetection.scoring.features.FeatureSnapshotValueStatus;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Objects;

public final class VelocitySignalEngine implements FraudSignalEngine {
    private static final String ENGINE_LANGUAGE = "java";
    private static final String ENGINE_VERSION = "1.0.0";
    private static final String EVIDENCE_SOURCE = "VELOCITY";

    private final FeatureSnapshotReaderFactory readerFactory;

    public VelocitySignalEngine(FeatureSnapshotReaderFactory readerFactory) {
        this.readerFactory = Objects.requireNonNull(readerFactory, "readerFactory is required");
    }

    @Override
    public FraudEngineResult evaluate(ScoringContext context) {
        Objects.requireNonNull(context, "context is required");
        FeatureSnapshotReader reader = readerFactory.from(context);
        VelocityInputs inputs = readInputs(reader);
        FeatureSnapshotValueStatus status = firstNonPresent(inputs.values());
        if (status == FeatureSnapshotValueStatus.MISSING) {
            return operationalResult(
                    FraudEngineStatus.UNAVAILABLE,
                    VelocitySignalReasonCode.VELOCITY_FEATURES_UNAVAILABLE,
                    FraudEngineEvidenceStatus.UNAVAILABLE,
                    context
            );
        }
        if (status != null) {
            return operationalResult(
                    FraudEngineStatus.DEGRADED,
                    VelocitySignalReasonCode.VELOCITY_FEATURE_TYPE_INVALID,
                    FraudEngineEvidenceStatus.PARTIAL,
                    context
            );
        }

        ValidatedVelocityInputs validated;
        try {
            validated = validate(inputs);
        } catch (InvalidVelocityFeatureValueException exception) {
            return operationalResult(
                    FraudEngineStatus.DEGRADED,
                    VelocitySignalReasonCode.VELOCITY_FEATURE_VALUE_INVALID,
                    FraudEngineEvidenceStatus.PARTIAL,
                    context
            );
        } catch (InconsistentVelocityFeaturesException exception) {
            return operationalResult(
                    FraudEngineStatus.DEGRADED,
                    VelocitySignalReasonCode.VELOCITY_FEATURES_INCONSISTENT,
                    FraudEngineEvidenceStatus.PARTIAL,
                    context
            );
        }

        VelocitySignalPolicy.VelocityDecision decision = VelocitySignalPolicy.decide(
                new VelocitySignalPolicy.VelocityFacts(
                        validated.recentTransactionCount(),
                        validated.recentAmountSumPln(),
                        validated.transactionVelocityPerMinute(),
                        validated.rapidTransferFraudCaseCandidate()
                )
        );
        return availableResult(decision, context);
    }

    @Override
    public FraudEngineDescriptor descriptor() {
        return new FraudEngineDescriptor(
                FraudEngineIdentityContract.VELOCITY_PRIMARY_ENGINE_ID,
                FraudEngineType.VELOCITY,
                ENGINE_LANGUAGE,
                ENGINE_VERSION,
                false
        );
    }

    private VelocityInputs readInputs(FeatureSnapshotReader reader) {
        return new VelocityInputs(
                reader.integerValue(FraudFeatureContract.RECENT_TRANSACTION_COUNT),
                reader.stringValue(FraudFeatureContract.RECENT_TRANSACTION_COUNT_WINDOW),
                reader.decimalValue(FraudFeatureContract.RECENT_AMOUNT_SUM_PLN),
                reader.doubleValue(FraudFeatureContract.TRANSACTION_VELOCITY_PER_MINUTE),
                reader.booleanValue(FraudFeatureContract.RAPID_TRANSFER_FRAUD_CASE_CANDIDATE),
                reader.integerValue(FraudFeatureContract.RAPID_TRANSFER_COUNT),
                reader.decimalValue(FraudFeatureContract.RAPID_TRANSFER_TOTAL_PLN),
                reader.stringValue(FraudFeatureContract.RAPID_TRANSFER_WINDOW),
                reader.decimalValue(FraudFeatureContract.RAPID_TRANSFER_THRESHOLD_PLN)
        );
    }

    private FeatureSnapshotValueStatus firstNonPresent(List<FeatureSnapshotValue<?>> values) {
        FeatureSnapshotValueStatus firstInvalid = null;
        for (FeatureSnapshotValue<?> value : values) {
            if (value.status() == FeatureSnapshotValueStatus.PRESENT) {
                continue;
            }
            if (value.status() == FeatureSnapshotValueStatus.MISSING) {
                return FeatureSnapshotValueStatus.MISSING;
            }
            if (firstInvalid == null) {
                firstInvalid = value.status();
            }
        }
        return firstInvalid;
    }

    private ValidatedVelocityInputs validate(VelocityInputs inputs) {
        int count = inputs.recentTransactionCount().value();
        int rapidCount = inputs.rapidTransferCount().value();
        BigDecimal amountPln = inputs.recentAmountSumPln().value();
        BigDecimal rapidTotalPln = inputs.rapidTransferTotalPln().value();
        BigDecimal thresholdPln = inputs.rapidTransferThresholdPln().value();
        double velocityPerMinute = inputs.transactionVelocityPerMinute().value();
        Duration recentWindow = parsePositiveDuration(inputs.recentTransactionCountWindow().value());
        Duration rapidWindow = parsePositiveDuration(inputs.rapidTransferWindow().value());

        if (count < 0
                || rapidCount < 0
                || amountPln.signum() < 0
                || rapidTotalPln.signum() < 0
                || thresholdPln.signum() <= 0
                || !Double.isFinite(velocityPerMinute)
                || velocityPerMinute < 0.0d) {
            throw new InvalidVelocityFeatureValueException();
        }
        if (!recentWindow.equals(rapidWindow)
                || rapidCount != count
                || rapidTotalPln.compareTo(amountPln) != 0
                || thresholdPln.compareTo(VelocitySignalPolicy.RAPID_TRANSFER_PLN_THRESHOLD) != 0) {
            throw new InconsistentVelocityFeaturesException();
        }
        double expectedVelocity = BigDecimal.valueOf(count)
                .divide(BigDecimal.valueOf(Math.max(recentWindow.toMinutes(), 1L)), 4, RoundingMode.HALF_UP)
                .doubleValue();
        boolean expectedCandidate = count >= VelocitySignalPolicy.RAPID_TRANSFER_MIN_COUNT
                && rapidTotalPln.compareTo(VelocitySignalPolicy.RAPID_TRANSFER_PLN_THRESHOLD) >= 0;
        if (Double.compare(velocityPerMinute, expectedVelocity) != 0
                || inputs.rapidTransferFraudCaseCandidate().value() != expectedCandidate) {
            throw new InconsistentVelocityFeaturesException();
        }
        return new ValidatedVelocityInputs(
                count,
                amountPln,
                velocityPerMinute,
                inputs.rapidTransferFraudCaseCandidate().value()
        );
    }

    private Duration parsePositiveDuration(String value) {
        try {
            Duration duration = Duration.parse(value);
            if (duration.isZero() || duration.isNegative()) {
                throw new InvalidVelocityFeatureValueException();
            }
            return duration;
        } catch (DateTimeParseException exception) {
            throw new InvalidVelocityFeatureValueException();
        }
    }

    private FraudEngineResult availableResult(VelocitySignalPolicy.VelocityDecision decision, ScoringContext context) {
        return new FraudEngineResult(
                FraudEngineIdentityContract.VELOCITY_PRIMARY_ENGINE_ID,
                FraudEngineType.VELOCITY,
                ENGINE_LANGUAGE,
                FraudEngineStatus.AVAILABLE,
                decision.score(),
                decision.riskLevel(),
                decision.reasonCodes().isEmpty() ? FraudEngineConfidence.LOW : FraudEngineConfidence.MEDIUM,
                decision.reasonCodes(),
                contributions(decision),
                evidence(decision),
                0L,
                null,
                null,
                null,
                context.receivedAt()
        );
    }

    private FraudEngineResult operationalResult(
            FraudEngineStatus status,
            VelocitySignalReasonCode reasonCode,
            FraudEngineEvidenceStatus evidenceStatus,
            ScoringContext context
    ) {
        return new FraudEngineResult(
                FraudEngineIdentityContract.VELOCITY_PRIMARY_ENGINE_ID,
                FraudEngineType.VELOCITY,
                ENGINE_LANGUAGE,
                status,
                null,
                null,
                FraudEngineConfidence.UNKNOWN,
                List.of(reasonCode.wireValue()),
                List.of(),
                List.of(new FraudEngineEvidence(
                        FraudEngineEvidenceType.OPERATIONAL_FALLBACK,
                        reasonCode.wireValue(),
                        "Velocity status",
                        "Velocity diagnostic input was not usable.",
                        EVIDENCE_SOURCE,
                        evidenceStatus
                )),
                0L,
                null,
                null,
                reasonCode.wireValue(),
                context.receivedAt()
        );
    }

    private List<FraudEngineContribution> contributions(VelocitySignalPolicy.VelocityDecision decision) {
        return List.of(
                        contribution(decision.rapidBurst(), "RAPID_TRANSFER_FRAUD_CASE_CANDIDATE", "RAPID_BURST_CONFIRMED", 0.40d),
                        contribution(decision.highRate(), "TRANSACTION_VELOCITY_PER_MINUTE", "HIGH_RATE", 0.25d),
                        contribution(decision.countSpike(), "RECENT_TRANSACTION_COUNT", "HIGH_COUNT", 0.20d),
                        contribution(decision.highAmount(), "RECENT_AMOUNT_SUM_PLN", "AT_OR_ABOVE_THRESHOLD", 0.15d)
                )
                .stream()
                .filter(Objects::nonNull)
                .toList();
    }

    private FraudEngineContribution contribution(boolean present, String feature, String value, double weight) {
        if (!present) {
            return null;
        }
        return new FraudEngineContribution(feature, value, weight, FraudEngineContributionDirection.INCREASES_RISK);
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

    private record VelocityInputs(
            FeatureSnapshotValue<Integer> recentTransactionCount,
            FeatureSnapshotValue<String> recentTransactionCountWindow,
            FeatureSnapshotValue<BigDecimal> recentAmountSumPln,
            FeatureSnapshotValue<Double> transactionVelocityPerMinute,
            FeatureSnapshotValue<Boolean> rapidTransferFraudCaseCandidate,
            FeatureSnapshotValue<Integer> rapidTransferCount,
            FeatureSnapshotValue<BigDecimal> rapidTransferTotalPln,
            FeatureSnapshotValue<String> rapidTransferWindow,
            FeatureSnapshotValue<BigDecimal> rapidTransferThresholdPln
    ) {
        List<FeatureSnapshotValue<?>> values() {
            return List.of(
                    recentTransactionCount,
                    recentTransactionCountWindow,
                    recentAmountSumPln,
                    transactionVelocityPerMinute,
                    rapidTransferFraudCaseCandidate,
                    rapidTransferCount,
                    rapidTransferTotalPln,
                    rapidTransferWindow,
                    rapidTransferThresholdPln
            );
        }
    }

    private record ValidatedVelocityInputs(
            int recentTransactionCount,
            BigDecimal recentAmountSumPln,
            double transactionVelocityPerMinute,
            boolean rapidTransferFraudCaseCandidate
    ) {
    }

    private static final class InvalidVelocityFeatureValueException extends RuntimeException {
    }

    private static final class InconsistentVelocityFeaturesException extends RuntimeException {
    }
}
