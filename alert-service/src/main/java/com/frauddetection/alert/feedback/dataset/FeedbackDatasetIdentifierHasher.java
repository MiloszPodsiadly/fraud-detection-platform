package com.frauddetection.alert.feedback.dataset;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;

final class FeedbackDatasetIdentifierHasher {

    private static final Pattern EVALUATION_RECORD_ID = Pattern.compile("eval_[a-f0-9]{32}");
    private static final Pattern TRANSACTION_REFERENCE = Pattern.compile("txnref_[a-f0-9]{32}");

    private FeedbackDatasetIdentifierHasher() {
    }

    static String evaluationRecordId(String feedbackId) {
        requireSourceIdentifier(feedbackId, "feedbackId");
        return "eval_" + sha256(feedbackId).substring(0, 32);
    }

    static String transactionReference(String transactionId) {
        requireSourceIdentifier(transactionId, "transactionId");
        return "txnref_" + sha256(transactionId).substring(0, 32);
    }

    static String requireEvaluationRecordId(String value) {
        if (value == null || !EVALUATION_RECORD_ID.matcher(value).matches()) {
            throw new IllegalArgumentException("evaluationRecordId must be a bounded pseudonymous reference");
        }
        return value;
    }

    static String requireTransactionReference(String value) {
        if (value == null || !TRANSACTION_REFERENCE.matcher(value).matches()) {
            throw new IllegalArgumentException("transactionReference must be a bounded pseudonymous reference");
        }
        return value;
    }

    private static void requireSourceIdentifier(String value, String fieldName) {
        if (value == null || value.isBlank() || value.length() > 128 || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(fieldName + " is invalid");
        }
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }
}
