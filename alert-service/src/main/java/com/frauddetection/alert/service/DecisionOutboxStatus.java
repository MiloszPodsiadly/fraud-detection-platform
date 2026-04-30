package com.frauddetection.alert.service;

public final class DecisionOutboxStatus {
    public static final String PENDING = "PENDING";
    public static final String PUBLISHED = "PUBLISHED";
    public static final String FAILED_RETRYABLE = "FAILED_RETRYABLE";

    private DecisionOutboxStatus() {
    }
}
