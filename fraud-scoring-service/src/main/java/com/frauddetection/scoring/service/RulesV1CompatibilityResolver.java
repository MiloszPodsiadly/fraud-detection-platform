package com.frauddetection.scoring.service;

import com.frauddetection.common.events.contract.TransactionEnrichedEvent;
import com.frauddetection.common.events.features.FraudFeatureContract;
import com.frauddetection.common.events.features.FraudFeatureThresholdContract;
import com.frauddetection.common.events.features.FraudFeatureValueBoundsContract;
import com.frauddetection.common.events.reason.ReasonCode;
import com.frauddetection.scoring.features.FeatureSnapshotReader;
import com.frauddetection.scoring.features.FeatureSnapshotValue;
import com.frauddetection.scoring.features.FeatureSnapshotValueStatus;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

final class RulesV1CompatibilityResolver {
    private static final double HIGH_VELOCITY_LEGACY_FLAG_WEIGHT = 0.20d;
    private static final double HIGH_VELOCITY_COUNT_WEIGHT = 0.10d;
    private static final double HIGH_VELOCITY_RATE_WEIGHT = 0.12d;
    private static final double HIGH_AMOUNT_LEGACY_FLAG_WEIGHT = 0.14d;
    private static final double HIGH_AMOUNT_SUM_WEIGHT = 0.10d;
    private static final double RAPID_LEGACY_FLAG_WEIGHT = 0.45d;
    private static final double RAPID_LEGACY_CANDIDATE_WEIGHT = 0.20d;

    private RulesV1CompatibilityResolver() {
    }

    static List<RulesV1SignalResolution> resolve(TransactionEnrichedEvent event) {
        return List.of(
                highVelocity(event),
                highAmountActivity(event),
                rapidPln20kBurst(event)
        );
    }

    private static RulesV1SignalResolution highVelocity(TransactionEnrichedEvent event) {
        Map<String, Object> snapshot = snapshot(event);
        Optional<Integer> canonicalCount = canonicalInteger(snapshot, FraudFeatureContract.RECENT_TRANSACTION_COUNT);
        if (canonicalCount.isPresent()) {
            if (canonicalCount.get() < RulesScoringPolicyV1.HIGH_VELOCITY_RECENT_TRANSACTION_COUNT_THRESHOLD) {
                return resolution(ReasonCode.HIGH_VELOCITY, PredicateResolution.FALSE, RulesV1Contribution.none());
            }
            if (topLevelRateTriggers(event)) {
                return resolution(
                        ReasonCode.HIGH_VELOCITY,
                        PredicateResolution.TRUE,
                        new RulesV1Contribution(
                                RulesScoringPolicyV1.HIGH_VELOCITY_WEIGHT,
                                List.of(RulesV1ContributionSource.CANONICAL, RulesV1ContributionSource.TOP_LEVEL_COMPATIBILITY)
                        )
                );
            }
            double weight = HIGH_VELOCITY_COUNT_WEIGHT;
            List<RulesV1ContributionSource> sources = new ArrayList<>();
            sources.add(RulesV1ContributionSource.CANONICAL);
            if (containsFeatureFlag(event.featureFlags(), FraudFeatureContract.FLAG_HIGH_VELOCITY)) {
                weight += HIGH_VELOCITY_LEGACY_FLAG_WEIGHT;
                sources.add(RulesV1ContributionSource.LEGACY_FLAG);
            }
            return resolution(ReasonCode.HIGH_VELOCITY, PredicateResolution.TRUE, new RulesV1Contribution(weight, sources));
        }

        double weight = 0.0d;
        List<RulesV1ContributionSource> sources = new ArrayList<>();
        if (topLevelCountWindowIsCanonical(event)
                && event.recentTransactionCount() != null
                && event.recentTransactionCount() >= RulesScoringPolicyV1.HIGH_VELOCITY_RECENT_TRANSACTION_COUNT_THRESHOLD) {
            weight += HIGH_VELOCITY_COUNT_WEIGHT;
            sources.add(RulesV1ContributionSource.TOP_LEVEL_COMPATIBILITY);
        }
        if (topLevelRateTriggers(event)) {
            weight += HIGH_VELOCITY_RATE_WEIGHT;
            sources.add(RulesV1ContributionSource.TOP_LEVEL_COMPATIBILITY);
        }
        if (containsFeatureFlag(event.featureFlags(), FraudFeatureContract.FLAG_HIGH_VELOCITY)) {
            weight += HIGH_VELOCITY_LEGACY_FLAG_WEIGHT;
            sources.add(RulesV1ContributionSource.LEGACY_FLAG);
        }
        return weight > 0.0d
                ? resolution(ReasonCode.HIGH_VELOCITY, PredicateResolution.TRUE, new RulesV1Contribution(weight, sources))
                : resolution(ReasonCode.HIGH_VELOCITY, PredicateResolution.ABSENT, RulesV1Contribution.none());
    }

