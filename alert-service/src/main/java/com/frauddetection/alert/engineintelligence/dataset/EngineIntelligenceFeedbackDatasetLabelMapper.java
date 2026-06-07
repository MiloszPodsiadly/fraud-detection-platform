package com.frauddetection.alert.engineintelligence.dataset;

import com.frauddetection.common.events.enums.AnalystDecision;

import java.util.Locale;

final class EngineIntelligenceFeedbackDatasetLabelMapper {

    private EngineIntelligenceFeedbackDatasetLabelMapper() {
    }

    static MappedLabel map(AnalystDecision decision) {
        if (decision == null) {
            return new MappedLabel(
                    EngineIntelligenceFeedbackDatasetLabel.NOT_EVALUATION_ELIGIBLE,
                    EngineIntelligenceFeedbackDatasetLabelSource.MISSING_ALERT_DECISION
            );
        }
        return switch (decision) {
            case CONFIRMED_FRAUD -> new MappedLabel(
                    EngineIntelligenceFeedbackDatasetLabel.ANALYST_CONFIRMED_FRAUD,
                    EngineIntelligenceFeedbackDatasetLabelSource.ALERT_ANALYST_DECISION
            );
            case MARKED_LEGITIMATE -> new MappedLabel(
                    EngineIntelligenceFeedbackDatasetLabel.ANALYST_MARKED_LEGITIMATE,
                    EngineIntelligenceFeedbackDatasetLabelSource.ALERT_ANALYST_DECISION
            );
            case REQUIRE_MORE_EVIDENCE, ESCALATED -> new MappedLabel(
                    EngineIntelligenceFeedbackDatasetLabel.NOT_EVALUATION_ELIGIBLE,
                    EngineIntelligenceFeedbackDatasetLabelSource.ALERT_ANALYST_DECISION
            );
        };
    }

    static MappedLabel mapWireValue(String decision) {
        if (decision == null || decision.isBlank()) {
            return map(null);
        }
        String normalized = decision.trim().toUpperCase(Locale.ROOT);
        if ("INCONCLUSIVE".equals(normalized) || "NEEDS_MORE_INFO".equals(normalized)) {
            return new MappedLabel(
                    EngineIntelligenceFeedbackDatasetLabel.NOT_EVALUATION_ELIGIBLE,
                    EngineIntelligenceFeedbackDatasetLabelSource.ALERT_ANALYST_DECISION
            );
        }
        try {
            return map(AnalystDecision.valueOf(normalized));
        } catch (IllegalArgumentException exception) {
            return new MappedLabel(
                    EngineIntelligenceFeedbackDatasetLabel.NOT_EVALUATION_ELIGIBLE,
                    EngineIntelligenceFeedbackDatasetLabelSource.UNKNOWN_ALERT_DECISION
            );
        }
    }

    record MappedLabel(
            EngineIntelligenceFeedbackDatasetLabel evaluationLabel,
            EngineIntelligenceFeedbackDatasetLabelSource labelSource
    ) {
    }
}
