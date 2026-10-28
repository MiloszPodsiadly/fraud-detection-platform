package com.frauddetection.scoring.orchestration.aggregation;

import com.frauddetection.common.events.intelligence.EngineIntelligenceReasonCodePolicy;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

public final class FraudEngineReasonCodeNormalizer {
    public List<String> normalize(
            String engineId,
            List<String> source,
            FraudEngineAggregationPolicy policy,
            List<FraudEngineAggregationWarning> warnings
    ) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String reasonCode : source) {
            if (reasonCode == null) {
                warn(warnings, policy, engineId, FraudEngineAggregationWarningCode.REASON_CODE_NULL_DROPPED);
                continue;
            }
            String trimmed = reasonCode.trim();
            if (trimmed.isEmpty()) {
                warn(warnings, policy, engineId, FraudEngineAggregationWarningCode.REASON_CODE_BLANK_DROPPED);
                continue;
            }
            if (trimmed.length() > policy.maxStringLength()
                    || !FraudEngineAggregationSafety.isSafe(trimmed)
                    || !isAllowedForEngine(engineId, trimmed)) {
                warn(warnings, policy, engineId, FraudEngineAggregationWarningCode.REASON_CODE_UNSUPPORTED_DROPPED);
                continue;
            }
            if (normalized.size() == policy.maxReasonCodesPerEngine()) {
                warn(warnings, policy, engineId, FraudEngineAggregationWarningCode.REASON_CODE_LIMIT_APPLIED);
                break;
            }
            normalized.add(trimmed);
        }
        return List.copyOf(normalized);
    }

    boolean isAllowed(String reasonCode) {
        return reasonCode != null && EngineIntelligenceReasonCodePolicy.isKnownReasonCode(reasonCode);
    }

    boolean isAllowedForEngine(String engineId, String reasonCode) {
        return reasonCode != null && EngineIntelligenceReasonCodePolicy.isAllowedForEngine(engineId, reasonCode);
    }

    private void warn(
            List<FraudEngineAggregationWarning> warnings,
            FraudEngineAggregationPolicy policy,
            String engineId,
            FraudEngineAggregationWarningCode code
    ) {
        FraudEngineAggregationSafety.addWarning(warnings, policy, engineId, code);
    }
}
