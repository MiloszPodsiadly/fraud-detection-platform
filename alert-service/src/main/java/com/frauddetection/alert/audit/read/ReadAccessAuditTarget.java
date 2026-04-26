package com.frauddetection.alert.audit.read;

public record ReadAccessAuditTarget(
        ReadAccessEndpointCategory endpointCategory,
        ReadAccessResourceType resourceType,
        String resourceId,
        String queryHash,
        Integer page,
        Integer size
) {
}
