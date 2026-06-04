package com.frauddetection.alert.engineintelligence.api;

import java.util.regex.Pattern;

final class EngineIntelligenceTransactionIdPolicy {

    private static final int MAX_TRANSACTION_ID_LENGTH = 128;
    private static final Pattern TRANSACTION_ID_PATTERN = Pattern.compile("^[A-Za-z0-9._:-]+$");

    private EngineIntelligenceTransactionIdPolicy() {
    }

    static String normalize(String transactionId) {
        if (transactionId == null || transactionId.isBlank()) {
            throw new EngineIntelligenceScoredTransactionNotFoundException();
        }
        String normalized = transactionId.trim();
        if (normalized.length() > MAX_TRANSACTION_ID_LENGTH
                || transactionId.chars().anyMatch(Character::isISOControl)
                || !TRANSACTION_ID_PATTERN.matcher(normalized).matches()) {
            throw new EngineIntelligenceScoredTransactionNotFoundException();
        }
        return normalized;
    }
}
