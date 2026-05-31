package com.frauddetection.scoring.orchestration.aggregation;

import com.frauddetection.common.events.reason.ReasonCode;
import com.frauddetection.scoring.engine.ml.PythonMlSignalReasonCode;
import com.frauddetection.scoring.engine.rules.RuleBasedSignalReasonCode;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class FraudEngineReasonCodeNormalizer {
    private static final Set<String> ALLOWED_REASON_CODES = allowedReasonCodes();

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
                    || !ALLOWED_REASON_CODES.contains(trimmed)) {
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
        return reasonCode != null && ALLOWED_REASON_CODES.contains(reasonCode);
    }

    private void warn(
            List<FraudEngineAggregationWarning> warnings,
            FraudEngineAggregationPolicy policy,
            String engineId,
            FraudEngineAggregationWarningCode code
    ) {
        FraudEngineAggregationSafety.addWarning(warnings, policy, engineId, code);
    }

    private static Set<String> allowedReasonCodes() {
        List<String> orchestrationCodes = List.of(
                "ORCHESTRATOR_ENGINE_EXCEPTION",
                "ORCHESTRATOR_ENGINE_NULL_RESULT",
                "ORCHESTRATOR_ENGINE_REJECTED",
                "ORCHESTRATOR_ENGINE_TIMEOUT"
        );
        return Stream.of(
                        Stream.of(ReasonCode.values())
                                .filter(reasonCode -> reasonCode != ReasonCode.UNKNOWN)
                                .map(ReasonCode::wireValue),
                        Stream.of(PythonMlSignalReasonCode.values()).map(PythonMlSignalReasonCode::wireValue),
                        Stream.of(RuleBasedSignalReasonCode.values()).map(RuleBasedSignalReasonCode::wireValue),
                        orchestrationCodes.stream()
                )
                .flatMap(stream -> stream)
                .collect(Collectors.toUnmodifiableSet());
    }
}
