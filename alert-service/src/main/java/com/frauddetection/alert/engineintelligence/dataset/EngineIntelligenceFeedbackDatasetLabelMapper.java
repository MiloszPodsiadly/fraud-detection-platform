package com.frauddetection.alert.engineintelligence.dataset;

import com.frauddetection.common.events.enums.AnalystDecision;

import java.util.Locale;

final class EngineIntelligenceFeedbackDatasetLabelMapper {

    private EngineIntelligenceFeedbackDatasetLabelMapper() {
    }

    static MappedLabel map(AnalystDecision decision) {
        if (decision == null) {
            return new MappedLabel(
                    EngineIntelligenceFeedbackDatasetLabel.NON_TRAINING,
                    EngineIntelligenceFeedbackDatasetLabelSource.MISSING_ALERT_DECISION
            );
        }
        return switch (decision) {
            case CONFIRMED_FRAUD -> new MappedLabel(
                    EngineIntelligenceFeedbackDatasetLabel.POSITIVE,
                    EngineIntelligenceFeedbackDatasetLabelSource.ALERT_ANALYST_DECISION
            );
            case MARKED_LEGITIMATE -> new MappedLabel(
                    EngineIntelligenceFeedbackDatasetLabel.NEGATIVE,
                    EngineIntelligenceFeedbackDatasetLabelSource.ALERT_ANALYST_DECISION
            );
            case REQUIRE_MORE_EVIDENCE, ESCALATED -> new MappedLabel(
                    EngineIntelligenceFeedbackDatasetLabel.NON_TRAINING,
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
                    EngineIntelligenceFeedbackDatasetLabel.NON_TRAINING,
                    EngineIntelligenceFeedbackDatasetLabelSource.ALERT_ANALYST_DECISION
            );
        }
        try {
            return map(AnalystDecision.valueOf(normalized));
        } catch (IllegalArgumentException exception) {
            return new MappedLabel(
                    EngineIntelligenceFeedbackDatasetLabel.NON_TRAINING,
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
