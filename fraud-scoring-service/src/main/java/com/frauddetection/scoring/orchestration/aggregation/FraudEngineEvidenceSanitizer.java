package com.frauddetection.scoring.orchestration.aggregation;

import com.frauddetection.common.events.engine.FraudEngineEvidence;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

public final class FraudEngineEvidenceSanitizer {
    private static final Set<String> ALLOWED_SOURCES = Set.of("RULES", "ML_MODEL", "ORCHESTRATOR");
    private final FraudEngineReasonCodeNormalizer reasonCodeNormalizer = new FraudEngineReasonCodeNormalizer();

    public List<BoundedFraudEngineEvidenceSummary> sanitize(
            String engineId,
            List<FraudEngineEvidence> source,
            FraudEngineAggregationPolicy policy,
            List<FraudEngineAggregationWarning> warnings
    ) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        List<BoundedFraudEngineEvidenceSummary> sanitized = new ArrayList<>();
        for (FraudEngineEvidence evidence : source) {
            if (sanitized.size() == policy.maxEvidenceItemsPerEngine()) {
                warn(warnings, policy, engineId, FraudEngineAggregationWarningCode.EVIDENCE_LIMIT_APPLIED);
                break;
            }
            if (!isSafe(evidence)) {
                warn(warnings, policy, engineId, FraudEngineAggregationWarningCode.EVIDENCE_UNSAFE_DROPPED);
                continue;
            }
            String title = truncate(evidence.title(), policy.maxEvidenceTitleLength(), warnings, policy, engineId);
            String description = evidence.description() == null
                    ? "Bounded engine signal."
                    : truncate(evidence.description(), policy.maxEvidenceDescriptionLength(), warnings, policy, engineId);
            sanitized.add(new BoundedFraudEngineEvidenceSummary(
                    evidence.evidenceType(),
                    evidence.reasonCode(),
                    title,
                    description,
                    evidence.source(),
                    evidence.status()
            ));
        }
        sanitized.sort(Comparator
                .comparing((BoundedFraudEngineEvidenceSummary summary) -> summary.evidenceType().name())
                .thenComparing(summary -> summary.reasonCode() == null ? "" : summary.reasonCode())
                .thenComparing(BoundedFraudEngineEvidenceSummary::title));
        return List.copyOf(sanitized);
    }

    private boolean isSafe(FraudEngineEvidence evidence) {
        return evidence != null
                && ALLOWED_SOURCES.contains(evidence.source())
                && (evidence.reasonCode() == null
                || (FraudEngineAggregationSafety.isSafe(evidence.reasonCode())
                && reasonCodeNormalizer.isAllowed(evidence.reasonCode())))
                && FraudEngineAggregationSafety.isSafe(evidence.title())
                && FraudEngineAggregationSafety.isSafe(evidence.description());
    }

    private String truncate(
            String value,
            int maximum,
            List<FraudEngineAggregationWarning> warnings,
            FraudEngineAggregationPolicy policy,
            String engineId
    ) {
        if (value.length() > maximum) {
            warn(warnings, policy, engineId, FraudEngineAggregationWarningCode.EVIDENCE_TEXT_TRUNCATED);
        }
        return FraudEngineAggregationSafety.truncate(value, maximum);
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
