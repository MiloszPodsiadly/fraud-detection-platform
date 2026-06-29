package com.frauddetection.alert.feedback.dataset;

import com.frauddetection.alert.feedback.FraudFeedbackLabel;
import com.frauddetection.alert.feedback.FraudFeedbackReasonCode;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

final class FeedbackDatasetReasonCodePolicy {

    private static final int MAX_DECISION_REASON_CODES = 10;

    private FeedbackDatasetReasonCodePolicy() {
    }

    static List<String> validatedDecisionReasonCodes(FraudFeedbackLabel feedbackLabel, List<String> reasonCodes) {
        List<String> validated = FeedbackDatasetSafety.copyRequiredMachineCodes(
                reasonCodes,
                "decisionReasonCodes",
                MAX_DECISION_REASON_CODES
        );
        Set<FraudFeedbackReasonCode> allowedCodes = allowedReasonCodes(feedbackLabel);
        for (String reasonCode : validated) {
            FraudFeedbackReasonCode parsed = parse(reasonCode);
            if (!allowedCodes.contains(parsed)) {
                throw new IllegalArgumentException("decisionReasonCodes must match feedbackLabel");
            }
        }
        return validated;
    }

    private static FraudFeedbackReasonCode parse(String reasonCode) {
        try {
            return FraudFeedbackReasonCode.valueOf(reasonCode);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("decisionReasonCodes must be known fraud feedback reason codes");
        }
    }

    private static Set<FraudFeedbackReasonCode> allowedReasonCodes(FraudFeedbackLabel feedbackLabel) {
        if (feedbackLabel == null) {
            throw new IllegalArgumentException("feedbackLabel is required");
        }
        return switch (feedbackLabel) {
            case CONFIRMED_FRAUD -> EnumSet.of(
                    FraudFeedbackReasonCode.CUSTOMER_CONFIRMED_FRAUD,
                    FraudFeedbackReasonCode.DOCUMENTATION_CONFIRMED_FRAUD,
                    FraudFeedbackReasonCode.CHARGEBACK_SIGNAL,
                    FraudFeedbackReasonCode.ACCOUNT_TAKEOVER_INDICATOR,
                    FraudFeedbackReasonCode.ANALYST_CONFIRMED_FRAUD
            );
            case CONFIRMED_LEGITIMATE -> EnumSet.of(
                    FraudFeedbackReasonCode.CUSTOMER_CONFIRMED_LEGITIMATE,
                    FraudFeedbackReasonCode.DOCUMENTATION_CONFIRMED_LEGITIMATE,
                    FraudFeedbackReasonCode.MERCHANT_CONFIRMED,
                    FraudFeedbackReasonCode.FALSE_POSITIVE_PATTERN,
                    FraudFeedbackReasonCode.ANALYST_CONFIRMED_LEGITIMATE
            );
            case INCONCLUSIVE -> EnumSet.of(
                    FraudFeedbackReasonCode.INSUFFICIENT_EVIDENCE,
                    FraudFeedbackReasonCode.ANALYST_INCONCLUSIVE
            );
            case NEEDS_MORE_INFO -> EnumSet.of(
                    FraudFeedbackReasonCode.NEEDS_CUSTOMER_CONTACT,
                    FraudFeedbackReasonCode.INSUFFICIENT_EVIDENCE,
                    FraudFeedbackReasonCode.ANALYST_NEEDS_MORE_INFO
            );
        };
    }
}
