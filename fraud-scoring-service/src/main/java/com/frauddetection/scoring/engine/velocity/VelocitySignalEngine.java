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
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

public final class VelocitySignalEngine implements FraudSignalEngine {
    private static final String ENGINE_LANGUAGE = "java";
    private static final String EVIDENCE_SOURCE = "VELOCITY";
    private static final Duration MAX_OBSERVED_WINDOW = Duration.ofDays(1);
    private static final Instant ORCHESTRATOR_GENERATED_AT_PLACEHOLDER = Instant.EPOCH;

    private final FeatureSnapshotReaderFactory readerFactory;

    public VelocitySignalEngine(FeatureSnapshotReaderFactory readerFactory) {
        this.readerFactory = Objects.requireNonNull(readerFactory, "readerFactory is required");
    }

    @Override
    public FraudEngineResult evaluate(ScoringContext context) {
        Objects.requireNonNull(context, "context is required");
        FeatureSnapshotReader reader = readerFactory.from(context);
        VelocityInputs inputs = readInputs(reader);
        InputReadiness readiness = readiness(inputs.values());
        if (readiness == InputReadiness.MISSING_ONLY) {
            return operationalResult(
                    FraudEngineStatus.UNAVAILABLE,
                    VelocitySignalReasonCode.VELOCITY_FEATURES_UNAVAILABLE,
                    FraudEngineEvidenceStatus.UNAVAILABLE,
                    context
            );
        }
        if (readiness == InputReadiness.INVALID_TYPE) {
            return operationalResult(
                    FraudEngineStatus.DEGRADED,
                    VelocitySignalReasonCode.VELOCITY_FEATURE_TYPE_INVALID,
                    FraudEngineEvidenceStatus.PARTIAL,
                    context
            );
        }
        if (readiness == InputReadiness.INVALID_VALUE) {
            return operationalResult(
                    FraudEngineStatus.DEGRADED,
                    VelocitySignalReasonCode.VELOCITY_FEATURE_VALUE_INVALID,
                    FraudEngineEvidenceStatus.PARTIAL,
                    context
            );
        }
        if (readiness == InputReadiness.INCONSISTENT) {
            return operationalResult(
                    FraudEngineStatus.DEGRADED,
                    VelocitySignalReasonCode.VELOCITY_FEATURES_INCONSISTENT,
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
                        validated.transactionVelocityPerMinute()
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
                VelocitySignalPolicy.ENGINE_VERSION,
                false
        );
    }

    private VelocityInputs readInputs(FeatureSnapshotReader reader) {
        return new VelocityInputs(
                reader.integerValue(FraudFeatureContract.RECENT_TRANSACTION_COUNT),
                reader.stringValue(FraudFeatureContract.RECENT_TRANSACTION_COUNT_WINDOW),
                reader.decimalValue(FraudFeatureContract.RECENT_AMOUNT_SUM_PLN),
                reader.doubleValue(FraudFeatureContract.TRANSACTION_VELOCITY_PER_MINUTE)
        );
    }

    private InputReadiness readiness(List<FeatureSnapshotValue<?>> values) {
        boolean missing = false;
        for (FeatureSnapshotValue<?> value : values) {
            if (value.status() == FeatureSnapshotValueStatus.WRONG_ACCESSOR
                    || value.status() == FeatureSnapshotValueStatus.NOT_ALLOWED) {
                return InputReadiness.INVALID_TYPE;
            }
        }
        for (FeatureSnapshotValue<?> value : values) {
            if (value.status() == FeatureSnapshotValueStatus.INVALID_TYPE) {
                return InputReadiness.INVALID_TYPE;
            }
            if (value.status() == FeatureSnapshotValueStatus.MISSING) {
                missing = true;
            }
        }
        try {
            validatePresentInputDomains(values);
        } catch (InvalidVelocityFeatureValueException exception) {
            return InputReadiness.INVALID_VALUE;
        }
        try {
            validatePresentInputRelationships(values);
        } catch (InconsistentVelocityFeaturesException exception) {
            return InputReadiness.INCONSISTENT;
        }
        return missing ? InputReadiness.MISSING_ONLY : InputReadiness.READY;
    }

    private void validatePresentInputDomains(List<FeatureSnapshotValue<?>> values) {
        for (FeatureSnapshotValue<?> value : values) {
            if (value.status() != FeatureSnapshotValueStatus.PRESENT) {
                continue;
            }
            Object actual = value.value();
            if (actual instanceof Integer integer && integer < 0) {
                throw new InvalidVelocityFeatureValueException();
            }
            if (actual instanceof BigDecimal decimal && decimal.signum() < 0) {
                throw new InvalidVelocityFeatureValueException();
            }
            if (actual instanceof Double doubleValue && (!Double.isFinite(doubleValue) || doubleValue < 0.0d)) {
                throw new InvalidVelocityFeatureValueException();
            }
            if (actual instanceof String stringValue) {
                parsePositiveDuration(stringValue);
            }
        }
    }

    private void validatePresentInputRelationships(List<FeatureSnapshotValue<?>> values) {
        FeatureSnapshotValue<?> count = values.get(0);
        FeatureSnapshotValue<?> amount = values.get(2);
        FeatureSnapshotValue<?> rate = values.get(3);
        if (count.status() != FeatureSnapshotValueStatus.PRESENT) {
            return;
        }
        if ((Integer) count.value() != 0) {
            return;
        }
        if (amount.status() == FeatureSnapshotValueStatus.PRESENT
                && ((BigDecimal) amount.value()).signum() > 0) {
            throw new InconsistentVelocityFeaturesException();
        }
        if (rate.status() == FeatureSnapshotValueStatus.PRESENT
                && Double.compare((Double) rate.value(), 0.0d) > 0) {
            throw new InconsistentVelocityFeaturesException();
        }
    }

    private ValidatedVelocityInputs validate(VelocityInputs inputs) {
        int count = inputs.recentTransactionCount().value();
        BigDecimal amountPln = inputs.recentAmountSumPln().value();
        double velocityPerMinute = inputs.transactionVelocityPerMinute().value();
        parsePositiveDuration(inputs.recentTransactionCountWindow().value());

        if (count < 0
                || amountPln.signum() < 0
                || !Double.isFinite(velocityPerMinute)
                || velocityPerMinute < 0.0d) {
            throw new InvalidVelocityFeatureValueException();
        }
        if (count == 0 && (amountPln.signum() > 0 || Double.compare(velocityPerMinute, 0.0d) > 0)) {
            throw new InconsistentVelocityFeaturesException();
        }
        return new ValidatedVelocityInputs(
                count,
                amountPln,
                velocityPerMinute
        );
    }

    private Duration parsePositiveDuration(String value) {
        try {
            Duration duration = Duration.parse(value);
            if (duration.isZero() || duration.isNegative() || duration.compareTo(MAX_OBSERVED_WINDOW) > 0) {
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
                FraudEngineConfidence.UNKNOWN,
                decision.reasonCodes(),
                contributions(decision),
                evidence(decision),
                0L,
                null,
                null,
                null,
                ORCHESTRATOR_GENERATED_AT_PLACEHOLDER
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
                ORCHESTRATOR_GENERATED_AT_PLACEHOLDER
        );
    }

    private List<FraudEngineContribution> contributions(VelocitySignalPolicy.VelocityDecision decision) {
        return Stream.of(
                        contribution(decision.rapidBurst(), "RAPID_TRANSFER_PLN_BURST", 0.40d),
                        contribution(decision.highRate(), "TRANSACTION_VELOCITY_PER_MINUTE", 0.25d),
                        contribution(decision.countSpike(), "RECENT_TRANSACTION_COUNT", 0.20d),
                        contribution(decision.highAmount(), "RECENT_AMOUNT_SUM_PLN", 0.15d)
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

    private record VelocityInputs(
            FeatureSnapshotValue<Integer> recentTransactionCount,
            FeatureSnapshotValue<String> recentTransactionCountWindow,
            FeatureSnapshotValue<BigDecimal> recentAmountSumPln,
            FeatureSnapshotValue<Double> transactionVelocityPerMinute
    ) {
        List<FeatureSnapshotValue<?>> values() {
            return List.of(
                    recentTransactionCount,
                    recentTransactionCountWindow,
                    recentAmountSumPln,
                    transactionVelocityPerMinute
            );
        }
    }

    private record ValidatedVelocityInputs(
            int recentTransactionCount,
            BigDecimal recentAmountSumPln,
            double transactionVelocityPerMinute
    ) {
    }

    private enum InputReadiness {
        READY,
        MISSING_ONLY,
        INVALID_TYPE,
        INVALID_VALUE,
        INCONSISTENT
    }

    private static final class InvalidVelocityFeatureValueException extends RuntimeException {
    }

    private static final class InconsistentVelocityFeaturesException extends RuntimeException {
    }
}
