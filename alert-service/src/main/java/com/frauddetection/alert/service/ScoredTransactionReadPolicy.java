package com.frauddetection.alert.service;

import java.util.regex.Pattern;

final class ScoredTransactionReadPolicy {

    private static final int MAX_TRANSACTION_ID_LENGTH = 128;
    private static final Pattern TRANSACTION_ID_PATTERN = Pattern.compile("^[A-Za-z0-9._:-]+$");

    private ScoredTransactionReadPolicy() {
    }

    static String normalizeTransactionId(String transactionId) {
        if (transactionId == null || transactionId.isBlank()) {
            throw invalidTransactionId();
        }
        String normalized = transactionId.trim();
        if (normalized.length() > MAX_TRANSACTION_ID_LENGTH
                || transactionId.chars().anyMatch(Character::isISOControl)
                || !TRANSACTION_ID_PATTERN.matcher(normalized).matches()) {
            throw invalidTransactionId();
        }
        return normalized;
    }

    private static ScoredTransactionReadValidationException invalidTransactionId() {
        return new ScoredTransactionReadValidationException("INVALID_TRANSACTION_ID");
    }
}
