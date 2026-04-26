package com.frauddetection.alert.audit;

import java.util.List;

public record AuditEventReadResponse(
        String status,
        int count,
        int limit,
        List<AuditEventResponse> events
) {
    static AuditEventReadResponse available(int limit, List<AuditEventDocument> documents) {
        List<AuditEventResponse> events = documents.stream()
                .map(AuditEventResponse::from)
                .toList();
        return new AuditEventReadResponse("AVAILABLE", events.size(), limit, events);
    }

    static AuditEventReadResponse unavailable(int limit) {
        return new AuditEventReadResponse("UNAVAILABLE", 0, limit, List.of());
    }
}
