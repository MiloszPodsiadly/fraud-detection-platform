package com.frauddetection.alert.api;

import java.util.List;

public record FraudCaseWorkQueueSliceResponse(
        List<FraudCaseWorkQueueItemResponse> content,
        int page,
        int size,
        boolean hasNext,
        Integer nextPage,
        String nextCursor,
        String sort
) {
    public FraudCaseWorkQueueSliceResponse(
            List<FraudCaseWorkQueueItemResponse> content,
            int page,
            int size,
            boolean hasNext,
            Integer nextPage
    ) {
        this(content, page, size, hasNext, nextPage, null, null);
    }
}
