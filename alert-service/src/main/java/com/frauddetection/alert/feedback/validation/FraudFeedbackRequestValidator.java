package com.frauddetection.alert.feedback.validation;

import com.frauddetection.alert.feedback.AnalystDecision;
import com.frauddetection.alert.feedback.CreateFraudFeedbackRequest;
import com.frauddetection.alert.feedback.FraudFeedbackLabel;
import com.frauddetection.alert.feedback.FraudFeedbackReasonCode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class FraudFeedbackRequestValidator {

    private static final int MAX_REASON_CODES = 10;
    private static final int MAX_REASON_CODE_LENGTH = 128;
    private static final int MAX_NOTES_LENGTH = 500;
    private static final Pattern REASON_CODE_PATTERN = Pattern.compile("[A-Z0-9_]+");
    private static final List<String> UNSAFE_TERMS = List.of(
            "token",
            "secret",
            "password",
            "raw" + "payload",
            "raw" + "mlrequest",
            "raw" + "mlresponse",
            "raw" + "featurevector",
            "raw" + "evidence",
            "stack" + "trace",
            "exception" + "message",
            "final" + "decision",
            "payment" + "decision",
            "payment" + "authorization",
            "approve" + "payment",
            "decline" + "payment",
            "block" + "transaction",
            "authorize" + "payment"
    );

    public ValidatedFraudFeedback validate(CreateFraudFeedbackRequest request) {
        if (request == null) {
            throw badRequest("FRAUD_FEEDBACK_REQUEST_REQUIRED");
        }
        if (request.analystDecision() == null) {
            throw badRequest("FRAUD_FEEDBACK_ANALYST_DECISION_REQUIRED");
        }
        if (request.feedbackLabel() == null) {
            throw badRequest("FRAUD_FEEDBACK_LABEL_REQUIRED");
        }
        validateDecisionMatchesLabel(request.analystDecision(), request.feedbackLabel());
        List<String> reasonCodes = validateReasonCodes(request.decisionReasonCodes());
        validateReasonCodesMatchLabel(request.feedbackLabel(), reasonCodes);
        String notes = validateNotes(request.notes());
        return new ValidatedFraudFeedback(
                request.analystDecision(),
                request.feedbackLabel(),
                reasonCodes,
                notes
        );
    }

    private void validateDecisionMatchesLabel(AnalystDecision decision, FraudFeedbackLabel label) {
        boolean matches = switch (decision) {
            case MARKED_FRAUD -> label == FraudFeedbackLabel.CONFIRMED_FRAUD;
            case MARKED_LEGITIMATE -> label == FraudFeedbackLabel.CONFIRMED_LEGITIMATE;
            case MARKED_INCONCLUSIVE -> label == FraudFeedbackLabel.INCONCLUSIVE;
            case REQUESTED_MORE_INFO -> label == FraudFeedbackLabel.NEEDS_MORE_INFO;
        };
        if (!matches) {
            throw badRequest("FRAUD_FEEDBACK_DECISION_LABEL_MISMATCH");
        }
    }

    private void validateReasonCodesMatchLabel(FraudFeedbackLabel feedbackLabel, List<String> reasonCodes) {
        Set<FraudFeedbackReasonCode> allowedCodes = allowedReasonCodes(feedbackLabel);
        for (String reasonCode : reasonCodes) {
            if (!allowedCodes.contains(FraudFeedbackReasonCode.valueOf(reasonCode))) {
                throw badRequest("FRAUD_FEEDBACK_REASON_CODE_LABEL_MISMATCH");
            }
        }
    }

    private Set<FraudFeedbackReasonCode> allowedReasonCodes(FraudFeedbackLabel feedbackLabel) {
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

    private List<String> validateReasonCodes(List<String> reasonCodes) {
        if (reasonCodes == null || reasonCodes.isEmpty()) {
            throw badRequest("FRAUD_FEEDBACK_REASON_CODES_REQUIRED");
        }
        if (reasonCodes.size() > MAX_REASON_CODES) {
            throw badRequest("FRAUD_FEEDBACK_REASON_CODES_TOO_MANY");
        }
        return reasonCodes.stream()
                .map(this::validateReasonCode)
                .toList();
    }

    private String validateReasonCode(String reasonCode) {
        if (reasonCode == null || reasonCode.isBlank()) {
            throw badRequest("FRAUD_FEEDBACK_REASON_CODE_REQUIRED");
        }
        String normalized = reasonCode.trim();
        if (normalized.length() > MAX_REASON_CODE_LENGTH || !REASON_CODE_PATTERN.matcher(normalized).matches()) {
            throw badRequest("FRAUD_FEEDBACK_REASON_CODE_INVALID");
        }
        rejectUnsafeTerms(normalized, "FRAUD_FEEDBACK_REASON_CODE_UNSAFE");
        try {
            FraudFeedbackReasonCode.valueOf(normalized);
        } catch (IllegalArgumentException exception) {
            throw badRequest("FRAUD_FEEDBACK_REASON_CODE_UNKNOWN");
        }
        return normalized;
    }

    private String validateNotes(String notes) {
        if (notes == null || notes.isBlank()) {
            return null;
        }
        String normalized = notes.trim();
        if (normalized.length() > MAX_NOTES_LENGTH) {
            throw badRequest("FRAUD_FEEDBACK_NOTES_TOO_LONG");
        }
        rejectUnsafeTerms(normalized, "FRAUD_FEEDBACK_NOTES_UNSAFE");
        return normalized;
    }

    private void rejectUnsafeTerms(String value, String reason) {
        String normalized = value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "");
        for (String term : UNSAFE_TERMS) {
            if (normalized.contains(term)) {
                throw badRequest(reason);
            }
        }
    }

    private ResponseStatusException badRequest(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }
}