    private static RulesV1SignalResolution highAmountActivity(TransactionEnrichedEvent event) {
        FeatureSnapshotReader reader = new FeatureSnapshotReader(snapshot(event));
        FeatureSnapshotValue<Integer> canonicalCount = reader.integerValue(FraudFeatureContract.RECENT_TRANSACTION_COUNT);
        FeatureSnapshotValue<BigDecimal> canonicalAmount = reader.decimalValue(FraudFeatureContract.RECENT_AMOUNT_SUM_PLN);
        PredicateResolution canonicalResolution = highAmountCanonicalResolution(canonicalCount, canonicalAmount);
        if (canonicalResolution == PredicateResolution.INVALID || canonicalResolution == PredicateResolution.FALSE) {
            return resolution(ReasonCode.HIGH_AMOUNT_ACTIVITY, canonicalResolution, RulesV1Contribution.none());
        }
        if (canonicalResolution == PredicateResolution.TRUE) {
            return resolution(
                    ReasonCode.HIGH_AMOUNT_ACTIVITY,
                    PredicateResolution.TRUE,
                    new RulesV1Contribution(RulesScoringPolicyV1.RECENT_AMOUNT_ACTIVITY_WEIGHT,
                            List.of(RulesV1ContributionSource.CANONICAL))
            );
        }

        double weight = 0.0d;
        List<RulesV1ContributionSource> sources = new ArrayList<>();
        Optional<BigDecimal> topLevelAmount = safeTopLevelRecentAmountPln(event);
        if (topLevelAmount.isPresent() && topLevelAmount.get().compareTo(BigDecimal.valueOf(5000)) >= 0) {
            weight += HIGH_AMOUNT_SUM_WEIGHT;
            sources.add(RulesV1ContributionSource.TOP_LEVEL_COMPATIBILITY);
        }
        if (containsFeatureFlag(event.featureFlags(), FraudFeatureContract.FLAG_HIGH_AMOUNT_ACTIVITY)) {
            weight += HIGH_AMOUNT_LEGACY_FLAG_WEIGHT;
            sources.add(RulesV1ContributionSource.LEGACY_FLAG);
        }
        return weight > 0.0d
                ? resolution(ReasonCode.HIGH_AMOUNT_ACTIVITY, PredicateResolution.ABSENT, new RulesV1Contribution(weight, sources))
                : resolution(ReasonCode.HIGH_AMOUNT_ACTIVITY, PredicateResolution.ABSENT, RulesV1Contribution.none());
    }

    private static RulesV1SignalResolution rapidPln20kBurst(TransactionEnrichedEvent event) {
        FeatureSnapshotReader reader = new FeatureSnapshotReader(snapshot(event));
        FeatureSnapshotValue<Integer> rapidCount = reader.integerValue(FraudFeatureContract.RAPID_TRANSFER_COUNT);
        FeatureSnapshotValue<BigDecimal> rapidTotal = reader.decimalValue(FraudFeatureContract.RAPID_TRANSFER_TOTAL_PLN);
        PredicateResolution rapidResolution = rapidCanonicalResolution(rapidCount, rapidTotal);
        if (rapidResolution == PredicateResolution.INVALID || rapidResolution == PredicateResolution.FALSE) {
            return resolution(ReasonCode.RAPID_PLN_20K_BURST, rapidResolution, RulesV1Contribution.none());
        }
        if (rapidResolution == PredicateResolution.TRUE) {
            return resolution(
                    ReasonCode.RAPID_PLN_20K_BURST,
                    PredicateResolution.TRUE,
                    new RulesV1Contribution(RulesScoringPolicyV1.RAPID_PLN_20K_BURST_WEIGHT,
                            List.of(RulesV1ContributionSource.CANONICAL))
            );
        }

        FeatureSnapshotValue<Integer> recentCount = reader.integerValue(FraudFeatureContract.RECENT_TRANSACTION_COUNT);
        FeatureSnapshotValue<BigDecimal> recentAmount = reader.decimalValue(FraudFeatureContract.RECENT_AMOUNT_SUM_PLN);
        PredicateResolution recentResolution = rapidCanonicalResolution(recentCount, recentAmount);
        if (recentResolution == PredicateResolution.INVALID || recentResolution == PredicateResolution.FALSE) {
            return resolution(ReasonCode.RAPID_PLN_20K_BURST, recentResolution, RulesV1Contribution.none());
        }
        if (recentResolution == PredicateResolution.TRUE) {
            return resolution(
                    ReasonCode.RAPID_PLN_20K_BURST,
                    PredicateResolution.TRUE,
                    new RulesV1Contribution(RulesScoringPolicyV1.RAPID_PLN_20K_BURST_WEIGHT,
                            List.of(RulesV1ContributionSource.CANONICAL))
            );
        }

        double weight = 0.0d;
        List<RulesV1ContributionSource> sources = new ArrayList<>();
        if (Boolean.TRUE.equals(snapshot(event).get(FraudFeatureContract.RAPID_TRANSFER_FRAUD_CASE_CANDIDATE))) {
            weight += RAPID_LEGACY_CANDIDATE_WEIGHT;
            sources.add(RulesV1ContributionSource.LEGACY_DERIVED_CANDIDATE);
        }
        if (containsFeatureFlag(event.featureFlags(), FraudFeatureContract.FLAG_RAPID_PLN_20K_BURST)) {
            weight += RAPID_LEGACY_FLAG_WEIGHT;
            sources.add(RulesV1ContributionSource.LEGACY_FLAG);
        }
        return weight > 0.0d
                ? resolution(ReasonCode.RAPID_PLN_20K_BURST, PredicateResolution.ABSENT, new RulesV1Contribution(weight, sources))
                : resolution(ReasonCode.RAPID_PLN_20K_BURST, PredicateResolution.ABSENT, RulesV1Contribution.none());
    }

