package com.frauddetection.alert.service;

public final class DecisionOutboxStatus {
    public static final String PENDING = "PENDING";
    public static final String PROCESSING = "PROCESSING";
    public static final String PUBLISHED = "PUBLISHED";
    public static final String FAILED_RETRYABLE = "FAILED_RETRYABLE";
    public static final String FAILED_TERMINAL = "FAILED_TERMINAL";

    private DecisionOutboxStatus() {
    }
}
