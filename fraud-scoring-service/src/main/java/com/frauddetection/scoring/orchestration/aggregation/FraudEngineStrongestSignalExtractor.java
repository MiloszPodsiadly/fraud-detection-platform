package com.frauddetection.scoring.orchestration.aggregation;

import com.frauddetection.common.events.engine.FraudEngineEvidenceType;
import com.frauddetection.common.events.engine.FraudEngineStatus;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public final class FraudEngineStrongestSignalExtractor {
    private static final Map<String, Integer> ENGINE_ORDER = Map.of(
            "rules.primary", 0,
            "ml.python.primary", 1
    );

    public List<FraudEngineStrongestSignal> extract(
            List<NormalizedFraudEngineResult> results,
            FraudEngineAggregationPolicy policy
    ) {
        List<FraudEngineStrongestSignal> signals = new ArrayList<>();
        for (NormalizedFraudEngineResult result : results) {
            FraudEngineSignalCategory category = result.status() == FraudEngineStatus.AVAILABLE
                    && result.riskLevel() != null
                    ? FraudEngineSignalCategory.FRAUD_SIGNAL
                    : FraudEngineSignalCategory.OPERATIONAL_SIGNAL;
            FraudEngineEvidenceType evidenceType = result.evidence().isEmpty()
                    ? null
                    : result.evidence().getFirst().evidenceType();
            for (String reasonCode : result.reasonCodes()) {
                signals.add(new FraudEngineStrongestSignal(
                        result.engineId(),
                        result.engineType(),
                        result.status(),
                        result.riskLevel(),
                        result.score(),
                        reasonCode,
                        evidenceType,
                        category
                ));
            }
        }
        signals.sort(signalComparator());
        return List.copyOf(signals.subList(0, Math.min(signals.size(), policy.maxStrongestSignals())));
    }

    private Comparator<FraudEngineStrongestSignal> signalComparator() {
        return Comparator
                .comparingInt((FraudEngineStrongestSignal signal) ->
                        signal.signalCategory() == FraudEngineSignalCategory.FRAUD_SIGNAL ? 0 : 1)
                .thenComparing(Comparator.comparingInt(this::riskSeverity).reversed())
                .thenComparing(Comparator.comparingDouble(this::score).reversed())
                .thenComparingInt(signal -> ENGINE_ORDER.getOrDefault(signal.engineId(), Integer.MAX_VALUE))
                .thenComparing(FraudEngineStrongestSignal::reasonCode);
    }

    private int riskSeverity(FraudEngineStrongestSignal signal) {
        return FraudEngineRiskSeverity.rank(signal.riskLevel());
    }

    private double score(FraudEngineStrongestSignal signal) {
        return signal.score() == null ? -1.0d : signal.score();
    }
}
