package com.frauddetection.alert.engineintelligence.api;

import com.frauddetection.alert.engineintelligence.feedback.EngineIntelligenceFeedbackAccuracyAssessment;
import com.frauddetection.alert.engineintelligence.feedback.EngineIntelligenceFeedbackDocument;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class EngineIntelligenceFeedbackReadPolicy {

    private static final int MAX_ID_LENGTH = 128;
    private static final int MAX_REASON_CODES = 5;
    private static final int MAX_REASON_CODE_LENGTH = 128;
    private static final Set<String> ACCURACY_ASSESSMENT_NAMES = Arrays.stream(EngineIntelligenceFeedbackAccuracyAssessment.values())
            .map(Enum::name)
            .collect(Collectors.toUnmodifiableSet());
    private static final List<String> FORBIDDEN_TERMS = List.of(
            "rawEvidence",
            "rawContribution",
            "featureSnapshot",
            "featureVector",
            "rawPayload",
            "payload",
            "endpoint",
            "token",
            "secret",
            "stacktrace",
            "exceptionMessage",
            "internalAggregation",
            "EngineIntelligenceProjection",
            "FraudEngineAggregationResult",
            "NormalizedFraud" + "EngineResult",
            "Scoring" + "Context",
            "rawMlResponse",
            "finalDecision",
            "recommendedAction",
            "approve",
            "decline",
            "block",
            "paymentAuthorization",
            "modelTrainingLabel",
            "groundTruth",
            "ruleUpdate",
            "truePositive",
            "falsePositive",
            "confirmedFraud"
    );

    public List<EngineIntelligenceFeedbackDocument> validate(List<EngineIntelligenceFeedbackDocument> documents) {
        if (documents == null) {
            return List.of();
        }
        try {
            for (EngineIntelligenceFeedbackDocument document : documents) {
                validateDocument(document);
            }
            return List.copyOf(documents);
        } catch (RuntimeException exception) {
            throw new EngineIntelligenceFeedbackReadUnavailableException();
        }
    }

    private void validateDocument(EngineIntelligenceFeedbackDocument document) {
        Objects.requireNonNull(document, "feedback document is required");
        validateBoundedSafeString(document.getFeedbackId(), MAX_ID_LENGTH);
        Objects.requireNonNull(document.getFeedbackType(), "feedback type is required");
        Objects.requireNonNull(document.getUsefulness(), "usefulness is required");
        Objects.requireNonNull(document.getAccuracyAssessment(), "accuracy assessment is required");
        Objects.requireNonNull(document.getSubmittedAt(), "submitted at is required");
        validateReasonCodes(document.getSelectedReasonCodes());
    }

    private void validateReasonCodes(List<String> reasonCodes) {
        List<String> boundedReasonCodes = reasonCodes == null ? List.of() : reasonCodes;
        if (boundedReasonCodes.size() > MAX_REASON_CODES) {
            throw new IllegalArgumentException("too many reason codes");
        }
        for (String reasonCode : boundedReasonCodes) {
            validateBoundedSafeString(reasonCode, MAX_REASON_CODE_LENGTH);
            if (ACCURACY_ASSESSMENT_NAMES.contains(reasonCode.trim())) {
                throw new IllegalArgumentException("invalid reason code");
            }
        }
    }

    private void validateBoundedSafeString(String value, int maxLength) {
        if (!org.springframework.util.StringUtils.hasText(value)) {
            throw new IllegalArgumentException("required value missing");
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength || normalized.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("invalid value");
        }
        if (containsForbiddenTerm(normalized)) {
            throw new IllegalArgumentException("forbidden value");
        }
    }

    private boolean containsForbiddenTerm(String value) {
        String compact = value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return FORBIDDEN_TERMS.stream()
                .map(term -> term.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", ""))
                .anyMatch(compact::contains);
    }
}