    private static RulesV1SignalResolution resolution(
            ReasonCode reasonCode,
            PredicateResolution predicateResolution,
            RulesV1Contribution contribution
    ) {
        return new RulesV1SignalResolution(reasonCode.wireValue(), predicateResolution, contribution);
    }

    private static PredicateResolution highAmountCanonicalResolution(
            FeatureSnapshotValue<Integer> count,
            FeatureSnapshotValue<BigDecimal> amount
    ) {
        if (invalid(count) || invalid(amount)) {
            return PredicateResolution.INVALID;
        }
        if (present(count) && count.value() < 2) {
            return PredicateResolution.FALSE;
        }
        if (present(amount) && amount.value().compareTo(BigDecimal.valueOf(5000)) < 0) {
            return PredicateResolution.FALSE;
        }
        return present(count) && present(amount) ? PredicateResolution.TRUE : PredicateResolution.ABSENT;
    }

    private static PredicateResolution rapidCanonicalResolution(
            FeatureSnapshotValue<Integer> count,
            FeatureSnapshotValue<BigDecimal> amount
    ) {
        if (invalid(count) || invalid(amount)) {
            return PredicateResolution.INVALID;
        }
        if (present(count) && count.value() < FraudFeatureThresholdContract.RAPID_TRANSFER_MIN_COUNT) {
            return PredicateResolution.FALSE;
        }
        if (present(amount) && amount.value().compareTo(FraudFeatureThresholdContract.RAPID_TRANSFER_PLN_THRESHOLD) < 0) {
            return PredicateResolution.FALSE;
        }
        return present(count) && present(amount) ? PredicateResolution.TRUE : PredicateResolution.ABSENT;
    }

    private static boolean rapidPredicate(int count, BigDecimal amount) {
        try {
            return FraudFeatureThresholdContract.isRapidTransferPlnBurst(count, amount);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static boolean topLevelRateTriggers(TransactionEnrichedEvent event) {
        return event.transactionVelocityPerMinute() != null && event.transactionVelocityPerMinute() >= 5.0d;
    }

    private static Optional<BigDecimal> safeTopLevelRecentAmountPln(TransactionEnrichedEvent event) {
        if (event.recentAmountSum() == null
                || !"PLN".equalsIgnoreCase(event.recentAmountSum().currency())
                || !topLevelAmountWindowIsCanonical(event)) {
            return Optional.empty();
        }
        return Optional.ofNullable(event.recentAmountSum().amount());
    }

    private static boolean topLevelCountWindowIsCanonical(TransactionEnrichedEvent event) {
        return FraudFeatureValueBoundsContract.isRulesV1CanonicalWindowText(event.recentTransactionCountWindow());
    }

    private static boolean topLevelAmountWindowIsCanonical(TransactionEnrichedEvent event) {
        return FraudFeatureValueBoundsContract.isRulesV1CanonicalWindowText(event.recentAmountSumWindow());
    }

    private static Optional<Integer> canonicalInteger(Map<String, Object> snapshot, String key) {
        Object value = snapshot.get(key);
        return value instanceof Integer integer ? Optional.of(integer) : Optional.empty();
    }

    private static Optional<BigDecimal> canonicalDecimal(Map<String, Object> snapshot, String key) {
        Object value = snapshot.get(key);
        return value instanceof BigDecimal decimal ? Optional.of(decimal) : Optional.empty();
    }

    private static boolean present(FeatureSnapshotValue<?> value) {
        return value.status() == FeatureSnapshotValueStatus.PRESENT;
    }

    private static boolean invalid(FeatureSnapshotValue<?> value) {
        return value.status() != FeatureSnapshotValueStatus.PRESENT
                && value.status() != FeatureSnapshotValueStatus.MISSING;
    }

    private static boolean containsFeatureFlag(List<String> featureFlags, String featureFlag) {
        return featureFlags != null && featureFlags.contains(featureFlag);
    }

    private static Map<String, Object> snapshot(TransactionEnrichedEvent event) {
        return event.featureSnapshot() == null ? Map.of() : event.featureSnapshot();
    }
}
