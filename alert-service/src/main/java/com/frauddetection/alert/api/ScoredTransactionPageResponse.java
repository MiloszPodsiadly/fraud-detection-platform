package com.frauddetection.alert.api;

import java.util.List;

public record ScoredTransactionPageResponse(
        List<ScoredTransactionResponse> content,
        long totalElements,
        int totalPages,
        int page,
        int size,
        boolean totalElementsCapped
) {
}
