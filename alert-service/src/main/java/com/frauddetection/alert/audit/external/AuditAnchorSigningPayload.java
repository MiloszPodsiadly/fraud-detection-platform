package com.frauddetection.alert.audit.external;

record AuditAnchorSigningPayload(
        String partitionKey,
        String localAnchorId,
        long chainPosition,
        String lastEventHash,
        String externalKey,
        String externalHash,
        ExternalImmutabilityLevel immutabilityLevel
) {
}
