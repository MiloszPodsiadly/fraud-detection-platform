package com.frauddetection.alert.suspicious.api;

import java.util.List;

public record SuspiciousTransactionSliceResponse(
        List<SuspiciousTransactionResponse> content,
        int page,
        int size,
        boolean hasNext
) {

    public SuspiciousTransactionSliceResponse {
        content = content == null ? List.of() : List.copyOf(content);
    }
}
