package com.frauddetection.alert.feedback.validation;

import com.frauddetection.alert.feedback.AnalystDecision;
import com.frauddetection.alert.feedback.FraudFeedbackLabel;

import java.util.List;

public record ValidatedFraudFeedback(
        AnalystDecision analystDecision,
        FraudFeedbackLabel feedbackLabel,
        List<String> decisionReasonCodes,
        String notes
) {
}
