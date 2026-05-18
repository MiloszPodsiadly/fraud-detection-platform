package com.frauddetection.alert.suspicious.api;

import java.util.List;

public record SuspiciousTransactionSliceResponse(
        List<SuspiciousTransactionResponse> content,
        int size,
        boolean hasNext,
        String nextCursor
) {

    public SuspiciousTransactionSliceResponse {
        content = content == null ? List.of() : List.copyOf(content);
        nextCursor = hasNext ? nextCursor : null;
    }
}
