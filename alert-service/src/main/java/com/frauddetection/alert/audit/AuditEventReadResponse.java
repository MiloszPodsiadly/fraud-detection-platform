package com.frauddetection.alert.audit;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record AuditEventReadResponse(
        @JsonProperty("status")
        String status,

        @JsonProperty("reason_code")
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String reasonCode,

        @JsonProperty("message")
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String message,

        @JsonProperty("count")
        int count,

        @JsonProperty("limit")
        int limit,

        @JsonProperty("events")
        List<AuditEventResponse> events
) {
    private static final String UNAVAILABLE_REASON_CODE = "AUDIT_STORE_UNAVAILABLE";
    private static final String UNAVAILABLE_MESSAGE = "Audit event store is currently unavailable.";

    static AuditEventReadResponse available(int limit, List<AuditEventDocument> documents) {
        return available(limit, documents, List.of());
    }

    static AuditEventReadResponse available(
            int limit,
            List<AuditEventDocument> documents,
            List<AuditEventDocument> compensations
    ) {
        return available(limit, documents, compensations, java.util.Map.of());
    }

    static AuditEventReadResponse available(
            int limit,
            List<AuditEventDocument> documents,
            List<AuditEventDocument> compensations,
            java.util.Map<String, AuditExternalAnchorStatus> externalStatuses
    ) {
        var semantics = AuditEventBusinessSemantics.index(documents, compensations, externalStatuses);
        List<AuditEventResponse> events = documents.stream()
                .map(document -> AuditEventResponse.from(document, semantics.get(document.auditId())))
                .toList();
        return new AuditEventReadResponse("AVAILABLE", null, null, events.size(), limit, events);
    }

    static AuditEventReadResponse unavailable(int limit) {
        return new AuditEventReadResponse("UNAVAILABLE", UNAVAILABLE_REASON_CODE, UNAVAILABLE_MESSAGE, 0, limit, List.of());
    }
}
