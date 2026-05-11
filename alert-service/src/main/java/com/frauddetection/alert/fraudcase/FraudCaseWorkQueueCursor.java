package com.frauddetection.alert.fraudcase;

public record FraudCaseWorkQueueCursor(
        int version,
        String sortField,
        String sortDirection,
        String lastValue,
        String lastId,
        String queryHash
) {
}
