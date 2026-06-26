package com.frauddetection.alert.feedback;

import java.util.List;

public record CreateFraudFeedbackRequest(
        AnalystDecision analystDecision,
        FraudFeedbackLabel feedbackLabel,
        List<String> decisionReasonCodes,
        String notes
) {
}
