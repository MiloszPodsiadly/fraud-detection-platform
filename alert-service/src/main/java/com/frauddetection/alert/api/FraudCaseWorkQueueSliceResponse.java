package com.frauddetection.alert.api;

import java.util.List;

public record FraudCaseWorkQueueSliceResponse(
        List<FraudCaseWorkQueueItemResponse> content,
        int page,
        int size,
        boolean hasNext,
        Integer nextPage
) {
}
